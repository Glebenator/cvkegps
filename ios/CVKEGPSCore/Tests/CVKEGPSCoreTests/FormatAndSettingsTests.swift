import Foundation
import Testing
@testable import CVKEGPSCore

/// The same cases as the Android DisplayFormatTest, and the stored settings.
struct DisplayFormatTests {
    func format(_ locale: String, _ units: Units = .metric) -> DisplayFormat {
        DisplayFormat(locale: Locale(identifier: locale), units: units, text: .english)
    }

    @Test func convertsSiUnitsAndUsesTheLocaleDecimalSeparator() {
        let german = format("de_DE")
        #expect(german.speed(10) == "36 km/h")
        #expect(german.speedNumber(3.5) == "12,6")
        #expect(german.speedNumber(300) == "1080")
        #expect(german.distance(999.4) == "999 m")
        #expect(german.distance(1_234) == "1,23 km")
        #expect(german.distance(1_234_500) == "1.234,5 km")
    }

    @Test func imperialAndNauticalUnitsConvertFromSiUnits() {
        let imperial = format("en_US", .forCountry("US"))
        #expect(imperial.speed(10) == "22.4 mph")
        #expect(imperial.distance(160.9) == "528 ft")
        #expect(imperial.distance(161) == "0.1 mi")
        #expect(imperial.distance(1_609_344) == "1,000 mi")
        #expect(imperial.altitude(8_849) == "29032 ft")
        #expect(imperial.altitudeNumber(8_849) == "29032")
        let nautical = format("en_US", Units(speed: .knots, distance: .nauticalMiles, altitude: .metres))
        #expect(nautical.speed(10) == "19.4 kn")
        #expect(nautical.distance(92.6) == "0.05 nmi")
        #expect(nautical.altitude(-12.4) == "-12 m")
    }

    @Test func regionsGetTheirRoadUnitsAndOtherRegionsGetMetricUnits() {
        #expect(Units.forCountry("us") == Units(speed: .milesPerHour, distance: .miles, altitude: .feet))
        #expect(Units.forCountry("GB") == Units(speed: .milesPerHour, distance: .miles, altitude: .metres))
        #expect(Units.forCountry("DE") == .metric)
        #expect(Units.forCountry("") == .metric)
    }

    @Test func durationShowsOnlyTheUsefulUnits() {
        let english = format("en_US")
        #expect(english.duration(-1) == "0 s")
        #expect(english.duration(59_999_999_999) == "59 s")
        #expect(english.duration(65_000_000_000) == "1 min 5 s")
        #expect(english.duration(7_380_000_000_000) == "2 h 3 min")
    }

    @Test func clockPadsMinutesAndSecondsOnlyWhereNeeded() {
        let english = format("en_US")
        #expect(english.clock(-1) == "0:00")
        #expect(english.clock(9_999_999_999) == "0:09")
        #expect(english.clock(724_000_000_000) == "12:04")
        #expect(english.clock(3_724_000_000_000) == "1:02:04")
    }
}

@MainActor
struct SettingsTests {
    func defaults() -> UserDefaults {
        let name = "settings-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func defaultsApplyUntilTheUserChoosesAndChoicesStay() {
        let storage = defaults()
        let store = SettingsStore(defaults: storage)
        #expect(store.values == SettingsValues())
        #expect(store.values.units(country: "US") == .forCountry("US"))
        store.set(\.speedUnit, .knots)
        store.set(\.mapLayer, .satellite)
        store.set(\.networkFallback, false)
        store.set(\.screenOn, .duringTrip)
        let read = SettingsStore(defaults: storage).values
        #expect(read.speedUnit == .knots)
        #expect(read.units(country: "US") == Units(speed: .knots, distance: .miles, altitude: .feet))
        #expect(read.mapLayer == .satellite)
        #expect(!read.networkFallback)
        #expect(read.screenOn == .duringTrip)
        // Nil returns to the region's unit.
        store.set(\.speedUnit, nil)
        #expect(SettingsStore(defaults: storage).values.speedUnit == nil)
    }

    @Test func unknownStoredValuesReadAsTheDefault() {
        let storage = defaults()
        storage.set("warp", forKey: "speed_unit")
        storage.set("hologram", forKey: "map_layer")
        let values = SettingsStore(defaults: storage).values
        #expect(values.speedUnit == nil)
        #expect(values.mapLayer == .standard)
    }
}
