#!/bin/sh
# Prints one xcodebuild -destination for the newest available iPhone 17 Pro simulator.
set -eu
UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
data = json.load(sys.stdin)
found = []
for runtime, devices in data.get("devices", {}).items():
    if "iOS" not in runtime:
        continue
    for device in devices:
        if device.get("name") == "iPhone 17 Pro" and device.get("isAvailable", True):
            found.append((runtime, device["udid"]))
if not found:
    sys.exit("no iPhone 17 Pro simulator")
found.sort()
print(found[-1][1])
')
printf "platform=iOS Simulator,id=%s\n" "$UDID"
