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
