import Foundation
import AppKit
import JavaScriptCore
import ImageIO
import UniformTypeIdentifiers
import AurisShared

private func expect(_ value: @autoclosure () -> Bool, _ message: String) {
    guard value() else { fatalError(message) }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.lock(); count += 1; lock.unlock() }
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}

private final class Callback: EngineCallback, @unchecked Sendable {
    let counter: Counter
    init(_ counter: Counter) { self.counter = counter }
    deinit { counter.increment() }
    func onStateChanged(state: PlaybackState) {}
    func onTrackEnded(reason: TrackEndReason, source: String) {}
    func onError(message: String) {}
    func onHandoff(info: TrackInfoRec) {}
    func onDurationChanged(seconds: Double) {}
}

@main struct MemoryVerification {
    @MainActor static func main() async throws {
        setbuf(stdout, nil)
        for _ in 0..<40 {
            weak var released: JSContext?
            autoreleasepool {
                let engine = ModuleJsEngine.Engine(moduleId: "memory", script: "module.exports.searchTracks = function(q) { return [{title:q}]; };", fetchBase: "https://example.invalid/")
                released = engine.context
                expect(engine.context.evaluateScript("__bch_mod.searchTracks('fixture')[0].title")?.toString() == "fixture", "module exports changed")
                engine.context.evaluateScript("var timerFired = false; setTimeout(function() { timerFired = true; }, 0);")
                expect(engine.context.evaluateScript("timerFired")?.toBool() == true, "timer binding stopped executing callbacks")
            }
            expect(released == nil, "module interpreter leaked after its engine was released")
        }
        print("PASS 40 interpreter lifecycles, module exports, and timer execution")

        let paths = AutomixModelPolicy.paths(beat: "beat", vocal: "vocal", playbackStarted: false, enabled: true, efficient: false)
        expect(paths.beat.isEmpty && paths.vocal.isEmpty, "browsing loaded models")
        let disabled = AutomixModelPolicy.paths(beat: "beat", vocal: "vocal", playbackStarted: true, enabled: false, efficient: false)
        expect(disabled.beat.isEmpty && disabled.vocal.isEmpty, "disabled Automix retained models")
        let efficient = AutomixModelPolicy.paths(beat: "beat", vocal: "vocal", playbackStarted: true, enabled: true, efficient: true)
        expect(efficient.beat == "beat" && efficient.vocal.isEmpty, "Efficient mode loaded vocals")
        let enabled = AutomixModelPolicy.paths(beat: "beat", vocal: "vocal", playbackStarted: true, enabled: true, efficient: false)
        expect(enabled.beat == "beat" && enabled.vocal == "vocal", "normal Automix lost models")
        print("PASS model lifetime policy for browsing, disabled, Efficient, and normal playback")

        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("bitchord-memory-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder); unsetenv("BITCHORD_LIBRARY_PATH") }
        let fixture = ProcessInfo.processInfo.environment["MEMORY_FIXTURE"]!
        let cover = makeCover(red: 1, blue: 0, alpha: 0.4)
        for (index, title) in ["zebra", "Alpha", "MiXeD"].enumerated() {
            let path = folder.appendingPathComponent("\(index).m4a").path
            try FileManager.default.copyItem(atPath: fixture, toPath: path)
            expect(writeTrackTags(path: path, title: title, artist: "Artist", album: "Album", artwork: index == 2 ? Data() : cover), "fixture tag write failed")
        }
        setenv("BITCHORD_LIBRARY_PATH", folder.path, 1)
        let library = LocalLibrary()
        library.restore()
        await library.waitForScan()
        expect(library.tracks.count == 3 && library.tracks.allSatisfy { $0.artwork == nil && $0.artworkURL != nil }, "scan retained embedded cover bytes")
        expect(library.visibleTracks.map(\.title) == ["Alpha", "MiXeD", "zebra"], "shared title order changed")
        expect(library.visibleTracks.map(\.title) == ["Alpha", "MiXeD", "zebra"], "cached order changed")
        library.query = "mixed"
        expect(library.visibleTracks.map(\.title) == ["MiXeD"], "query did not invalidate visible rows")
        library.query = ""; library.sort = .titleDescending
        expect(library.visibleTracks.map(\.title) == ["zebra", "MiXeD", "Alpha"], "sort did not invalidate visible rows")
        let reference = library.tracks[0].artworkURL!
        let bytes = await LocalArtwork.shared.data(for: reference)
        expect(bytes != nil, "lazy artwork missing")
        let source = CGImageSourceCreateWithData(bytes! as CFData, nil)!
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
        expect(max(image.width, image.height) == 1024, "embedded artwork was not downsampled")
        expect(image.alphaInfo == .premultipliedLast || image.alphaInfo == .premultipliedFirst || image.alphaInfo == .last || image.alphaInfo == .first, "transparent cover lost its alpha channel")
        let repeated = await LocalArtwork.shared.data(for: reference)
        expect(repeated == bytes, "warm artwork cache changed the cover")
        let rowBytes = await LocalArtwork.shared.data(for: LocalArtwork.sizedReference(reference, pixels: 128))
        let rowSource = CGImageSourceCreateWithData(rowBytes! as CFData, nil)!
        let rowImage = CGImageSourceCreateImageAtIndex(rowSource, 0, nil)!
        expect(max(rowImage.width, rowImage.height) == 128, "row retained a full-size decoded cover")
        let noCover = await LocalArtwork.shared.data(for: library.tracks[2].artworkURL!)
        expect(noCover == nil, "untagged artwork should use a placeholder")
        let path = library.tracks[0].path
        // Replace the file itself, as an external library editor can do.
        try FileManager.default.removeItem(atPath: path)
        try FileManager.default.copyItem(atPath: fixture, toPath: path)
        expect(writeTrackTags(path: path, title: "Updated", artist: "Artist", album: "Album", artwork: makeCover(red: 0, blue: 1)), "replacement tag write failed")
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(2)], ofItemAtPath: path)
        let updated = await LocalArtwork.shared.data(for: reference)
        expect(updated != bytes && updated != nil, "modified file reused stale embedded artwork")
        library.restore(); await library.waitForScan()
        expect(library.visibleTracks.map(\.title) == ["Updated", "MiXeD", "Alpha"], "rescan did not invalidate cached library rows")
        print("PASS lazy artwork, bounded image dimensions, missing/replaced covers, shared sorting, filtering, and rescan invalidation")

        if ProcessInfo.processInfo.environment["MEMORY_AUDIO"] == "1" {
            let counter = Counter()
            for round in 1...3 {
                var engine: PlayerEngine? = PlayerEngine()
                engine!.registerCallback(callback: Callback(counter))
                try engine!.start(rate: nil, channels: nil)
                engine = nil
                for _ in 0..<100 where counter.value < round { try await Task.sleep(for: .milliseconds(20)) }
                expect(counter.value == round, "started native engine retained its callback after teardown")
            }
            print("PASS three silent CoreAudio engine lifecycles release their callbacks")
        } else { print("SKIP CoreAudio lifecycle (set MEMORY_AUDIO=1 on a Mac with an output device)") }
    }

    private static func makeCover(red: CGFloat, blue: CGFloat, alpha: CGFloat = 1) -> Data {
        let context = CGContext(data: nil, width: 2048, height: 1536, bitsPerComponent: 8, bytesPerRow: 2048 * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.setFillColor(CGColor(red: red, green: 0.1, blue: blue, alpha: alpha))
        context.fill(CGRect(x: 0, y: 0, width: 2048, height: 1536))
        let data = NSMutableData()
        let target = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(target, context.makeImage()!, nil)
        expect(CGImageDestinationFinalize(target), "cover encode failed")
        return data as Data
    }
}
