import type {
  ClamshellCapabilities,
  ClamshellError,
  FoldState,
} from '../specs/Clamshell.nitro'

export const pendingCapabilities = (): ClamshellCapabilities => ({
  detectionStatus: 'pending',
  isFoldable: false,
  hasContinuousAngle: false,
  angleRange: null,
  supportedPostures: [],
  hasFoldGeometry: false,
})

export const supportedCapabilities = (): ClamshellCapabilities => ({
  detectionStatus: 'resolved',
  isFoldable: true,
  hasContinuousAngle: true,
  angleRange: { min: 0, max: 360 },
  supportedPostures: ['halfOpen', 'flat'],
  hasFoldGeometry: true,
})

export const emptyState = (): FoldState => ({
  angle: null,
  posture: 'unknown',
  orientation: 'none',
  geometry: null,
})

function listeners<T>(name: string) {
  const callbacks = new Set<(value: T) => void>()
  const add = jest.fn<() => void, [(value: T) => void]>((callback) => {
    device.events.push(`add:${name}`)
    callbacks.add(callback)
    return jest.fn(() => {
      device.events.push(`remove:${name}`)
      callbacks.delete(callback)
    })
  })
  return {
    add,
    callbacks,
    emit(value: T) {
      for (const callback of [...callbacks]) {
        callback(value)
      }
    },
    reset() {
      callbacks.clear()
      add.mockClear()
    },
  }
}

export const device = {
  state: emptyState(),
  capabilities: pendingCapabilities(),
  events: [] as string[],
  stateListeners: listeners<FoldState>('state'),
  capabilityListeners: listeners<ClamshellCapabilities>('capabilities'),
  angleListeners: listeners<number>('angle'),
  errorListeners: listeners<ClamshellError>('error'),
  emitState(state: FoldState) {
    this.state = state
    this.stateListeners.emit(state)
  },
  emitCapabilities(capabilities: ClamshellCapabilities) {
    this.capabilities = capabilities
    this.capabilityListeners.emit(capabilities)
  },
  emitAngle(degrees: number) {
    this.state = { ...this.state, angle: degrees }
    this.angleListeners.emit(degrees)
  },
  reset() {
    this.state = emptyState()
    this.capabilities = pendingCapabilities()
    this.events.length = 0
    this.stateListeners.reset()
    this.capabilityListeners.reset()
    this.angleListeners.reset()
    this.errorListeners.reset()
    native.getSnapshot.mockClear()
    native.getCapabilities.mockClear()
    native.startAngleUpdates.mockClear()
    native.stopAngleUpdates.mockClear()
    uiHost.addSink.mockClear()
    uiHost.removeSink.mockClear()
    uiHost.sinks.clear()
  },
}

export const native = {
  getSnapshot: jest.fn(() => device.state),
  getCapabilities: jest.fn(() => device.capabilities),
  addStateListener: device.stateListeners.add,
  addCapabilitiesListener: device.capabilityListeners.add,
  addAngleListener: device.angleListeners.add,
  addErrorListener: device.errorListeners.add,
  startAngleUpdates: jest.fn(() => {
    device.events.push('start')
  }),
  stopAngleUpdates: jest.fn(() => {
    device.events.push('stop')
  }),
}

let nextSink = 0
export const uiHost = {
  sinks: new Map<number, (degrees: number) => void>(),
  addSink: jest.fn((callback: (degrees: number) => void) => {
    device.events.push('add:sink')
    const id = ++nextSink
    uiHost.sinks.set(id, callback)
    return id
  }),
  removeSink: jest.fn((id: number) => {
    device.events.push('remove:sink')
    uiHost.sinks.delete(id)
  }),
  emit(degrees: number) {
    for (const sink of uiHost.sinks.values()) {
      sink(degrees)
    }
  },
}
