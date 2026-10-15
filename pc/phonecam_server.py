#!/usr/bin/env python3
"""
PhoneCam server for Assetto Corsa (Windows).

Relays pose data from the Android app to the PhoneCam Lua app running inside
Assetto Corsa (Custom Shaders Patch), and installs that Lua app for you.

    phone  --ws://<PC-IP>:8765/phone-->  this server  --ws://127.0.0.1:8765/ac-->  AC Lua app

Run:  python phonecam_server.py          (GUI)
      python phonecam_server.py --nogui  (console only)
"""
import argparse
import asyncio
import os
import re
import shutil
import socket
import sys
import threading
import time

try:
    import websockets
except ImportError:
    print("Missing dependency. Run:  pip install websockets")
    sys.exit(1)

PORT = 8765
HERE = os.path.dirname(os.path.abspath(__file__))
LUA_SRC = os.path.normpath(os.path.join(HERE, "..", "ac_lua", "PhoneCam"))



# --------------------------------------------------------------------------
# Blinkers: the phone sends "B L" / "B R" / "B H"; we press the key you bound
# in Assetto Corsa (Controls) using real keyboard scancodes (works with DirectInput).
# --------------------------------------------------------------------------
import json

CONFIG_PATH = os.path.join(HERE, "phonecam_config.json")
DEFAULT_KEYS = {"left": "Num4", "right": "Num6", "hazard": "Num5", "photo": "F12"}   # F12 = Steam screenshot key

SCANCODES = {
    **{str(i): 0x01 + i for i in range(1, 10)}, "0": 0x0B,
    "Q": 0x10, "W": 0x11, "E": 0x12, "R": 0x13, "T": 0x14, "Y": 0x15, "U": 0x16, "I": 0x17, "O": 0x18, "P": 0x19,
    "A": 0x1E, "S": 0x1F, "D": 0x20, "F": 0x21, "G": 0x22, "H": 0x23, "J": 0x24, "K": 0x25, "L": 0x26,
    "Z": 0x2C, "X": 0x2D, "C": 0x2E, "V": 0x2F, "B": 0x30, "N": 0x31, "M": 0x32,
    "[": 0x1A, "]": 0x1B, ";": 0x27, "'": 0x28, ",": 0x33, ".": 0x34, "/": 0x35, "\\": 0x2B,
    "Num0": 0x52, "Num1": 0x4F, "Num2": 0x50, "Num3": 0x51, "Num4": 0x4B, "Num5": 0x4C,
    "Num6": 0x4D, "Num7": 0x47, "Num8": 0x48, "Num9": 0x49,
    **{f"F{i}": 0x3A + i for i in range(1, 11)}, "F11": 0x57, "F12": 0x58,
}


def load_config():
    cfg = dict(DEFAULT_KEYS)
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update({k: v for k, v in json.load(f).items() if k in DEFAULT_KEYS and v in SCANCODES})
    except Exception:
        pass
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
    except Exception:
        pass


def press_key(name, hold=0.06):
    """Press and release a key via SendInput (Windows only)."""
    sc = SCANCODES.get(name)
    if sc is None:
        return False
    if os.name != "nt":
        print(f"[blinker] (not Windows) would press {name}")
        return True
    import ctypes
    from ctypes import wintypes

    ULONG_PTR = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class _U(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]

    def send(flags):
        inp = INPUT(type=1, u=_U(ki=KEYBDINPUT(0, sc, 0x0008 | flags, 0, 0)))   # KEYEVENTF_SCANCODE
        ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    send(0)
    time.sleep(hold)
    send(0x0002)      # KEYEVENTF_KEYUP
    return True


def find_steam_screenshots():
    """Newest Steam screenshots folder for Assetto Corsa (app 244210), or None."""
    import glob
    roots = []
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            roots.append(winreg.QueryValueEx(k, "SteamPath")[0])
    except Exception:
        pass
    roots += [r"C:\Program Files (x86)\Steam", r"C:\Program Files\Steam"]
    found = []
    for r in roots:
        found += glob.glob(os.path.join(r, "userdata", "*", "760", "remote", "244210", "screenshots"))
    found = [f for f in found if os.path.isdir(f)]
    return max(found, key=os.path.getmtime) if found else None


# --------------------------------------------------------------------------
# Relay server
# --------------------------------------------------------------------------
class Relay:
    def __init__(self, log):
        self.log = log
        self.phones = set()
        self.acs = set()
        self.loop = None
        self.packets = 0
        self.last_packet = 0.0
        self._stop = None
        self.error = None
        self.keys = load_config()

    @staticmethod
    def _path(ws):
        p = getattr(ws, "path", None)
        if p is None:
            req = getattr(ws, "request", None)
            p = getattr(req, "path", "") if req is not None else ""
        return p or ""

    def _to_ac(self, msg):
        for ws in list(self.acs):
            asyncio.ensure_future(self._safe_send(ws, msg))

    @staticmethod
    async def _safe_send(ws, msg):
        try:
            await ws.send(msg)
        except Exception:
            pass

    def _blinker(self, code):
        name = {"L": "left", "R": "right", "H": "hazard", "P": "photo"}.get(code)
        if not name:
            return
        key = self.keys.get(name)
        self.log(f"{name.capitalize()}: pressing {key}")
        threading.Thread(target=press_key, args=(key,), daemon=True).start()

    async def handler(self, ws, path=None):
        path = path or self._path(ws)
        if path.startswith("/phone"):
            self.phones.add(ws)
            self.log("Phone connected")
            self._to_ac("S 1")
            try:
                async for msg in ws:
                    if isinstance(msg, str):
                        if msg.startswith("B "):
                            self._blinker(msg[2:].strip())
                            continue
                        self.packets += 1
                        self.last_packet = time.time()
                        self._to_ac(msg)
            except Exception:
                pass
            finally:
                self.phones.discard(ws)
                self.log("Phone disconnected")
                if not self.phones:
                    self._to_ac("S 0")
        elif path.startswith("/ac"):
            self.acs.add(ws)
            self.log("Assetto Corsa connected")
            try:
                await ws.send("S 1" if self.phones else "S 0")
                async for _ in ws:  # AC does not send anything important
                    pass
            except Exception:
                pass
            finally:
                self.acs.discard(ws)
                self.log("Assetto Corsa disconnected")
        else:
            await ws.close()

    async def _main(self):
        self._stop = asyncio.Event()
        # ping_interval=None: the AC-side socket may not answer pings
        async with websockets.serve(self.handler, "0.0.0.0", PORT,
                                    ping_interval=None, max_size=2 ** 16):
            self.log(f"Server listening on port {PORT}")
            await self._stop.wait()

    def run(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._main())
        except OSError as e:
            self.error = str(e)
            self.log(f"ERROR: cannot start server: {e} (is another copy running?)")
        except Exception as e:  # pragma: no cover
            self.error = str(e)
            self.log(f"ERROR: {e}")

    def start_thread(self):
        t = threading.Thread(target=self.run, daemon=True)
        t.start()
        return t

    def stop(self):
        if self.loop and self._stop:
            self.loop.call_soon_threadsafe(self._stop.set)


# --------------------------------------------------------------------------
# Helpers: IPs and Assetto Corsa location
# --------------------------------------------------------------------------
def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # no packet is actually sent
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips or ["<your PC IP>"]


def find_ac_folder():
    """Try to find the assettocorsa install folder (Steam)."""
    candidates = []
    try:
        import winreg
        for hive, key, val in (
            (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath"),
        ):
            try:
                with winreg.OpenKey(hive, key) as k:
                    steam = winreg.QueryValueEx(k, val)[0]
                    candidates.append(os.path.join(steam, "steamapps", "common", "assettocorsa"))
                    vdf = os.path.join(steam, "steamapps", "libraryfolders.vdf")
                    if os.path.isfile(vdf):
                        with open(vdf, encoding="utf-8", errors="ignore") as f:
                            for m in re.finditer(r'"path"\s+"([^"]+)"', f.read()):
                                lib = m.group(1).replace("\\\\", "\\")
                                candidates.append(os.path.join(lib, "steamapps", "common", "assettocorsa"))
            except OSError:
                continue
    except ImportError:
        pass
    for drive in "CDEFGH":
        candidates.append(rf"{drive}:\Program Files (x86)\Steam\steamapps\common\assettocorsa")
        candidates.append(rf"{drive}:\SteamLibrary\steamapps\common\assettocorsa")
        candidates.append(rf"{drive}:\Steam\steamapps\common\assettocorsa")
    for c in candidates:
        if os.path.isfile(os.path.join(c, "AssettoCorsa.exe")):
            return c
    return ""


def install_lua(ac_folder):
    if not os.path.isdir(LUA_SRC):
        raise RuntimeError(f"Lua app source not found: {LUA_SRC}")
    if not os.path.isfile(os.path.join(ac_folder, "AssettoCorsa.exe")):
        raise RuntimeError("That folder does not contain AssettoCorsa.exe")
    dst = os.path.join(ac_folder, "apps", "lua", "PhoneCam")
    os.makedirs(dst, exist_ok=True)
    shutil.copytree(LUA_SRC, dst, dirs_exist_ok=True)
    csp = os.path.isdir(os.path.join(ac_folder, "extension"))
    return dst, csp


# --------------------------------------------------------------------------
# GUI
# --------------------------------------------------------------------------
def run_gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, scrolledtext

    root = tk.Tk()
    root.title("AC PhoneCam")
    root.geometry("640x700")

    logbox = scrolledtext.ScrolledText(root, height=10, state="disabled")

    def log(msg):
        line = time.strftime("[%H:%M:%S] ") + msg + "\n"

        def _add():
            logbox.configure(state="normal")
            logbox.insert("end", line)
            logbox.see("end")
            logbox.configure(state="disabled")
        try:
            root.after(0, _add)
        except Exception:
            pass

    relay = Relay(log)
    relay.start_thread()

    ips = local_ips()
    tk.Label(root, text="1. Phone app > enter this PC address:", anchor="w",
             font=("Segoe UI", 10, "bold")).pack(fill="x", padx=12, pady=(12, 0))
    tk.Label(root, text="   " + "   or   ".join(f"{ip}:{PORT}" for ip in ips), anchor="w",
             font=("Consolas", 14)).pack(fill="x", padx=12)

    tk.Label(root, text="2. Install the camera app into Assetto Corsa (once):", anchor="w",
             font=("Segoe UI", 10, "bold")).pack(fill="x", padx=12, pady=(14, 0))
    frm = tk.Frame(root)
    frm.pack(fill="x", padx=12)
    path_var = tk.StringVar(value=find_ac_folder())
    tk.Entry(frm, textvariable=path_var).pack(side="left", fill="x", expand=True)

    def browse():
        d = filedialog.askdirectory(title="Select your assettocorsa folder")
        if d:
            path_var.set(d.replace("/", "\\"))
    tk.Button(frm, text="Browse...", command=browse).pack(side="left", padx=4)

    def do_install():
        try:
            dst, csp = install_lua(path_var.get())
            log(f"Installed to {dst}")
            if not csp:
                messagebox.showwarning(
                    "Custom Shaders Patch missing?",
                    "Installed, but no 'extension' folder was found.\n"
                    "Install Custom Shaders Patch (CSP) via Content Manager: "
                    "Settings > Custom Shaders Patch.")
            else:
                messagebox.showinfo("Done", "PhoneCam installed.\nStart Assetto Corsa and drive!")
        except Exception as e:
            messagebox.showerror("Install failed", str(e))
    tk.Button(root, text="Install / Update into Assetto Corsa", command=do_install).pack(
        padx=12, pady=6, anchor="w")

    tk.Label(root, text="Blinker keys (must match the keys bound in AC > Options > Controls):", anchor="w",
             font=("Segoe UI", 10, "bold")).pack(fill="x", padx=12, pady=(10, 0))
    kf = tk.Frame(root)
    kf.pack(fill="x", padx=12)
    from tkinter import ttk
    key_vars = {}
    for label, name in (("Left", "left"), ("Hazards", "hazard"), ("Right", "right"), ("Photo", "photo")):
        tk.Label(kf, text=label + ":").pack(side="left")
        v = tk.StringVar(value=relay.keys[name])
        key_vars[name] = v
        cb = ttk.Combobox(kf, textvariable=v, values=list(SCANCODES.keys()), width=6, state="readonly")
        cb.pack(side="left", padx=(2, 10))

        def _changed(*_a, n=name, var=v):
            relay.keys[n] = var.get()
            save_config(relay.keys)
        v.trace_add("write", _changed)

    def test(name):
        log(f"Test {name} in 3 s - switch to Assetto Corsa now")
        root.after(3000, lambda: threading.Thread(target=press_key, args=(relay.keys[name],), daemon=True).start())
    tf = tk.Frame(root)
    tf.pack(fill="x", padx=12, pady=(4, 0))
    for label, name in (("Test left", "left"), ("Test hazards", "hazard"), ("Test right", "right"), ("Test photo", "photo")):
        tk.Button(tf, text=label, command=lambda n=name: test(n)).pack(side="left", padx=(0, 6))

    def open_shots():
        d = find_steam_screenshots()
        if d and os.name == "nt":
            os.startfile(d)
        else:
            messagebox.showinfo("Screenshots", "Folder not found. In Steam: View > Screenshots > Show on disk.")
    tk.Button(root, text="Open Steam screenshots folder (Assetto Corsa)", command=open_shots).pack(
        padx=12, pady=(6, 0), anchor="w")
    tk.Label(root, text="Photo = Steam's screenshot key (F12 by default, see Steam > Settings > In Game). "
             "Needs AC launched through Steam.", anchor="w", fg="gray").pack(fill="x", padx=12)

    tk.Label(root, text="3. Keep this window open. Status:", anchor="w",
             font=("Segoe UI", 10, "bold")).pack(fill="x", padx=12, pady=(10, 0))
    status = tk.Label(root, text="", anchor="w", justify="left", font=("Consolas", 11))
    status.pack(fill="x", padx=12)

    logbox.pack(fill="both", expand=True, padx=12, pady=12)

    def tick():
        rate = "streaming" if time.time() - relay.last_packet < 1 else "idle"
        status.config(text=(
            f"Phone:          {'connected (' + rate + ')' if relay.phones else 'not connected'}\n"
            f"Assetto Corsa:  {'connected' if relay.acs else 'not connected (start a session)'}\n"
            f"Packets relayed: {relay.packets}"))
        root.after(300, tick)
    tick()

    def on_close():
        relay.stop()
        root.destroy()
    root.protocol("WM_DELETE_WINDOW", on_close)
    root.mainloop()


def run_console():
    relay = Relay(lambda m: print(time.strftime("[%H:%M:%S] ") + m, flush=True))
    print("Phone app address:", "  or  ".join(f"{ip}:{PORT}" for ip in local_ips()))
    ac = find_ac_folder()
    print("AC folder:", ac or "(not found - use the GUI or copy ac_lua/PhoneCam to <AC>/apps/lua/)")
    try:
        relay.run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--nogui", action="store_true")
    args = ap.parse_args()
    if args.nogui:
        run_console()
    else:
        try:
            run_gui()
        except ImportError:
            run_console()
