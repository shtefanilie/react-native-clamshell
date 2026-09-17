import { describe, it, expect } from 'react-native-harness'
import { Clamshell } from 'react-native-clamshell'

describe('Clamshell', () => {
  it('calls the native implementation', () => {
    expect(Clamshell.sum(1, 2)).toBe(3)
  })
})
