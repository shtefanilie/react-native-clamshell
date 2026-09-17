#!/bin/bash

set -euo pipefail

xcodebuild \
  -workspace example/ios/ClamshellExample.xcworkspace \
  -scheme ClamshellExample \
  -configuration Release \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
