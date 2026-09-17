#!/bin/bash

set -euo pipefail

test_name="${1:?test method name is required}"
device_id="$(xcrun simctl list devices available --json | node -e '
const fs = require("node:fs")
const devices = Object.values(JSON.parse(fs.readFileSync(0, "utf8")).devices)
  .flat()
  .filter(device => device.isAvailable && device.name.startsWith("iPhone"))
const booted = devices.find(device => device.state === "Booted")
const selected = booted ?? devices[0]
if (!selected) process.exit(1)
process.stdout.write(selected.udid)
')"

xcodebuild \
  -workspace example/ios/ClamshellExample.xcworkspace \
  -scheme ClamshellExample \
  -destination "platform=iOS Simulator,id=${device_id}" \
  -only-testing:"ClamshellTests/ClamshellNativeTests/${test_name}" \
  CODE_SIGNING_ALLOWED=NO \
  test
