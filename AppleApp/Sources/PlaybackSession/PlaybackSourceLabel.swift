import Foundation
import AurisShared

/// The queue identifies a recording; the resolved origin identifies the stream actually used.
enum PlaybackSourceLabel {
    static func name(origin: String?, source: String, isLocal: Bool, sourceConfigId: String? = nil) -> String? {
        // An add-on's own name, when the stream came from one. "Alternative
        // source" and "Add-on" are placeholders for a name the app already has,
        // and a listener asking "what is this playing from?" deserves it.
        let addon = resolvedAddonName(configId: sourceConfigId, source: source)
        switch origin {
        case "substitute": return addon ?? "Alternative source"
        case "youtube": return "YouTube"
        case "cache": return "Cached audio"
        case "local": return "Local files"
        default:
            if isLocal { return "Local files" }
            if source.hasPrefix("yt:") { return "YouTube" }
            if source.hasPrefix("src:") { return addon ?? "Add-on" }
            if source.hasPrefix("saavn:") { return "JioSaavn" }
            return nil
        }
    }

    /// The configured source's display name, from its id.
    ///
    /// The id comes from the resolved stream when the resolver reported one, and
    /// from the queue key otherwise: a `src:<configId>::<trackId>` row knows its
    /// own source without asking the resolver again.
    private static func resolvedAddonName(configId: String?, source: String) -> String? {
        let id = configId ?? configIdInKey(source)
        guard let id, !id.isEmpty else { return nil }
        let name = SourceResolverBridge.shared.displayName(configId: id)
        return (name?.isEmpty == false) ? name : nil
    }

    private static func configIdInKey(_ key: String) -> String? {
        guard key.hasPrefix("src:") else { return nil }
        let rest = key.dropFirst(4)
        guard let separator = rest.range(of: "::") else { return nil }
        let id = String(rest[..<separator.lowerBound])
        return id.isEmpty ? nil : id
    }
}
