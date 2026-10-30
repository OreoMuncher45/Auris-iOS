package com.music.auris.data.flow

import com.music.auris.data.LikeState
import com.music.auris.data.innertube.Innertube
import com.music.auris.data.innertube.InnertubeParser
import com.music.auris.data.model.LikeStatus
import com.music.auris.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Swift-facing Flow surface.
 *
 * The app speaks JSON over these calls, like every other bridge in the shared
 * module: the engine and the store are shared Kotlin, and the tuner UI, the
 * mix list and the play action live in SwiftUI on the app side.
 */
object FlowBridge {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val songListSerializer = ListSerializer(Song.serializer())

    private const val LIBRARY_SONGS = "FEmusic_liked_videos"
    private const val HISTORY = "FEmusic_history"

    /** The mix, its tuner state and the unlock status in one payload. */
    @Serializable
    data class FlowSnapshot(
        val songs: List<Song>,
        val mood: String,
        val discovery: Float,
        val memory: Float,
        val excludedGenres: List<String>,
        val unlocked: Boolean,
        val favTrackCount: Int,
        val favArtistCount: Int,
        val neededMore: Int,
        val radioName: String,
        val browseId: String,
    )

    fun interface FlowCallback {
        fun onResult(json: String?, message: String?)
    }

    /**
     * The tuner and unlock status without touching the network.
     *
     * The unlock rule counts favorites, and the counts come from the library,
     * so this is only as fresh as the last build; [build] refreshes them.
     */
    fun status(callback: FlowCallback) {
        val tuner = FlowStore.tuner.value
        val mood = FlowStore.mood.value
        val snapshot = FlowStore.readSnapshot()
        val payload = FlowSnapshot(
            songs = snapshot.orEmpty(),
            mood = mood.name,
            discovery = tuner.discovery,
            memory = tuner.memory,
            excludedGenres = tuner.excludedGenres.toList(),
            unlocked = true,
            favTrackCount = 0,
            favArtistCount = 0,
            neededMore = 0,
            radioName = FlowRules.FLOW_RADIO_NAME,
            browseId = FlowRules.FLOW_BROWSE_ID,
        )
        callback.onResult(json.encodeToString(FlowSnapshot.serializer(), payload), null)
    }

    /**
     * Build (or rebuild) the mix from favorites, history and recommendations.
     *
     * Everything the engine needs is gathered here, in the shared module,
     * where the InnerTube session already lives.
     */
    fun build(limit: Int = FlowEngine.INITIAL_TRACKS, callback: FlowCallback) {
        bridgeScope.launch {
            try {
                val generation = Innertube.sessionGeneration
                Innertube.checkSession(generation)
                Innertube.ensureSessionScope()

                val seeds = coroutineScope {
                    val favorites = async { fetchLibrarySongs() }
                    val history = async { fetchHistory() }
                    val quickPicks = async { fetchQuickPicks() }
                    val fav = favorites.await()
                    val hist = history.await()
                    val exclude = (fav.map { it.videoId } + hist.map { it.videoId }).toSet()
                    FlowEngine.SeedPool(fav, hist, quickPicks.await().filter { it.videoId !in exclude })
                }

                val dislikeIds = LikeState.overrides.value
                    .filterValues { it == LikeStatus.DISLIKE }
                    .keys

                val songs = FlowEngine.buildInitialQueue(
                    radio = FlowEngine.FlowRadio { videoId -> fetchRadio(videoId) },
                    seeds = seeds,
                    dislikeIds = dislikeIds,
                    bannedIds = FlowStore.bannedIds.value,
                    skipCounts = FlowStore.skipCounts.value,
                    config = FlowStore.config,
                    limit = limit,
                )
                FlowStore.saveSnapshot(songs)

                val tuner = FlowStore.tuner.value
                val payload = FlowSnapshot(
                    songs = songs,
                    mood = FlowStore.mood.value.name,
                    discovery = tuner.discovery,
                    memory = tuner.memory,
                    excludedGenres = tuner.excludedGenres.toList(),
                    unlocked = FlowRules.isUnlocked(seeds.favorites.size, 0),
                    favTrackCount = seeds.favorites.size,
                    favArtistCount = 0,
                    neededMore = 0,
                    radioName = FlowRules.FLOW_RADIO_NAME,
                    browseId = FlowRules.FLOW_BROWSE_ID,
                )
                callback.onResult(json.encodeToString(FlowSnapshot.serializer(), payload), null)
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }

    /** Append fresh recommendations to an existing mix. */
    fun grow(current: List<Song>, limit: Int = FlowEngine.TOPUP_TRACKS, callback: FlowCallback) {
        bridgeScope.launch {
            try {
                val songs = FlowEngine.grow(
                    radio = FlowEngine.FlowRadio { videoId -> fetchRadio(videoId) },
                    current = current,
                    limit = limit,
                    dislikeIds = LikeState.overrides.value
                        .filterValues { it == LikeStatus.DISLIKE }.keys,
                    bannedIds = FlowStore.bannedIds.value,
                    skipCounts = FlowStore.skipCounts.value,
                    tuner = FlowStore.tuner.value,
                )
                FlowStore.saveSnapshot(songs)
                callback.onResult(json.encodeToString(songListSerializer, songs), null)
            } catch (e: Throwable) {
                callback.onResult(null, e.message ?: e.toString())
            }
        }
    }

    /** Persist the tuner and rebuild the mix in one step. */
    fun applyConfig(
        mood: String,
        discovery: Float,
        memory: Float,
        excludedGenres: List<String>,
        callback: FlowCallback,
    ) {
        FlowStore.setMood(FlowMood.fromNameOrFlow(mood))
        FlowStore.setDiscovery(discovery)
        FlowStore.setMemory(memory)
        FlowStore.setExcludedGenres(excludedGenres.toSet())
        build(callback = callback)
    }

    fun ban(videoId: String) {
        FlowStore.ban(videoId)
    }

    fun unban(videoId: String) {
        FlowStore.unban(videoId)
    }

    /** A quick skip counts against the track; a full listen forgives one. */
    fun recordSkip(videoId: String, playedMs: Long, durationMs: Long) {
        if (videoId.isBlank()) return
        val quickSkip = playedMs < 30_000 || (durationMs > 0 && playedMs < durationMs * 0.4)
        if (quickSkip) FlowStore.recordSkip(videoId) else FlowStore.recordFullListen(videoId)
    }

    private suspend fun fetchLibrarySongs(): List<Song> {
        if (Innertube.cookie == null) return emptyList()
        return runCatching {
            InnertubeParser.collectSongsDeep(Innertube.browse(LIBRARY_SONGS)).distinctBy { it.videoId }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchHistory(): List<Song> {
        if (Innertube.cookie == null) return emptyList()
        return runCatching {
            InnertubeParser.collectSongsDeep(Innertube.browse(HISTORY)).distinctBy { it.videoId }
        }.getOrDefault(emptyList())
    }

    /** Recommendations from the home feed: new releases and the explore shelf. */
    private suspend fun fetchQuickPicks(): List<Song> {
        return runCatching {
            val shelves = InnertubeParser.parseHome(Innertube.browse("FEmusic_explore"))
            shelves.flatMap { shelf -> shelf.items }.mapNotNull { item ->
                item.videoId?.let {
                    Song(
                        videoId = it,
                        title = item.title,
                        artist = item.subtitle.orEmpty(),
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchRadio(videoId: String): List<Song> =
        runCatching {
            InnertubeParser.parseWatchQueue(Innertube.next(videoId)).filterNot { it.isVideo }
        }.getOrDefault(emptyList())
}
