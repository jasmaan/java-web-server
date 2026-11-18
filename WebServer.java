

/**
 * WebServer Class
 * 
 * Assignment completed in a University computer networking course.
 * 
 * Implements a multithreaded web server
 * supporting nonpersistent connections.
 *
 */


import java.util.*;
import java.util.logging.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;


public class WebServer extends Thread {
	// global logger object, configures in the driver class
	private static final Logger logger = Logger.getLogger("WebServer");

	private boolean shutdown = false; // shutdown flag
	private int port; 				  // port server listens on
	private String root;			  // server root directory
	private int timeout;			  // connection timeout (ms)
	private ServerSocket serverSocket; // main socket
	private ExecutorService threadPool; // manage threads



	
    /**
     * Constructor to initialize the web server
     * 
     * @param port 	Server port at which the web server listens > 1024
	 * @param root	Server's root file directory
	 * @param timeout	Idle connection timeout in milli-seconds
     * 
     */
	public WebServer(int port, String root, int timeout) {
		this.port = port;
		this.root = root;
		this.timeout = timeout;
	}

	
    /**
	 * Main method in the web server thread.
	 * The web server remains in listening mode 
	 * and accepts connection requests from clients 
	 * until it receives the shutdown signal.
	 * 
     */
	public void run() {

		threadPool = Executors.newCachedThreadPool();		// cached thread pool so new threads spawn as needed
		try {
			serverSocket = new ServerSocket(port);
			serverSocket.setSoTimeout(200);					// socket timeout before throwing exception 

			logger.info("Server started at port: " + port + " with root: " + root);

			while (!shutdown) {
				try {
					Socket clientSocket = serverSocket.accept(); 	// block the waiting for client connection

					System.out.println("==== Client Info ====");    // client info as required 
					System.out.println("IP: " + clientSocket.getInetAddress().getHostAddress());	// uses Java's getInetAddress (https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html)
					System.out.println("Port: " + clientSocket.getPort());

					WorkerThread worker = new WorkerThread(clientSocket);
					threadPool.execute(worker);
				} catch (SocketTimeoutException e){
					// expected, allows shutdown flag check
				}
			}
		} catch (IOException e) {
			if (!shutdown) {
				System.out.println("Error in server main thread:");			// print trace and terminate if socket not created
				e.printStackTrace();
			}

		}

		// Executor Service pattern from course tutorial
		try {
			threadPool.shutdown();
			threadPool.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			System.out.println("Thread pool interrupted during shutdown.");
			e.printStackTrace();
		}

		// Close server socket if open (referred to JavaDoc on ServerSocket)
		if (serverSocket != null && !serverSocket.isClosed()) {
			try {
				serverSocket.close();
			} catch (IOException e) {
				System.out.println("Error closing server socket:");
				e.printStackTrace();
			}
		}

		logger.info("Server has correctly shut down.");


	}

    /**
     * Signals the web server to shutdown.
	 *
     */
	public void shutdown() {
		shutdown = true;
	}

	/**
	 * Method for the Worker Thread
	 * Reads HTTP request from connected client and sends back response
	 * Uses Runnable so that it can be used in ExecutorService
	 * Takes some inspiration from 'TCP: Socket Server' 
     */
	private class WorkerThread implements Runnable { 

		private Socket clientSocket; 	// connected client's socket

		public WorkerThread(Socket clientSocket) {
			this.clientSocket = clientSocket;		// constructor stores client socket for use in run()
		}

		/**
		 * Main logic for handling HTTP request/response
		 * Reads request, determines validity, sends appropriate response.
		 */
		public void run() {
			try {
				clientSocket.setSoTimeout(timeout); // causes readLine to throw exception if client is idle (https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html)
				BufferedReader inputStream = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); // stream setup pattern from course tutorial
				OutputStream outputStream = clientSocket.getOutputStream();

				// Reading the request
				String requestLine = null;
				List<String> headerLines = new ArrayList<String>();
				try {
					requestLine = inputStream.readLine(); 	// reads first line (GET /index.html ...)
					String line;			
					while ((line = inputStream.readLine()) != null && !line.isEmpty()) {	// read remaining lines
						headerLines.add(line);
					}
				} catch (SocketTimeoutException e) {
					logger.warning("Client timed out. Sending 408.");				// request timeout, sends 408 
					sendErrorResponse(outputStream, 408, "Request Timeout");
					cleanUp(inputStream, outputStream);
					return;
				}

				// WHen client closes connection immediately 
				if (requestLine == null) {
					cleanUp(inputStream, outputStream);
					return;
				}

				// Print request info
				System.out.println("==== Request Info ====");
				System.out.println(requestLine);
				for (String h : headerLines) {
					System.out.println(h);
				}

				// Parse request (3 parts needed where method is GET and version is HTTP/1.1)
				String[] parts = requestLine.split("\\s+");
				if (parts.length != 3 || !parts[0].equals("GET") || !parts[2].equals("HTTP/1.1")) {	
					logger.warning("Error in request line: " + requestLine);
					sendErrorResponse(outputStream, 400, "Bad Request");	// bad request error response when error in formatting 
					cleanUp(inputStream, outputStream);
					return;

				}
				String objPath = parts[1]; // object path from request line
				if (objPath.equals("/")) {
					objPath = "/index.html";	// default to index.html if client requests root
				}

				// Locate file on disk
				File requestedFile = new File(root, objPath);
				if (!requestedFile.exists()) {
					logger.warning("File not found: " + requestedFile.getAbsolutePath());
					sendErrorResponse(outputStream, 404, "Not Found");	// not found error when file does not exist (404)
					cleanUp(inputStream, outputStream);
					return;
				}

				sendOkResponse(outputStream, requestedFile);
				cleanUp(inputStream, outputStream);
			} catch (IOException e) {
				System.out.println("IO error in worker thread.");		// exceptions in worker threads clean up and termiante
				e.printStackTrace();
				try {
					clientSocket.close();
				} catch (IOException e2) {
					e2.printStackTrace();
				}
			}
		}

		// HELPER METHODS BELOW
		// Moved certain functions for better modularization 
		//


		// Builds/sends HTTP 200 OK response with all required headers and file content as response body
		// @param outputStream	the output stream to the client
		// @param file			the file to send
		// @throws IOException	if writing to stream fails
		private void sendOkResponse(OutputStream outputStream, File file) throws IOException {
			ZonedDateTime gmtDate = ZonedDateTime.now(ZoneId.of("GMT"));
			String httpDate = gmtDate.format(DateTimeFormatter.RFC_1123_DATE_TIME);		// builds date string in RFC 1123 

			ZonedDateTime lastModified = Files.getLastModifiedTime(file.toPath()).toInstant().atZone(ZoneId.of("GMT"));		// Get last modified date of file in RFC 1123 format
			String	lastModifiedStr = lastModified.format(DateTimeFormatter.RFC_1123_DATE_TIME);						    // Code took inspiration here from Oracle's 'getLastModifiedTime' under Files, 'toInstant' under FileTime, and 'atZone' under Instant class

			String contentType = Files.probeContentType(file.toPath());	// get content type, used 'https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html' under probeContentType
																		// had to convert File to Path for the Files class methods (https://docs.oracle.com/javase/8/docs/api/java/io/File.html)
			if (contentType == null) {
				contentType = "application/octet-stream";		// in case unknown type 
			}
			long contentLength = file.length(); 	// size of file in bytes

			// Build up HTTP response to print to console
			StringBuilder response = new StringBuilder();
			response.append("HTTP/1.1 200 OK\r\n");
			response.append("Server: WebServer\r\n");
			response.append("Date: " + httpDate + "\r\n");
			response.append("Last-Modified: " + lastModifiedStr + "\r\n");
			response.append("Content-Length: " + contentLength + "\r\n");
			response.append("Content-Type: " + contentType + "\r\n");
			response.append("Connection: close\r\n");
			response.append("\r\n");

			System.out.println("==== Response Info ====");		// specific response info to be printed
			String[] lines = response.toString().split("\r\n");
			for (String line : lines) {
				System.out.println(line);
			}

			outputStream.write(response.toString().getBytes("US-ASCII"));		// write headers to client
			outputStream.flush();

			FileInputStream fileIn = new FileInputStream(file);
			byte[] buffer = new byte[4096]; 	// 4kb buffer
			int bytesRead;
			while ((bytesRead = fileIn.read(buffer)) != -1) {					// write file content as raw bytes
				outputStream.write(buffer, 0, bytesRead);
			}
			outputStream.flush();
			fileIn.close();
		}

		// Builds/sends HTTP error response with required content only
		// For 400, 404, 408 responses
		// @param outputStream		the output stream to the client
		// @param statusCode		the HTTP status code
		// @param statusPhrase 		the HTTP status phrase
		// @throws IOException 		if writing to stream fails 
		private void sendErrorResponse(OutputStream outputStream, int statusCode, String statusPhrase) throws IOException {
			ZonedDateTime gmtDate = ZonedDateTime.now(ZoneId.of("GMT"));
			String httpDate = gmtDate.format(DateTimeFormatter.RFC_1123_DATE_TIME);		// Builds date in RFC 1123

			StringBuilder response = new StringBuilder();			// gathers required response info
			response.append("HTTP/1.1 " + statusCode + " " + statusPhrase + "\r\n");
			response.append("Server: WebServer\r\n");
			response.append("Date: " + httpDate + "\r\n");
			response.append("Connection: close\r\n");
			response.append("\r\n");

			System.out.println("==== Response Info ====");
			String[] lines = response.toString().split("\r\n");
			for (String line : lines) {
				System.out.println(line);
			}

			outputStream.write(response.toString().getBytes("US-ASCII"));
			outputStream.flush();
		}

		// Closes in/out stream + socket, used at end of request/response cycle 
		// @param inputStream	the buffered reader to close
		// @param outputStream 	the output stream to close
		private void cleanUp(BufferedReader inputStream, OutputStream outputStream) {
			try {
				if (inputStream != null) {
					inputStream.close();
				}
				if (outputStream != null) {
					outputStream.close();
				}
				if (clientSocket != null && !clientSocket.isClosed()) {
					clientSocket.close();
				}
			} catch (IOException e) {
				System.out.println("Error in worker thread cleanup: ");
				e.printStackTrace();
			}
		}
	}
}
