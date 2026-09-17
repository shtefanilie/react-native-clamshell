import { describe, expect, it } from 'react-native-harness'
// Harness deliberately installs a Jest marker in the device runtime. Import the
// native constructor directly so Reanimated does not select its JS-only Jest facade.
import { makeMutable } from 'react-native-reanimated/src/mutables'
import { isUIRuntime } from 'react-native-worklets'
import { getAngleRuntimeHost } from '../../src/internal/angleRuntimeHost'

const delay = (milliseconds: number) =>
  new Promise<void>(resolve => setTimeout(resolve, milliseconds))

describe('native angle UI-runtime bridge', () => {
  it('installs the JSI host', () => {
    expect(getAngleRuntimeHost()).toBeDefined()
  })

  it('retains independent sinks and delivers while the RN runtime is blocked', async () => {
    const host = getAngleRuntimeHost()
    const first = makeMutable({ count: 0, degrees: 0, isUI: false })
    const second = makeMutable({ count: 0, degrees: 0, isUI: false })

    const firstId = host.addSink(degrees => {
      'worklet'
      first.value = {
        count: first.value.count + 1,
        degrees,
        isUI: isUIRuntime(),
      }
    })
    host.addSink(degrees => {
      'worklet'
      second.value = {
        count: second.value.count + 1,
        degrees,
        isUI: isUIRuntime(),
      }
    })

    host.collectGarbage()
    host.emit(42)
    await delay(100)
    expect(first.value.isUI).toBe(true)
    expect(second.value.isUI).toBe(true)
    expect(first.value.degrees).toBe(42)
    expect(second.value.degrees).toBe(42)

    expect(() => host.removeSink(Number.NaN)).toThrow()
    expect(() => host.removeSink(1.5)).toThrow()
    expect(() => host.removeSink(Number.MAX_SAFE_INTEGER + 1)).toThrow()
    expect(() => host.startSynthetic(-1)).toThrow()
    expect(() => host.startSynthetic(Number.POSITIVE_INFINITY)).toThrow()

    host.startSynthetic(10)
    try {
      const beforeBlock = second.value.count
      const blockedUntil = Date.now() + 2000
      while (Date.now() < blockedUntil) {
        // Intentionally occupy the RN runtime. Native UI-runtime delivery must continue.
      }
      expect(second.value.count).toBeGreaterThan(beforeBlock)
    } finally {
      host.stopSynthetic()
    }

    const firstBeforeRemoval = first.value.count
    const secondBeforeRemoval = second.value.count
    host.emitConcurrentlyAndRemove(firstId, 43)
    host.removeSink(firstId)
    const firstAfterRemoval = first.value.count

    expect(firstAfterRemoval).toBe(firstBeforeRemoval)
    expect(second.value.count).toBeGreaterThan(secondBeforeRemoval)
    const secondAfterRemoval = second.value.count
    host.emit(44)
    await delay(50)
    expect(first.value.count).toBe(firstAfterRemoval)
    expect(second.value.count).toBeGreaterThan(secondAfterRemoval)
    expect(second.value.isUI).toBe(true)

    host.emitConcurrentlyAndInvalidate(45)
    const secondAfterInvalidation = second.value.count
    host.emit(99)
    await delay(50)
    expect(second.value.count).toBe(secondAfterInvalidation)

    host.dispose()
    const reloadedHost = getAngleRuntimeHost()
    const reloaded = makeMutable(0)
    const reloadedId = reloadedHost.addSink(degrees => {
      'worklet'
      reloaded.value = degrees
    })
    try {
      reloadedHost.emit(7)
      await delay(50)
      expect(reloaded.value).toBe(7)
    } finally {
      reloadedHost.removeSink(reloadedId)
      reloadedHost.dispose()
    }
  })
})
