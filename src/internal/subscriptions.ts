export type Unsubscribe = () => void

export function once(cleanup: Unsubscribe): Unsubscribe {
  let active = true
  return () => {
    if (!active) return
    active = false
    cleanup()
  }
}

export function notify<T>(callback: (value: T) => void, value: T): void {
  try {
    callback(value)
  } catch (error) {
    console.error('[react-native-clamshell] Listener failed', error)
  }
}

export function cleanupAll(cleanups: (Unsubscribe | undefined)[]): void {
  const errors: unknown[] = []
  for (const cleanup of cleanups) {
    try {
      cleanup?.()
    } catch (error) {
      errors.push(error)
    }
  }
  if (errors.length === 1) throw errors[0]
  if (errors.length > 1) {
    throw new AggregateError(errors, '[react-native-clamshell] Cleanup failed')
  }
}

export function subscribeNative<T>(
  register: (callback: (value: T) => void) => Unsubscribe,
  callback: (value: T) => void
): Unsubscribe {
  let active = true
  const remove = register((value) => {
    if (active) notify(callback, value)
  })
  return once(() => {
    active = false
    remove()
  })
}
