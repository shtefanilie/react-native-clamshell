# Native UI-runtime angle bridge

## Supported dependency contract

- React Native Worklets: `0.12.2`
- Stable native API version: `WORKLETS_STABLE_API_VERSION` = `0.12.1`
- Public native header: `worklets/Compat/StableApi.h`
- Namespace: `worklets`

The project-owned `clamshell::UIRuntimeAngleSink` and
`clamshell::AngleRuntimeBridge` interfaces do not expose Worklets types.
`WorkletsAngleSink` is private to `cpp/AngleRuntimeBridge.cpp`.

## Exact native API path

`AngleRuntimeHost.addSink` serializes its worklet with the public JavaScript
`createSerializable(worklet, true)` API. Native code retains the resulting
`std::shared_ptr<worklets::Serializable>`, validated as
`worklets::Serializable::ValueType::WorkletType` by
`worklets::extractSerializable`.

The private TypeScript adapter obtains opaque holders through the public
`getUIRuntimeHolder()` and `getUISchedulerHolder()` JavaScript APIs and passes
them to the raw native host method. Native code resolves those holders with
`worklets::getWorkletRuntimeFromHolder` and
`worklets::getUISchedulerFromHolder`. Native producers call
`worklets::scheduleOnUI`; that job locks a weak
`worklets::WorkletRuntime` and invokes the retained callback with
`worklets::runSyncOnRuntime(runtime, worklet, jsi::Value(degrees))`.

Runtime identity is asserted inside that callback with the official JavaScript
`isUIRuntime()` API. No Nitro callback, JS listener, or JS-side `scheduleOnUI`
participates in native delivery.

On Android, the adapter enters `facebook::jni::ThreadScope::WithClassLoader`
before calling the Worklets scheduler. This attaches arbitrary native producer
threads because Worklets' Android `UISchedulerWrapper` queries Java UI-thread
state during dispatch.

## Ownership, GC, and teardown

- `AngleRuntimeBridge` mutex-protects subscription IDs and snapshots sinks
  before invocation, so native emission never holds its lock while dispatching.
- Each sink strongly retains its serialized worklet and UI scheduler, but only
  weakly retains the UI Worklet runtime.
- Each sink tracks active state and pending UI jobs under a mutex. Removal and
  invalidation mark sinks inactive, cancel queued jobs before invocation, and
  wait for queued or executing jobs to finish before returning. Repeated and
  concurrent removal is harmless.
- The probe forces Hermes GC through `jsi::Instrumentation::collectGarbage`;
  the retained native serializable remains callable.
- `invalidate()` stops synthetic emission, clears all sinks, and makes future
  emissions no-ops. Destruction/disposal performs the same order.

## Platform registration

- Android: `cpp-adapter.cpp` registers `AngleRuntimeHost` through Nitro's
  `HybridObjectRegistry`; generated CMake compiles the bridge and links
  `react-native-worklets::worklets`.
- iOS: generated `ClamshellAutolinking.mm` performs registry insertion in the
  same translation unit as the generated `Clamshell` registration and calls a
  project-owned C++ factory; the pod links `RNWorklets`.
- The iOS harness force-installs the just-built app before launching it. Harness
  1.5.0 otherwise skips installation when the bundle ID already exists, which
  can execute a stale native binary while loading the current JavaScript test.

## Verification evidence

- `yarn test:worklet:android`: PASS, 1 suite and 2 tests.
- `yarn test:worklet:ios`: PASS, 1 suite and 2 tests.
- Durable Android output: `.superpowers/sdd/2026-09-16-clamshell-revised/task-3-fix1-android.log`.
- Durable iOS output: `.superpowers/sdd/2026-09-16-clamshell-revised/task-3-fix1-ios.log`.

Both platform artifacts cover official `isUIRuntime()` identity, native
delivery during a two-second blocked JS interval, deterministic concurrent
emit/remove, queued cancellation during invalidation, GC retention, and
dispose/recreation. These ignored evidence paths are repository-relative so
package documentation contains no machine-specific path.

Full red/green results and root-cause evidence are recorded in
`.superpowers/sdd/2026-09-16-clamshell-revised/task-3-report.md`.
