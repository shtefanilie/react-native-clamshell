# API guide

`react-native-clamshell` separates low-frequency fold state from continuous
hinge-angle delivery. Use the React hooks for components and the `Clamshell`
facade for imperative integrations.

## Capability readiness

Read capabilities before deciding whether to show fold-aware UI:

```tsx
import { useClamshellCapabilities } from 'react-native-clamshell'

function FoldAwareContent() {
  const capabilities = useClamshellCapabilities()

  if (capabilities.detectionStatus === 'pending') return null
  if (!capabilities.isFoldable) return <RegularContent />
  return <FoldableContent />
}
```

- `pending` means native detection has not resolved yet. It does not mean the
  device is unsupported.
- `resolved` with `isFoldable: false` confirms that the current device or
  runtime is unsupported.
- Unsupported hardware is a normal capability state, not an error.
- Later supporting evidence may move a device from unsupported to foldable.
  Mounted angle subscriptions remain ready for that transition.

## Foldable capability matrix

This matrix groups representative foldable families by brand. Capabilities can
vary by model, firmware, region, and operating-system release, so every
unverified behavior requires a runtime probe on the target device.

Legend: `✅ confirmed working`, `⚠️ partial, device-dependent, or unverified`,
`❌ unavailable`.

| Brand and models                           | Public angle                                                                                                                                                                                                                                         | Posture                                                                                                                                                                                                | Geometry                                                                                                                                                                                                    | Evidence/limits                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| HONOR: Magic V foldable family             | ⚠️ No HONOR documentation or device evidence found for public `TYPE_HINGE_ANGLE`; HONOR's posture feature identifies foldables but is not an angle API, so probe the standard sensor at runtime                                                      | ✅ HONOR documents WindowManager `FLAT`/`HALF_OPENED` and vendor `Settings.Global` key `hn_fold_screen_state` (`unknown`/`expanded`/`folded`/`half-folded`) for HONOR foldables                        | ✅ HONOR documents `FoldingFeature` bounds, orientation, and occlusion through Sidecar; AndroidX derives separation from feature type/state and version 1.5.1 retains the Sidecar backend                   | ⚠️ [HONOR's guide](https://developer.honor.com/cn/docs/11100/guides/foldable_device_developer_adaptation_guide) detects the family with `com.hihonor.hardware.sensor.posture`, not a model allowlist; its sample uses WindowManager `1.0.0-beta02`, while [AndroidX 1.5.1 still ships Sidecar support](https://android.googlesource.com/platform/frameworks/support/+/be8b763fe78ab805aa1c9a3bff09f1ff8081dbb2/window/window/src/main/java/androidx/window/layout/adapter/sidecar/); no physical angle evidence                                                                                                                       |
| Samsung: Galaxy Z Fold and Z Flip families | ⚠️ Public `TYPE_HINGE_ANGLE` output is firmware-dependent; tested Fold4 emitted only 0/90/180, while Samsung's continuous `Folding Angle` sensor requires signature-only `com.samsung.permission.SSENSOR`                                            | ✅ Samsung documents `FLAT`/`HALF_OPENED` posture and Flex Mode for both Galaxy Z Fold and Z Flip families                                                                                             | ✅ Samsung documents `FoldingFeature` bounds in window coordinates plus state, orientation, occlusion, and separation for both families                                                                     | ⚠️ [Samsung's Flex Mode guide](https://developer.samsung.com/galaxy-z/flex-mode.html) defines one WindowManager contract for Fold and Flip; [project hardware evidence](verification.md#samsung-sm-f936b) confirms posture/geometry on a Fold4, but public angle granularity remains model/firmware-dependent                                                                                                                                                                                                                                                                                                                         |
| Google: Pixel Fold family                  | ✅ Pixel Fold and Pixel 9 Pro Fold AOSP product configs declare `android.hardware.sensor.hinge_angle`; Android requires this feature to provide public `TYPE_HINGE_ANGLE` degree readings through `SensorManager`; resolution remains device-defined | ✅ Android's WindowManager contract reports `FLAT` and, when hardware has a stable half-folded position, `HALF_OPENED`; closed has no `FoldingFeature` because the app window does not cross the hinge | ✅ WindowManager exposes app-window-relative bounds, orientation, occlusion, and separation; display features may be absent in movable multi-window or compatibility-letterbox modes                        | ⚠️ [Pixel Fold](https://android.googlesource.com/device/google/felix/+/refs/heads/main/device-felix.mk#314) and [Pixel 9 Pro Fold](https://android.googlesource.com/device/google/comet/+/refs/heads/main/device-comet.mk#442) configs plus Android's [sensor](https://source.android.com/docs/compatibility/15/android-15-cdd#7312_hinge_angle_sensor) and [WindowManager](https://source.android.com/docs/core/display/windowmanager-extensions#window_layout_information) contracts confirm the common APIs; later generations remain inferred and require runtime verification                                                    |
| Motorola: Razr foldable family             | ⚠️ Public `TYPE_HINGE_ANGLE` is present on stock Razr 50 Ultra and configured or consumed by normal apps on Razr 40 Ultra and Razr 50; exact readings and other generations remain model/firmware-dependent                                          | ✅ Stock Razr 50 Ultra uses AOSP `DeviceStateManager` states including closed, tent, half-opened, and opened; Razr 40 Ultra configuration similarly defines `CLOSED`/`HALF_OPENED`/`OPENED`            | ✅ Android's foldable contract exposes app-window-relative hinge bounds and state through WindowManager; `FoldingFeature` supplies horizontal orientation, occlusion, and separation for the clamshell fold | ⚠️ Evidence includes [stock Razr 50 Ultra captures](https://github.com/drabikp/arcfox-port/blob/b780cfd778e3c284a8a0c4be1ddb1ae5c1c4ef6c/docs/FINDINGS.md), [Razr 40 Ultra device configuration](https://github.com/AmeChanRain/device_motorola_zeekr/blob/532d025604061668263f2e03ba5bb6a6b765e794/fold/device_state_configuration.xml), and [Razr 50 app code](https://github.com/JimmyThompson1997/Motorolla/blob/9b2737bbdf3488ae5a50d6ee6ab5e036123a1f10/pucky-apk/app/src/main/java/com/pucky/device/sensors/PhysicalGestureFeedbackController.java); later and older generations are inferred and require runtime verification |
| iPhone Duo                                 | ✅ iOS 27.1 Simulator emits exact continuous values across the hinge sweep                                                                                                                                                                           | ✅ iOS 27.1 Simulator reports `closed`, `halfOpen`, and `fullyOpen`                                                                                                                                    | ❌ Current implementation reports `hasFoldGeometry: false` and `geometry: null`                                                                                                                             | ✅ Verified with public APIs and no entitlement in the iOS 27.1 Simulator; physical hardware remains unverified                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

## React hooks

### `useHingeAngle()`

```ts
function useHingeAngle(): SharedValue<number | null>
```

Returns a Reanimated `SharedValue` containing the latest hinge angle in
degrees. Read it inside Worklets UI-runtime worklets rather than during React
render:

```tsx
import Animated, { useAnimatedStyle } from 'react-native-reanimated'
import { useHingeAngle } from 'react-native-clamshell'

function HingeIndicator() {
  const angle = useHingeAngle()
  const style = useAnimatedStyle(() => ({
    opacity: angle.value == null ? 0.35 : 1,
    transform: [{ rotate: `${angle.value ?? 0}deg` }],
  }))

  return <Animated.View style={style} />
}
```

The initial value comes from `Clamshell.getSnapshot().angle`. Native samples
then update the shared value directly on the UI runtime without a JavaScript
angle listener or React rerender.

`null` means angle delivery is unsupported or no sample is available yet. `0`
is a valid angle and must not be treated as missing. The hook starts native
angle demand when delivery may be available and releases its independent demand
lease when unmounted.

### `useFoldState()`

```ts
function useFoldState(): FoldState
```

Returns the current low-frequency posture, orientation, geometry, and cached
angle snapshot:

```tsx
import { Text } from 'react-native'
import { useFoldState } from 'react-native-clamshell'

function FoldStatus() {
  const { posture, orientation, geometry } = useFoldState()

  return (
    <Text>
      {posture} / {orientation} /{' '}
      {geometry ? 'geometry available' : 'no geometry'}
    </Text>
  )
}
```

Angle-only samples do not rerender consumers of this hook. When posture,
orientation, or geometry changes, the returned snapshot includes the latest
cached angle. Use `useHingeAngle()` for animation or other continuous angle
work.

### `useClamshellCapabilities()`

```ts
function useClamshellCapabilities(): ClamshellCapabilities
```

Returns capability detection state and supported fold features. It rerenders
only when a capability field meaningfully changes. Check `detectionStatus`
before interpreting `isFoldable` or the other capability fields.

## Imperative facade

Import the singleton facade when integrating outside React or handling
JavaScript-side events:

```ts
import { Clamshell } from 'react-native-clamshell'
```

### `Clamshell.getSnapshot()`

```ts
getSnapshot(): FoldState
```

Synchronously returns the authoritative native fold snapshot. This read is not
a subscription.

### `Clamshell.getCapabilities()`

```ts
getCapabilities(): ClamshellCapabilities
```

Synchronously returns the authoritative native capability snapshot.

### `Clamshell.onStateChange()`

```ts
onStateChange(callback: (state: FoldState) => void): Unsubscribe
```

Subscribes to low-frequency posture, orientation, and geometry changes. An
angle-only sample does not trigger this callback.

### `Clamshell.onCapabilitiesChange()`

```ts
onCapabilitiesChange(
  callback: (capabilities: ClamshellCapabilities) => void
): Unsubscribe
```

Subscribes to capability changes, including detection resolving or later
supporting evidence becoming available.

### `Clamshell.onAngle()`

```ts
onAngle(callback: (degrees: number) => void): Unsubscribe
```

Subscribes to angle samples on the JavaScript thread. Use it for application
state, telemetry, or diagnostics, not animation. `useHingeAngle()` is the
native UI-runtime path for animation.

Each angle subscription owns an independent native demand lease. A confirmed
unsupported capability waits without starting angle updates and activates if
later capabilities report continuous-angle support.

To initialize JavaScript state without missing a sample between the initial
read and listener registration, subscribe before refreshing from the current
snapshot:

```tsx
React.useEffect(() => {
  const unsubscribe = Clamshell.onAngle(setAngle)
  setAngle(Clamshell.getSnapshot().angle)
  return unsubscribe
}, [])
```

### `Clamshell.onError()`

```ts
onError(callback: (error: ClamshellError) => void): Unsubscribe
```

Subscribes to typed native delivery errors. Unsupported hardware and a missing
angle sample are normal state, so they are not emitted here.

### Subscription cleanup

Every listener returns an independent, idempotent `Unsubscribe` function:

```ts
type Unsubscribe = () => void
```

Calling it more than once has no effect. A callback queued before cleanup is
ignored after cleanup. If a consumer callback throws, the package reports a
`[react-native-clamshell] Listener failed` console error and continues
notifying other listeners. Unexpected native setup or cleanup failures surface
to the caller.

## Types

All types below are exported from `react-native-clamshell`.

### `FoldState`

```ts
interface FoldState {
  angle: number | null
  posture: Posture
  orientation: FoldOrientation
  geometry: FoldGeometry | null
}
```

- `angle`: latest cached angle in degrees, or `null` when unavailable.
- `posture`: normalized physical posture.
- `orientation`: orientation of the fold feature.
- `geometry`: root-relative fold feature bounds and behavior, or `null` when
  unavailable.

### `ClamshellCapabilities`

```ts
interface ClamshellCapabilities {
  detectionStatus: CapabilityDetectionStatus
  isFoldable: boolean
  hasContinuousAngle: boolean
  angleRange: AngleRange | null
  supportedPostures: Posture[]
  hasFoldGeometry: boolean
}
```

- `detectionStatus`: `pending` until native capability detection resolves,
  then `resolved`.
- `isFoldable`: whether current evidence identifies a foldable device.
- `hasContinuousAngle`: whether continuous angle delivery is available.
- `angleRange`: reported minimum and maximum degrees, or `null` when the
  runtime does not advertise a range.
- `supportedPostures`: postures reported as supported by the runtime.
- `hasFoldGeometry`: whether fold geometry is available.

### `AngleRange`

```ts
interface AngleRange {
  min: number
  max: number
}
```

Both values are degrees. A `null` capability range means the platform does not
advertise its range, not that angle delivery is necessarily unavailable.

### Fold geometry

```ts
interface Rect {
  x: number
  y: number
  width: number
  height: number
}

interface FoldGeometry {
  bounds: Rect
  isSeparating: boolean
  occlusionType: OcclusionType
}
```

On Android, `bounds` are React Native root-view-relative density-independent
pixels. A fold may have zero width or height. `isSeparating` indicates whether
content regions are physically separated. `occlusionType` indicates whether
the fold fully occludes content within its bounds.

iOS reports `geometry: null` until a verified root-relative geometry source is
available.

### String unions

```ts
type Posture = 'closed' | 'halfOpen' | 'flat' | 'fullyOpen' | 'unknown'
type FoldOrientation = 'vertical' | 'horizontal' | 'none'
type OcclusionType = 'none' | 'full'
type CapabilityDetectionStatus = 'pending' | 'resolved'
```

`unknown` and `none` preserve cases where the native runtime cannot provide a
more specific value. Supported posture values vary by platform and hardware;
consult `supportedPostures` rather than assuming every value can occur.

### Errors

```ts
type ClamshellErrorCode = 'sensorRegistrationFailed' | 'permissionDenied'

interface ClamshellError {
  code: ClamshellErrorCode
  message: string
}
```

- `sensorRegistrationFailed`: native angle delivery could not register or
  attach correctly.
- `permissionDenied`: the platform denied required sensor access.

Errors are reserved for native registration, permission/security, host
attachment, or invalid native-delivery faults. Lack of foldable hardware,
missing iOS runtime support, and lack of a current angle sample remain normal
capability or snapshot states.

## Native lifecycle

Native coordinators own foreground/background suspension, physical sensor
registration, host attachment, listener cleanup, and Worklets sink
invalidation. UI-runtime sinks are removed before their native demand leases
are released. Consumers should mount hooks or retain imperative subscriptions
only while they need updates, then use the returned cleanup function.
