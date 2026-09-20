import React, { StrictMode } from 'react'
import { act, create } from 'react-test-renderer'
import type { ReactTestRenderer } from 'react-test-renderer'
import type { SharedValue } from 'react-native-reanimated'
import { useHingeAngle } from '../hooks/useHingeAngle'
import { useFoldState } from '../hooks/useFoldState'
import { useClamshellCapabilities } from '../hooks/useClamshellCapabilities'
import { device, native, supportedCapabilities, uiHost } from './nativeMock'

jest.mock('../native', () => ({
  nativeClamshell: jest.requireActual('./nativeMock').native,
}))
jest.mock('../internal/angleRuntimeHost', () => ({
  getAngleRuntimeHost: () => jest.requireActual('./nativeMock').uiHost,
}))
jest.mock('react-native-reanimated', () => {
  const { useState } = jest.requireActual<typeof React>('react')
  return {
    useSharedValue: <T,>(initial: T) => {
      const [value] = useState(() => ({ value: initial }))
      return value
    },
  }
})

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const originalError = console.error
const roots: ReactTestRenderer[] = []
const angles = new Map<string, SharedValue<number | null>>()
let angleRenders = 0

function AngleProbe({ id = 'angle' }: { id?: string }) {
  const angle = useHingeAngle()
  angles.set(id, angle)
  angleRenders += 1
  return null
}

async function mount(element: React.ReactElement): Promise<ReactTestRenderer> {
  let root: ReactTestRenderer | undefined
  await act(async () => {
    root = create(element)
  })
  if (!root) {
    throw new Error('Renderer did not mount')
  }
  roots.push(root)
  return root
}

beforeEach(() => {
  device.reset()
  angles.clear()
  angleRenders = 0
  jest
    .spyOn(console, 'error')
    .mockImplementation((message: unknown, ...args: unknown[]) => {
      if (
        typeof message === 'string' &&
        message.startsWith('react-test-renderer is deprecated')
      ) {
        return
      }
      originalError(message, ...args)
    })
})

afterEach(async () => {
  await act(async () => {
    for (const root of roots.splice(0)) {
      root.unmount()
    }
  })
  jest.restoreAllMocks()
})

it.each([null, 0, 92])(
  'initializes the SharedValue from native angle %s',
  async (initial) => {
    device.state = { ...device.state, angle: initial }
    await mount(<AngleProbe />)
    expect(angles.get('angle')?.value).toBe(initial)
    expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  }
)

it('writes UI-runtime samples without React renders or a JS angle listener', async () => {
  await mount(<AngleProbe />)
  expect(angles.get('angle')?.value).toBeNull()
  const renders = angleRenders
  uiHost.emit(92)
  expect(angles.get('angle')?.value).toBe(92)
  expect(angleRenders).toBe(renders)
  expect(native.addAngleListener).not.toHaveBeenCalled()
  expect(device.events.indexOf('add:sink')).toBeLessThan(
    device.events.indexOf('start')
  )
})

it('preserves independent sinks and removes a sink before releasing its lease', async () => {
  const root = await mount(
    <>
      <AngleProbe key="a" id="a" />
      <AngleProbe key="b" id="b" />
    </>
  )
  const first = angles.get('a')
  const second = angles.get('b')
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(2)
  uiHost.emit(10)
  expect(first?.value).toBe(10)
  expect(second?.value).toBe(10)
  device.events.length = 0
  await act(async () => {
    root.update(
      <>
        <AngleProbe key="b" id="b" />
      </>
    )
  })
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
  expect(uiHost.sinks.size).toBe(1)
  expect(device.events.indexOf('remove:sink')).toBeLessThan(
    device.events.indexOf('stop')
  )
  uiHost.emit(20)
  expect(first?.value).toBe(10)
  expect(second?.value).toBe(20)
  await act(async () => {
    root.unmount()
  })
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(2)
  expect(uiHost.sinks.size).toBe(0)
})

it('does not recreate sinks on unrelated React renders', async () => {
  const root = await mount(<AngleProbe />)
  const initial = angles.get('angle')
  await act(async () => {
    root.update(<AngleProbe />)
  })
  expect(angles.get('angle')).toBe(initial)
  expect(uiHost.addSink).toHaveBeenCalledTimes(1)
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
})

it('waits for supported capability and then uses the latest snapshot', async () => {
  device.capabilities = { ...device.capabilities, detectionStatus: 'resolved' }
  await mount(<AngleProbe />)
  expect(angles.get('angle')?.value).toBeNull()
  expect(uiHost.addSink).not.toHaveBeenCalled()
  expect(native.startAngleUpdates).not.toHaveBeenCalled()
  const renders = angleRenders
  device.state = { ...device.state, angle: 75 }
  await act(async () => {
    device.emitCapabilities(supportedCapabilities())
  })
  expect(angles.get('angle')?.value).toBe(75)
  expect(uiHost.sinks.size).toBe(1)
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  expect(angleRenders).toBe(renders)
})

it('clears unsupported angle without taking native lifecycle ownership', async () => {
  const root = await mount(<AngleProbe />)
  uiHost.emit(90)
  await act(async () => {
    device.emitCapabilities({
      ...device.capabilities,
      detectionStatus: 'resolved',
    })
  })
  expect(angles.get('angle')?.value).toBeNull()
  expect(native.stopAngleUpdates).not.toHaveBeenCalled()
  uiHost.emit(100)
  expect(angles.get('angle')?.value).toBeNull()
  await act(async () => {
    device.emitCapabilities(supportedCapabilities())
  })
  uiHost.emit(110)
  expect(angles.get('angle')?.value).toBe(110)
  expect(native.startAngleUpdates).toHaveBeenCalledTimes(1)
  await act(async () => {
    root.unmount()
  })
  expect(native.stopAngleUpdates).toHaveBeenCalledTimes(1)
})

it('balances StrictMode effect recreation and final unmount', async () => {
  const root = await mount(
    <StrictMode>
      <AngleProbe />
    </StrictMode>
  )
  expect(
    native.startAngleUpdates.mock.calls.length -
      native.stopAngleUpdates.mock.calls.length
  ).toBe(1)
  expect(uiHost.sinks.size).toBe(1)
  await act(async () => {
    root.unmount()
  })
  expect(native.startAngleUpdates.mock.calls.length).toBe(
    native.stopAngleUpdates.mock.calls.length
  )
  expect(uiHost.sinks.size).toBe(0)
  expect(device.capabilityListeners.callbacks.size).toBe(0)
})

it('updates state/capability hooks only for meaningful low-frequency changes', async () => {
  const states: string[] = []
  const capabilities: string[] = []
  function StateProbe() {
    const state = useFoldState()
    states.push(`${state.posture}:${state.angle}`)
    return null
  }
  function CapabilityProbe() {
    const value = useClamshellCapabilities()
    capabilities.push(`${value.detectionStatus}:${value.isFoldable}`)
    return null
  }
  await mount(
    <>
      <StateProbe />
      <CapabilityProbe />
    </>
  )
  expect(states).toEqual(['unknown:null'])
  expect(capabilities).toEqual(['pending:false'])
  await act(async () => {
    device.emitAngle(25)
  })
  expect(states).toHaveLength(1)
  await act(async () => {
    device.emitState({ ...device.state, posture: 'halfOpen' })
    device.emitCapabilities({
      ...supportedCapabilities(),
      hasContinuousAngle: false,
    })
  })
  expect(states).toEqual(['unknown:null', 'halfOpen:25'])
  expect(capabilities).toEqual(['pending:false', 'resolved:true'])
  await act(async () => {
    device.emitState({ ...device.state })
    device.emitCapabilities({ ...device.capabilities })
  })
  expect(states).toHaveLength(2)
  expect(capabilities).toHaveLength(2)
})

it('cleans up native subscriptions when UI sink registration fails', async () => {
  uiHost.addSink.mockImplementationOnce(() => {
    throw new Error('sink failed')
  })
  await expect(mount(<AngleProbe />)).rejects.toThrow('sink failed')
  expect(native.startAngleUpdates).not.toHaveBeenCalled()
  expect(device.capabilityListeners.callbacks.size).toBe(0)
})
