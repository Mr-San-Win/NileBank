"""Loopback-only SMTP sink for fictional browser QA; not a production mail service."""
import socketserver
import threading

class LocalSmtpSink:
    def __init__(self):
        self.messages = []
        messages = self.messages
        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.wfile.write(b"220 localhost QA SMTP\r\n")
                data = None
                while True:
                    raw = self.rfile.readline()
                    if not raw:
                        return
                    line = raw.rstrip(b"\r\n")
                    if data is not None:
                        if line == b".":
                            messages.append(b"\n".join(data).decode("utf-8"))
                            data = None
                            self.wfile.write(b"250 accepted\r\n")
                        else:
                            data.append(line)
                    elif line == b"DATA":
                        data = []
                        self.wfile.write(b"354 end with dot\r\n")
                    elif line == b"QUIT":
                        self.wfile.write(b"221 bye\r\n")
                        return
                    else:
                        self.wfile.write(b"250 localhost\r\n")
        self.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
