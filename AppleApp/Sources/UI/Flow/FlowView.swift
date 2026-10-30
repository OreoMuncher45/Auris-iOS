import SwiftUI
import AurisShared

/// Flow — an endless personal mix.
///
/// The screen is the tuner plus the mix it produces. The engine, the store
/// and the radio call live in the shared Kotlin module; this view owns the
/// editing state and hands a committed tuner back for a rebuild.
struct FlowView: View {
    @State private var model = FlowModel()
    @State private var showTuner = false
    @Environment(PlaybackController.self) private var controller
    @Environment(ToastCenter.self) private var toast

    var body: some View {
        List {
            header
            if model.loading && model.songs.isEmpty {
                SkeletonRows(count: 6)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            } else if let error = model.error, model.songs.isEmpty {
                errorRow(error)
            } else if model.songs.isEmpty {
                emptyRow
            } else {
                mixSection
            }
        }
        .listStyle(.plain)
        .navigationTitle("Flow")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showTuner = true
                } label: {
                    Label("Tuner", systemImage: "slider.horizontal.3")
                }
                .disabled(model.loading)
            }
        }
        .sheet(isPresented: $showTuner) {
            tunerSheet
        }
        .task {
            model.loadStored()
            if model.songs.isEmpty { model.build() }
        }
        .refreshable { model.build() }
    }

    // MARK: - Header

    private var header: some View {
        Section {
            VStack(alignment: .leading, spacing: Theme.Metrics.m) {
                HStack(spacing: Theme.Metrics.m) {
                    Text("Your personal soundtrack")
                        .font(.headline)
                    Spacer()
                    if model.loading {
                        ProgressView().controlSize(.small)
                    }
                }
                moodStrip
                if model.snapshot.favTrackCount > 0 {
                    Text("\(model.snapshot.favTrackCount) favorites seeded")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, Theme.Metrics.s)
            .listRowInsets(EdgeInsets(top: Theme.Metrics.s, leading: Theme.Metrics.l, bottom: Theme.Metrics.s, trailing: Theme.Metrics.l))
            .listRowBackground(Color.clear)
        }
    }

    /// The mood chips: Flow itself, then the moods that bias the mix.
    private var moodStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Theme.Metrics.m) {
                ForEach(FlowMoodOption.allCases) { mood in
                    Button {
                        model.mood = mood
                        model.applyTuner()
                    } label: {
                        Label(mood.label, systemImage: mood.symbol)
                            .font(.subheadline)
                            .padding(.horizontal, Theme.Metrics.m + 2)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    .chromeGlass(in: Capsule())
                    .tint(model.mood == mood ? Theme.accent : .secondary)
                    .accessibilityAddTraits(model.mood == mood ? [.isSelected] : [])
                }
            }
            .padding(.vertical, 2)
        }
    }

    // MARK: - Mix

    private var mixSection: some View {
        Section {
            ForEach(Array(model.songs.enumerated()), id: \.element.id) { index, song in
                SongRow(
                    entry: QueueEntry(
                        id: song.videoId, title: song.title, artist: song.artist,
                        source: "yt:\(song.videoId)", thumbnailUrl: song.thumbnailUrl,
                        durationText: song.durationText, albumName: song.albumName,
                        artworkData: nil, isLocal: false
                    ),
                    isCurrent: controller.current?.id == song.videoId,
                    play: { play(from: index) },
                    playNext: { controller.playNext(model.queueEntries()[index]) },
                    addToQueue: { controller.addToQueue(model.queueEntries()[index]) }
                )
                .listRowBackground(Color.clear)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        model.ban(song)
                    } label: {
                        Label("Not for me", systemImage: "nosign")
                    }
                }
            }
            if model.growing {
                HStack {
                    ProgressView().controlSize(.small)
                    Text("Finding more…").font(.footnote).foregroundStyle(.secondary)
                }
                .listRowBackground(Color.clear)
            } else {
                Button {
                    model.grow()
                } label: {
                    Label("More like this", systemImage: "plus.circle")
                        .font(.subheadline)
                }
                .listRowBackground(Color.clear)
            }
        } header: {
            Text("Your mix")
        }
    }

    private var emptyRow: some View {
        Section {
            EmptyStateView(
                icon: Image(systemName: "infinity"),
                title: "No mix yet",
                subtitle: "Flow builds an endless mix from your favorites, your history and fresh finds.",
                buttonTitle: "Build my mix"
            ) {
                model.build()
            }
            .listRowBackground(Color.clear)
        }
    }

    private func errorRow(_ error: String) -> some View {
        Section {
            VStack(alignment: .leading, spacing: Theme.Metrics.m) {
                Text("Flow could not build a mix").font(.headline)
                Text(error).font(.footnote).foregroundStyle(.secondary)
                Button("Try again") { model.build() }
                    .buttonStyle(.borderedProminent)
            }
            .listRowBackground(Color.clear)
        }
    }

    // MARK: - Tuner

    private var tunerSheet: some View {
        NavigationStack {
            Form {
                Section("Mood") {
                    Picker("Mood", selection: $model.mood) {
                        ForEach(FlowMoodOption.allCases) { mood in
                            Label(mood.label, systemImage: mood.symbol).tag(mood)
                        }
                    }
                    .pickerStyle(.menu)
                }

                Section {
                    VStack(alignment: .leading, spacing: Theme.Metrics.s) {
                        Text("Discovery").font(.subheadline)
                        Text("How much of the mix is new to you.")
                            .font(.caption).foregroundStyle(.secondary)
                        Slider(value: $model.discovery, in: 0...1)
                    }
                    VStack(alignment: .leading, spacing: Theme.Metrics.s) {
                        Text("Memory").font(.subheadline)
                        Text("How much of the mix follows what you played lately.")
                            .font(.caption).foregroundStyle(.secondary)
                        Slider(value: $model.memory, in: 0...1)
                    }
                } header: {
                    Text("Tuner")
                } footer: {
                    Text("At the extremes the mix is strict: full Discovery drops everything familiar, and full Memory leans entirely on recent plays.")
                }

                Section("Genres") {
                    FlowGenreGrid(excluded: $model.excludedGenres)
                }
            }
            .navigationTitle("Flow Tuner")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showTuner = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        model.applyTuner()
                        showTuner = false
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - Playback

    private func play(from index: Int) {
        let entries = model.queueEntries()
        guard entries.indices.contains(index) else { return }
        controller.play(
            entries,
            at: index,
            context: model.snapshot.radioName,
            contextID: model.snapshot.browseId
        )
        if index >= entries.count - 5 { model.grow() }
    }
}

/// The Home entry point: a card that opens Flow.
///
/// It sits above the feed rather than inside it, because the mix is the
/// listener's own material and the feed is the catalogue's.
struct FlowEntryCard: View {
    var body: some View {
        NavigationLink {
            FlowView()
        } label: {
            HStack(spacing: Theme.Metrics.l) {
                ZStack {
                    RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [Color(red: 0.13, green: 0.20, blue: 0.42),
                                         Color(red: 0.05, green: 0.06, blue: 0.14)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Image(systemName: "infinity")
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .frame(width: 56, height: 56)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Flow")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("Your personal soundtrack — endless")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(Theme.Metrics.l)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Flow, your personal soundtrack")
    }
}

/// The genre toggles: a grid of chips, each one excluding a genre when on.
private struct FlowGenreGrid: View {
    @Binding var excluded: Set<String>

    private let columns = [GridItem(.adaptive(minimum: 96), spacing: Theme.Metrics.m)]

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: Theme.Metrics.m) {
            ForEach(FlowGenreOption.allCases) { genre in
                let on = !excluded.contains(genre.label.lowercased())
                Button {
                    if on {
                        excluded.insert(genre.label.lowercased())
                    } else {
                        excluded.remove(genre.label.lowercased())
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: on ? "checkmark.circle.fill" : "circle")
                            .font(.caption)
                        Text(genre.label).font(.caption)
                    }
                    .padding(.horizontal, Theme.Metrics.m)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .chromeGlass(in: Capsule())
                .tint(on ? Theme.accent : .secondary)
                .accessibilityLabel("\(genre.label) \(on ? "included" : "excluded")")
            }
        }
        .padding(.vertical, Theme.Metrics.s)
    }
}
