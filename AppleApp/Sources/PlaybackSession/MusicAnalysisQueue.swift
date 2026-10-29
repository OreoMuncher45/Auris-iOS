import Foundation

/// One accelerator analysis at a time, shared by overlapping requests for the
/// same file. Obsolete consumers release their interest instead of multiplying
/// expensive sessions. The last cancelled consumer cancels the work as well.
actor MusicAnalysisQueue {
    static let shared = MusicAnalysisQueue()
    private struct Flight {
        let id: UUID
        let task: Task<Bool, Never>
        var waiters: Set<UUID>
    }
    private var flights: [String: Flight] = [:]
    private var tail: Task<Void, Never>?
    var pendingCount: Int { flights.values.reduce(0) { $0 + $1.waiters.count } }

    func perform(key: String, operation: @escaping @Sendable () async -> Bool) async -> Bool {
        guard !Task.isCancelled else { return false }
        let waiter = UUID()
        let flight: Flight
        if var existing = flights[key] {
            existing.waiters.insert(waiter); flights[key] = existing
            flight = existing
        } else {
            // Two tracks per pair, with room for one replacement pair. Let
            // excess speculative work use cached/fallback analysis.
            guard flights.count < 4 else { return false }
            let previous = tail
            let task = Task(priority: .utility) {
                await previous?.value
                guard !Task.isCancelled else { return false }
                return await operation()
            }
            flight = Flight(id: UUID(), task: task, waiters: [waiter])
            flights[key] = flight
            tail = Task { _ = await task.value }
        }
        return await withTaskCancellationHandler {
            let result = await flight.task.value
            release(key: key, id: flight.id, waiter: waiter, cancelled: false)
            return result && !Task.isCancelled
        } onCancel: {
            Task { await self.release(key: key, id: flight.id, waiter: waiter, cancelled: true) }
        }
    }

    private func release(key: String, id: UUID, waiter: UUID, cancelled: Bool) {
        guard var flight = flights[key], flight.id == id else { return }
        flight.waiters.remove(waiter)
        if flight.waiters.isEmpty {
            if cancelled { flight.task.cancel() }
            flights[key] = nil
        } else { flights[key] = flight }
    }

    func cancelAll() {
        flights.values.forEach { $0.task.cancel() }
        flights.removeAll()
        // Keep the tail barrier until the cancelled active session has
        // actually stopped; a new foreground request must not overlap it.
    }
}
