"""PC-only password recovery page for the project's localhost Site URL."""

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


DIRECTORY = Path(__file__).resolve().parent
PROJECT = DIRECTORY.parent.parent


def load_config():
    properties = {}
    path = PROJECT / "ShareGuardAndroid" / "local.properties"
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    url = properties.get("SUPABASE_URL", "").rstrip("/")
    key = properties.get("SUPABASE_PUBLISHABLE_KEY", "")
    if not re.fullmatch(r"https://[a-z0-9]+\.supabase\.co", url):
        raise ValueError("Set a hosted SUPABASE_URL in ShareGuardAndroid/local.properties.")
    if not re.fullmatch(r"sb_publishable_[A-Za-z0-9_-]+", key):
        raise ValueError("Set SUPABASE_PUBLISHABLE_KEY; never use an admin/secret key here.")
    return {"supabaseUrl": url, "publishableKey": key}


def make_handler(config):
    class RecoveryHandler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            # Do not log callback URLs or credentials. This server has no write API.
            pass

        def do_GET(self):
            host = self.headers.get("Host", "").lower()
            if host not in {"localhost:3000", "127.0.0.1:3000"}:
                self.reply(403, b"Local access only.", "text/plain")
                return
            path = urlsplit(self.path).path
            assets = {
                "/": ("index.html", "text/html; charset=utf-8"),
                "/recovery.js": ("recovery.js", "text/javascript; charset=utf-8"),
                "/style.css": ("style.css", "text/css; charset=utf-8"),
            }
            if path == "/config.json":
                self.reply(200, json.dumps(config).encode(), "application/json")
            elif path in assets:
                filename, content_type = assets[path]
                self.reply(200, (DIRECTORY / filename).read_bytes(), content_type)
            elif path == "/favicon.ico":
                self.reply(204, b"", "image/x-icon")
            else:
                self.reply(404, b"Not found.", "text/plain")

        def reply(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Security-Policy", (
                "default-src 'none'; script-src 'self'; style-src 'self'; "
                f"connect-src 'self' {config['supabaseUrl']}; "
                "form-action 'none'; base-uri 'none'; frame-ancestors 'none'"
            ))
            self.end_headers()
            self.wfile.write(body)

    return RecoveryHandler


if __name__ == "__main__":
    config = load_config()
    with ThreadingHTTPServer(("127.0.0.1", 3000), make_handler(config)) as server:
        print("ShareGuard recovery: http://localhost:3000", flush=True)
        print("Open a new password-recovery email on THIS PC. Ctrl+C stops the server.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
