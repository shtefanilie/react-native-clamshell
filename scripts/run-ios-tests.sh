#!/bin/bash

set -euo pipefail

device_id="${IOS_SIMULATOR_UDID:-}"
if [[ -z "$device_id" ]]; then
  device_id="$(xcrun simctl list devices available --json | node -e '
const fs = require("node:fs")
const devices = Object.entries(JSON.parse(fs.readFileSync(0, "utf8")).devices)
  .filter(([runtime]) => runtime.includes("SimRuntime.iOS-"))
  .sort(([a], [b]) => b.localeCompare(a, undefined, { numeric: true }))
  .flatMap(([, devices]) => devices)
  .filter(device => device.isAvailable && device.name.startsWith("iPhone"))
const booted = devices.find(device => device.state === "Booted")
const selected = booted ?? devices[0]
if (!selected) {
  console.error("No available iPhone simulator; install an iOS runtime first.")
  process.exit(1)
}
process.stdout.write(selected.udid)
')"
fi

selectors=()
if [[ $# -eq 0 ]]; then
  selectors+=("-only-testing:ClamshellTests")
else
  for selector in "$@"; do
    case "$selector" in
      ClamshellTests|ClamshellTests/*) ;;
      test*) selector="ClamshellTests/ClamshellNativeTests/$selector" ;;
      *) selector="ClamshellTests/$selector" ;;
    esac
    selectors+=("-only-testing:$selector")
  done
fi

xcodebuild \
  -workspace example/ios/ClamshellExample.xcworkspace \
  -scheme ClamshellExample \
  -derivedDataPath "${IOS_DERIVED_DATA_PATH:-example/ios/build}" \
  -destination "platform=iOS Simulator,id=${device_id}" \
  -parallel-testing-enabled NO \
  "${selectors[@]}" \
  CODE_SIGNING_ALLOWED=NO \
  test
