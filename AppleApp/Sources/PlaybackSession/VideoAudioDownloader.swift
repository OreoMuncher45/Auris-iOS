import Foundation

enum VideoAudioDownloader {
    typealias Fetch = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    static func download(
        source: URL, headers: [String: String], to destination: URL,
        fetch: @escaping Fetch = { try await URLSession.shared.data(for: $0) }
    ) async throws {
        let chunk: Int64 = 1 << 20
        @Sendable func request(_ start: Int64, _ end: Int64) async throws -> (Data, HTTPURLResponse) {
            try Task.checkCancellation()
            var request = URLRequest(url: source, timeoutInterval: 20)
            headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
            request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
            let (data, response) = try await fetch(request)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            return (data, http)
        }
        let partial = destination.appendingPathExtension("part")
        FileManager.default.createFile(atPath: partial.path, contents: nil)
        let file = try FileHandle(forWritingTo: partial)
        var completed = false
        defer {
            try? file.close()
            if !completed { try? FileManager.default.removeItem(at: partial) }
        }
        let (first, response) = try await request(0, chunk - 1)
        guard (response.statusCode == 200 || response.statusCode == 206), !first.isEmpty else { throw URLError(.badServerResponse) }
        try file.write(contentsOf: first)
        if response.statusCode == 206 {
            let total = response.value(forHTTPHeaderField: "Content-Range")?
                .split(separator: "/").last.flatMap { Int64($0) }
            if let total {
                guard total >= Int64(first.count), Int64(first.count) == min(total, chunk) else { throw URLError(.badServerResponse) }
                try await VideoRangeDelivery.stream(from: Int64(first.count), to: total, chunkBytes: chunk, parallel: 2,
                    fetch: { start, end in
                        let (bytes, http) = try await request(start, end)
                        guard http.statusCode == 206 else { throw URLError(.badServerResponse) }
                        return bytes
                    }, receive: { try file.write(contentsOf: $0) })
            } else {
                // Retain the sequential EOF fallback for servers without a
                // total size. Never concatenate a full-body 200 after a range.
                var offset = Int64(first.count)
                var count = first.count
                while count == Int(chunk) {
                    let (bytes, http) = try await request(offset, offset + chunk - 1)
                    if http.statusCode == 416 { break }
                    guard http.statusCode == 206, !bytes.isEmpty, bytes.count <= Int(chunk) else { throw URLError(.badServerResponse) }
                    try file.write(contentsOf: bytes)
                    offset += Int64(bytes.count); count = bytes.count
                }
            }
        }
        try Task.checkCancellation()
        try file.close()
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: partial, to: destination)
        completed = true
    }
}
