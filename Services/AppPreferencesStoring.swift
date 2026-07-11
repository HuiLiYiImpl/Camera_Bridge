protocol AppPreferencesStoring: AnyObject {
    func loadConnectionConfig() -> CameraConnectionConfig
    func saveConnectionConfig(_ config: CameraConnectionConfig)
}

extension AppPreferencesStore: AppPreferencesStoring {}
