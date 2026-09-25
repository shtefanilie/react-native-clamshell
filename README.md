# react-native-clamshell

<p align="center">
  <img src="docs/assets/clamshell-demo.gif" alt="React Native Clamshell demo" width="594" />
</p>

Foldable-device posture, fold geometry, and hinge-angle delivery for React
Native New Architecture apps.

`react-native-clamshell` exposes low-frequency fold state through Nitro
methods/listeners and continuous hinge angle updates through a native Worklets
UI-runtime bridge. Angle animation does not depend on a JavaScript listener or
JS-thread scheduling fallback.

[![Version](https://img.shields.io/npm/v/react-native-clamshell.svg)](https://www.npmjs.com/package/react-native-clamshell)
[![Downloads](https://img.shields.io/npm/dm/react-native-clamshell.svg)](https://www.npmjs.com/package/react-native-clamshell)
[![License](https://img.shields.io/npm/l/react-native-clamshell.svg)](https://github.com/stefanilie/react-native-clamshell/blob/main/LICENSE)

## Installation

```sh
yarn add react-native-clamshell react-native-nitro-modules \
  react-native-reanimated react-native-worklets
```

Configure the Worklets Babel plugin last:

```js
module.exports = {
  plugins: ['react-native-worklets/plugin'],
}
```

Then install native dependencies and rebuild the app:

```sh
bundle exec pod install
yarn android # or your app's Android build
yarn ios     # or your app's iOS build
```

## Usage

```tsx
import { useHingeAngle } from 'react-native-clamshell'
import Animated, { useAnimatedStyle } from 'react-native-reanimated'

export function FoldIndicator() {
  const hingeAngle = useHingeAngle()

  const style = useAnimatedStyle(() => ({
    opacity: hingeAngle.value == null ? 0.35 : 1,
    transform: [{ rotate: `${hingeAngle.value ?? 0}deg` }],
  }))

  return <Animated.View style={style} />
}
```

`useHingeAngle()` returns a Reanimated shared value updated directly on the UI
runtime. See the [API guide](docs/api.md) for hooks, imperative subscriptions,
capability readiness, lifecycle behavior, errors, and exported types.

## Foldable capability matrix

This matrix groups representative foldable families by brand. Capabilities can
vary by model, firmware, region, and operating-system release, so every
unverified behavior requires a runtime probe on the target device.

Legend: `✅ confirmed working`, `⚠️ partial, device-dependent, or unverified`,
`❌ unavailable`.

| Brand and models                                                                                        | Public angle                                                                                                                                                                                                                                                                                        | Posture                                                                                                                                                              | Geometry                                                                                                                                                            | Evidence/limits                                                                                                                                                                                                   |
| ------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Honor: Magic V3, Magic V5                                                                               | ⚠️ Unknown; runtime probe required                                                                                                                                                                                                                                                                  | ⚠️ Modern global builds are expected to expose WindowManager posture, but require runtime verification                                                               | ⚠️ WindowManager bounds, orientation, occlusion, and separation are expected, but require runtime verification                                                      | ⚠️ No physical-device evidence is available                                                                                                                                                                       |
| Samsung: Galaxy Z Flip6, Galaxy Z Fold6, Galaxy Z Fold7, Galaxy Z Flip5, Galaxy Z Flip7, Galaxy Z Fold5 | ⚠️ Device/firmware-dependent; project-local Samsung hardware observation of 0/90/180, with additional developer reports for Flip5 and Fold7                                                                                                                                                         | ⚠️ WindowManager exposes `FLAT`/`HALF_OPENED` where supported; verify per device/runtime                                                                             | ⚠️ WindowManager exposes orientation, bounds, occlusion, and separation where supported; verify per device/runtime                                                  | ⚠️ Project-local hardware observation: continuous private sensor access uses the signature permission `com.samsung.permission.SSENSOR`, unavailable to ordinary third-party apps; Flip5/Fold7 evidence is non-OEM |
| Google: Pixel 9 Pro Fold, Pixel 10 Pro Fold                                                             | ⚠️ Approximately 5-degree steps in non-OEM developer reports; provisional                                                                                                                                                                                                                           | ⚠️ WindowManager posture expected; runtime verification required per build                                                                                           | ⚠️ WindowManager geometry expected; runtime verification required per build                                                                                         | ⚠️ No physical verification; reported angle behavior must not be treated as verified                                                                                                                              |
| Motorola: Razr 50 Ultra, Razr 40 Ultra, Razr 40, Razr 60 Ultra, Razr 50, Razr 60, Razr 2022             | ⚠️ Mixed: standard `TYPE_HINGE_ANGLE` is confirmed present on stock Razr 50 Ultra, has normal-app implementation evidence for Razr 50, and is strongly supported/configured on Razr 40 Ultra; exact granularity is unknown; Razr 40, the Razr 60 family, and Razr 2022 need physical runtime probes | ⚠️ WindowManager/device-state support is expected and partially evidenced; horizontal fold, but exact `FoldingFeature` output requires per-SKU/firmware verification | ⚠️ WindowManager/device-state support is expected and partially evidenced; horizontal fold, but exact `FoldingFeature` bounds require per-SKU/firmware verification | ⚠️ Evidence is mixed and incomplete; missing evidence is not evidence of absence                                                                                                                                  |
| iPhone Duo                                                                                              | ✅ Continuous, exact hinge-angle values verified in the iOS 27.1 Simulator unverified                                                                                                                                                                                                               | ✅ Public `closed`/`partiallyOpen`/`fullyOpen`/`unknown` status API and simulator behavior are verified;                                                             | ⚠️ Public reserved-region APIs document frame, margins, active state, and division/occlusion kinds                            | ⚠️ Ordinary public APIs with no documented entitlement; iOS 27.1 Simulator verified

## Verification labels

| Signal                 | Android                                                                                                                    | iOS                                                                               |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| Native build/test      | Verified locally and in release gate commands                                                                              | Verified locally; 27.1 UIKit runtime assertions skip without runtime              |
| Fold posture           | Hardware-verified on a Samsung foldable; emulator-verified on Pixel 10 Pro Fold AVD                                        | Implemented-unverified for real hinge delivery                                    |
| Fold geometry          | Hardware-verified as RN-root-relative DIP on a Samsung foldable                                                            | Not claimed; reports `null`                                                       |
| Continuous angle       | Emulator-verified; tested Samsung hardware emitted only 0/90/180 degree samples                                            | iOS 27.1 Simulator-verified with exact hinge values; physical hardware unverified |
| UI-runtime delivery    | Harness-verified on Android and iOS simulator                                                                              | Simulator-verified with live hinge samples                                        |
| Real foldable hardware | Posture/geometry verified on a Samsung foldable; continuous angle unavailable through its public sensor on tested firmware | Unverified                                                                        |

Verification command output and device details are maintained outside the
public package history.

## Credits

Bootstrapped with
[create-nitro-module](https://github.com/patrickkabwe/create-nitro-module).
