#!/bin/bash

set -euo pipefail

xcodebuild -version
status=0

for sdk in iphoneos iphonesimulator; do
  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  sdk_version="$(xcrun --sdk "$sdk" --show-sdk-version)"
  target="arm64-apple-ios15.1"
  if [[ "$sdk" == "iphonesimulator" ]]; then
    target="${target}-simulator"
  fi

  printf '\nSDK: %s %s\nPath: %s\nTarget: %s\n' \
    "$sdk" "$sdk_version" "$sdk_path" "$target"

  xcrun --sdk "$sdk" swiftc -typecheck \
    -sdk "$sdk_path" -target "$target" - <<'SWIFT'
import UIKit

let _: UIView.Type = UIView.self
SWIFT
  printf 'UIKit control: PASS\n'

  if xcrun --sdk "$sdk" swiftc -typecheck \
    -sdk "$sdk_path" -target "$target" - <<'SWIFT'
import UIKit

@available(iOS 27.1, *)
@MainActor
func probeHingeInteraction(on view: UIView) {
  let interaction = UIHingeInteraction(updateHandler: { _, update in
    guard let hinge: UIHinge = update.hinge else { return }
    let radians: CGFloat = hinge.angle
    let degrees: Double = Double(radians) * (180.0 / .pi)
    _ = degrees
    switch hinge.status {
    case .unknown, .closed, .partiallyOpen, .fullyOpen: break
    @unknown default: break
    }
  })
  view.addInteraction(interaction)
  interaction.isEnabled = false
  view.removeInteraction(interaction)
}
SWIFT
  then
    printf 'Hinge initializer, payload, units adapter, and attachment compile: PASS\n'
  else
    printf 'Hinge type lookup: FAIL; Task 4 cannot proceed with this SDK.\n' >&2
    status=1
  fi
done

exit "$status"
