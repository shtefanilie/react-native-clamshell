import XCTest
@testable import Clamshell

final class ClamshellCoordinatorTests: XCTestCase {
  func testPendingDefaultsAndUnsupportedHostResolution() throws {
    let coordinator = ClamshellCoordinator(emitUiAngle: { _ in }, invalidateUiSinks: {}, reportDiagnostic: { _, _ in })
    XCTAssertEqual(try coordinator.getCapabilities().detectionStatus.stringValue, "pending")
    XCTAssertFalse(try coordinator.getCapabilities().isFoldable)
    XCTAssertEqual(try coordinator.getSnapshot().posture.stringValue, "unknown")
    XCTAssertNil(try coordinator.getSnapshot().angle)

    let host = coordinator.attachHost()
    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: false),
      state: normalizedFoldState(posture: .unknown, degrees: nil)
    ))

    XCTAssertEqual(try coordinator.getCapabilities().detectionStatus.stringValue, "resolved")
    XCTAssertFalse(try coordinator.getCapabilities().isFoldable)
    XCTAssertNil(try coordinator.getSnapshot().angle)
  }

  func testStateListenersIgnoreAngleOnlySamplesButSnapshotKeepsLatestAngle() throws {
    let coordinator = ClamshellCoordinator(emitUiAngle: { _ in }, invalidateUiSinks: {}, reportDiagnostic: { _, _ in })
    let host = coordinator.attachHost()
    var states: [FoldState] = []
    let remove = try coordinator.addStateListener { states.append($0) }

    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .partiallyOpen, degrees: 90)
    ))
    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .partiallyOpen, degrees: 95)
    ))

    XCTAssertEqual(states.map { $0.posture.stringValue }, ["halfOpen"])
    XCTAssertEqual(try XCTUnwrap(try coordinator.getSnapshot().angle?.numberValue), 95)
    remove()
  }

  func testAngleDemandGatesJsAndUiRuntimeFanout() throws {
    var uiAngles: [Double] = []
    let coordinator = ClamshellCoordinator(emitUiAngle: { uiAngles.append($0) }, invalidateUiSinks: {}, reportDiagnostic: { _, _ in })
    let host = coordinator.attachHost()
    var jsAngles: [Double] = []
    let remove = try coordinator.addAngleListener { jsAngles.append($0) }

    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .partiallyOpen, degrees: 90)
    ))
    XCTAssertTrue(jsAngles.isEmpty)
    XCTAssertTrue(uiAngles.isEmpty)

    try coordinator.startAngleUpdates()
    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .partiallyOpen, degrees: 91)
    ))
    XCTAssertEqual(jsAngles, [91])
    XCTAssertEqual(uiAngles, [91])

    try coordinator.stopAngleUpdates()
    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .partiallyOpen, degrees: 92)
    ))
    XCTAssertEqual(jsAngles, [91])
    XCTAssertEqual(uiAngles, [91])
    remove()
  }

  func testCallbackIsolationRepeatedUnsubscribeAndReentrantDisposal() throws {
    var invalidations = 0
    let coordinator = ClamshellCoordinator(emitUiAngle: { _ in }, invalidateUiSinks: { invalidations += 1 }, reportDiagnostic: { _, _ in })
    let host = coordinator.attachHost()
    var first = 0
    var second = 0
    var removeFirst: (() -> Void)!
    removeFirst = try coordinator.addStateListener { _ in
      first += 1
      removeFirst()
      coordinator.dispose()
    }
    let removeSecond = try coordinator.addStateListener { _ in second += 1 }

    coordinator.onHostUpdate(host, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .closed, degrees: 0)
    ))

    XCTAssertEqual(first, 1)
    XCTAssertEqual(second, 0)
    XCTAssertEqual(invalidations, 1)
    removeFirst()
    removeSecond()
    XCTAssertThrowsError(try coordinator.startAngleUpdates())
    coordinator.dispose()
    XCTAssertEqual(invalidations, 1)
  }

  func testHostReplacementRejectsStaleUpdatesAndDetachReturnsPending() throws {
    let coordinator = ClamshellCoordinator(emitUiAngle: { _ in }, invalidateUiSinks: {}, reportDiagnostic: { _, _ in })
    let first = coordinator.attachHost()
    let second = coordinator.attachHost()

    coordinator.onHostUpdate(first, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .closed, degrees: 0)
    ))
    XCTAssertEqual(try coordinator.getSnapshot().posture.stringValue, "unknown")

    coordinator.onHostUpdate(second, HingeUpdate(
      capabilities: testCapabilities(isFoldable: true),
      state: normalizedFoldState(posture: .fullyOpen, degrees: 180)
    ))
    XCTAssertEqual(try coordinator.getSnapshot().posture.stringValue, "fullyOpen")

    coordinator.detachHost(second)
    XCTAssertEqual(try coordinator.getCapabilities().detectionStatus.stringValue, "pending")
    XCTAssertEqual(try coordinator.getSnapshot().posture.stringValue, "unknown")
    XCTAssertNil(try coordinator.getSnapshot().angle)
  }

  func testReportsTypedErrors() throws {
    let coordinator = ClamshellCoordinator(emitUiAngle: { _ in }, invalidateUiSinks: {}, reportDiagnostic: { _, _ in })
    var errors: [ClamshellError] = []
    _ = try coordinator.addErrorListener { errors.append($0) }

    coordinator.reportError(.sensorregistrationfailed, message: "bad sample")

    XCTAssertEqual(errors.map(\.code), [.sensorregistrationfailed])
    XCTAssertEqual(errors.map(\.message), ["bad sample"])
  }
}

private func testCapabilities(isFoldable: Bool) -> ClamshellCapabilities {
  ClamshellCapabilities(
    detectionStatus: .resolved,
    isFoldable: isFoldable,
    hasContinuousAngle: isFoldable,
    angleRange: nil,
    supportedPostures: isFoldable ? [.closed, .halfopen, .fullyopen] : [],
    hasFoldGeometry: false
  )
}

private extension Variant_NullType_Double {
  var numberValue: Double? {
    if case .second(let value) = self { return value }
    return nil
  }
}
