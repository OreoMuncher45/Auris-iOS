import Foundation
import CryptoKit

/// A small cache of MP4 segment indexes, not video frames or entire movies.
/// Shares startup probes between next-track preparation and the resource loader.
actor VideoIndexCache {
    static let shared = VideoIndexCache()
    typealias Fetch = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    private struct Entry { let index: VideoSegmentIndex; let saved: Date }
    private var entries: [String: Entry] = [:]
    private var flights: [String: Task<VideoSegmentIndex, Error>] = [:]
    private let fetch: Fetch

    init(fetch: @escaping Fetch = { try await URLSession.shared.data(for: $0) }) { self.fetch = fetch }

    func index(source: URL, headers: [String: String]) async throws -> VideoSegmentIndex {
        let identity = source.absoluteString + headers.sorted { $0.key < $1.key }
            .map { "\u{0}\($0.key):\($0.value)" }.joined()
        let key = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        if let hit = entries[key], Date().timeIntervalSince(hit.saved) < 120 { return hit.index }
        if let flight = flights[key] { return try await flight.value }
        guard flights.count < 4 else { throw URLError(.resourceUnavailable) }
        let fetch = self.fetch
        let flight = Task<VideoSegmentIndex, Error> {
            for count in [65_536, 1_048_576] {
                try Task.checkCancellation()
                var request = URLRequest(url: source, timeoutInterval: 15)
                headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
                request.setValue("bytes=0-\(count - 1)", forHTTPHeaderField: "Range")
                let (bytes, response) = try await fetch(request)
                guard let http = response as? HTTPURLResponse,
                      http.statusCode == 206 || http.statusCode == 200,
                      !bytes.isEmpty, bytes.count <= (http.statusCode == 206 ? count : 1_048_576)
                else { throw URLError(.badServerResponse) }
                if let index = VideoSegmentIndex.parse(head: bytes) { return index }
                if http.statusCode == 200 { throw URLError(.cannotParseResponse) }
            }
            throw URLError(.cannotParseResponse)
        }
        flights[key] = flight
        defer { flights[key] = nil }
        let index = try await flight.value
        entries = entries.filter { Date().timeIntervalSince($0.value.saved) < 120 }
        if entries.count >= 4, let oldest = entries.min(by: { $0.value.saved < $1.value.saved })?.key { entries[oldest] = nil }
        entries[key] = Entry(index: index, saved: Date())
        return index
    }
}
