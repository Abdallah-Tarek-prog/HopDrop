#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cli="$here/out/cli-publish/HopDrop.Cli"
[[ -x "$cli" ]] || { echo "Build the test CLI as described in Source/Linux/README.md." >&2; exit 1; }
work="$(mktemp -d)"
pid=''
cleanup() { if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi; rm -rf "$work"; }
trap cleanup EXIT
"$cli" --data "$work/a" --receive-dir "$work/received" --name "HopDrop Linux receiver" --port 17410 --qr-address 127.0.0.1 --auto-confirm-sas --ask accept qr > "$work/receiver.log" 2>&1 &
pid=$!
uri=''
for i in $(seq 1 100); do
  uri="$(sed -n 's/^Pairing URI: //p' "$work/receiver.log" | head -n 1)"
  [[ -n "$uri" ]] && break
  sleep 0.1
done
[[ -n "$uri" ]] || { cat "$work/receiver.log"; exit 1; }
id="$(printf '%s' "$uri" | sed -n 's/.*[?&]id=\([^&]*\).*/\1/p')"
"$cli" --data "$work/b" --port 17411 pair-qr "$uri"
python3 - "$work/b/devices.json" <<'PY'
import json,sys
items=json.load(open(sys.argv[1]))
assert len(items)==1 and items[0]['Platform']=='linux',items
print('linux pairing identity: PASS')
PY
printf 'HopDrop WSL file transfer\n' > "$work/sample.txt"
"$cli" --data "$work/b" --port 17411 send "$id" "$work/sample.txt"
found="$(find "$work/received" -type f -name sample.txt | head -n 1)"
[[ -n "$found" ]]
cmp "$work/sample.txt" "$found"
mkdir -p "$work/photos/sub"
printf 'folder file\n' > "$work/photos/sub/child.txt"
"$cli" --data "$work/b" --port 17411 send "$id" "$work/photos"
found_folder="$(find "$work/received" -type f -name child.txt | head -n 1)"
[[ -n "$found_folder" ]]
cmp "$work/photos/sub/child.txt" "$found_folder"
"$cli" --data "$work/b" --port 17411 text "$id" 'مرحبا من لينكس'
"$cli" --data "$work/c" --port 17412 --auto-confirm-sas pair-sas 127.0.0.1:17410
python3 - "$work/b/history.json" "$work/a/history.json" <<'PY'
import json,sys
for path in sys.argv[1:]:
    items=json.load(open(path))
    assert items and items[-1]['Transport']=='local_network',items
print('file/folder/text transfer and local_network activity: PASS')
PY
