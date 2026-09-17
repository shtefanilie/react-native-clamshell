#!/bin/bash

set -euo pipefail
export RN_HARNESS_DEBUG_USE_WATCHMAN=0

platform="${1:?platform is required}"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
example_dir="${root_dir}/example"

if [[ "${platform}" == "android" ]]; then
  "${example_dir}/android/gradlew" -p "${example_dir}/android" assembleDebug --no-daemon --build-cache
  apk_candidates=("${example_dir}"/android/app/build/outputs/apk/debug/*.apk)
  if [[ ! -f "${apk_candidates[0]}" ]]; then
    echo "Unable to locate the built Android app bundle." >&2
    exit 1
  fi
  export HARNESS_APP_PATH="${apk_candidates[0]}"
elif [[ "${platform}" == "ios" ]]; then
  device_selection="$(xcrun simctl list devices available --json | node -e '
const fs = require("node:fs")
const devices = Object.entries(JSON.parse(fs.readFileSync(0, "utf8")).devices)
  .flatMap(([runtime, entries]) => entries
    .filter(device => device.isAvailable && device.name.startsWith("iPhone"))
    .map(device => ({ ...device, version: runtime.match(/iOS-(\d+(?:-\d+)*)$/)?.[1]?.replaceAll("-", ".") })))
const selected = devices.find(device => device.state === "Booted") ?? devices[0]
if (!selected?.version) process.exit(1)
process.stdout.write(`${selected.name}|${selected.version}|${selected.udid}|${selected.state}`)
')"
  IFS='|' read -r DEVICE_MODEL IOS_VERSION device_udid device_state <<< "${device_selection}"
  export DEVICE_MODEL IOS_VERSION
  yarn pod
  xcodebuild CC=clang CPLUSPLUS=clang++ LD=clang LDPLUSPLUS=clang++ \
    -derivedDataPath "${example_dir}/ios/build" \
    -UseModernBuildSystem=YES \
    -workspace "${example_dir}/ios/ClamshellExample.xcworkspace" \
    -scheme ClamshellExample \
    -sdk iphonesimulator \
    -configuration Debug \
    build CODE_SIGNING_ALLOWED=NO
  app_candidates=("${example_dir}"/ios/build/Build/Products/Debug-iphonesimulator/*.app)
  if [[ ! -d "${app_candidates[0]}" ]]; then
    echo "Unable to locate the built iOS app bundle." >&2
    exit 1
  fi
  export HARNESS_APP_PATH="${app_candidates[0]}"
  if [[ "${device_state}" != "Booted" ]]; then
    xcrun simctl boot "${device_udid}"
    xcrun simctl bootstatus "${device_udid}" -b
  fi
  xcrun simctl install "${device_udid}" "${HARNESS_APP_PATH}"
else
  echo "Unsupported platform: ${platform}" >&2
  exit 1
fi

pushd "${example_dir}" >/dev/null
yarn react-native-harness __tests__/worklet-bridge.harness.ts --no-watchman --harnessRunner "${platform}"
popd >/dev/null
