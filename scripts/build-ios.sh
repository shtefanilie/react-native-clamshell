#!/bin/bash

set -euo pipefail

configuration="${IOS_BUILD_CONFIGURATION:-Release}"
xcodebuild_command=(
  xcodebuild
  -workspace example/ios/ClamshellExample.xcworkspace
  -scheme ClamshellExample
  -configuration "$configuration"
)

if [[ -n "${IOS_DERIVED_DATA_PATH:-}" ]]; then
  xcodebuild_command+=(-derivedDataPath "$IOS_DERIVED_DATA_PATH")
fi

xcodebuild_command+=(
  -destination 'generic/platform=iOS Simulator'
  CODE_SIGNING_ALLOWED=NO
  build
)

"${xcodebuild_command[@]}"
