import Foundation

enum ClamshellCoordinatorError: Error {
  case disposed
  case tooManyAngleConsumers
}

final class ClamshellCoordinator {
  typealias DiagnosticReporter = (String, Error?) -> Void

  private final class Listener<T> {
    private let lock = NSRecursiveLock()
    private var callback: ((T) -> Void)?

    init(_ callback: @escaping (T) -> Void) {
      self.callback = callback
    }

    func invoke(_ value: T) {
      lock.lock()
      let callback = callback
      lock.unlock()
      callback?(value)
    }

    func release() {
      lock.lock()
      callback = nil
      lock.unlock()
    }
  }

  private let lock = NSRecursiveLock()
  private let emitUiAngle: (Double) -> Void
  private let invalidateUiSinks: () -> Void
  private let reportDiagnostic: DiagnosticReporter
  private var nextListenerID: UInt64 = 1
  private var nextHostID: UInt64 = 1
  private var currentHost: UInt64?
  private var requestedAngleConsumers = 0
  private var disposed = false
  private var capabilities = ClamshellCoordinator.pendingCapabilities()
  private var state = ClamshellCoordinator.emptyState()
  private var capabilityListeners: [UInt64: Listener<ClamshellCapabilities>] = [:]
  private var stateListeners: [UInt64: Listener<FoldState>] = [:]
  private var angleListeners: [UInt64: Listener<Double>] = [:]
  private var errorListeners: [UInt64: Listener<ClamshellError>] = [:]

  init(
    emitUiAngle: @escaping (Double) -> Void,
    invalidateUiSinks: @escaping () -> Void,
    reportDiagnostic: @escaping DiagnosticReporter
  ) {
    self.emitUiAngle = emitUiAngle
    self.invalidateUiSinks = invalidateUiSinks
    self.reportDiagnostic = reportDiagnostic
  }

  func getCapabilities() throws -> ClamshellCapabilities {
    lock.lock()
    defer { lock.unlock() }
    return capabilities
  }

  func getSnapshot() throws -> FoldState {
    lock.lock()
    defer { lock.unlock() }
    return state
  }

  func addCapabilitiesListener(_ callback: @escaping (ClamshellCapabilities) -> Void) throws -> () -> Void {
    lock.lock()
    if disposed {
      lock.unlock()
      throw ClamshellCoordinatorError.disposed
    }
    let id = nextRegisteredListenerID()
    let listener = Listener(callback)
    capabilityListeners[id] = listener
    lock.unlock()
    return makeRemoval { $0.capabilityListeners.removeValue(forKey: id) } release: { listener.release() }
  }

  func addStateListener(_ callback: @escaping (FoldState) -> Void) throws -> () -> Void {
    lock.lock()
    if disposed {
      lock.unlock()
      throw ClamshellCoordinatorError.disposed
    }
    let id = nextRegisteredListenerID()
    let listener = Listener(callback)
    stateListeners[id] = listener
    lock.unlock()
    return makeRemoval { $0.stateListeners.removeValue(forKey: id) } release: { listener.release() }
  }

  func addAngleListener(_ callback: @escaping (Double) -> Void) throws -> () -> Void {
    lock.lock()
    if disposed {
      lock.unlock()
      throw ClamshellCoordinatorError.disposed
    }
    let id = nextRegisteredListenerID()
    let listener = Listener(callback)
    angleListeners[id] = listener
    lock.unlock()
    return makeRemoval { $0.angleListeners.removeValue(forKey: id) } release: { listener.release() }
  }

  func addErrorListener(_ callback: @escaping (ClamshellError) -> Void) throws -> () -> Void {
    lock.lock()
    if disposed {
      lock.unlock()
      throw ClamshellCoordinatorError.disposed
    }
    let id = nextRegisteredListenerID()
    let listener = Listener(callback)
    errorListeners[id] = listener
    lock.unlock()
    return makeRemoval { $0.errorListeners.removeValue(forKey: id) } release: { listener.release() }
  }

  func attachHost() -> UInt64 {
    var capabilityEvents: [(Listener<ClamshellCapabilities>, ClamshellCapabilities)] = []
    var stateEvents: [(Listener<FoldState>, FoldState)] = []
    lock.lock()
    if disposed {
      lock.unlock()
      return 0
    }
    let host = nextHostID
    nextHostID += 1
    currentHost = host
    resetHostLocked(capabilityEvents: &capabilityEvents, stateEvents: &stateEvents)
    lock.unlock()
    deliverCapabilityEvents(capabilityEvents)
    deliverStateEvents(stateEvents)
    return host
  }

  func detachHost(_ host: UInt64) {
    var capabilityEvents: [(Listener<ClamshellCapabilities>, ClamshellCapabilities)] = []
    var stateEvents: [(Listener<FoldState>, FoldState)] = []
    lock.lock()
    guard !disposed, currentHost == host else {
      lock.unlock()
      return
    }
    currentHost = nil
    resetHostLocked(capabilityEvents: &capabilityEvents, stateEvents: &stateEvents)
    lock.unlock()
    deliverCapabilityEvents(capabilityEvents)
    deliverStateEvents(stateEvents)
  }

  func onHostUpdate(_ host: UInt64, _ update: HingeUpdate) {
    var capabilityEvents: [(Listener<ClamshellCapabilities>, ClamshellCapabilities)] = []
    var stateEvents: [(Listener<FoldState>, FoldState)] = []
    var angleEvents: [(Listener<Double>, Double)] = []
    var uiAngle: Double?
    lock.lock()
    guard !disposed, currentHost == host else {
      lock.unlock()
      return
    }
    if !sameCapabilities(capabilities, update.capabilities) {
      capabilities = update.capabilities
      capabilityEvents = ordered(capabilityListeners).map { ($0, update.capabilities) }
    }
    let previous = state
    state = update.state
    if !sameLowFrequencyState(previous, update.state) {
      stateEvents = ordered(stateListeners).map { ($0, update.state) }
    }
    if requestedAngleConsumers > 0,
       update.capabilities.hasContinuousAngle,
       let angle = update.state.angle?.numberValue {
      angleEvents = ordered(angleListeners).map { ($0, angle) }
      uiAngle = angle
    }
    lock.unlock()
    deliverCapabilityEvents(capabilityEvents)
    deliverStateEvents(stateEvents)
    deliverAngleEvents(angleEvents, uiAngle: uiAngle)
  }

  func startAngleUpdates() throws {
    lock.lock()
    defer { lock.unlock() }
    if disposed { throw ClamshellCoordinatorError.disposed }
    if requestedAngleConsumers == Int.max { throw ClamshellCoordinatorError.tooManyAngleConsumers }
    requestedAngleConsumers += 1
  }

  func stopAngleUpdates() throws {
    lock.lock()
    defer { lock.unlock() }
    if disposed { return }
    if requestedAngleConsumers > 0 { requestedAngleConsumers -= 1 }
  }

  func reportError(_ code: ClamshellErrorCode, message: String, cause: Error? = nil) {
    reportError(ClamshellError(code: code, message: message), cause: cause)
  }

  func reportError(_ error: ClamshellError, cause: Error? = nil) {
    var events: [(Listener<ClamshellError>, ClamshellError)] = []
    lock.lock()
    if !disposed {
      events = ordered(errorListeners).map { ($0, error) }
    }
    lock.unlock()
    if events.isEmpty {
      reportDiagnostic(error.message, cause)
    } else {
      deliverErrorEvents(events)
    }
  }

  func dispose() {
    let releasedCapabilities: [Listener<ClamshellCapabilities>]
    let releasedStates: [Listener<FoldState>]
    let releasedAngles: [Listener<Double>]
    let releasedErrors: [Listener<ClamshellError>]
    lock.lock()
    if disposed {
      lock.unlock()
      return
    }
    disposed = true
    currentHost = nil
    requestedAngleConsumers = 0
    releasedCapabilities = Array(capabilityListeners.values)
    releasedStates = Array(stateListeners.values)
    releasedAngles = Array(angleListeners.values)
    releasedErrors = Array(errorListeners.values)
    capabilityListeners.removeAll()
    stateListeners.removeAll()
    angleListeners.removeAll()
    errorListeners.removeAll()
    lock.unlock()
    releasedCapabilities.forEach { $0.release() }
    releasedStates.forEach { $0.release() }
    releasedAngles.forEach { $0.release() }
    releasedErrors.forEach { $0.release() }
    invalidateUiSinks()
  }

  private func nextRegisteredListenerID() -> UInt64 {
    let id = nextListenerID
    nextListenerID += 1
    return id
  }

  private func makeRemoval(
    _ remove: @escaping (ClamshellCoordinator) -> Void,
    release: @escaping () -> Void
  ) -> () -> Void {
    var removed = false
    return { [weak self] in
      guard let self else { return }
      self.lock.lock()
      if removed {
        self.lock.unlock()
        return
      }
      removed = true
      remove(self)
      self.lock.unlock()
      release()
    }
  }

  private func resetHostLocked(
    capabilityEvents: inout [(Listener<ClamshellCapabilities>, ClamshellCapabilities)],
    stateEvents: inout [(Listener<FoldState>, FoldState)]
  ) {
    let nextCapabilities = Self.pendingCapabilities()
    let nextState = Self.emptyState()
    if !sameCapabilities(capabilities, nextCapabilities) {
      capabilities = nextCapabilities
      capabilityEvents = ordered(capabilityListeners).map { ($0, nextCapabilities) }
    }
    if !sameLowFrequencyState(state, nextState) || state.angle != nil {
      state = nextState
      stateEvents = ordered(stateListeners).map { ($0, nextState) }
    }
  }

  private func ordered<T>(_ listeners: [UInt64: Listener<T>]) -> [Listener<T>] {
    listeners.keys.sorted().compactMap { listeners[$0] }
  }

  private func deliverCapabilityEvents(_ events: [(Listener<ClamshellCapabilities>, ClamshellCapabilities)]) {
    deliver(events)
  }

  private func deliverStateEvents(_ events: [(Listener<FoldState>, FoldState)]) {
    deliver(events)
  }

  private func deliverErrorEvents(_ events: [(Listener<ClamshellError>, ClamshellError)]) {
    deliver(events)
  }

  private func deliverAngleEvents(_ events: [(Listener<Double>, Double)], uiAngle: Double?) {
    deliver(events)
    if !isDisposed, let uiAngle { emitUiAngle(uiAngle) }
  }

  private func deliver<T>(_ events: [(Listener<T>, T)]) {
    for (listener, value) in events {
      if isDisposed { return }
      listener.invoke(value)
    }
  }

  private var isDisposed: Bool {
    lock.lock()
    defer { lock.unlock() }
    return disposed
  }

  private static func pendingCapabilities() -> ClamshellCapabilities {
    ClamshellCapabilities(
      detectionStatus: .pending,
      isFoldable: false,
      hasContinuousAngle: false,
      angleRange: nil,
      supportedPostures: [],
      hasFoldGeometry: false
    )
  }

  private static func emptyState() -> FoldState {
    FoldState(angle: nil, posture: .unknown, orientation: .none, geometry: nil)
  }
}

private func sameCapabilities(_ lhs: ClamshellCapabilities, _ rhs: ClamshellCapabilities) -> Bool {
  lhs.detectionStatus.stringValue == rhs.detectionStatus.stringValue &&
    lhs.isFoldable == rhs.isFoldable &&
    lhs.hasContinuousAngle == rhs.hasContinuousAngle &&
    lhs.hasFoldGeometry == rhs.hasFoldGeometry &&
    lhs.supportedPostures.map(\.stringValue) == rhs.supportedPostures.map(\.stringValue) &&
    lhs.angleRange?.minValue == rhs.angleRange?.minValue &&
    lhs.angleRange?.maxValue == rhs.angleRange?.maxValue
}

private func sameLowFrequencyState(_ lhs: FoldState, _ rhs: FoldState) -> Bool {
  lhs.posture.stringValue == rhs.posture.stringValue &&
    lhs.orientation.stringValue == rhs.orientation.stringValue &&
    lhs.geometry == nil && rhs.geometry == nil
}

private extension Variant_NullType_Double {
  var numberValue: Double? {
    if case .second(let value) = self { return value }
    return nil
  }
}

private extension Variant_NullType_AngleRange {
  var minValue: Double? {
    if case .second(let range) = self { return range.min }
    return nil
  }

  var maxValue: Double? {
    if case .second(let range) = self { return range.max }
    return nil
  }
}
