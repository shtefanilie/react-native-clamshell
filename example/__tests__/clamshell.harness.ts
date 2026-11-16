import { describe, it, expect } from 'react-native-harness'
import { Clamshell } from 'react-native-clamshell'

const postures = ['closed', 'halfOpen', 'flat', 'fullyOpen', 'unknown']

describe('Clamshell', () => {
  it('reads capabilities from the native implementation', () => {
    const capabilities = Clamshell.getCapabilities()

    expect(['pending', 'resolved']).toContain(capabilities.detectionStatus)
    expect(typeof capabilities.isFoldable).toBe('boolean')
    expect(typeof capabilities.hasContinuousAngle).toBe('boolean')
    expect(Array.isArray(capabilities.supportedPostures)).toBe(true)
  })

  it('reads a fold state snapshot from the native implementation', () => {
    const snapshot = Clamshell.getSnapshot()

    expect(postures).toContain(snapshot.posture)
    expect(['vertical', 'horizontal', 'none']).toContain(snapshot.orientation)
  })

  it('subscribes to and unsubscribes from native state changes', () => {
    const unsubscribe = Clamshell.onStateChange(() => {})

    expect(typeof unsubscribe).toBe('function')
    expect(() => unsubscribe()).not.toThrow()
  })
})
