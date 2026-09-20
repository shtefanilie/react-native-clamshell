import { Clamshell } from './facade'
import { cleanupAll, notify, once } from './internal/subscriptions'
import type { Unsubscribe } from './internal/subscriptions'
import type {
  ClamshellCapabilities,
  FoldGeometry,
  FoldState,
} from './specs/Clamshell.nitro'

function createExternalStore<T>(
  read: () => T,
  register: (callback: (value: T) => void) => Unsubscribe,
  equal: (previous: T, next: T) => boolean
) {
  let cached: T | undefined
  let removeNative: Unsubscribe | undefined
  const listeners = new Set<() => void>()

  const update = (next: T): boolean => {
    if (cached !== undefined && equal(cached, next)) return false
    cached = next
    return true
  }

  const publish = (next: T) => {
    if (!update(next)) return
    for (const listener of [...listeners]) {
      if (listeners.has(listener)) notify(listener, undefined)
    }
  }

  return {
    getSnapshot(): T {
      if (cached === undefined || removeNative === undefined) {
        const next = read()
        if (cached === undefined || !equal(cached, next)) cached = next
      }
      return cached
    },
    subscribe(listener: () => void): Unsubscribe {
      if (cached === undefined) cached = read()
      const entry = () => listener()
      listeners.add(entry)
      if (listeners.size === 1) {
        try {
          removeNative = register(publish)
          // Catch state changes between React's render and native registration.
          publish(read())
        } catch (error) {
          listeners.delete(entry)
          const remove = removeNative
          removeNative = undefined
          cleanupAll([
            () => {
              throw error
            },
            remove,
          ])
        }
      }
      return once(() => {
        listeners.delete(entry)
        if (listeners.size === 0) {
          const remove = removeNative
          removeNative = undefined
          remove?.()
        }
      })
    },
  }
}

function sameGeometry(a: FoldGeometry | null, b: FoldGeometry | null): boolean {
  if (a === b) return true
  if (a === null || b === null) return false
  return (
    a.isSeparating === b.isSeparating &&
    a.occlusionType === b.occlusionType &&
    a.bounds.x === b.bounds.x &&
    a.bounds.y === b.bounds.y &&
    a.bounds.width === b.bounds.width &&
    a.bounds.height === b.bounds.height
  )
}

function sameState(a: FoldState, b: FoldState): boolean {
  return (
    a.posture === b.posture &&
    a.orientation === b.orientation &&
    sameGeometry(a.geometry, b.geometry)
  )
}

function sameCapabilities(
  a: ClamshellCapabilities,
  b: ClamshellCapabilities
): boolean {
  return (
    a.detectionStatus === b.detectionStatus &&
    a.isFoldable === b.isFoldable &&
    a.hasContinuousAngle === b.hasContinuousAngle &&
    a.hasFoldGeometry === b.hasFoldGeometry &&
    a.angleRange?.min === b.angleRange?.min &&
    a.angleRange?.max === b.angleRange?.max &&
    a.supportedPostures.length === b.supportedPostures.length &&
    a.supportedPostures.every(
      (posture, index) => posture === b.supportedPostures[index]
    )
  )
}

export function createClamshellStores(
  source: Pick<
    typeof Clamshell,
    'getSnapshot' | 'getCapabilities' | 'onStateChange' | 'onCapabilitiesChange'
  >
) {
  return {
    foldState: createExternalStore(
      source.getSnapshot,
      source.onStateChange,
      sameState
    ),
    capabilities: createExternalStore(
      source.getCapabilities,
      source.onCapabilitiesChange,
      sameCapabilities
    ),
  }
}

export const { foldState: foldStateStore, capabilities: capabilitiesStore } =
  createClamshellStores(Clamshell)
