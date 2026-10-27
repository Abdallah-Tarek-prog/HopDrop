#!/usr/bin/env python3
"""Native app/CLI/MCP integration, using synthetic data and an isolated opted-in profile."""
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parents[3]
app = str(repo / "Source/Linux/out/publish/HopDrop.Linux")
cli = str(repo / "Source/Linux/out/cli-publish/HopDrop.Cli")

with socket.socket() as probe:
    try:
        probe.bind(("127.0.0.1", 7410))
    except OSError:
        raise SystemExit("Port 7410 must be free for the isolated cold-start test.")

with tempfile.TemporaryDirectory(prefix="hopdrop-native-agents-") as directory:
    root = Path(directory)
    profile = str(root / "sender")
    receiver = None

    def hosts():
        found = []
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                args = (entry / "cmdline").read_bytes().decode().split("\0")
            except (OSError, UnicodeError):
                continue
            if args and args[0] == app and "--minimized" in args and "--data" in args:
                if args[args.index("--data") + 1] == profile:
                    found.append(int(entry.name))
        return found

    def stop_host():
        for pid in hosts():
            os.kill(pid, signal.SIGTERM)
        for _ in range(100):
            if not hosts():
                return
            time.sleep(0.05)
        raise AssertionError("Isolated app did not stop")

    def command(args, **options):
        result = subprocess.run(args, capture_output=True, text=True, timeout=45, **options)
        assert result.returncode == 0, (result.stdout, result.stderr)
        return result.stdout

    def tool(name, arguments):
        messages = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "hopdrop-native-test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": name, "arguments": arguments}},
        ]
        output = command([app, "--mcp", "--data", profile], input="".join(json.dumps(m) + "\n" for m in messages))
        replies = [json.loads(line) for line in output.splitlines()]
        reply = next(r for r in replies if r.get("id") == 2)
        result = reply["result"]
        assert not result.get("isError"), result
        return json.loads(result["content"][0]["text"])

    try:
        log_path = root / "receiver.log"
        with log_path.open("w") as log:
            receiver = subprocess.Popen([cli, "--data", str(root / "receiver"), "--receive-dir", str(root / "received"), "--name", "Native test receiver", "--port", "0", "--qr-address", "127.0.0.1", "qr"], stdout=log, stderr=log)
        uri = None
        for _ in range(100):
            uri = next((line.removeprefix("Pairing URI: ") for line in log_path.read_text().splitlines() if line.startswith("Pairing URI: ")), None)
            if uri:
                break
            time.sleep(0.1)
        assert uri, log_path.read_text()
        command([cli, "--data", profile, "--receive-dir", str(root / "sender-received"), "--port", "7410", "pair-qr", uri])
        preferences = Path(profile) / "linux.json"
        preferences.write_text(json.dumps({"Agents": True, "Language": "ar", "Theme": "dark"}))

        devices = json.loads(command([app, "agent", "devices", "--data", profile]))
        assert devices["ok"] and len(devices["devices"]) == 1 and hosts(), devices
        stop_host()
        assert len(tool("list_devices", {})["devices"]) == 1 and hosts()
        print("PASS: CLI and MCP cold launch, isolated preference, clean protocol streams", flush=True)

        (root / "Fixture/sub").mkdir(parents=True)
        for relative, value in {"requested.txt": b"requested synthetic file\n", "Fixture/sub/A.txt": b"upper\n", "Fixture/sub/a.txt": b"lower\n"}.items():
            (root / relative).write_bytes(value)
        sent = tool("send_files", {"device": "Native test receiver", "paths": [str(root / "requested.txt"), str(root / "Fixture")], "wait_seconds": 20})
        assert sent["status"] == "sent" and sent["files"] == 3, sent
        assert tool("transfer_status", {"transfer_id": sent["transferId"]})["status"] == "sent"
        for relative in ["requested.txt", "Fixture/sub/A.txt", "Fixture/sub/a.txt"]:
            assert hashlib.sha256((root / relative).read_bytes()).digest() == hashlib.sha256((root / "received" / relative).read_bytes()).digest()
        recent = tool("recent_transfers", {"direction": "sent", "limit": 1})["transfers"][0]
        assert recent["result"] == "ok" and recent["transport"] == "local_network", recent
        print("PASS: native MCP send/status/activity, nested folder, exact filenames and file hashes", flush=True)

        stop_host()
        preferences.write_text(json.dumps({"Agents": False}))
        disabled = subprocess.run([app, "agent", "devices", "--data", profile], capture_output=True, text=True, timeout=40)
        assert disabled.returncode == 1 and json.loads(disabled.stdout)["ok"] is False, disabled.stdout
        print("PASS: disabled native preference denies agent access", flush=True)
    finally:
        stop_host()
        if receiver is not None:
            receiver.terminate()
            receiver.wait(timeout=10)
