# react-native-clamshell

Foldable-device posture, fold geometry, and hinge-angle delivery for React
Native New Architecture apps.

`react-native-clamshell` exposes low-frequency fold state through Nitro
methods/listeners and continuous hinge angle updates through a native Worklets
UI-runtime bridge. Angle animation does not depend on a JavaScript listener or
JS-thread scheduling fallback.

**Release status:** implementation and example diagnostics are complete for
the current dependency matrix. Android behavior is verified on a Pixel 10 Pro
Fold emulator. Real Android foldable hardware and real iOS 27.1 hinge
delivery remain unverified.

[![Version](https://img.shields.io/npm/v/react-native-clamshell.svg)](https://www.npmjs.com/package/react-native-clamshell)
[![Downloads](https://img.shields.io/npm/dm/react-native-clamshell.svg)](https://www.npmjs.com/package/react-native-clamshell)
[![License](https://img.shields.io/npm/l/react-native-clamshell.svg)](https://github.com/stefanilie/react-native-clamshell/blob/main/LICENSE)

## Requirements

- React 19.2.3
- React Native 0.86.0 with the New Architecture enabled
- React Native Nitro Modules 0.37.1
- React Native Reanimated 4.6.0
- React Native Worklets 0.12.2
- Android compile SDK 36 and Java 17 for the example/release gate
- iOS deployment target 15.1 or newer
- Xcode 27.1 SDK to compile `UIHingeInteraction` support
- Node 24.14.0 and Yarn 4.9.2 for the verified repository toolchain

The package declares React, React Native, Nitro Modules, Reanimated, and
Worklets as exact peer dependencies.

## Installation

```sh
yarn add react-native-clamshell react@19.2.3 react-native@0.86.0 \
  react-native-nitro-modules@0.37.1 react-native-reanimated@4.6.0 \
  react-native-worklets@0.12.2
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

## Capability readiness

Capability detection is explicit:

- `pending` means native detection has not resolved yet.
- `resolved` with `isFoldable: false` means the current device/runtime
  is confirmed unsupported.
- Unsupported hardware is reported as capability state, not as an error.
- Later supporting evidence may move a device from unresolved/unsupported to
  foldable; angle subscriptions keep a native demand lease while mounted.

Do not treat `pending` as unsupported in UI.

## Hooks

```tsx
import {
  useClamshellCapabilities,
  useFoldState,
  useHingeAngle,
} from 'react-native-clamshell'
import { useAnimatedStyle } from 'react-native-reanimated'

export function FoldIndicator() {
  const capabilities = useClamshellCapabilities()
  const foldState = useFoldState()
  const hingeAngle = useHingeAngle()

  const style = useAnimatedStyle(() => ({
    opacity: hingeAngle.value == null ? 0.35 : 1,
    transform: [{ rotate: `${hingeAngle.value ?? 0}deg` }],
  }))

  return { capabilities, foldState, style }
}
```

`useFoldState()` returns the native snapshot store for posture, root-relative
fold geometry, and the latest cached angle. `useClamshellCapabilities()`
returns the native capability store. Both hooks use stable external stores;
angle-only samples do not rerender React.

`useHingeAngle()` returns `SharedValue<number | null>`. `null` means unsupported
or no sample yet. `0` is a valid angle sample. Read this value inside Worklets
UI-runtime worklets, not during React render.

## Imperative facade

```ts
import { Clamshell } from 'react-native-clamshell'

const snapshot = Clamshell.getSnapshot()
const capabilities = Clamshell.getCapabilities()

const offState = Clamshell.onStateChange((next) => {
  console.log(next.posture, next.geometry)
})

const offCapabilities = Clamshell.onCapabilitiesChange((next) => {
  console.log(next.detectionStatus, next.isFoldable)
})

const offAngle = Clamshell.onAngle((degrees) => {
  console.log('JS-side angle work:', degrees)
})

const offError = Clamshell.onError((error) => {
  console.error(error.code, error.message)
})

offAngle()
offCapabilities()
offState()
offError()
```

Every listener returns an independent, idempotent unsubscribe function.
Consumer callback exceptions are reported with a `[react-native-clamshell]`
console error and do not block other listeners. Unexpected setup/cleanup faults
surface explicitly.

Use `onAngle` for JS-side reactions, telemetry, or diagnostics. Do not use it
for animation; `useHingeAngle()` is the native UI-runtime path.

## Coordinates and lifecycle

Android geometry bounds are React Native root-view-relative DIP. The fold
feature may have zero width or height, so diagnostics draw a minimum visible
overlay while preserving the measured value in text. iOS geometry is `null`
until Apple exposes a verified root-relative geometry source for the runtime.

Native coordinators own lifecycle, foreground/background suspension, physical
sensor registration, host attachment, listener cleanup, and Worklets sink
invalidation. UI-runtime sinks are removed before the corresponding native
demand lease is released.

## Errors

Typed errors are reserved for native registration, permission/security, host
attachment, or invalid native-delivery faults. Lack of foldable hardware,
missing iOS 27.1 runtime support, or lack of a current angle sample are normal
capability/snapshot states.

## Verification labels

| Signal | Android | iOS |
|---|---|---|
| Native build/test | Verified locally and in release gate commands | Verified locally; 27.1 UIKit runtime assertions skip without runtime |
| Fold posture | Emulator-verified on Pixel 10 Pro Fold AVD | Implemented-unverified for real hinge delivery |
| Fold geometry | Emulator-verified as RN-root-relative DIP | Not claimed; reports `null` |
| Continuous angle | Emulator-verified through hinge sensor sample | Implemented-unverified without iOS 27.1 runtime |
| UI-runtime delivery | Harness-verified on Android and iOS simulator | Harness-verified with synthetic native samples |
| Real foldable hardware | Unverified | Unverified |

Verification command output and device details are maintained outside the
public package history.

## Development

```sh
asdf exec corepack yarn install --immutable
asdf exec corepack yarn check:generated
asdf exec corepack yarn test:js
asdf exec corepack yarn test:android
asdf exec corepack yarn test:ios
asdf exec corepack yarn test:worklet:android
asdf exec corepack yarn test:worklet:ios
asdf exec corepack yarn typecheck
asdf exec corepack yarn lint
asdf exec corepack yarn build:android:debug
asdf exec corepack yarn build:android:release
IOS_DERIVED_DATA_PATH=example/ios/build asdf exec corepack yarn build:ios
asdf exec corepack yarn build
asdf exec corepack yarn check:package
```

## Credits

Bootstrapped with
[create-nitro-module](https://github.com/patrickkabwe/create-nitro-module).
