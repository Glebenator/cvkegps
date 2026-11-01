import CoreLocation
import XCTest

/// End-to-end flows on the simulator. They use the production Core Location path with simulated locations from XCTest,
/// so they prove functional behaviour only. GPS accuracy and battery use need a real iPhone.
/// Each capture is an attachment, so the report can show real app screens. All locations in the captures are simulated.
final class TripFlowUITests: XCTestCase {
    private let app = XCUIApplication()
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    /// A street in Vancouver, moving east.
    private let start = CLLocationCoordinate2D(latitude: 49.2827, longitude: -123.1207)

    override func setUp() {
        continueAfterFailure = false
    }

    /// A simulated fix `metres` east of the start, at `speed` m/s.
    private func fix(east metres: Double, speed: Double, accuracy: Double = 4) -> XCUILocation {
        let longitude = start.longitude + metres / (111_320 * cos(start.latitude * .pi / 180))
        return XCUILocation(location: CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: start.latitude + metres * 0.000002, longitude: longitude),
            altitude: 70 + metres / 100, horizontalAccuracy: accuracy, verticalAccuracy: 3, course: 88, courseAccuracy: 4,
            speed: speed, speedAccuracy: 0.4, timestamp: Date()))
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Allows location in the system dialog, if it shows.
    private func allowLocation() {
        for label in ["Allow While Using App", "Allow Once"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: 3) {
                button.tap()
                return
            }
        }
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    private func waitFor(_ element: XCUIElement, _ timeout: TimeInterval = 15, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Missing \(element)", file: file, line: line)
    }

    /// Grants access, shows live readings, records a trip along a simulated street, stops and saves it,
    /// replays it, and opens Settings. Captures are in the light theme unless the run changes the simulator appearance.
    func test1RecordAndReplayTrip() throws {
        app.launch()
        XCUIDevice.shared.location = fix(east: 0, speed: 0)
        let action = element("gps_action")
        if action.waitForExistence(timeout: 5) {
            capture("speed-permission")
            action.tap()
            allowLocation()
        }
        XCUIDevice.shared.location = fix(east: 0, speed: 12)
        let speed = element("current_speed")
        waitFor(speed)
        // 12 m/s is 43.2 km/h, or 26.8 mph in a United States region.
        let live = NSPredicate(format: "label CONTAINS '43.2' OR label CONTAINS '26.8'")
        expectation(for: live, evaluatedWith: speed)
        waitForExpectations(timeout: 20)
        capture("speed-live")

        element("gps_card").tap()
        waitFor(app.navigationBars["GPS details"])
        capture("gps-details")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        element("trip_start").tap()
        let confirm = element("confirm_start")
        if confirm.waitForExistence(timeout: 5) {
            capture("trip-notice")
            confirm.tap()
        }
        waitFor(element("recording_bar"))
        // About 40 seconds of driving at 12 m/s, then a stop.
        for second in 1...40 {
            XCUIDevice.shared.location = fix(east: Double(second) * 12, speed: second > 36 ? 0 : 12)
            Thread.sleep(forTimeInterval: 1)
        }
        capture("speed-recording")
        element("recording_bar").tap()
        waitFor(element("trip_stop"))
        Thread.sleep(forTimeInterval: 6)
        capture("live-trip")

        element("trip_stop").tap()
        waitFor(element("open_replay"), 20)
        Thread.sleep(forTimeInterval: 3)
        capture("saved-trip")

        element("open_replay").tap()
        waitFor(element("replay_play"), 30)
        element("replay_play").tap()
        Thread.sleep(forTimeInterval: 4)
        capture("replay-playing")
        element("replay_stop").tap()
        Thread.sleep(forTimeInterval: 2)
        capture("replay-charts")
        element("replay_back").tap()
        element("replay_back").tap()
        waitFor(element("open_replay"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitFor(app.navigationBars["Trips"])
        capture("trips-list")

        element("open_settings").firstMatch.tap()
        waitFor(element("settings_done"))
        capture("settings")
        element("settings_done").tap()
    }

    /// The main screens again with the trip that the first test saved, for a second appearance or text size.
    func test2Screens() throws {
        app.launch()
        XCUIDevice.shared.location = fix(east: 0, speed: 9)
        allowLocation()
        waitFor(element("current_speed"))
        Thread.sleep(forTimeInterval: 3)
        capture("speed")
        app.tabBars.buttons["Trips"].tap()
        let trip = element("saved_trip").firstMatch
        waitFor(trip)
        capture("trips-list")
        trip.tap()
        waitFor(element("open_replay"), 20)
        Thread.sleep(forTimeInterval: 3)
        capture("saved-trip")
        element("open_replay").tap()
        waitFor(element("replay_play"), 30)
        Thread.sleep(forTimeInterval: 2)
        capture("replay")
    }
}
