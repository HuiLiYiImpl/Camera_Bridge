import Foundation

actor DownloadStore {
    private static let manifestFileName = "downloads-manifest.json"
    private static let queueManifestFileName = "download-jobs.json"

    private let fileManager: FileManager
    private let rootDirectory: URL

    init(fileManager: FileManager = .default, rootDirectory: URL? = nil) {
        self.fileManager = fileManager
        if let rootDirectory {
            self.rootDirectory = rootDirectory
        } else {
            let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
            self.rootDirectory = documents.appendingPathComponent("Nikon Connect", isDirectory: true)
        }
    }

    func downloadsDirectoryURL() throws -> URL {
        let url = rootDirectory.appendingPathComponent("Downloads", isDirectory: true)
        try ensureDirectoryExists(at: url)
        return url
    }

    func listRecords() throws -> [DownloadRecord] {
        let manifest = try loadManifest()
        return manifest.sorted { $0.completedAt > $1.completedAt }
    }

    func store(data: Data, from asset: PhotoAsset) throws -> DownloadRecord {
        let directory = try downloadsDirectoryURL()
        let destination = uniqueDestinationURL(in: directory, fileName: asset.fileName)

        do {
            try data.write(to: destination, options: .atomic)
        } catch {
            throw CameraAppError.fileSystemFailure(error.localizedDescription)
        }

        let record = DownloadRecord(
            sourceAssetIdentifier: asset.remoteIdentifier,
            fileName: destination.lastPathComponent,
            savedURL: destination,
            byteSize: Int64(data.count),
            exportedToPhotoLibrary: false
        )

        var manifest = try loadManifest()
        manifest.append(record)
        try saveManifest(manifest)
        return record
    }

    func loadDownloadJobs() throws -> DownloadQueueState {
        try loadQueueState()
    }

    func saveDownloadQueueState(_ state: DownloadQueueState) throws {
        try saveQueueState(state)
    }

    func upsertDownloadJob(
        _ job: DownloadJob,
        queueStatus: DownloadQueueStatus,
        activeJobID: UUID?
    ) throws -> DownloadQueueState {
        var state = try loadQueueState()
        if let index = state.jobs.firstIndex(where: { $0.id == job.id }) {
            state.jobs[index] = job
        } else {
            state.jobs.append(job)
        }
        state.activeJobID = activeJobID
        state.status = queueStatus
        try saveQueueState(state)
        return state
    }

    func removeDownloadJobs(
        ids: [UUID],
        queueStatus: DownloadQueueStatus,
        activeJobID: UUID?
    ) throws -> DownloadQueueState {
        var state = try loadQueueState()
        state.jobs.removeAll { ids.contains($0.id) }
        state.activeJobID = activeJobID
        state.status = state.jobs.contains(where: { !$0.status.isTerminal }) ? queueStatus : .idle
        try saveQueueState(state)
        return state
    }

    func markInterruptedRunningJobs(reason: String?) throws -> DownloadQueueState {
        var state = try loadQueueState()
        let now = Date()
        var didInterruptRunningJob = false
        state.jobs = state.jobs.map { job in
            guard job.status == .running else {
                return job
            }

            var updatedJob = job
            updatedJob.status = .interrupted
            updatedJob.updatedAt = now
            updatedJob.errorMessage = reason
            didInterruptRunningJob = true
            return updatedJob
        }

        if didInterruptRunningJob {
            state.activeJobID = nil
            state.status = state.jobs.contains(where: { !$0.status.isTerminal }) ? .interrupted : .idle
        } else if !state.jobs.contains(where: { !$0.status.isTerminal }) {
            state.activeJobID = nil
            state.status = .idle
        }

        try saveQueueState(state)
        return state
    }

    func storeDownloadedFile(at sourceURL: URL, from asset: PhotoAsset) throws -> DownloadRecord {
        let directory = try downloadsDirectoryURL()
        let destination = uniqueDestinationURL(in: directory, fileName: asset.fileName)

        do {
            try fileManager.moveItem(at: sourceURL, to: destination)
        } catch {
            throw CameraAppError.fileSystemFailure("无法移动下载文件：\(error.localizedDescription)")
        }

        let byteSize: Int64
        do {
            let attributes = try fileManager.attributesOfItem(atPath: destination.path)
            byteSize = (attributes[.size] as? NSNumber)?.int64Value ?? Int64(asset.byteSize)
        } catch {
            throw CameraAppError.fileSystemFailure("无法读取下载文件大小：\(error.localizedDescription)")
        }

        let record = DownloadRecord(
            sourceAssetIdentifier: asset.remoteIdentifier,
            fileName: destination.lastPathComponent,
            savedURL: destination,
            byteSize: byteSize,
            exportedToPhotoLibrary: false
        )

        var manifest = try loadManifest()
        manifest.append(record)
        try saveManifest(manifest)
        return record
    }

    func markExported(recordID: UUID) throws -> DownloadRecord {
        var manifest = try loadManifest()
        guard let index = manifest.firstIndex(where: { $0.id == recordID }) else {
            throw CameraAppError.fileSystemFailure("找不到下载记录。")
        }

        manifest[index].exportedToPhotoLibrary = true
        try saveManifest(manifest)
        return manifest[index]
    }

    private func manifestURL() throws -> URL {
        try downloadsDirectoryURL().appendingPathComponent(Self.manifestFileName)
    }

    private func queueManifestURL() throws -> URL {
        try downloadsDirectoryURL().appendingPathComponent(Self.queueManifestFileName)
    }

    private func ensureDirectoryExists(at url: URL) throws {
        do {
            try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        } catch {
            throw CameraAppError.fileSystemFailure(error.localizedDescription)
        }
    }

    private func uniqueDestinationURL(in directory: URL, fileName: String) -> URL {
        let baseName = URL(fileURLWithPath: fileName).deletingPathExtension().lastPathComponent
        let ext = URL(fileURLWithPath: fileName).pathExtension
        var candidate = directory.appendingPathComponent(fileName)
        var index = 2

        while fileManager.fileExists(atPath: candidate.path) {
            let nextName = ext.isEmpty ? "\(baseName)-\(index)" : "\(baseName)-\(index).\(ext)"
            candidate = directory.appendingPathComponent(nextName)
            index += 1
        }

        return candidate
    }

    private func loadManifest() throws -> [DownloadRecord] {
        let url = try manifestURL()
        guard fileManager.fileExists(atPath: url.path) else { return [] }

        do {
            let data = try Data(contentsOf: url)
            let records = try JSONDecoder().decode([DownloadRecord].self, from: data)
            return records.filter { fileManager.fileExists(atPath: $0.savedURL.path) }
        } catch {
            throw CameraAppError.fileSystemFailure("无法读取下载清单：\(error.localizedDescription)")
        }
    }

    private func saveManifest(_ records: [DownloadRecord]) throws {
        let url = try manifestURL()

        do {
            let data = try JSONEncoder().encode(records)
            try data.write(to: url, options: .atomic)
        } catch {
            throw CameraAppError.fileSystemFailure("无法写入下载清单：\(error.localizedDescription)")
        }
    }

    private func loadQueueState() throws -> DownloadQueueState {
        let url = try queueManifestURL()
        guard fileManager.fileExists(atPath: url.path) else { return DownloadQueueState() }

        do {
            let data = try Data(contentsOf: url)
            return try JSONDecoder().decode(DownloadQueueState.self, from: data)
        } catch {
            throw CameraAppError.fileSystemFailure("无法读取下载队列：\(error.localizedDescription)")
        }
    }

    private func saveQueueState(_ state: DownloadQueueState) throws {
        let url = try queueManifestURL()

        do {
            let data = try JSONEncoder().encode(state)
            try data.write(to: url, options: .atomic)
        } catch {
            throw CameraAppError.fileSystemFailure("无法写入下载队列：\(error.localizedDescription)")
        }
    }
}
