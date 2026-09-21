import { nativeClamshell } from './native'
import { cleanupAll, once, subscribeNative } from './internal/subscriptions'
import type { Unsubscribe } from './internal/subscriptions'
import type {
  ClamshellCapabilities,
  ClamshellError,
  FoldState,
} from './specs/Clamshell.nitro'

export function subscribeAngle(
  attach: (canDeliver: () => boolean) => Unsubscribe,
  onAvailabilityChange: (available: boolean) => void = () => {}
): Unsubscribe {
  let closed = false
  let initializing = true
  let starting = false
  let hasLease = false
  let available = false
  let removeAngle: Unsubscribe | undefined
  let removeCapabilities: Unsubscribe | undefined

  const dispose = once(() => {
    closed = true
    cleanupAll([
      removeCapabilities,
      removeAngle,
      hasLease ? () => nativeClamshell.stopAngleUpdates() : undefined,
    ])
  })

  const reconcile = (capabilities: ClamshellCapabilities) => {
    if (closed) return
    available =
      capabilities.detectionStatus === 'pending' ||
      (capabilities.isFoldable && capabilities.hasContinuousAngle)
    onAvailabilityChange(available)
    if (!available) return
    // Once acquired, logical demand belongs to native until this consumer leaves.
    if (hasLease || starting) return
    starting = true
    try {
      removeAngle = once(attach(() => !closed && available))
      if (closed) {
        removeAngle()
        return
      }
      nativeClamshell.startAngleUpdates()
      hasLease = true
      // A synchronous native callback can unsubscribe while start is in progress.
      if (closed) {
        hasLease = false
        cleanupAll([removeAngle, () => nativeClamshell.stopAngleUpdates()])
      }
    } catch (error) {
      const remove = removeAngle
      removeAngle = undefined
      cleanupAll([
        () => {
          throw error
        },
        remove,
      ])
    } finally {
      starting = false
    }
  }

  try {
    removeCapabilities = subscribeNative<ClamshellCapabilities>(
      (callback) => nativeClamshell.addCapabilitiesListener(callback),
      (capabilities) => {
        if (!initializing) reconcile(capabilities)
      }
    )
    initializing = false
    reconcile(nativeClamshell.getCapabilities())
  } catch (error) {
    cleanupAll([
      () => {
        throw error
      },
      dispose,
    ])
  }
  return dispose
}

export const Clamshell = {
  getSnapshot: (): FoldState => nativeClamshell.getSnapshot(),
  getCapabilities: (): ClamshellCapabilities =>
    nativeClamshell.getCapabilities(),
  onStateChange: (callback: (state: FoldState) => void): Unsubscribe =>
    subscribeNative(
      (listener) => nativeClamshell.addStateListener(listener),
      callback
    ),
  onCapabilitiesChange: (
    callback: (capabilities: ClamshellCapabilities) => void
  ): Unsubscribe =>
    subscribeNative(
      (listener) => nativeClamshell.addCapabilitiesListener(listener),
      callback
    ),
  onError: (callback: (error: ClamshellError) => void): Unsubscribe =>
    subscribeNative(
      (listener) => nativeClamshell.addErrorListener(listener),
      callback
    ),
  onAngle: (callback: (degrees: number) => void): Unsubscribe =>
    subscribeAngle((canDeliver) =>
      subscribeNative<number>(
        (listener) => nativeClamshell.addAngleListener(listener),
        (degrees) => {
          if (canDeliver()) callback(degrees)
        }
      )
    ),
}
