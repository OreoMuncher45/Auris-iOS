import Foundation

enum AutomixModelPolicy {
    static func paths(beat: String, vocal: String, playbackStarted: Bool, enabled: Bool, efficient: Bool) -> (beat: String, vocal: String) {
        guard playbackStarted && enabled else { return ("", "") }
        return (beat, efficient ? "" : vocal)
    }
}

/// Serialize native model replacement off-main so rapid preference changes
/// finish in request order and never interleave two graph configurations.
actor AutomixModelLoader {
    static let shared = AutomixModelLoader()
    func configure(beat: String, vocal: String) -> Bool {
        configureAnalyzer(beatModelPath: beat, vocalModelPath: vocal)
    }
}
