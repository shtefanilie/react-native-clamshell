//
//  HybridClamshell.swift
//  Pods
//
//  Created by Stefan Ilie on 9/16/2026.
//

import Foundation

class HybridClamshell: HybridClamshellSpec {
    func getCapabilities() throws -> ClamshellCapabilities {
        return ClamshellCapabilities(
            detectionStatus: .pending,
            isFoldable: false,
            hasContinuousAngle: false,
            angleRange: nil,
            supportedPostures: [],
            hasFoldGeometry: false
        )
    }

    func addCapabilitiesListener(
        cb: @escaping (ClamshellCapabilities) -> Void
    ) throws -> () -> Void {
        return {}
    }

    func getSnapshot() throws -> FoldState {
        return FoldState(
            angle: nil,
            posture: .unknown,
            orientation: .none,
            geometry: nil
        )
    }

    func addStateListener(
        cb: @escaping (FoldState) -> Void
    ) throws -> () -> Void {
        return {}
    }

    func startAngleUpdates() throws {}

    func stopAngleUpdates() throws {}

    func addAngleListener(
        cb: @escaping (Double) -> Void
    ) throws -> () -> Void {
        return {}
    }

    func addErrorListener(
        cb: @escaping (ClamshellError) -> Void
    ) throws -> () -> Void {
        return {}
    }
}
