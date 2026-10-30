import Foundation
import AurisShared

/// The Flow mix as the app sees it: the shared engine's snapshot plus the
/// tuner state that produced it.
///
/// The engine, the store and the radio call live in the shared Kotlin module
/// (`data/flow/`); this is the Swift-facing shape of what they return.
struct FlowSnapshot: Decodable, Sendable {
    struct Song: Decodable, Identifiable, Hashable, Sendable {
        let videoId: String
        let title: String
        let artist: String
        let thumbnailUrl: String?
        let durationText: String?
        let artistId: String?
        let albumId: String?
        let albumName: String?
        let isVideo: Bool?
        let setVideoId: String?

        var id: String { videoId }
    }

    let songs: [Song]
    let mood: String
    let discovery: Float
    let memory: Float
    let excludedGenres: [String]
    let unlocked: Bool
    let favTrackCount: Int
    let favArtistCount: Int
    let neededMore: Int
    let radioName: String
    let browseId: String

    static let empty = FlowSnapshot(
        songs: [], mood: "FLOW", discovery: 0.35, memory: 0.5,
        excludedGenres: [], unlocked: false, favTrackCount: 0,
        favArtistCount: 0, neededMore: FlowRulesUnlock.favTotal,
        radioName: "Flow", browseId: "auris:flow"
    )
}

/// The unlock rule, mirrored from the shared module so the UI can show the
/// "N more favorites to unlock" hint without a round trip.
enum FlowRulesUnlock {
    static let favTotal: Int = 16
    static let artistCount: Int = 10
}

/// The engine's sizes, mirrored from the shared module's `FlowEngine`.
enum FlowLimits {
    static let initialTracks: Int32 = 50
    static let topUpTracks: Int32 = 10

    /// The current mix, in the JSON the shared bridge reads back.
    static func encodeSongs(_ songs: [FlowSnapshot.Song]) -> String {
        let rows = songs.map { song in
            FlowSongPayload(
                videoId: song.videoId,
                title: song.title,
                artist: song.artist,
                thumbnailUrl: song.thumbnailUrl,
                durationText: song.durationText,
                albumName: song.albumName,
                artistId: song.artistId,
                albumId: song.albumId,
                setVideoId: song.setVideoId,
                isVideo: song.isVideo ?? false
            )
        }
        guard let data = try? JSONEncoder().encode(rows),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }
}

/// The wire shape the shared module's `Song` decodes from.
private struct FlowSongPayload: Encodable {
    let videoId: String
    let title: String
    let artist: String
    let thumbnailUrl: String?
    let durationText: String?
    let albumName: String?
    let artistId: String?
    let albumId: String?
    let setVideoId: String?
    let isVideo: Bool
}

/// The moods the tuner offers, in the shared module's order.
enum FlowMoodOption: String, CaseIterable, Identifiable {
    case flow = "FLOW"
    case love = "LOVE"
    case workout = "WORKOUT"
    case chill = "CHILL"
    case sad = "SAD"
    case focus = "FOCUS"
    case party = "PARTY"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .flow: return "Flow"
        case .love: return "Love"
        case .workout: return "Workout"
        case .chill: return "Chill"
        case .sad: return "Sad"
        case .focus: return "Focus"
        case .party: return "Party"
        }
    }

    var symbol: String {
        switch self {
        case .flow: return "infinity"
        case .love: return "heart.fill"
        case .workout: return "figure.run"
        case .chill: return "leaf.fill"
        case .sad: return "cloud.rain.fill"
        case .focus: return "brain.head.profile"
        case .party: return "party.popper.fill"
        }
    }
}

/// The genre toggles the tuner offers, mirroring the shared module's list.
enum FlowGenreOption: String, CaseIterable, Identifiable {
    case pop = "Pop"
    case hipHop = "Hip-Hop"
    case rock = "Rock"
    case electronic = "Electronic"
    case dance = "Dance"
    case rnb = "R&B"
    case latin = "Latin"
    case kPop = "K-Pop"
    case country = "Country"
    case jazz = "Jazz"
    case classical = "Classical"
    case metal = "Metal"
    case indie = "Indie"
    case folk = "Folk"
    case soul = "Soul"
    case reggae = "Reggae"
    case punk = "Punk"
    case blues = "Blues"
    case soundtrack = "Soundtrack"
    case ambient = "Ambient"

    var id: String { rawValue }
    var label: String { rawValue }
}

/// Loads Flow mixes through the shared module's bridge.
@MainActor
@Observable
final class FlowModel {
    private(set) var snapshot: FlowSnapshot = .empty
    private(set) var loading = false
    private(set) var growing = false
    private(set) var error: String?

    /// The tuner state while the reader is editing it. Applied to the mix
    /// when they commit, so a slider drag does not rebuild the mix per frame.
    var mood: FlowMoodOption = .flow
    var discovery: Double = 0.35
    var memory: Double = 0.5
    var excludedGenres: Set<String> = []

    var songs: [FlowSnapshot.Song] { snapshot.songs }

    private final class Callback: FlowBridgeFlowCallback {
        let onDone: (String?, String?) -> Void
        init(onDone: @escaping (String?, String?) -> Void) { self.onDone = onDone }
        func onResult(json: String?, message: String?) { onDone(json, message) }
    }

    private func decode(_ json: String?) -> FlowSnapshot? {
        guard let json, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(FlowSnapshot.self, from: data)
    }

    /// Read the stored mix and tuner state without touching the network.
    func loadStored() {
        FlowBridge.shared.status(callback: Callback { [weak self] json, message in
            guard let self else { return }
            if let message {
                self.error = message
                return
            }
            guard let snapshot = self.decode(json) else { return }
            self.snapshot = snapshot
            self.mood = FlowMoodOption(rawValue: snapshot.mood) ?? .flow
            self.discovery = Double(snapshot.discovery)
            self.memory = Double(snapshot.memory)
            self.excludedGenres = Set(snapshot.excludedGenres)
        })
    }

    /// Build (or rebuild) the mix from favorites, history and recommendations.
    func build() {
        guard !loading else { return }
        loading = true
        error = nil
        FlowBridge.shared.build(limit: FlowLimits.initialTracks) { [weak self] json, message in
            guard let self else { return }
            self.loading = false
            if let message {
                self.error = message
                return
            }
            guard let snapshot = self.decode(json) else { return }
            self.snapshot = snapshot
            self.mood = FlowMoodOption(rawValue: snapshot.mood) ?? .flow
            self.discovery = Double(snapshot.discovery)
            self.memory = Double(snapshot.memory)
            self.excludedGenres = Set(snapshot.excludedGenres)
        }
    }

    /// Apply the tuner and rebuild in one step.
    func applyTuner() {
        guard !loading else { return }
        loading = true
        error = nil
        FlowBridge.shared.applyConfig(
            mood: mood.rawValue,
            discovery: Float(discovery),
            memory: Float(memory),
            excludedGenres: Array(excludedGenres)
        ) { [weak self] json, message in
            guard let self else { return }
            self.loading = false
            if let message {
                self.error = message
                return
            }
            if let snapshot = self.decode(json) { self.snapshot = snapshot }
        }
    }

    /// Append more recommendations to the current mix.
    func grow() {
        guard !growing, !snapshot.songs.isEmpty else { return }
        growing = true
        let currentJson = FlowLimits.encodeSongs(snapshot.songs)
        FlowBridge.shared.grow(currentJson: currentJson, limit: FlowLimits.topUpTracks) { [weak self] json, message in
            guard let self else { return }
            self.growing = false
            if let message {
                self.error = message
                return
            }
            guard let json, let data = json.data(using: .utf8),
                  let songs = try? JSONDecoder().decode([FlowSnapshot.Song].self, from: data) else { return }
            self.snapshot = FlowSnapshot(
                songs: songs,
                mood: self.snapshot.mood,
                discovery: self.snapshot.discovery,
                memory: self.snapshot.memory,
                excludedGenres: self.snapshot.excludedGenres,
                unlocked: self.snapshot.unlocked,
                favTrackCount: self.snapshot.favTrackCount,
                favArtistCount: self.snapshot.favArtistCount,
                neededMore: self.snapshot.neededMore,
                radioName: self.snapshot.radioName,
                browseId: self.snapshot.browseId
            )
        }
    }

    func ban(_ song: FlowSnapshot.Song) {
        FlowBridge.shared.ban(videoId: song.videoId)
        snapshot = FlowSnapshot(
            songs: snapshot.songs.filter { $0.videoId != song.videoId },
            mood: snapshot.mood, discovery: snapshot.discovery, memory: snapshot.memory,
            excludedGenres: snapshot.excludedGenres, unlocked: snapshot.unlocked,
            favTrackCount: snapshot.favTrackCount, favArtistCount: snapshot.favArtistCount,
            neededMore: snapshot.neededMore, radioName: snapshot.radioName, browseId: snapshot.browseId
        )
    }

    func unban(_ song: FlowSnapshot.Song) {
        FlowBridge.shared.unban(videoId: song.videoId)
    }

    /// A quick skip counts against the track; a full listen forgives one.
    func recordSkip(_ song: FlowSnapshot.Song, playedMs: Int64, durationMs: Int64) {
        FlowBridge.shared.recordSkip(videoId: song.videoId, playedMs: playedMs, durationMs: durationMs)
    }

    func queueEntries() -> [QueueEntry] {
        snapshot.songs.map { song in
            QueueEntry(
                id: song.videoId,
                title: song.title,
                artist: song.artist,
                source: "yt:\(song.videoId)",
                thumbnailUrl: song.thumbnailUrl,
                durationText: song.durationText,
                albumName: song.albumName,
                artworkData: nil,
                isLocal: false
            )
        }
    }
}
