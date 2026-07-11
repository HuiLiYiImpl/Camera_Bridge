import Foundation

final class AppPreferencesStore {
    private let userDefaults: UserDefaults
    private let configKey = "cameraConnectionConfig"

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    func loadConnectionConfig() -> CameraConnectionConfig {
        guard
            let data = userDefaults.data(forKey: configKey),
            let config = try? JSONDecoder().decode(CameraConnectionConfig.self, from: data)
        else {
            return .default
        }

        return config
    }

    func saveConnectionConfig(_ config: CameraConnectionConfig) {
        guard let data = try? JSONEncoder().encode(config) else { return }
        userDefaults.set(data, forKey: configKey)
    }
}
