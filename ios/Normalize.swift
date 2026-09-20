import Foundation
import UIKit

enum NativeAngleUnit {
  case radians
  case degrees
}

enum NativeHingePosture {
  case closed
  case partiallyOpen
  case fullyOpen
  case unknown

  var canonical: Posture {
    switch self {
    case .closed: return .closed
    case .partiallyOpen: return .halfopen
    case .fullyOpen: return .fullyopen
    case .unknown: return .unknown
    }
  }

  @available(iOS 27.1, tvOS 27.1, visionOS 27.1, *)
  init(status: UIHinge.Status) {
    switch status {
    case .unknown: self = .unknown
    case .closed: self = .closed
    case .partiallyOpen: self = .partiallyOpen
    case .fullyOpen: self = .fullyOpen
    @unknown default: self = .unknown
    }
  }
}

enum HingeContextState {
  case unavailable
  case available
}

enum HingeNormalizationError: Error, Equatable {
  case nonFiniteAngle
}

struct HingeUpdate {
  let capabilities: ClamshellCapabilities
  let state: FoldState
}

func normalizedFoldState(posture: NativeHingePosture, degrees: Double?) -> FoldState {
  FoldState(
    angle: degrees.map { .second($0) },
    posture: posture.canonical,
    orientation: .none,
    geometry: nil
  )
}

func nativeAngleToCanonicalDegrees(
  _ value: Double,
  unit: NativeAngleUnit
) throws -> Double {
  guard value.isFinite else {
    throw HingeNormalizationError.nonFiniteAngle
  }

  let degrees: Double
  switch unit {
  case .degrees:
    degrees = value
  case .radians:
    degrees = value * (180.0 / .pi)
  }
  guard degrees.isFinite else {
    throw HingeNormalizationError.nonFiniteAngle
  }
  // UNVERIFIED: Tech Talk 111464 - hardware zero point and range; do not clamp or offset.
  return degrees
}

func capabilitiesForHingeContext(
  _ contextState: HingeContextState
) -> ClamshellCapabilities {
  switch contextState {
  case .unavailable:
    return ClamshellCapabilities(
      detectionStatus: .resolved,
      isFoldable: false,
      hasContinuousAngle: false,
      angleRange: nil,
      supportedPostures: [],
      hasFoldGeometry: false
    )
  case .available:
    return ClamshellCapabilities(
      detectionStatus: .resolved,
      isFoldable: true,
      hasContinuousAngle: true,
      angleRange: nil,
      supportedPostures: [.closed, .halfopen, .fullyopen],
      hasFoldGeometry: false
    )
  }
}

@available(iOS 27.1, tvOS 27.1, visionOS 27.1, *)
@MainActor
func normalizedHingeUpdate(_ hinge: UIHinge?) throws -> HingeUpdate {
  guard let hinge else {
    return HingeUpdate(
      capabilities: capabilitiesForHingeContext(.unavailable),
      state: normalizedFoldState(posture: .unknown, degrees: nil)
    )
  }
  // UNVERIFIED: Tech Talk 111464 - physical cadence; UIKit guarantees no fixed rate.
  let degrees = try nativeAngleToCanonicalDegrees(Double(hinge.angle), unit: .radians)
  return HingeUpdate(
    capabilities: capabilitiesForHingeContext(.available),
    state: normalizedFoldState(posture: NativeHingePosture(status: hinge.status), degrees: degrees)
  )
}
