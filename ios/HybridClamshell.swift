//
//  HybridClamshell.swift
//  Pods
//
//  Created by Stefan Ilie on 9/16/2026.
//

import Foundation
import os.log

class HybridClamshell: HybridClamshellSpec {
    private let angleChannel: UInt64
    private let coordinator: ClamshellCoordinator
    @MainActor private var hostBinding: HingeHostBinding?

    override init() {
        let channel = ClamshellAngleRuntimeBinding.createChannel()
        ClamshellAngleRuntimeBinding.activateChannel(channel)
        angleChannel = channel
        coordinator = ClamshellCoordinator(
            emitUiAngle: { degrees in
                ClamshellAngleRuntimeBinding.emitDegrees(degrees, channel: channel)
            },
            invalidateUiSinks: {
                ClamshellAngleRuntimeBinding.releaseChannel(channel)
            },
            reportDiagnostic: { message, error in
                if let error {
                    Logger(subsystem: "Clamshell", category: "HybridClamshell")
                        .error("\(message, privacy: .public): \(String(describing: error), privacy: .public)")
                } else {
                    Logger(subsystem: "Clamshell", category: "HybridClamshell")
                        .error("\(message, privacy: .public)")
                }
            }
        )
        super.init()
        Task { @MainActor [weak self] in
            self?.startHostBinding()
        }
    }

    @objc
    func getAngleChannelId() -> UInt64 {
        angleChannel
    }

    func getCapabilities() throws -> ClamshellCapabilities {
        return try coordinator.getCapabilities()
    }

    func addCapabilitiesListener(
        cb: @escaping (ClamshellCapabilities) -> Void
    ) throws -> () -> Void {
        return try coordinator.addCapabilitiesListener(cb)
    }

    func getSnapshot() throws -> FoldState {
        return try coordinator.getSnapshot()
    }

    func addStateListener(
        cb: @escaping (FoldState) -> Void
    ) throws -> () -> Void {
        return try coordinator.addStateListener(cb)
    }

    func startAngleUpdates() throws {
        try coordinator.startAngleUpdates()
    }

    func stopAngleUpdates() throws {
        try coordinator.stopAngleUpdates()
    }

    func addAngleListener(
        cb: @escaping (Double) -> Void
    ) throws -> () -> Void {
        return try coordinator.addAngleListener(cb)
    }

    func addErrorListener(
        cb: @escaping (ClamshellError) -> Void
    ) throws -> () -> Void {
        return try coordinator.addErrorListener(cb)
    }

    func dispose() {
        Task { @MainActor [weak self] in
            self?.hostBinding?.dispose()
            self?.hostBinding = nil
        }
        coordinator.dispose()
        ClamshellAngleRuntimeBinding.releaseChannel(angleChannel)
    }

    @MainActor
    private func startHostBinding() {
        guard hostBinding == nil else { return }
        let binding = HingeHostBinding(
            onAttach: { [coordinator] in coordinator.attachHost() },
            onDetach: { [coordinator] host in coordinator.detachHost(host) },
            onUpdate: { [coordinator] host, update in coordinator.onHostUpdate(host, update) },
            onError: { [weak self] error in self?.handleHostError(error) }
        )
        hostBinding = binding
        binding.start()
    }

    private func handleHostError(_ error: Error) {
        switch error {
        case HingeNormalizationError.nonFiniteAngle:
            coordinator.reportError(.sensorregistrationfailed, message: "Invalid hinge angle sample", cause: error)
        default:
            coordinator.reportError(.sensorregistrationfailed, message: String(describing: error), cause: error)
        }
    }

    deinit {
        dispose()
    }
}
