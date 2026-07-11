enum PhotoAssetMerge {
    static func preservingCameraOrder(
        existing: [PhotoAsset],
        incoming: [PhotoAsset],
        resetTraversal: Bool
    ) -> [PhotoAsset] {
        let mergedAssets = resetTraversal ? incoming : (existing + incoming)
        var seenRemoteIdentifiers = Set<String>()
        return mergedAssets.filter { seenRemoteIdentifiers.insert($0.remoteIdentifier).inserted }
    }
}
