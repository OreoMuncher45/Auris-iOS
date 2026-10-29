import Foundation

enum VideoRangeDelivery {
    /// Keep a bounded window in flight, but deliver each contiguous range as
    /// soon as it arrives. A slow fourth request must not delay the first byte.
    static func stream(
        from first: Int64, to end: Int64, chunkBytes: Int64, parallel: Int,
        fetch: @escaping @Sendable (Int64, Int64) async throws -> Data,
        receive: (Data) throws -> Void
    ) async throws {
        guard first >= 0, end >= first, chunkBytes > 0, parallel > 0 else { throw URLError(.badURL) }
        try await withThrowingTaskGroup(of: (Int64, Data).self) { group in
            var requested = first
            var delivered = first
            var pending: [Int64: Data] = [:]
            func enqueue() {
                guard requested < end else { return }
                let start = requested
                let stop = start + min(chunkBytes, end - start)
                requested = stop
                group.addTask {
                    try Task.checkCancellation()
                    let data = try await fetch(start, stop - 1)
                    guard data.count == Int(stop - start) else { throw URLError(.badServerResponse) }
                    return (start, data)
                }
            }
            for _ in 0..<parallel { enqueue() }
            while let (start, bytes) = try await group.next() {
                try Task.checkCancellation()
                pending[start] = bytes
                while let bytes = pending.removeValue(forKey: delivered) {
                    try receive(bytes)
                    delivered += Int64(bytes.count)
                    // Refill only after delivery: pending + running never
                    // grows beyond the configured window on a slow first range.
                    enqueue()
                }
            }
        }
    }
}
