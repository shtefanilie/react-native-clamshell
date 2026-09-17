import XCTest

final class ClamshellNativeTests: XCTestCase {
  func testScaffold() {
    XCTAssertEqual("Clamshell", "Clamshell")
  }

  func testWorkletsBridge() {
    XCTFail("Task 3 native Worklets UI-runtime bridge is not implemented")
  }
}
