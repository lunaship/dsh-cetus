#!/bin/sh
# Prints one xcodebuild -destination.
# PERFORMANCE_IOS_RUNTIME pins an exact runtime, for example "iOS 26.5".
# Without it, the newest available iPhone 17 Pro is selected.
set -eu
REQUIRED="${PERFORMANCE_IOS_RUNTIME:-}"
UDID=$(REQUIRED="$REQUIRED" xcrun simctl list devices available -j | python3 -c '
import json, os, sys
required = os.environ.get("REQUIRED", "")
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
        if device.get("name") == "iPhone 17 Pro" and device.get("isAvailable", True):
            found.append((runtime, label, device["udid"]))
if not found:
    target = required or "any iOS"
    sys.exit("no iPhone 17 Pro simulator for " + target)
found.sort()
chosen = found[-1]
print(chosen[2])
print("simulator-runtime=" + chosen[1], file=sys.stderr)
')
printf "platform=iOS Simulator,id=%s\n" "$UDID"
