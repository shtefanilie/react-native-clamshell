import XCTest
import React
import UIKit
@testable import Clamshell

@MainActor
private final class TestHingeInteraction: NSObject, UIInteraction {
  weak var view: UIView?
  let deliver: (Result<HingeUpdate, Error>) -> Void

  init(deliver: @escaping (Result<HingeUpdate, Error>) -> Void) {
    self.deliver = deliver
  }

  func willMove(to view: UIView?) {}

  func didMove(to view: UIView?) {
    self.view = view
    if view == nil {
      deliver(.success(HingeUpdate(
        capabilities: capabilitiesForHingeContext(.unavailable),
        state: normalizedFoldState(posture: .unknown, degrees: nil)
      )))
    }
  }
}

private final class TestSurface: NSObject, RCTSurfaceProtocol {
  let stage = RCTSurfaceStage(rawValue: 0)
  let moduleName = "ClamshellHostTest"
  weak var delegate: RCTSurfaceDelegate?
  let rootViewTag: NSNumber = 1
  var properties: [AnyHashable: Any] = [:]
  let rootTag = 1
  let intrinsicSize = CGSize.zero

  func setMinimumSize(_ minimumSize: CGSize, maximumSize: CGSize) {}
  func setMinimumSize(_ minimumSize: CGSize, maximumSize: CGSize, viewportOffset: CGPoint) {}
  func view() -> RCTSurfaceView { RCTSurfaceView(surface: self) }
  func sizeThatFitsMinimumSize(_ minimumSize: CGSize, maximumSize: CGSize) -> CGSize { .zero }
  func start() {}
  func stop() {}
}

final class HingeHostBindingTests: XCTestCase {
  @MainActor
  func testAttachIsIdempotentAndReplacementDetachesOldInteraction() {
    let window = UIWindow()
    let first = UIView()
    let second = UIView()
    window.addSubview(first)
    window.addSubview(second)
    var current: UIView? = first
    var interactions: [TestHingeInteraction] = []
    var updates: [HingeUpdate?] = []
    let binding = HingeHostBinding(
      rootProvider: { current },
      makeInteraction: { handler in
        let interaction = TestHingeInteraction(deliver: handler)
        interactions.append(interaction)
        return interaction
      },
      onUpdate: { updates.append($0) },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.attach(to: first)
    binding.attach(to: first)
    XCTAssertEqual(first.interactions.count, 1)
    XCTAssertEqual(interactions.count, 1)

    current = second
    binding.rebind()
    XCTAssertTrue(first.interactions.isEmpty)
    XCTAssertEqual(second.interactions.count, 1)
    XCTAssertEqual(interactions.count, 2)
    // A detach callback must not resolve the replacement host as non-foldable.
    XCTAssertTrue(updates.compactMap { $0 }.isEmpty)
    binding.dispose()
  }

  @MainActor
  func testOldCallbacksAndCallbacksAfterDisposalAreIgnored() {
    let window = UIWindow()
    let first = UIView()
    let second = UIView()
    window.addSubview(first)
    window.addSubview(second)
    var current: UIView? = first
    var interactions: [TestHingeInteraction] = []
    var received = 0
    let binding = HingeHostBinding(
      rootProvider: { current },
      makeInteraction: { handler in
        let interaction = TestHingeInteraction(deliver: handler)
        interactions.append(interaction)
        return interaction
      },
      onUpdate: { if $0 != nil { received += 1 } },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    let sample = HingeUpdate(
      capabilities: capabilitiesForHingeContext(.available),
      state: normalizedFoldState(posture: .partiallyOpen, degrees: 90)
    )
    binding.rebind()
    interactions[0].deliver(.success(sample))
    XCTAssertEqual(received, 1)
    current = second
    binding.rebind()
    interactions[0].deliver(.success(sample))
    XCTAssertEqual(received, 1)
    interactions[1].deliver(.success(sample))
    XCTAssertEqual(received, 2)
    binding.dispose()
    binding.dispose()
    interactions[1].deliver(.success(sample))
    binding.rebind()
    XCTAssertEqual(received, 2)
    XCTAssertEqual(interactions.count, 2)
    XCTAssertTrue(second.interactions.isEmpty)
    XCTAssertTrue(second.subviews.isEmpty)
  }

  @MainActor
  func testBackgroundSuspendsAndForegroundRebindsWithoutUnsupportedEvent() {
    let window = UIWindow()
    let root = UIView()
    window.addSubview(root)
    let center = NotificationCenter()
    var samples = 0
    let binding = HingeHostBinding(
      rootProvider: { root },
      notificationCenter: center,
      makeInteraction: { TestHingeInteraction(deliver: $0) },
      onUpdate: { if $0 != nil { samples += 1 } },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.start()
    XCTAssertEqual(root.interactions.count, 1)
    center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    XCTAssertTrue(root.interactions.isEmpty)
    binding.rebind()
    XCTAssertTrue(root.interactions.isEmpty)
    XCTAssertEqual(samples, 0)
    center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertEqual(root.interactions.count, 1)
    binding.dispose()
    center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
    XCTAssertTrue(root.interactions.isEmpty)
  }

  @MainActor
  func testNoHostRemainsPendingAndUnsupportedOSResolvesOnlyWithHost() {
    let window = UIWindow()
    let root = UIView()
    window.addSubview(root)
    var current: UIView?
    var updates: [HingeUpdate?] = []
    let binding = HingeHostBinding(
      rootProvider: { current },
      makeInteraction: { _ in nil },
      onUpdate: { updates.append($0) },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.start()
    XCTAssertTrue(updates.compactMap { $0 }.isEmpty)
    current = root
    binding.rebind()
    XCTAssertEqual(updates.compactMap { $0 }.last?.capabilities.detectionStatus, .resolved)
    XCTAssertEqual(updates.compactMap { $0 }.last?.capabilities.isFoldable, false)
    current = nil
    binding.rebind()
    XCTAssertNil(updates.last!)
    binding.dispose()
  }

  @MainActor
  func testNormalizationErrorsAreReported() {
    let window = UIWindow()
    let root = UIView()
    window.addSubview(root)
    var interaction: TestHingeInteraction?
    var errors = 0
    let binding = HingeHostBinding(
      rootProvider: { root },
      makeInteraction: { handler in
        let next = TestHingeInteraction(deliver: handler)
        interaction = next
        return next
      },
      onUpdate: { _ in },
      onError: {
        XCTAssertEqual($0 as? HingeNormalizationError, .nonFiniteAngle)
        errors += 1
      }
    )
    binding.rebind()
    interaction?.deliver(.failure(HingeNormalizationError.nonFiniteAngle))
    XCTAssertEqual(errors, 1)
    binding.dispose()
  }

  @MainActor
  func testRootLeavingWindowTriggersRebinding() async {
    let window = UIWindow()
    let first = UIView()
    let second = UIView()
    window.addSubview(first)
    window.addSubview(second)
    var current: UIView? = first
    let binding = HingeHostBinding(
      rootProvider: { current },
      makeInteraction: { TestHingeInteraction(deliver: $0) },
      onUpdate: { _ in },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.start()
    first.removeFromSuperview()
    current = second
    let rebound = expectation(description: "Deferred root rebind")
    DispatchQueue.main.async { rebound.fulfill() }
    await fulfillment(of: [rebound], timeout: 2)
    XCTAssertTrue(first.interactions.isEmpty)
    XCTAssertEqual(second.interactions.count, 1)
    binding.dispose()
  }

  @MainActor
  func testSynchronousInitialUpdateIsDeliveredAfterAttachment() {
    let window = UIWindow()
    let root = UIView()
    window.addSubview(root)
    var received = 0
    let binding = HingeHostBinding(
      rootProvider: { root },
      makeInteraction: { handler in
        handler(.success(HingeUpdate(
          capabilities: capabilitiesForHingeContext(.available),
          state: normalizedFoldState(posture: .closed, degrees: 0)
        )))
        return TestHingeInteraction(deliver: handler)
      },
      onUpdate: { update in
        guard update != nil else { return }
        XCTAssertEqual(root.interactions.count, 1)
        received += 1
      },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.rebind()
    XCTAssertEqual(received, 1)
    binding.dispose()
  }

  @MainActor
  func testReentrantDisposalDuringReplacementCannotLeaveAnInteraction() {
    let window = UIWindow()
    let first = UIView()
    let second = UIView()
    window.addSubview(first)
    window.addSubview(second)
    var current: UIView? = first
    var binding: HingeHostBinding!
    binding = HingeHostBinding(
      rootProvider: { current },
      makeInteraction: { TestHingeInteraction(deliver: $0) },
      onUpdate: { if $0 == nil { binding.dispose() } },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.start()
    current = second
    binding.rebind()
    XCTAssertTrue(first.interactions.isEmpty)
    XCTAssertTrue(second.interactions.isEmpty)
    binding = nil
  }

  @MainActor
  func testDeinitReleasesInteractionAndObserver() async {
    let window = UIWindow()
    let root = UIView()
    window.addSubview(root)
    var binding: HingeHostBinding? = HingeHostBinding(
      rootProvider: { root },
      makeInteraction: { TestHingeInteraction(deliver: $0) },
      onUpdate: { _ in },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding?.start()
    weak var released = binding
    binding = nil
    XCTAssertNil(released)
    let cleanup = expectation(description: "Main-actor deinit cleanup")
    DispatchQueue.main.async { cleanup.fulfill() }
    await fulfillment(of: [cleanup], timeout: 2)
    XCTAssertTrue(root.interactions.isEmpty)
    XCTAssertTrue(root.subviews.isEmpty)
  }

  @MainActor
  func testDefaultFactoryResolvesUnsupportedBelowAvailability() throws {
    if #available(iOS 27.1, *) {
      throw XCTSkip("This regression covers older operating systems")
    }
    let window = UIWindow()
    let root = UIView()
    window.addSubview(root)
    var update: HingeUpdate?
    let binding = HingeHostBinding(
      rootProvider: { root },
      onUpdate: { update = $0 },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.start()
    XCTAssertEqual(update?.capabilities.detectionStatus, .resolved)
    XCTAssertEqual(update?.capabilities.isFoldable, false)
    XCTAssertTrue(root.interactions.isEmpty)
    binding.dispose()
  }

  @MainActor
  func testActiveSceneDiscoversReactRootAndRebindsReplacement() async throws {
    let scene = try XCTUnwrap(UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .first { $0.activationState == .foregroundActive })
    let oldKeyWindow = scene.windows.first { $0.isKeyWindow }
    let window = UIWindow(windowScene: scene)
    let controller = UIViewController()
    window.rootViewController = controller
    let first = RCTSurfaceHostingView(surface: TestSurface(), sizeMeasureMode: .init(rawValue: 0))
    let second = RCTSurfaceHostingView(surface: TestSurface(), sizeMeasureMode: .init(rawValue: 0))
    controller.view.addSubview(first)
    window.makeKeyAndVisible()
    let binding = HingeHostBinding(
      makeInteraction: { TestHingeInteraction(deliver: $0) },
      onUpdate: { _ in },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    defer {
      binding.dispose()
      window.isHidden = true
      oldKeyWindow?.makeKey()
    }
    binding.start()
    XCTAssertTrue(HingeHostBinding.activeReactRoot() === first)
    XCTAssertEqual(first.interactions.count, 1)
    first.removeFromSuperview()
    controller.view.addSubview(second)
    let rebound = expectation(description: "New RN root discovered")
    DispatchQueue.main.async { rebound.fulfill() }
    await fulfillment(of: [rebound], timeout: 2)
    XCTAssertTrue(HingeHostBinding.activeReactRoot() === second)
    XCTAssertTrue(first.interactions.isEmpty)
    XCTAssertEqual(second.interactions.count, 1)
  }

  @MainActor
  func testRealUIKitInteractionAttachesRebindsAndReleases() throws {
    guard #available(iOS 27.1, *) else {
      throw XCTSkip("Real UIHingeInteraction requires the iOS 27.1 simulator runtime")
    }
    let window = UIWindow()
    let first = UIView()
    let second = UIView()
    window.addSubview(first)
    window.addSubview(second)
    var current: UIView? = first
    let binding = HingeHostBinding(
      rootProvider: { current },
      onUpdate: { _ in },
      onError: { XCTFail("Unexpected error: \($0)") }
    )
    binding.start()
    weak let oldInteraction = first.interactions.first
    XCTAssertTrue(oldInteraction is UIHingeInteraction)
    current = second
    binding.rebind()
    XCTAssertTrue(first.interactions.isEmpty)
    XCTAssertNil(oldInteraction)
    XCTAssertTrue(second.interactions.first is UIHingeInteraction)
    binding.dispose()
    XCTAssertTrue(second.interactions.isEmpty)
  }
}
