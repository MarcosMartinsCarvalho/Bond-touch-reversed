from __future__ import annotations

import json
import queue
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import tkinter as tk
from tkinter import messagebox, ttk


def runtime_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


APP_DIR = runtime_dir()
SETTINGS_PATH = APP_DIR / "settings.json"
DEFAULT_SETTINGS = {
    "listen_host": "127.0.0.1",
    "listen_port": 8765,
    "phone_host": "127.0.0.1",
    "phone_port": 7878,
    "auto_start": True,
}


def find_adb() -> Path | None:
    candidates = [
        APP_DIR / "tools" / "platform-tools" / "adb.exe",
        APP_DIR / "platform-tools" / "adb.exe",
        APP_DIR / "adb.exe",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    found = shutil.which("adb")
    return Path(found) if found else None


def bundled_apk_path() -> Path | None:
    candidates = [
        APP_DIR / "android" / "BondBandBridge-game.apk",
        APP_DIR / "BondBandBridge-game.apk",
        APP_DIR.parent / "dist" / "BondBandBridge-game.apk",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def load_settings() -> dict[str, Any]:
    if SETTINGS_PATH.exists():
        try:
            loaded = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
            merged = dict(DEFAULT_SETTINGS)
            for key in DEFAULT_SETTINGS:
                if key in loaded:
                    merged[key] = loaded[key]
            return merged
        except Exception:
            pass
    return dict(DEFAULT_SETTINGS)


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], app: "PhoneBridgeApp") -> None:
        super().__init__(address, BridgeHandler)
        self.app = app


class BridgeHandler(BaseHTTPRequestHandler):
    server: BridgeServer

    def do_GET(self) -> None:
        self.respond(200, {"ok": True, "message": "BondCraft PC phone bridge listening"})

    def do_POST(self) -> None:
        if self.path != "/event":
            self.respond(404, {"ok": False, "error": "Use POST /event"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(max(0, length))
            payload = json.loads(raw.decode("utf-8") or "{}")
        except Exception as exc:
            self.respond(400, {"ok": False, "error": str(exc)})
            return
        ok, detail = self.server.app.forward_event(payload)
        self.respond(200 if ok else 502, {"ok": ok, "detail": detail})

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def respond(self, code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)


class PhoneBridgeApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("BondCraft PC Phone Bridge")
        self.geometry("800x560")
        self.minsize(720, 460)

        self.settings = load_settings()
        self.server: BridgeServer | None = None
        self.server_thread: threading.Thread | None = None
        self.log_queue: queue.Queue[str] = queue.Queue()

        self.listen_host_var = tk.StringVar(value=str(self.settings["listen_host"]))
        self.listen_port_var = tk.IntVar(value=int(self.settings["listen_port"]))
        self.phone_host_var = tk.StringVar(value=str(self.settings["phone_host"]))
        self.phone_port_var = tk.IntVar(value=int(self.settings["phone_port"]))
        self.auto_start_var = tk.BooleanVar(value=bool(self.settings["auto_start"]))
        self.status_var = tk.StringVar(value="Stopped")

        self.build_ui()
        self.after(100, self.poll_log)
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        if self.auto_start_var.get():
            self.after(200, self.start_server)

    def build_ui(self) -> None:
        root = ttk.Frame(self, padding=12)
        root.pack(fill="both", expand=True)

        header = ttk.Frame(root)
        header.pack(fill="x")
        ttk.Label(header, text="BondCraft PC Phone Bridge", font=("Segoe UI", 18, "bold")).pack(side="left")
        ttk.Label(header, textvariable=self.status_var).pack(side="right")

        box = ttk.LabelFrame(root, text="Bridge")
        box.pack(fill="x", pady=(10, 8))
        self.entry(box, "Listen host", self.listen_host_var, 0, 0, 16)
        self.spin(box, "Listen port", self.listen_port_var, 0, 2, 1, 65535, 8)
        self.entry(box, "Phone host", self.phone_host_var, 1, 0, 18)
        self.spin(box, "Phone port", self.phone_port_var, 1, 2, 1, 65535, 8)
        ttk.Checkbutton(box, text="Auto start", variable=self.auto_start_var).grid(row=0, column=4, padx=6, sticky="w")
        ttk.Button(box, text="Start", command=self.start_server).grid(row=0, column=5, padx=4, sticky="ew")
        ttk.Button(box, text="Stop", command=self.stop_server).grid(row=0, column=6, padx=4, sticky="ew")
        ttk.Button(box, text="Save", command=self.save_settings).grid(row=1, column=4, padx=4, pady=(8, 0), sticky="ew")
        ttk.Button(box, text="ADB Forward", command=self.adb_forward).grid(row=1, column=5, padx=4, pady=(8, 0), sticky="ew")
        ttk.Button(box, text="Send Test", command=self.send_test).grid(row=1, column=6, padx=4, pady=(8, 0), sticky="ew")
        ttk.Button(box, text="Install APK", command=self.install_apk).grid(row=2, column=5, padx=4, pady=(8, 0), sticky="ew")
        ttk.Button(box, text="ADB Devices", command=self.show_adb_devices).grid(row=2, column=6, padx=4, pady=(8, 0), sticky="ew")
        for col in range(7):
            box.columnconfigure(col, weight=1 if col in {1, 3} else 0)

        hint = ttk.Label(
            root,
            text="Minecraft owns haptic settings. This app only forwards final patterns to the phone shown in the Android app, or over ADB Forward.",
            foreground="#475467",
            wraplength=720,
        )
        hint.pack(fill="x", pady=(0, 8))

        self.log_text = tk.Text(root, height=16, wrap="word", font=("Consolas", 10))
        self.log_text.pack(fill="both", expand=True)

    def entry(self, parent: ttk.Frame, label: str, var: tk.Variable, row: int, col: int, width: int) -> None:
        ttk.Label(parent, text=label).grid(row=row, column=col, sticky="w", padx=(8, 2), pady=4)
        ttk.Entry(parent, textvariable=var, width=width).grid(row=row, column=col + 1, sticky="ew", padx=(4, 8), pady=4)

    def spin(self, parent: ttk.Frame, label: str, var: tk.Variable, row: int, col: int, low: int, high: int, width: int) -> None:
        ttk.Label(parent, text=label).grid(row=row, column=col, sticky="w", padx=(8, 2), pady=4)
        tk.Spinbox(parent, textvariable=var, from_=low, to=high, width=width).grid(row=row, column=col + 1, sticky="ew", padx=(4, 8), pady=4)

    def collect_settings(self) -> dict[str, Any]:
        return {
            "listen_host": self.listen_host_var.get().strip() or "127.0.0.1",
            "listen_port": int(self.listen_port_var.get()),
            "phone_host": self.phone_host_var.get().strip() or "127.0.0.1",
            "phone_port": int(self.phone_port_var.get()),
            "auto_start": bool(self.auto_start_var.get()),
        }

    def save_settings(self) -> None:
        self.settings = self.collect_settings()
        SETTINGS_PATH.write_text(json.dumps(self.settings, indent=2), encoding="utf-8")
        self.log(f"Saved {SETTINGS_PATH}")

    def start_server(self) -> None:
        if self.server is not None:
            return
        self.save_settings()
        try:
            host = self.settings["listen_host"]
            port = int(self.settings["listen_port"])
            self.server = BridgeServer((host, port), self)
            self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.server_thread.start()
            self.status_var.set(f"Listening on http://{host}:{port}/event")
            self.log(f"Listening for Minecraft events on http://{host}:{port}/event")
        except Exception as exc:
            self.server = None
            self.server_thread = None
            self.status_var.set("Stopped")
            messagebox.showerror("Could not start bridge", str(exc))

    def stop_server(self) -> None:
        server = self.server
        if server is None:
            return
        self.server = None
        self.server_thread = None
        server.shutdown()
        server.server_close()
        self.status_var.set("Stopped")
        self.log("Stopped bridge.")

    def forward_event(self, payload: dict[str, Any]) -> tuple[bool, str]:
        settings = self.collect_settings()
        url = f"http://{settings['phone_host']}:{int(settings['phone_port'])}/event"
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=0.9) as response:
                response.read()
            event = payload.get("event", "?")
            self.log(f"{event} -> phone")
            return True, "forwarded"
        except urllib.error.URLError as exc:
            self.log(f"Phone forward failed: {exc}")
            return False, str(exc)
        except Exception as exc:
            self.log(f"Phone forward failed: {exc}")
            return False, str(exc)

    def adb_forward(self) -> None:
        port = int(self.phone_port_var.get())
        adb = find_adb()
        if adb is None:
            messagebox.showerror("ADB not found", "This package should include tools\\platform-tools\\adb.exe. Re-extract the full package, then try again.")
            return
        try:
            result = subprocess.run(
                [str(adb), "forward", f"tcp:{port}", f"tcp:{port}"],
                capture_output=True,
                text=True,
                timeout=8,
            )
        except Exception as exc:
            messagebox.showerror("ADB forward failed", str(exc))
            return
        if result.returncode != 0:
            messagebox.showerror("ADB forward failed", result.stderr.strip() or result.stdout.strip())
            return
        self.phone_host_var.set("127.0.0.1")
        self.save_settings()
        self.log(f"ADB forward active: PC 127.0.0.1:{port} -> phone 127.0.0.1:{port}")

    def install_apk(self) -> None:
        adb = find_adb()
        if adb is None:
            messagebox.showerror("ADB not found", "This package should include tools\\platform-tools\\adb.exe. Re-extract the full package, then try again.")
            return
        apk = bundled_apk_path()
        if apk is None:
            messagebox.showerror("APK not found", "This package should include android\\BondBandBridge-game.apk.")
            return
        try:
            self.log("Installing Android app over USB...")
            result = subprocess.run(
                [str(adb), "install", "-r", str(apk)],
                capture_output=True,
                text=True,
                timeout=60,
            )
        except Exception as exc:
            messagebox.showerror("Install failed", str(exc))
            return
        output = (result.stdout + "\n" + result.stderr).strip()
        if result.returncode != 0:
            messagebox.showerror("Install failed", output or "ADB install failed.")
            self.log(output or "ADB install failed.")
            return
        self.log(output or "Android app installed.")
        messagebox.showinfo("Installed", "Android app installed. Open it on the phone and connect the band.")

    def show_adb_devices(self) -> None:
        adb = find_adb()
        if adb is None:
            messagebox.showerror("ADB not found", "This package should include tools\\platform-tools\\adb.exe. Re-extract the full package, then try again.")
            return
        try:
            result = subprocess.run(
                [str(adb), "devices"],
                capture_output=True,
                text=True,
                timeout=10,
            )
        except Exception as exc:
            messagebox.showerror("ADB failed", str(exc))
            return
        output = (result.stdout + "\n" + result.stderr).strip()
        self.log(output or "No ADB output.")
        messagebox.showinfo("ADB Devices", output or "No ADB output.")

    def send_test(self) -> None:
        ok, detail = self.forward_event({
            "source": "pc_bridge_test",
            "final": True,
            "event": "pc_test",
            "player": socket.gethostname(),
            "pattern": "warning",
            "strength": 55,
            "duration": 90,
            "cooldown": 0,
            "priority": 90,
            "color": "#FFFFFF",
        })
        if not ok:
            messagebox.showwarning("Test failed", detail)

    def poll_log(self) -> None:
        while True:
            try:
                message = self.log_queue.get_nowait()
            except queue.Empty:
                break
            self.log_text.insert("end", f"{time.strftime('%H:%M:%S')}  {message}\n")
            self.log_text.see("end")
        self.after(100, self.poll_log)

    def log(self, message: str) -> None:
        self.log_queue.put(message)

    def on_close(self) -> None:
        try:
            self.save_settings()
        except Exception:
            pass
        self.stop_server()
        self.destroy()


def main() -> None:
    PhoneBridgeApp().mainloop()


if __name__ == "__main__":
    main()
