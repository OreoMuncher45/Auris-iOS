package com.music.auris.data.flow

import com.music.auris.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Flow screen shows.
 *
 * The mix, the tuner that produced it and where the build got to. One state
 * object, so the screen never has to reconcile a list that came from one call
 * with a tuner that came from another.
 */
data class FlowUiState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val growing: Boolean = false,
    val error: String? = null,
    val unlocked: Boolean = false,
    val favTrackCount: Int = 0,
    val favArtistCount: Int = 0,
    val neededMore: Int = FlowRules.UNLOCK_FAV_TOTAL,
    val mood: FlowMood = FlowMood.FLOW,
    val tuner: FlowTuner = FlowTuner(),
    /** Tracks the listener banned from every future mix. */
    val bannedIds: Set<String> = emptySet(),
)

/**
 * The tuner as it is being edited, before it is committed.
 */
data class FlowDraft(
    val mood: FlowMood = FlowMood.FLOW,
    val discovery: Float = 0.35f,
    val memory: Float = 0.5f,
    val excludedGenres: Set<String> = emptySet(),
)

/**
 * Drives the Flow screen: builds a mix, grows it, and holds the tuner while
 * it is being edited.
 *
 * The tuner is written to the store as it is dragged, but the mix is only
 * rebuilt when the edits are committed — otherwise every pixel of a slider
 * would start a network round trip.
 */
class FlowController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow(
        FlowUiState(
            mood = FlowStore.mood.value,
            tuner = FlowStore.tuner.value,
            bannedIds = FlowStore.bannedIds.value,
        )
    )
    val state: StateFlow<FlowUiState> = _state.asStateFlow()

    /**
     * The tracks a grown mix added, emitted once per successful [grow] so the
     * player can extend its queue and the mix never runs dry mid-play.
     */
    private val _grown = MutableSharedFlow<List<Song>>(extraBufferCapacity = 1)
    val grown: SharedFlow<List<Song>> = _grown.asSharedFlow()

    init {
        scope.launch {
            FlowStore.bannedIds.collect { ids -> _state.update { it.copy(bannedIds = ids) } }
        }
    }

    /** The tuner as it is being edited, before it is committed. */
    private val _draft = MutableStateFlow(
        FlowDraft(
            mood = FlowStore.mood.value,
            discovery = FlowStore.tuner.value.discovery,
            memory = FlowStore.tuner.value.memory,
            excludedGenres = FlowStore.tuner.value.excludedGenres,
        )
    )
    val draft: StateFlow<FlowDraft> = _draft.asStateFlow()

    /** Shows the stored mix, if there is one, and builds a new one if not. */
    fun start() {
        if (_state.value.songs.isNotEmpty()) return
        val stored = FlowStore.readSnapshot()
        if (stored != null) {
            _state.update { it.copy(songs = stored) }
        }
        if (stored == null || FlowStore.snapshotAgeMs()?.let { it > FlowStore.FLOW_TTL_MS } == true) {
            build()
        }
    }

    /** Build (or rebuild) the mix from favourites, history and fresh picks. */
    fun build() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { FlowRepository.build() }
                .onSuccess { mix ->
                    _state.update {
                        it.copy(
                            songs = mix.songs,
                            loading = false,
                            error = null,
                            unlocked = mix.unlocked,
                            favTrackCount = mix.favTrackCount,
                            favArtistCount = mix.favArtistCount,
                            neededMore = mix.neededMore,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: e.toString(),
                        )
                    }
                }
        }
    }

    /** Append fresh recommendations to the mix. */
    fun grow() {
        if (_state.value.growing || _state.value.songs.isEmpty()) return
        _state.update { it.copy(growing = true) }
        scope.launch {
            val before = _state.value.songs.map { it.videoId }.toSet()
            runCatching { FlowRepository.grow(_state.value.songs) }
                .onSuccess { songs ->
                    val added = songs.filter { it.videoId !in before }
                    _state.update { it.copy(songs = songs, growing = false) }
                    if (added.isNotEmpty()) _grown.tryEmit(added)
                }
                .onFailure {
                    _state.update { it.copy(growing = false) }
                }
        }
    }

    /** The tuner as it is being edited. */
    fun draftMood(): FlowMood = _draft.value.mood

    fun draftDiscovery(): Float = _draft.value.discovery

    fun draftMemory(): Float = _draft.value.memory

    fun draftGenres(): Set<String> = _draft.value.excludedGenres

    fun setDraftMood(mood: FlowMood) {
        _draft.value = _draft.value.copy(mood = mood)
    }

    fun setDraftDiscovery(value: Float) {
        _draft.value = _draft.value.copy(discovery = value.coerceIn(0f, 1f))
    }

    fun setDraftMemory(value: Float) {
        _draft.value = _draft.value.copy(memory = value.coerceIn(0f, 1f))
    }

    fun toggleDraftGenre(genre: String, enabled: Boolean) {
        val next = if (enabled) {
            _draft.value.excludedGenres - genre.lowercase()
        } else {
            _draft.value.excludedGenres + genre.lowercase()
        }
        _draft.value = _draft.value.copy(excludedGenres = next)
    }

    /**
     * Commit the tuner and rebuild the mix.
     *
     * The store is written first, so the tuner survives even if the rebuild
     * fails — and the mix that comes back is the one the new tuner describes.
     */
    fun applyTuner() {
        val tuner = _draft.value
        FlowStore.setMood(tuner.mood)
        FlowStore.setDiscovery(tuner.discovery)
        FlowStore.setMemory(tuner.memory)
        FlowStore.setExcludedGenres(tuner.excludedGenres)
        _state.update {
            it.copy(
                mood = tuner.mood,
                tuner = FlowStore.tuner.value,
            )
        }
        build()
    }

    /** Put a track on the list of tracks this mix will not offer again. */
    fun ban(videoId: String) {
        FlowStore.ban(videoId)
        _state.update { it.copy(songs = it.songs.filterNot { s -> s.videoId == videoId }) }
    }

    /** Take a track back off the ban list; it can be offered again. */
    fun unban(videoId: String) {
        FlowStore.unban(videoId)
    }

    /** A skip counts against the track; a full listen forgives one. */
    fun recordSkip(videoId: String, playedMs: Long, durationMs: Long) {
        if (videoId.isBlank()) return
        if (FlowSignal.isQuickSkip(playedMs, durationMs)) FlowStore.recordSkip(videoId)
        else FlowStore.recordFullListen(videoId)
    }
}
