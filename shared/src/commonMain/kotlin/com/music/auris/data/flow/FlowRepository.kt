package com.music.auris.data.flow

import com.music.auris.data.LikeState
import com.music.auris.data.YtMusicRepository
import com.music.auris.data.model.LikeStatus
import com.music.auris.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The mix and what it was built from.
 *
 * @param songs the ordered mix.
 * @param unlocked whether Flow is available at all: it opens once the
 *   library holds enough favourites, or enough favourite artists.
 * @param favTrackCount how many liked tracks the library holds.
 * @param favArtistCount how many distinct artists those tracks are by.
 * @param neededMore how many more favourites before Flow unlocks.
 */
data class FlowMix(
    val songs: List<Song>,
    val unlocked: Boolean,
    val favTrackCount: Int,
    val favArtistCount: Int,
    val neededMore: Int,
)

/**
 * Builds Flow out of the account's own library.
 *
 * The engine stays pure and offline (see [FlowEngine]); everything that has
 * to reach the network happens here, through the same repository the rest of
 * the app uses, so a Flow mix is built from exactly the songs the Library
 * tab shows and the radio is the same radio the player uses.
 */
object FlowRepository {

    /**
     * Build (or rebuild) the mix from favourites, history and fresh picks.
     *
     * Nothing here throws: a signed-out account, a failed library read or a
     * dead radio all narrow the mix rather than break the screen.
     */
    suspend fun build(limit: Int = FlowEngine.INITIAL_TRACKS): FlowMix {
        val seeds = coroutineScope {
            val favourites = async { fetchFavourites() }
            val history = async { fetchHistory() }
            val quickPicks = async { fetchQuickPicks() }
            val fav = favourites.await()
            val hist = history.await()
            val exclude = (fav.map { it.videoId } + hist.map { it.videoId }).toSet()
            FlowEngine.SeedPool(fav, hist, quickPicks.await().filter { it.videoId !in exclude })
        }

        val favourites = seeds.favorites
        val artists = favourites.map { it.artist.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .size

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

        return FlowMix(
            songs = songs,
            unlocked = FlowRules.isUnlocked(favourites.size, artists),
            favTrackCount = favourites.size,
            favArtistCount = artists,
            neededMore = FlowStatus(
                unlocked = FlowRules.isUnlocked(favourites.size, artists),
                favTrackCount = favourites.size,
                favArtistCount = artists,
            ).neededMore,
        )
    }

    /**
     * Append fresh recommendations to an existing mix.
     *
     * Best-effort: a failed radio call keeps the list as it was.
     */
    suspend fun grow(
        current: List<Song>,
        limit: Int = FlowEngine.TOPUP_TRACKS,
    ): List<Song> {
        if (current.isEmpty()) return current
        val grown = FlowEngine.grow(
            radio = FlowEngine.FlowRadio { videoId -> fetchRadio(videoId) },
            current = current,
            limit = limit,
            dislikeIds = LikeState.overrides.value
                .filterValues { it == LikeStatus.DISLIKE }.keys,
            bannedIds = FlowStore.bannedIds.value,
            skipCounts = FlowStore.skipCounts.value,
            tuner = FlowStore.tuner.value,
        )
        FlowStore.saveSnapshot(grown)
        return grown
    }

    private suspend fun fetchFavourites(): List<Song> =
        runCatching {
            YtMusicRepository.library().getOrNull()?.likedSongs.orEmpty().distinctBy { it.videoId }
        }.getOrDefault(emptyList())

    private suspend fun fetchHistory(): List<Song> =
        runCatching {
            YtMusicRepository.history().getOrNull().orEmpty().distinctBy { it.videoId }
        }.getOrDefault(emptyList())

    /** Recommendations from the home feed: new releases and the explore shelf. */
    private suspend fun fetchQuickPicks(): List<Song> =
        runCatching {
            YtMusicRepository.quickPicks().getOrNull().orEmpty().distinctBy { it.videoId }
        }.getOrDefault(emptyList())

    private suspend fun fetchRadio(videoId: String): List<Song> =
        runCatching {
            YtMusicRepository.radio(videoId).getOrNull().orEmpty().filterNot { it.isVideo }
        }.getOrDefault(emptyList())
}
