import Foundation
import AVFoundation
import QuartzCore

// Only logging and the unrelated app-wide owner are stubbed. The controller,
// resource loader, network delivery, segment parser and AVPlayer are production.
final class PlaybackDebugLog: @unchecked Sendable {
    static let shared = PlaybackDebugLog()
    func record(_ message: String) { print(message) }
}
@MainActor final class VideoPlayback {
    let controller = VideoSideController(clock: VideoClock(position: { 0 }, isPlaying: { false }))
}

@main struct VideoStartupVerification {
    @MainActor static func main() async throws {
        setbuf(stdout, nil)
        let source = CommandLine.arguments[1]
        let variant = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "current"
        func rendition(_ source: String) -> ResolvedVideoSide {
            ResolvedVideoSide(url: source, width: 1920, height: 1080, itag: 0, mimeType: "video/mp4",
                codecs: "avc1", bitrate: 2_000_000, headers: [:], availableVideoHeights: [1080], hasNParameter: false,
                clientName: "fixture", profileId: "fixture", musicVideoType: "MUSIC_VIDEO_TYPE_OMV")
        }
        let side = rendition(source)
        let cases = ["cold", "warm"] + (variant == "current" ? ["prepared-next"] : [])
        for temperature in cases {
            let picture = temperature == "prepared-next" ? rendition(source + "?prepared=1") : side
            if temperature == "prepared-next" {
                _ = try await VideoIndexCache.shared.index(source: URL(string: picture.url!)!, headers: [:])
            }
            let start = CACurrentMediaTime()
            let controller = VideoSideController(clock: VideoClock(position: { CACurrentMediaTime() - start }, isPlaying: { true }))
            controller.attach(picture)
            var observed: AVPlayerItem?
            var output: AVPlayerItemVideoOutput?
            var gotFrame = false
            while CACurrentMediaTime() - start < 12 {
                if controller.state == .failed { break }
                if let item = controller.player.currentItem, item !== observed {
                    observed = item
                    let fresh = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
                    item.add(fresh); output = fresh
                }
                if let output, let frame = output.copyPixelBuffer(forItemTime: controller.player.currentTime(), itemTimeForDisplay: nil) {
                    guard CVPixelBufferGetWidth(frame) == 1920 && CVPixelBufferGetHeight(frame) == 1080 else { fatalError("unexpected video frame") }
                    print(String(format: "FIRST_FRAME %@ %@ %.1f ms 1920x1080", variant, temperature, (CACurrentMediaTime() - start) * 1000))
                    gotFrame = true
                    break
                }
                try await Task.sleep(for: .milliseconds(10))
            }
            controller.detach()
            guard gotFrame else { fatalError("no decoded \(temperature) frame from local H.264 fixture") }
        }
        if variant == "current" {
            let controller = VideoSideController(clock: VideoClock(position: { 0 }, isPlaying: { false }))
            controller.attach(side)
            let deadline = CACurrentMediaTime() + 8
            while controller.state != .ready && CACurrentMediaTime() < deadline {
                try await Task.sleep(for: .milliseconds(10))
            }
            guard controller.state == .ready else { fatalError("paused fixture never became ready") }
            let map = VideoVersionAlignment.Map(segments: [.init(songStart: 0, songEnd: 3, offset: 1)], confident: true, minimumWindowSimilarity: 1)
            controller.attach(side, timeMap: map)
            while abs(controller.player.currentTime().seconds - 1) > 0.12 && CACurrentMediaTime() < deadline {
                try await Task.sleep(for: .milliseconds(10))
            }
            guard abs(controller.player.currentTime().seconds - 1) <= 0.12, controller.player.rate == 0 else {
                fatalError("paused picture did not adopt its new alignment without a timer or transport event")
            }
            controller.detach()
            print("PASS paused video adopts a new alignment without restarting playback")
        }
    }
}
