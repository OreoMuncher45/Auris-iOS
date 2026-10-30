import Foundation

private func expect(_ value: @autoclosure () -> Bool, _ message: String) {
    guard value() else { fatalError(message) }
}
private final class State: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String: Int] = [:]
    func add(_ key: String, _ delta: Int = 1) { lock.lock(); storage[key, default: 0] += delta; lock.unlock() }
    func maximum(_ key: String, _ value: Int) { lock.lock(); storage[key] = max(storage[key] ?? 0, value); lock.unlock() }
    func get(_ key: String) -> Int { lock.lock(); defer { lock.unlock() }; return storage[key] ?? 0 }
}
private actor Gate {
    private var open = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if open { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func release() { open = true; waiters.forEach { $0.resume() }; waiters.removeAll() }
}
private func until(_ check: () async -> Bool) async throws {
    let deadline = Date().addingTimeInterval(3)
    while !(await check()) {
        guard Date() < deadline else { fatalError("timed out waiting for test state") }
        try await Task.sleep(for: .milliseconds(5))
    }
}

@main struct ComputeVideoVerification {
    static func main() async throws {
        setbuf(stdout, nil)
        try await queueChecks()
        try await rangeChecks()
        try await indexChecks()
        try overlayChecks()
        try await downloadChecks()
    }

    static func queueChecks() async throws {
        let queue = MusicAnalysisQueue()
        let gate = Gate(); let counts = State()
        let one = Task {
            await queue.perform(key: "same") {
                counts.add("executions"); await gate.wait(); return true
            }
        }
        try await until { counts.get("executions") == 1 }
        let two = Task { await queue.perform(key: "same") { fatalError("duplicate accelerator session") } }
        try await until { await queue.pendingCount == 2 }
        one.cancel()
        try await until { await queue.pendingCount == 1 }
        await gate.release()
        let a = await one.value; let b = await two.value
        expect(!a && b && counts.get("executions") == 1, "one cancelled consumer cancelled shared analysis")

        let firstGate = Gate()
        let first = Task {
            await queue.perform(key: "first") {
                counts.add("active"); counts.maximum("peak", counts.get("active"))
                await firstGate.wait(); counts.add("active", -1); return true
            }
        }
        try await until { counts.get("active") == 1 }
        let next = Task {
            await queue.perform(key: "next") {
                counts.add("active"); counts.maximum("peak", counts.get("active"))
                counts.add("active", -1); return true
            }
        }
        try await until { await queue.pendingCount == 2 }
        expect(counts.get("active") == 1, "accelerator work overlapped")
        await firstGate.release()
        let firstResult = await first.value; let nextResult = await next.value
        expect(firstResult && nextResult && counts.get("peak") == 1, "analysis did not run serially")

        let cancelled = Task {
            await queue.perform(key: "background") {
                counts.add("backgroundStarted")
                do { try await Task.sleep(for: .seconds(30)); return true }
                catch { counts.add("backgroundCancelled"); return false }
            }
        }
        try await until { counts.get("backgroundStarted") == 1 }
        await queue.cancelAll()
        let result = await cancelled.value
        expect(!result && counts.get("backgroundCancelled") == 1, "backgrounding failed to cancel running analysis")
        let fresh = await queue.perform(key: "foreground") { true }
        expect(fresh, "cancelled analysis poisoned later foreground work")
        print("PASS accelerator work coalesces, runs serially, respects consumer/background cancellation, and resumes")
    }

    static func rangeChecks() async throws {
        let gate = Gate(); let state = State()
        let task = Task {
            var output = Data()
            try await VideoRangeDelivery.stream(from: 0, to: 12, chunkBytes: 1, parallel: 4,
                fetch: { start, _ in
                    state.add("started")
                    if start == 0 { await gate.wait() } else { state.add("finished") }
                    return Data([UInt8(start)])
                }, receive: { output.append($0) })
            return output
        }
        try await until { state.get("finished") == 3 }
        expect(state.get("started") == 4, "slow first range grew an unbounded pending window")
        await gate.release()
        let output = try await task.value
        expect(output == Data(0..<12), "out-of-order network ranges corrupted video bytes")

        let lastGate = Gate(); let watchdogState = State()
        let watchdog = Task {
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            watchdogState.add("timeout"); await lastGate.release()
        }
        try await VideoRangeDelivery.stream(from: 0, to: 4, chunkBytes: 1, parallel: 4,
            fetch: { start, _ in
                if start == 3 { await lastGate.wait() }
                return Data([UInt8(start)])
            }, receive: { bytes in
                if bytes.first == 0 { Task { await lastGate.release() } }
            })
        watchdog.cancel()
        expect(watchdogState.get("timeout") == 0, "first byte waited for the slowest range in the batch")
        do {
            try await VideoRangeDelivery.stream(from: 0, to: 10, chunkBytes: 10, parallel: 1,
                fetch: { _, _ in Data([0]) }, receive: { _ in })
            fatalError("short HTTP range was silently accepted")
        } catch is URLError {}
        print("PASS range delivery starts before slow later ranges, preserves byte order, bounds pending work, and rejects short responses")
    }

    static func indexChecks() async throws {
        let head = makeIndex(); let state = State()
        let cache = VideoIndexCache(fetch: { request in
            state.add("requests")
            try await Task.sleep(for: .milliseconds(120))
            return (head, HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil, headerFields: [:])!)
        })
        let source = URL(string: "https://example.invalid/video")!
        let began = Date()
        async let one = cache.index(source: source, headers: ["Authorization": "test-a"])
        async let two = cache.index(source: source, headers: ["Authorization": "test-a"])
        let (a,b) = try await (one,two)
        let cold = Date().timeIntervalSince(began)
        expect(a == b && a.segments.count == 2 && state.get("requests") == 1, "startup probes were not shared")
        let warmStarted = Date()
        let warm = try await cache.index(source: source, headers: ["Authorization": "test-a"])
        let warmSeconds = Date().timeIntervalSince(warmStarted)
        expect(warm == a && state.get("requests") == 1, "prepared segment index still needed a network probe")
        _ = try await cache.index(source: source, headers: ["Authorization": "test-b"])
        expect(state.get("requests") == 2, "startup cache crossed credential boundaries")
        for i in 0..<4 { _ = try await cache.index(source: source.appendingPathComponent("\(i)"), headers: [:]) }
        _ = try await cache.index(source: source, headers: ["Authorization": "test-a"])
        expect(state.get("requests") == 7, "startup cache failed to evict old entries")
        let malformed = VideoIndexCache(fetch: { request in
            (Data([0,1,2]), HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil, headerFields: [:])!)
        })
        do { _ = try await malformed.index(source: source, headers: [:]); fatalError("invalid MP4 index accepted") }
        catch is URLError {}
        let complete = VideoIndexCache(fetch: { request in
            (head, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: [:])!)
        })
        let completeIndex = try await complete.index(source: source, headers: [:])
        expect(completeIndex == a, "small full-body MP4 fallback stopped parsing its valid index")
        print(String(format: "PASS shared startup index, credential isolation, eviction, malformed-index fallback; fixture cold %.1f ms, warm %.3f ms", cold * 1000, warmSeconds * 1000))
    }

    static func overlayChecks() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("AurisOverlayCheck-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let audio = directory.appendingPathComponent("track.audio")
        try Data([1,2,3]).write(to: audio)
        let record = AutomixAnalysisOverlayRec(bpm: 120, beatConfidence: 0.9, beatInterval: 0.5,
            beats: [0,0.5], downbeats: [0], key: "C major", keyConfidence: 0.8, phraseBoundaries: [0],
            vocalProbability: nil, contentEnd: 10, audibleStart: 0, outroStart: 8, mixInTime: 0, source: "music_understanding")
        let signature = AutomixOverlayCache.fingerprint(forFilePath: audio.path)!
        AutomixOverlayCache.store(record, forFilePath: audio.path, expected: signature, directory: directory)
        expect(AutomixOverlayCache.load(forFilePath: audio.path, directory: directory)?.bpm == 120, "valid accelerator analysis cache did not load")
        try Data([1,2,3,4]).write(to: audio)
        expect(AutomixOverlayCache.load(forFilePath: audio.path, directory: directory) == nil, "changed recording reused stale analysis")
        AutomixOverlayCache.store(record, forFilePath: audio.path, expected: signature, directory: directory)
        expect(AutomixOverlayCache.load(forFilePath: audio.path, directory: directory) == nil, "file changed during analysis but result was cached")
        try Data().write(to: URL(fileURLWithPath: audio.path + ".grow"))
        expect(AutomixOverlayCache.fingerprint(forFilePath: audio.path) == nil, "incomplete recording allowed persistent accelerator analysis")
        try Data().write(to: URL(fileURLWithPath: audio.path + ".complete"))
        AutomixOverlayCache.store(record, forFilePath: audio.path, directory: directory)
        expect(AutomixOverlayCache.load(forFilePath: audio.path, directory: directory)?.bpm == 120, "completed recording was never eligible for analysis")
        print("PASS accelerator cache verifies file identity, refuses changed/partial inputs, and accepts completed files")
    }

    static func downloadChecks() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("AurisVideoDownloadCheck-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = URL(string: "https://example.invalid/audio")!
        let chunk = 1 << 20
        let input = (0..<4).reduce(into: Data()) { $0.append(Data(repeating: UInt8($1), count: chunk)) } + Data([4,4,4])
        let counts = State()
        let destination = directory.appendingPathComponent("audio")
        try await VideoAudioDownloader.download(source: url, headers: ["X-Test": "fixture"], to: destination, fetch: { request in
            counts.add("active"); counts.maximum("peak", counts.get("active"))
            defer { counts.add("active", -1) }
            expect(request.value(forHTTPHeaderField: "X-Test") == "fixture", "alignment download lost provider headers")
            let pieces = request.value(forHTTPHeaderField: "Range")!.dropFirst(6).split(separator: "-")
            let first = Int(pieces[0])!; let last = min(Int(pieces[1])!, input.count - 1)
            try await Task.sleep(for: .milliseconds(first == chunk ? 30 : 5))
            return (input.subdata(in: first..<last+1), HTTPURLResponse(url: url, statusCode: 206, httpVersion: nil,
                headerFields: ["Content-Range": "bytes \(first)-\(last)/\(input.count)"])!)
        })
        let written = try Data(contentsOf: destination)
        expect(written == input, "parallel alignment download corrupted recording bytes")
        expect(counts.get("peak") == 2, "alignment download did not use its bounded two-range window")
        let full = directory.appendingPathComponent("full")
        try await VideoAudioDownloader.download(source: url, headers: [:], to: full, fetch: { _ in
            (input, HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: [:])!)
        })
        let fullBytes = try Data(contentsOf: full)
        expect(fullBytes == input, "full-body HTTP fallback changed")
        let bad = directory.appendingPathComponent("bad")
        do {
            try await VideoAudioDownloader.download(source: url, headers: [:], to: bad, fetch: { request in
                (Data([1]), HTTPURLResponse(url: url, statusCode: 206, httpVersion: nil, headerFields: ["Content-Range": "bytes 0-0/100"])!)
            })
            fatalError("truncated recording was published")
        } catch is URLError {}
        expect(!FileManager.default.fileExists(atPath: bad.path) && !FileManager.default.fileExists(atPath: bad.path + ".part"), "failed alignment download left a usable partial file")
        print("PASS bounded parallel alignment downloads, ordered bytes, provider headers, full-body fallback, and failed-file cleanup")
    }

    static func makeIndex() -> Data {
        func u32(_ value: UInt32) -> Data { var value = value.bigEndian; return withUnsafeBytes(of: &value) { Data($0) } }
        func box(_ name: String, _ body: Data) -> Data { u32(UInt32(body.count + 8)) + Data(name.utf8) + body }
        var body = u32(0) + u32(1) + u32(1000) + u32(0) + u32(0)
        body.append(Data([0,0,0,2]))
        body.append(u32(1000) + u32(1000) + u32(0x90000000))
        body.append(u32(1500) + u32(1000) + u32(0x90000000))
        return box("ftyp", Data([0,0,0,0])) + box("moov", Data([0,0,0,0])) + box("sidx", body)
    }
}
