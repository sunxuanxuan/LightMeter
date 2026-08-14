import Foundation

struct FilmPreviewSettings: Codable, Equatable, Sendable {
    var selectedPresetID: String?
    var manualConfig: ManualCameraConfig

    init(
        selectedPresetID: String? = nil,
        manualConfig: ManualCameraConfig = ManualCameraConfig()
    ) {
        self.selectedPresetID = selectedPresetID
        self.manualConfig = manualConfig
    }
}

protocol FilmPreviewSettingsStore {
    func load() -> FilmPreviewSettings
    func save(_ settings: FilmPreviewSettings) throws
}

struct UserDefaultsFilmPreviewSettingsStore: FilmPreviewSettingsStore {
    private struct Payload: Codable {
        let version: Int
        let settings: FilmPreviewSettings
    }

    private let defaults: UserDefaults
    private let key = "film_preview_settings"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> FilmPreviewSettings {
        guard let data = defaults.data(forKey: key),
              let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.version == 1,
              payload.settings.manualConfig.isValid else {
            return FilmPreviewSettings()
        }
        return payload.settings
    }

    func save(_ settings: FilmPreviewSettings) throws {
        guard settings.manualConfig.isValid else {
            throw FilmPreviewSettingsError.invalidManualConfig
        }
        let data = try JSONEncoder().encode(
            Payload(version: 1, settings: settings)
        )
        defaults.set(data, forKey: key)
    }
}

private enum FilmPreviewSettingsError: LocalizedError {
    case invalidManualConfig

    var errorDescription: String? {
        "手动配置参数超出有效范围"
    }
}
