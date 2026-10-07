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

# Fake "internet" for the end-to-end run, lives in the "phone" namespace:
#   198.51.100.10:80    HTTP, says hello
#   198.51.100.10:5353  UDP echo
#   1.1.1.1:53          DNS over UDP and TCP, every A record is 198.51.100.10
import socket, struct, threading, http.server, socketserver, sys

INTERNET = "198.51.100.10"
DNS_IP = "1.1.1.1"

def dns_answer(query):
    ident = query[:2]
    # question section: name, type, class
    i = 12
    while query[i] != 0:
        i += query[i] + 1
    qend = i + 1 + 4
    qtype = struct.unpack(">H", query[i + 1:i + 3])[0]
    question = query[12:qend]
    name = []
    j = 12
    while query[j] != 0:
        name.append(query[j + 1:j + 1 + query[j]].decode()); j += query[j] + 1
    sys.stderr.write("DNS %s type=%d\n" % (".".join(name), qtype)); sys.stderr.flush()
    if qtype == 1:
        header = ident + struct.pack(">HHHHH", 0x8180, 1, 1, 0, 0)
        rr = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 60, 4) + socket.inet_aton(INTERNET)
        return header + question + rr
    header = ident + struct.pack(">HHHHH", 0x8180, 1, 0, 0, 0)
    return header + question

def dns_udp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind((DNS_IP, 53))
    while True:
        data, addr = s.recvfrom(2048)
        s.sendto(dns_answer(data), addr)

def dns_tcp():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((DNS_IP, 53)); s.listen(16)
    def serve(c):
        try:
            while True:
                h = c.recv(2)
                if len(h) < 2: return
                n = struct.unpack(">H", h)[0]
                q = b""
                while len(q) < n:
                    chunk = c.recv(n - len(q))
                    if not chunk: return
                    q += chunk
                a = dns_answer(q)
                c.sendall(struct.pack(">H", len(a)) + a)
        finally:
            c.close()
    while True:
        c, _ = s.accept()
        threading.Thread(target=serve, args=(c,), daemon=True).start()

def echo_udp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind((INTERNET, 5353))
    while True:
        data, addr = s.recvfrom(4096)
        s.sendto(data, addr)

class Hello(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"hello-from-the-internet"
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass

class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

for target in (dns_udp, dns_tcp, echo_udp):
    threading.Thread(target=target, daemon=True).start()
print("servers up", flush=True)
Server((INTERNET, 80), Hello).serve_forever()
