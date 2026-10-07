#!/bin/sh
# Prints one xcodebuild -destination.
# PERFORMANCE_IOS_RUNTIME pins an exact runtime, for example "iOS 26.5".
# SIMULATOR_DEVICE_NAME selects the device. It defaults to iPhone 17 Pro.
# Without a pinned runtime, the newest available match is selected.
set -eu
REQUIRED="${PERFORMANCE_IOS_RUNTIME:-}"
DEVICE_NAME="${SIMULATOR_DEVICE_NAME:-iPhone 17 Pro}"
UDID=$(xcrun simctl list devices available -j | REQUIRED="$REQUIRED" DEVICE_NAME="$DEVICE_NAME" python3 -c '
import json, os, sys
required = os.environ.get("REQUIRED", "")
device_name = os.environ.get("DEVICE_NAME", "iPhone 17 Pro")
data = json.load(sys.stdin)
found = []
for runtime, devices in data.get("devices", {}).items():
    if "iOS" not in runtime:
        continue
    raw = runtime.rsplit(".", 1)[-1]
    label = "iOS " + raw.removeprefix("iOS-").replace("-", ".", 1)
    if required and label != required:
        continue
    for device in devices:
        if device.get("name") == device_name and device.get("isAvailable", True):
            found.append((runtime, label, device["udid"]))
if not found:
    target = required or "any iOS"
    sys.exit("no " + device_name + " simulator for " + target)
found.sort()
chosen = found[-1]
print(chosen[2])
print("simulator-runtime=" + chosen[1], file=sys.stderr)
print("simulator-device=" + device_name, file=sys.stderr)
')
printf "platform=iOS Simulator,id=%s\n" "$UDID"
