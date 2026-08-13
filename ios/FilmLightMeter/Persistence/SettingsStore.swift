import Foundation

protocol SettingsStore {
    func load() -> AppSettings
    func save(_ settings: AppSettings) throws
}

struct UserDefaultsSettingsStore: SettingsStore {
    private struct Payload: Codable {
        let version: Int
        let settings: AppSettings
    }

    private let defaults: UserDefaults
    private let key = "film_light_meter_settings"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> AppSettings {
        guard let data = defaults.data(forKey: key),
              let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.version == 1 else {
            return AppSettings()
        }
        var settings = payload.settings
        settings.normalize()
        return settings
    }

    func save(_ settings: AppSettings) throws {
        var normalized = settings
        normalized.normalize()
        let data = try JSONEncoder().encode(Payload(version: 1, settings: normalized))
        defaults.set(data, forKey: key)
    }
}
