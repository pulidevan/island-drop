# Stand-in for the Android receiver: same Bonjour type + wire protocol.
import socket, json, subprocess, sys, os
out = sys.argv[1]
srv = socket.socket(); srv.bind(("0.0.0.0", 0)); srv.listen(1)
port = srv.getsockname()[1]
adv = subprocess.Popen(["dns-sd", "-R", "Test Pixel", "_islanddrop._tcp", "local", str(port)], stdout=subprocess.DEVNULL)
print("listening", port, flush=True)
try:
    conn, _ = srv.accept()
    f = conn.makefile("rb")
    header = json.loads(f.readline())
    print("header", header, flush=True)
    conn.sendall(b"ACCEPT\n")
    for fi in header["files"]:
        data = f.read(fi["size"])
        open(os.path.join(out, fi["name"]), "wb").write(data)
        print("saved", fi["name"], len(data), flush=True)
    conn.sendall(b"DONE\n")
    conn.close()
finally:
    adv.terminate()
