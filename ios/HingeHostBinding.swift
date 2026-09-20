import Foundation
import React
import UIKit

@MainActor
final class HingeHostBinding: NSObject {
  typealias Handler = @MainActor (Result<HingeUpdate, Error>) -> Void
  typealias InteractionFactory = @MainActor (@escaping Handler) -> UIInteraction?

  private let rootProvider: @MainActor () -> UIView?
  private let notificationCenter: NotificationCenter
  private let makeInteraction: InteractionFactory
  private let onAttach: () -> UInt64
  private let onDetach: (UInt64) -> Void
  private let onUpdate: (UInt64, HingeUpdate) -> Void
  private let onError: (Error) -> Void
  private weak var rootView: UIView?
  private var interaction: UIInteraction?
  private var hostObserver: HingeHostObserverView?
  private var attachmentID: UUID?
  private var hostID: UInt64?
  private var pendingUpdate: Result<HingeUpdate, Error>?
  private var attaching = false
  private var started = false
  private var suspended = false
  private var disposed = false
  private var queuedRebind: DispatchWorkItem?

  init(
    rootProvider: @escaping @MainActor () -> UIView? = { HingeHostBinding.activeReactRoot() },
    notificationCenter: NotificationCenter = .default,
    makeInteraction: @escaping InteractionFactory = { HingeHostBinding.makeUIKitInteraction($0) },
    onAttach: @escaping () -> UInt64,
    onDetach: @escaping (UInt64) -> Void,
    onUpdate: @escaping (UInt64, HingeUpdate) -> Void,
    onError: @escaping (Error) -> Void
  ) {
    self.rootProvider = rootProvider
    self.notificationCenter = notificationCenter
    self.makeInteraction = makeInteraction
    self.onAttach = onAttach
    self.onDetach = onDetach
    self.onUpdate = onUpdate
    self.onError = onError
    super.init()
  }

  convenience init(
    rootProvider: @escaping @MainActor () -> UIView? = { HingeHostBinding.activeReactRoot() },
    notificationCenter: NotificationCenter = .default,
    makeInteraction: @escaping InteractionFactory = { HingeHostBinding.makeUIKitInteraction($0) },
    onUpdate: @escaping (HingeUpdate?) -> Void,
    onError: @escaping (Error) -> Void
  ) {
    var nextHost: UInt64 = 1
    self.init(
      rootProvider: rootProvider,
      notificationCenter: notificationCenter,
      makeInteraction: makeInteraction,
      onAttach: {
        defer { nextHost += 1 }
        return nextHost
      },
      onDetach: { _ in onUpdate(nil) },
      onUpdate: { _, update in onUpdate(update) },
      onError: onError
    )
  }

  func start() {
    guard !disposed, !started else { return }
    started = true
    for name in [
      UIApplication.willResignActiveNotification,
      UIApplication.didEnterBackgroundNotification,
    ] {
      notificationCenter.addObserver(self, selector: #selector(suspend), name: name, object: nil)
    }
    notificationCenter.addObserver(
      self, selector: #selector(resume),
      name: UIApplication.didBecomeActiveNotification, object: nil
    )
    for name in [
      UIScene.didActivateNotification, UIScene.willDeactivateNotification,
      UIScene.didDisconnectNotification, UIWindow.didBecomeKeyNotification,
      UIWindow.didBecomeVisibleNotification, UIWindow.didBecomeHiddenNotification,
      .RCTContentDidAppear,
    ] {
      notificationCenter.addObserver(self, selector: #selector(hostChanged), name: name, object: nil)
    }
    rebind()
  }

  func attach(to view: UIView) {
    guard !disposed, !suspended else { return }
    guard view.window != nil else {
      detach()
      return
    }
    guard rootView !== view || attachmentID == nil else { return }
    detach()
    // A consumer may dispose the binding in the host-unavailable callback.
    guard !disposed, !suspended, attachmentID == nil else { return }
    let id = UUID()
    attachmentID = id
    let host = onAttach()
    hostID = host
    rootView = view
    attaching = true
    let observer = HingeHostObserverView(frame: .zero)
    observer.isUserInteractionEnabled = false
    observer.isAccessibilityElement = false
    view.addSubview(observer)
    observer.onWindowChange = { [weak self] in self?.scheduleRebind() }
    hostObserver = observer
    interaction = makeInteraction { [weak self] result in
      self?.receive(result, attachmentID: id)
    }
    if let interaction {
      view.addInteraction(interaction)
    } else {
      pendingUpdate = .success(HingeUpdate(
        capabilities: capabilitiesForHingeContext(.unavailable),
        state: normalizedFoldState(posture: .unknown, degrees: nil)
      ))
    }
    attaching = false
    if let pendingUpdate {
      self.pendingUpdate = nil
      receive(pendingUpdate, attachmentID: id)
    }
  }

  func detach() {
    let hadHost = attachmentID != nil
    let host = hostID
    // Invalidate first: UIKit can send a nil hinge while being removed.
    attachmentID = nil
    hostID = nil
    pendingUpdate = nil
    attaching = false
    if let interaction {
      rootView?.removeInteraction(interaction)
    }
    interaction = nil
    hostObserver?.onWindowChange = nil
    hostObserver?.removeFromSuperview()
    hostObserver = nil
    rootView = nil
    if hadHost, !disposed, let host { onDetach(host) }
  }

  func rebind() {
    guard !disposed, !suspended else { return }
    guard let root = rootProvider() else {
      detach()
      return
    }
    attach(to: root)
  }

  func dispose() {
    guard !disposed else { return }
    disposed = true
    notificationCenter.removeObserver(self)
    queuedRebind?.cancel()
    queuedRebind = nil
    detach()
  }

  private func receive(_ result: Result<HingeUpdate, Error>, attachmentID id: UUID) {
    guard !disposed, !suspended, attachmentID == id,
          let rootView, rootView.window != nil, rootProvider() === rootView else { return }
    if attaching {
      pendingUpdate = result
      return
    }
    guard let hostID else { return }
    switch result {
    case .success(let update): onUpdate(hostID, update)
    case .failure(let error): onError(error)
    }
  }

  @objc private func suspend() {
    suspended = true
    detach()
  }

  @objc private func resume() {
    suspended = false
    rebind()
  }

  @objc private func hostChanged() {
    rebind()
    // Scene/window notifications may precede the new root entering its window.
    scheduleRebind()
  }

  private func scheduleRebind() {
    guard !disposed else { return }
    queuedRebind?.cancel()
    let work = DispatchWorkItem { [weak self] in self?.rebind() }
    queuedRebind = work
    DispatchQueue.main.async(execute: work)
  }

  static func activeReactRoot() -> UIView? {
    guard UIApplication.shared.applicationState == .active else { return nil }
    let scenes = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .filter { $0.activationState == .foregroundActive }
      .sorted { $0.session.persistentIdentifier < $1.session.persistentIdentifier }
    let windows = scenes.flatMap(\.windows).filter { !$0.isHidden }
    for window in windows.filter(\.isKeyWindow) + windows.filter({ !$0.isKeyWindow }) {
      if let root = reactRoot(in: window) { return root }
    }
    return nil
  }

  static func reactRoot(in view: UIView) -> UIView? {
    if view is RCTSurfaceHostingView { return view }
    for child in view.subviews {
      if let root = reactRoot(in: child) { return root }
    }
    return nil
  }

  private static func makeUIKitInteraction(_ handler: @escaping Handler) -> UIInteraction? {
    guard #available(iOS 27.1, tvOS 27.1, visionOS 27.1, *) else { return nil }
    return UIHingeInteraction { _, update in
      do {
        handler(.success(try normalizedHingeUpdate(update.hinge)))
      } catch {
        handler(.failure(error))
      }
    }
  }

  deinit {
    queuedRebind?.cancel()
    notificationCenter.removeObserver(self)
    let root = rootView
    let interaction = interaction
    let observer = hostObserver
    Task { @MainActor in
      if let interaction { root?.removeInteraction(interaction) }
      observer?.onWindowChange = nil
      observer?.removeFromSuperview()
    }
  }
}

@MainActor
private final class HingeHostObserverView: UIView {
  var onWindowChange: (() -> Void)?

  override func didMoveToWindow() {
    super.didMoveToWindow()
    onWindowChange?()
  }
}
