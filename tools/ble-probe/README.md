# BLE probe

Python3.11+ and Bleak3.0.2. From this folder:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe bond_ble_probe.py scan
.\.venv\Scripts\python.exe bond_ble_probe.py classify --target BOND
.\.venv\Scripts\python.exe bond_ble_probe.py payloads
```

`payloads` is offline. `vibrate-v2` sends a packet but does not perform a complete first-time auth flow. [Protocol and evidence](../../research/notes/protocol-map.md).
