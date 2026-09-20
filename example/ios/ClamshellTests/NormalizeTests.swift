import XCTest
import UIKit
@testable import Clamshell

final class NormalizeTests: XCTestCase {
  func testDegreesArePreservedWithoutInventingHardwareBounds() throws {
    for degrees in [-15.0, 0, 90, 180, 360, 400] {
      XCTAssertEqual(try nativeAngleToCanonicalDegrees(degrees, unit: .degrees), degrees)
    }
  }

  func testRadiansConvertToDegrees() throws {
    XCTAssertEqual(try nativeAngleToCanonicalDegrees(0, unit: .radians), 0)
    XCTAssertEqual(try nativeAngleToCanonicalDegrees(.pi / 2, unit: .radians), 90, accuracy: 1e-10)
    XCTAssertEqual(try nativeAngleToCanonicalDegrees(.pi, unit: .radians), 180, accuracy: 1e-10)
    XCTAssertEqual(try nativeAngleToCanonicalDegrees(2 * .pi, unit: .radians), 360, accuracy: 1e-10)
  }

  func testInvalidSamplesThrowInsteadOfCrashingOrEmittingNaN() {
    for value in [Double.nan, .infinity, -.infinity] {
      for unit in [NativeAngleUnit.degrees, .radians] {
        XCTAssertThrowsError(try nativeAngleToCanonicalDegrees(value, unit: unit)) {
          XCTAssertEqual($0 as? HingeNormalizationError, .nonFiniteAngle)
        }
      }
    }
    XCTAssertThrowsError(try nativeAngleToCanonicalDegrees(.greatestFiniteMagnitude, unit: .radians))
  }

  func testProjectPostureMapping() {
    let cases: [(NativeHingePosture, String)] = [
      (.closed, "closed"), (.partiallyOpen, "halfOpen"),
      (.fullyOpen, "fullyOpen"), (.unknown, "unknown"),
    ]
    for (posture, expected) in cases {
      let state = normalizedFoldState(posture: posture, degrees: nil)
      XCTAssertEqual(state.posture.stringValue, expected)
    }
  }

  func testSnapshotPreservesAngleAndKeepsGeometryUnavailable() {
    let state = normalizedFoldState(posture: .partiallyOpen, degrees: 90)
    guard case .second(let angle) = state.angle else {
      return XCTFail("Expected numeric angle")
    }
    XCTAssertEqual(angle, 90)
    XCTAssertEqual(state.orientation, .none)
    XCTAssertNil(state.geometry)
  }

  func testNoHingeResolvesUnsupportedWithoutInventingAngle() {
    let capabilities = capabilitiesForHingeContext(.unavailable)
    XCTAssertEqual(capabilities.detectionStatus, .resolved)
    XCTAssertFalse(capabilities.isFoldable)
    XCTAssertFalse(capabilities.hasContinuousAngle)
    XCTAssertNil(capabilities.angleRange)
    XCTAssertTrue(capabilities.supportedPostures.isEmpty)
    XCTAssertFalse(capabilities.hasFoldGeometry)
  }

  func testPresentHingeDoesNotInventAngleRangeOrGeometry() {
    let capabilities = capabilitiesForHingeContext(.available)
    XCTAssertEqual(capabilities.detectionStatus, .resolved)
    XCTAssertTrue(capabilities.isFoldable)
    XCTAssertTrue(capabilities.hasContinuousAngle)
    XCTAssertNil(capabilities.angleRange)
    XCTAssertEqual(capabilities.supportedPostures, [.closed, .halfopen, .fullyopen])
    XCTAssertFalse(capabilities.hasFoldGeometry)
  }

  @MainActor
  func testUIKitNilHingeProducesUnsupportedSnapshot() throws {
    guard #available(iOS 27.1, *) else {
      throw XCTSkip("UIHinge requires iOS 27.1")
    }
    let update = try normalizedHingeUpdate(nil)
    XCTAssertFalse(update.capabilities.isFoldable)
    XCTAssertEqual(update.capabilities.detectionStatus, .resolved)
    XCTAssertNil(update.state.angle)
    XCTAssertEqual(update.state.posture, .unknown)
    XCTAssertEqual(update.state.orientation, .none)
    XCTAssertNil(update.state.geometry)
  }

  @MainActor
  func testUIKitPostureMapping() throws {
    guard #available(iOS 27.1, *) else {
      throw XCTSkip("UIHinge requires iOS 27.1")
    }
    let cases: [(UIHinge.Status, String)] = [
      (.closed, "closed"), (.partiallyOpen, "halfOpen"),
      (.fullyOpen, "fullyOpen"), (.unknown, "unknown"),
    ]
    for (status, expected) in cases {
      let state = normalizedFoldState(posture: NativeHingePosture(status: status), degrees: nil)
      XCTAssertEqual(state.posture.stringValue, expected)
    }
  }
}
