#!/usr/bin/env python3
"""Reads and writes files on the ComputerCraft computer whose screen is open in the game.

  cc_transfer.py install            type the helper program `cb` into the computer (via the edit program)
  cc_transfer.py get <name> [file]  download <name> from the computer (to stdout or a local file)
  cc_transfer.py put <file> <name>  upload a local file as <name>

get/put talk to the `cb` helper (cb.lua) running on the computer; both ends verify length and checksum.
The computer must be at its shell prompt (run `mc cc terminate` first if a program is running).
"""
import json, os, re, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
INSTANCE = os.environ.get("MC_INSTANCE", os.path.expanduser(
    "~/Library/Application Support/PrismLauncher/instances/Lycodon-Client(1)/minecraft"))
URL = "http://127.0.0.1:25599"
TOKEN = open(os.path.join(INSTANCE, "claudebridge.token")).read().strip()


def req(path, body=None):
    r = urllib.request.Request(URL + path, data=body, headers={"X-Token": TOKEN}, method="POST" if body is not None else "GET")
    return json.loads(urllib.request.urlopen(r, timeout=10).read().decode("utf-8"))


def screen():
    s = req("/cc")
    if not s.get("open"):
        sys.exit("no computer screen open in the game")
    if s.get("inputError"):
        sys.exit("input failed: " + s["inputError"])
    return s["lines"]


def type_text(text, enter=True):
    req("/cc/type" + ("" if enter else "?enter=0"), text.encode("latin-1"))
    drain()


def key(code, times=1):
    req("/cc/key?code=%d&times=%d" % (code, times), b"")
    drain()


def drain():
    while req("/state")["ccPending"] > 0:
        time.sleep(0.1)


def wait_for(pred, what, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        lines = screen()
        if pred(lines):
            return lines
        time.sleep(0.15)
    sys.exit("timed out waiting for " + what + "; screen:\n" + "\n".join(screen()))


def checksum(data):
    return sum(data) % 65536


def enc(data):
    out = []
    for b in data:
        if b == 92: out.append("\\\\")
        elif b == 10: out.append("\\n")
        elif b == 9: out.append("\\t")
        elif 32 <= b <= 126: out.append(chr(b))
        else: out.append("\\%03d" % b)
    return "".join(out)


def dec(text):
    out, i = bytearray(), 0
    while i < len(text):
        c = text[i]
        if c != "\\":
            out.append(ord(c)); i += 1
        elif text[i + 1] == "n": out.append(10); i += 2
        elif text[i + 1] == "t": out.append(9); i += 2
        elif text[i + 1] == "\\": out.append(92); i += 2
        else: out.append(int(text[i + 1:i + 4])); i += 4
    return bytes(out)


def get(name):
    type_text("cb get " + name)
    header = re.compile(r"^CB (\d+)/(\d+) (\d+) (\d+)$")
    parts, page = [], 1
    while True:
        def ready(lines, page=page):
            if lines[0].startswith("CB NOFILE") or any(l.startswith("CB NOFILE") for l in lines):
                sys.exit("no such file on the computer: " + name)
            m = header.match(lines[0])
            return bool(m) and int(m.group(1)) == page
        lines = wait_for(ready, "page %d of %s" % (page, name))
        m = header.match(lines[0])
        pages, size, want = int(m.group(2)), int(m.group(3)), int(m.group(4))
        for row in lines[1:]:
            if row.endswith("|"):
                parts.append(row[:-1])
        key(28)
        if page == pages:
            break
        page += 1
    wait_for(lambda l: any(x.startswith("CB DONE") for x in l), "end of transfer")
    data = dec("".join(parts))
    if len(data) != size or checksum(data) != want:
        sys.exit("transfer corrupted: got %d bytes sum %d, computer says %d bytes sum %d" % (len(data), checksum(data), size, want))
    return data


def put(data, name):
    type_text("cb put " + name)
    time.sleep(0.4)
    body = "\n".join(":" + enc(line) for line in data.split(b"\n"))
    type_text(body + "\n=%d %d" % (len(data), checksum(data)))
    lines = wait_for(lambda l: any(x.startswith("CB OK") or x.startswith("CB FAIL") for x in l), "upload result", 60)
    result = [x for x in lines if x.startswith("CB OK") or x.startswith("CB FAIL")][-1]
    if not result.startswith("CB OK"):
        sys.exit("upload rejected by the computer (" + result + "), file not written")
    return result


def install():
    """Bootstrap: type cb.lua through the edit program, then let cb read itself back to prove it arrived intact."""
    data = open(os.path.join(HERE, "cb.lua"), "rb").read()
    type_text("rm cb\nedit cb")
    wait_for(lambda l: "Ctrl" in l[-1], "the editor")
    type_text(data.decode("ascii").rstrip("\n"), enter=False)
    key(29); key(28)            # Ctrl, Enter  -> [Save]
    time.sleep(0.5)
    key(29); key(205); key(28)  # Ctrl, Right, Enter -> Exit
    time.sleep(0.5)
    back = get("cb")
    if back.rstrip(b"\n") != data.rstrip(b"\n"):
        sys.exit("cb arrived damaged on the computer (%d bytes instead of %d)" % (len(back), len(data)))
    print("cb installed and verified (%d bytes)" % len(back))


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["install"]:
        install()
    elif a[:1] == ["get"] and len(a) >= 2:
        d = get(a[1])
        if len(a) > 2:
            open(a[2], "wb").write(d); print("%s: %d bytes" % (a[2], len(d)))
        else:
            sys.stdout.buffer.write(d)
    elif a[:1] == ["put"] and len(a) == 3:
        print(put(open(a[1], "rb").read(), a[2]))
    else:
        sys.exit(__doc__)
