from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit

payload = Path(sys.argv[1]).read_bytes()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if urlsplit(self.path).path != "/video.mp4":
            self.send_error(404)
            return
        requested = self.headers.get("Range")
        first, last = 0, len(payload) - 1
        if requested:
            span = requested.removeprefix("bytes=").split("-")
            first = int(span[0])
            last = min(int(span[1]) if span[1] else last, last)
        if first >= len(payload):
            self.send_response(416)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = payload[first:last + 1]
        time.sleep(0.12)
        self.send_response(206 if requested else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        if requested:
            self.send_header("Content-Range", f"bytes {first}-{last}/{len(payload)}")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
Path(sys.argv[2]).write_text(str(server.server_port))
server.serve_forever()
