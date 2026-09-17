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

    let firstId = host.addSink(degrees => {
      'worklet'
      first.value = {
        count: first.value.count + 1,
        degrees,
        isUI: isUIRuntime(),
      }
    })
    const secondId = host.addSink(degrees => {
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

    host.startSynthetic(10)
    const beforeBlock = second.value.count
    const blockedUntil = Date.now() + 2000
    while (Date.now() < blockedUntil) {
      // Intentionally occupy the RN runtime. Native UI-runtime delivery must continue.
    }
    const afterBlock = second.value.count
    expect(afterBlock).toBeGreaterThan(beforeBlock)
    host.removeSink(firstId)
    host.removeSink(firstId)
    firstId = 0
    await delay(100)
    const firstAfterRemoval = first.value.count
    await delay(100)
    host.stopSynthetic()

    expect(second.value.count).toBeGreaterThanOrEqual(afterBlock)
    expect(first.value.count).toBe(firstAfterRemoval)
    expect(second.value.isUI).toBe(true)

    host.removeSink(secondId)
    const secondBeforeInvalidation = second.value.count
    host.invalidate()
    host.emit(99)
    await delay(100)
    expect(second.value.count).toBe(secondBeforeInvalidation)
  })
})
