# Java Web Server

A multithreaded HTTP server written in Java with raw sockets. It serves static files from a root directory, and each connection is handled by a worker thread from a thread pool.

- Parses GET requests and serves files with Date, Last-Modified, Content-Type, and Content-Length headers.
- Returns 400 for malformed requests, 404 for missing files, and 408 when a client goes idle.
- Closes idle connections after a configurable timeout.
- Shuts down cleanly, letting in progress requests finish.

`WebServer.java` is my implementation. `ServerDriver.java` is the launcher written by Majid Ghaderi.

## Build and run

```bash
javac WebServer.java ServerDriver.java
java ServerDriver -p 2025 -r www
```

Then open http://localhost:2025 in a browser. 

Note: Point -r at any folder to serve your own files.
