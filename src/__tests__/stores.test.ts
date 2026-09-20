import { Clamshell } from '../facade'
import { createClamshellStores } from '../stores'
import { device, emptyState, native, supportedCapabilities } from './nativeMock'

jest.mock('../native', () => ({
  nativeClamshell: jest.requireActual('./nativeMock').native,
}))

beforeEach(() => device.reset())

it('keeps stable snapshot identity across equivalent native reads', () => {
  const { foldState, capabilities } = createClamshellStores(Clamshell)
  const state = foldState.getSnapshot()
  const caps = capabilities.getSnapshot()
  device.state = { ...state }
  device.capabilities = {
    ...caps,
    supportedPostures: [...caps.supportedPostures],
  }
  expect(foldState.getSnapshot()).toBe(state)
  expect(capabilities.getSnapshot()).toBe(caps)
})

it('does not update or notify for angle-only events; the next posture includes latest angle', () => {
  const { foldState } = createClamshellStores(Clamshell)
  const notify = jest.fn()
  const initial = foldState.getSnapshot()
  const off = foldState.subscribe(notify)
  device.emitAngle(70)
  device.emitState({ ...device.state, angle: 80 })
  expect(foldState.getSnapshot()).toBe(initial)
  expect(notify).not.toHaveBeenCalled()
  device.emitState({ ...device.state, posture: 'halfOpen' })
  expect(foldState.getSnapshot()).toEqual({
    ...emptyState(),
    posture: 'halfOpen',
    angle: 80,
  })
  expect(notify).toHaveBeenCalledTimes(1)
  off()
})

it('compares all geometry fields by value', () => {
  const { foldState } = createClamshellStores(Clamshell)
  const off = foldState.subscribe(jest.fn())
  const geometry = {
    bounds: { x: 0, y: 10, width: 1, height: 200 },
    isSeparating: true,
    occlusionType: 'none' as const,
  }
  device.emitState({ ...device.state, geometry })
  const original = foldState.getSnapshot()
  device.emitState({
    ...device.state,
    geometry: { ...geometry, bounds: { ...geometry.bounds } },
  })
  expect(foldState.getSnapshot()).toBe(original)
  for (const key of ['x', 'y', 'width', 'height'] as const) {
    const before = foldState.getSnapshot()
    device.emitState({
      ...device.state,
      geometry: { ...geometry, bounds: { ...geometry.bounds, [key]: 5 } },
    })
    expect(foldState.getSnapshot()).not.toBe(before)
  }
  device.emitState({
    ...device.state,
    geometry: { ...geometry, occlusionType: 'full' },
  })
  expect(foldState.getSnapshot().geometry?.occlusionType).toBe('full')
  device.emitState({
    ...device.state,
    geometry: { ...geometry, isSeparating: false },
  })
  expect(foldState.getSnapshot().geometry?.isSeparating).toBe(false)
  device.emitState({
    ...device.state,
    geometry: null,
    orientation: 'horizontal',
  })
  expect(foldState.getSnapshot().geometry).toBeNull()
  expect(foldState.getSnapshot().orientation).toBe('horizontal')
  off()
})

it('tracks readiness and every capability field without false reference changes', () => {
  const { capabilities } = createClamshellStores(Clamshell)
  const initial = capabilities.getSnapshot()
  const off = capabilities.subscribe(jest.fn())
  device.emitCapabilities(supportedCapabilities())
  const supported = capabilities.getSnapshot()
  expect(supported).not.toBe(initial)
  device.emitCapabilities(supportedCapabilities())
  expect(capabilities.getSnapshot()).toBe(supported)
  device.emitCapabilities({ ...supported, angleRange: { min: 0, max: 180 } })
  expect(capabilities.getSnapshot().angleRange?.max).toBe(180)
  device.emitCapabilities({ ...supported, supportedPostures: ['flat'] })
  expect(capabilities.getSnapshot().supportedPostures).toEqual(['flat'])
  device.emitCapabilities({ ...supported, hasContinuousAngle: false })
  expect(capabilities.getSnapshot().hasContinuousAngle).toBe(false)
  device.emitCapabilities({ ...supported, hasFoldGeometry: false })
  expect(capabilities.getSnapshot().hasFoldGeometry).toBe(false)
  off()
})

it('shares one native listener but treats duplicate subscribers independently', () => {
  const { foldState } = createClamshellStores(Clamshell)
  const notify = jest.fn()
  const first = foldState.subscribe(notify)
  const second = foldState.subscribe(notify)
  expect(native.addStateListener).toHaveBeenCalledTimes(1)
  first()
  first()
  device.emitState({ ...device.state, posture: 'flat' })
  expect(notify).toHaveBeenCalledTimes(1)
  expect(device.stateListeners.callbacks.size).toBe(1)
  second()
  expect(device.stateListeners.callbacks.size).toBe(0)
})

it('closes the render-to-subscription race by reading after native registration', () => {
  const { foldState } = createClamshellStores(Clamshell)
  foldState.getSnapshot()
  native.addStateListener.mockImplementationOnce((callback) => {
    device.stateListeners.callbacks.add(callback)
    device.state = { ...device.state, posture: 'flat' }
    return () => {
      device.stateListeners.callbacks.delete(callback)
    }
  })
  const notify = jest.fn()
  const off = foldState.subscribe(notify)
  expect(foldState.getSnapshot().posture).toBe('flat')
  expect(notify).toHaveBeenCalledTimes(1)
  off()
})

it('refreshes meaningful state after all consumers were disconnected', () => {
  const { foldState } = createClamshellStores(Clamshell)
  const off = foldState.subscribe(jest.fn())
  off()
  device.state = { ...device.state, posture: 'flat' }
  expect(foldState.getSnapshot().posture).toBe('flat')
})

it('isolates a failing store subscriber from the remaining subscribers', () => {
  const report = jest.spyOn(console, 'error').mockImplementation(() => {})
  const { foldState } = createClamshellStores(Clamshell)
  const failure = new Error('store consumer')
  const offFirst = foldState.subscribe(() => {
    throw failure
  })
  const notify = jest.fn()
  const offSecond = foldState.subscribe(notify)
  device.emitState({ ...device.state, posture: 'flat' })
  expect(notify).toHaveBeenCalledTimes(1)
  expect(report).toHaveBeenCalledWith(
    expect.stringContaining('react-native-clamshell'),
    failure
  )
  offFirst()
  offSecond()
  report.mockRestore()
})
