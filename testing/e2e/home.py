#!/usr/bin/env python3

# Copyright (C) 2026 pyamsoft
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.  You may obtain a copy
# of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
# License for the specific language governing permissions and limitations
# under the License.

# "Home Wi-Fi": a DIFFERENT internet that happens to use the same address as the phone's internet
import http.server, socketserver
class Home(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"hello-from-HOME"
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
print("home up", flush=True)
Server(("198.51.100.10", 80), Home).serve_forever()
