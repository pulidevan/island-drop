import socket, json, subprocess, re, sys, time, os
path = sys.argv[1]
p = subprocess.Popen(["dns-sd", "-B", "_islanddrop-mac._tcp", "local"], stdout=subprocess.PIPE, text=True)
name = None; t0 = time.time()
for line in p.stdout:
    m = re.search(r"Add\s+\d+\s+\d+\s+local\.\s+_islanddrop-mac\._tcp\.\s+(.+)$", line)
    if m: name = m.group(1).strip(); break
    if time.time() - t0 > 6: break
p.kill(); print("found", name, flush=True)
r = subprocess.Popen(["dns-sd", "-L", name, "_islanddrop-mac._tcp", "local"], stdout=subprocess.PIPE, text=True)
for line in r.stdout:
    m = re.search(r"can be reached at (\S+?)\.?:(\d+)", line)
    if m: host, port = m.group(1), int(m.group(2)); break
r.kill(); print("at", host, port, flush=True)
s = socket.create_connection(("127.0.0.1", port))
data = open(path, "rb").read()
hdr = {"v":1, "type":"files", "sender":"Test Device", "files":[{"name":os.path.basename(path), "size":len(data), "mime":"application/pdf"}], "total":len(data)}
s.sendall((json.dumps(hdr)+"\n").encode())
f = s.makefile("rb")
ans = f.readline().strip(); print("reply", ans, flush=True)
if ans == b"ACCEPT":
    s.sendall(data); print("done?", f.readline().strip(), flush=True)
