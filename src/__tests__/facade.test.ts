import { Clamshell } from '../facade'
import { device, native, supportedCapabilities } from './nativeMock'

jest.mock('../native', () => ({
  nativeClamshell: jest.requireActual('./nativeMock').native,
}))

beforeEach(() => device.reset())

it('reads authoritative snapshots and capabilities without caching angles', () => {
  expect(Clamshell.getSnapshot()).toBe(device.state)
  expect(Clamshell.getCapabilities()).toBe(device.capabilities)
  device.emitAngle(42)
  expect(Clamshell.getSnapshot().angle).toBe(42)
})

it('gives each angle subscription an independent lease and idempotent cleanup', () => {
  const first = jest.fn()
  const second = jest.fn()
  const offFirst = Clamshell.onAngle(first)
  const offSecond = Clamshell.onAngle(second)
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(2)
  expect(device.events.indexOf('add:angle')).toBeLessThan(
    device.events.indexOf('start')
  )
  device.emitAngle(90)
  expect(first).toHaveBeenCalledWith(90)
  expect(second).toHaveBeenCalledWith(90)
  offFirst()
  offFirst()
  device.emitAngle(100)
  expect(first).toHaveBeenCalledTimes(1)
  expect(second).toHaveBeenCalledTimes(2)
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
  offSecond()
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(2)
  expect(device.angleListeners.callbacks.size).toBe(0)
  expect(device.capabilityListeners.callbacks.size).toBe(0)
})

it('does not negative-gate pending capabilities', () => {
  const off = Clamshell.onAngle(jest.fn())
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  off()
})

it('waits on confirmed unsupported hardware and activates when evidence changes', () => {
  device.capabilities = { ...device.capabilities, detectionStatus: 'resolved' }
  const callback = jest.fn()
  const off = Clamshell.onAngle(callback)
  expect(native.startAngleUpdates).not.toHaveBeenCalled()
  expect(native.addAngleListener).not.toHaveBeenCalled()
  device.emitCapabilities(supportedCapabilities())
  device.emitCapabilities(supportedCapabilities())
  device.emitAngle(10)
  expect(callback).toHaveBeenCalledWith(10)
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  off()
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
})

it('retains logical demand across capability changes once acquired', () => {
  const callback = jest.fn()
  const off = Clamshell.onAngle(callback)
  device.emitCapabilities({
    ...device.capabilities,
    detectionStatus: 'resolved',
  })
  device.emitAngle(90)
  expect(callback).not.toHaveBeenCalled()
  device.emitCapabilities(supportedCapabilities())
  device.emitAngle(100)
  expect(callback).toHaveBeenCalledWith(100)
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  expect(native.stopAngleUpdates).not.toHaveBeenCalled()
  off()
})

it('balances a lease when a native start callback unsubscribes synchronously', () => {
  device.capabilities = { ...device.capabilities, detectionStatus: 'resolved' }
  const off = Clamshell.onAngle(jest.fn())
  native.startAngleUpdates.mockImplementationOnce(() => off())
  device.emitCapabilities(supportedCapabilities())
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
  expect(device.angleListeners.callbacks.size).toBe(0)
  expect(device.capabilityListeners.callbacks.size).toBe(0)
  off()
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
})

it('cleans up a waiting subscription without acquiring a lease', () => {
  device.capabilities = { ...device.capabilities, detectionStatus: 'resolved' }
  const off = Clamshell.onAngle(jest.fn())
  off()
  device.emitCapabilities(supportedCapabilities())
  expect(native.startAngleUpdates).not.toHaveBeenCalled()
  expect(native.stopAngleUpdates).not.toHaveBeenCalled()
})

it('delegates state, capability, and typed error subscriptions', () => {
  const state = jest.fn()
  const capabilities = jest.fn()
  const error = jest.fn()
  const offState = Clamshell.onStateChange(state)
  const offCapabilities = Clamshell.onCapabilitiesChange(capabilities)
  const offError = Clamshell.onError(error)
  device.emitState({ ...device.state, posture: 'halfOpen' })
  device.emitCapabilities(supportedCapabilities())
  const fault = {
    code: 'permissionDenied' as const,
    message: 'Sensor access denied',
  }
  device.errorListeners.emit(fault)
  expect(state).toHaveBeenCalledWith(device.state)
  expect(capabilities).toHaveBeenCalledWith(device.capabilities)
  expect(error).toHaveBeenCalledWith(fault)
  expect(native.startAngleUpdates).not.toHaveBeenCalled()
  offState()
  offState()
  offCapabilities()
  offError()
  expect(device.stateListeners.callbacks.size).toBe(0)
  expect(device.errorListeners.callbacks.size).toBe(0)
})

it('isolates listener exceptions and reports them without blocking peers', () => {
  const report = jest.spyOn(console, 'error').mockImplementation(() => {})
  const failure = new Error('listener failed')
  const first = Clamshell.onError(() => {
    throw failure
  })
  const peer = jest.fn()
  const second = Clamshell.onError(peer)
  const fault = {
    code: 'sensorRegistrationFailed' as const,
    message: 'Cannot register',
  }
  device.errorListeners.emit(fault)
  expect(peer).toHaveBeenCalledWith(fault)
  expect(report).toHaveBeenCalledWith(
    expect.stringContaining('react-native-clamshell'),
    failure
  )
  first()
  second()
  report.mockRestore()
})

it('ignores queued callbacks after unsubscribe, even for the same callback twice', () => {
  const callback = jest.fn()
  const first = Clamshell.onStateChange(callback)
  const queued = [...device.stateListeners.callbacks][0]!
  const second = Clamshell.onStateChange(callback)
  first()
  queued(device.state)
  device.emitState(device.state)
  expect(callback).toHaveBeenCalledTimes(1)
  second()
})

it('rolls back listener setup when native demand acquisition throws', () => {
  native.startAngleUpdates.mockImplementationOnce(() => {
    throw new Error('start failed')
  })
  expect(() => Clamshell.onAngle(jest.fn())).toThrow('start failed')
  expect(device.angleListeners.callbacks.size).toBe(0)
  expect(device.capabilityListeners.callbacks.size).toBe(0)
  expect(native.stopAngleUpdates).not.toHaveBeenCalled()
})

it('still releases demand when sink removal throws and never cleans twice', () => {
  native.addAngleListener.mockImplementationOnce(() => () => {
    throw new Error('remove failed')
  })
  const off = Clamshell.onAngle(jest.fn())
  expect(off).toThrow('remove failed')
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
  expect(off).not.toThrow()
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
})
