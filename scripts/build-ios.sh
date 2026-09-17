#!/bin/bash

set -euo pipefail

configuration="${IOS_BUILD_CONFIGURATION:-Release}"
derived_data_args=()

if [[ -n "${IOS_DERIVED_DATA_PATH:-}" ]]; then
  derived_data_args=(-derivedDataPath "$IOS_DERIVED_DATA_PATH")
fi

xcodebuild \
  -workspace example/ios/ClamshellExample.xcworkspace \
  -scheme ClamshellExample \
  -configuration "$configuration" \
  "${derived_data_args[@]}" \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
