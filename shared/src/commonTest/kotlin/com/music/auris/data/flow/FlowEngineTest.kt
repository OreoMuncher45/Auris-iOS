package com.music.auris.data.flow

import com.music.auris.data.model.Song
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Flow engine, tested without a network.
 *
 * The engine is pure: the seeds, the tuner and the radio come in, the mix
 * comes out. That is what makes it testable at all, and these tests are the
 * reason it stays that way.
 */
class FlowEngineTest {

    private fun song(id: String, artist: String = "Artist", title: String = "Track $id") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun radio(vararg ids: String) = FlowEngine.FlowRadio { ids.map { song(it) } }

    // ---- Scoring -----------------------------------------------------------

    @Test
    fun aFavouriteOutscoresAFreshTrack() {
        val favourite = song("fav")
        val fresh = song("fresh")
        val tuner = FlowTuner(discovery = 0.35f, memory = 0.5f)
        val favourites = setOf("fav")

        val favouriteScore = FlowEngine.score(favourite, favourites, emptyMap(), emptyMap(), tuner, emptyList())
        val freshScore = FlowEngine.score(fresh, favourites, emptyMap(), emptyMap(), tuner, emptyList())

        assertTrue(favouriteScore > freshScore, "a favourite should outscore a fresh track")
    }

    @Test
    fun recentHistoryOutscoresOldHistory() {
        val tuner = FlowTuner(discovery = 0.35f, memory = 1f)
        val recent = FlowEngine.score(song("a"), emptySet(), mapOf("a" to 0), emptyMap(), tuner, emptyList())
        val old = FlowEngine.score(song("b"), emptySet(), mapOf("b" to 19), emptyMap(), tuner, emptyList())

        assertTrue(recent > old, "the most recent play should outscore the oldest")
    }

    @Test
    fun aSkippedTrackIsDemoted() {
        val tuner = FlowTuner()
        val skipped = FlowEngine.score(song("a"), emptySet(), emptyMap(), mapOf("a" to 2), tuner, emptyList())
        val fresh = FlowEngine.score(song("b"), emptySet(), emptyMap(), emptyMap(), tuner, emptyList())

        assertTrue(skipped < fresh, "a track skipped twice should sit below a fresh one")
    }

    @Test
    fun aMoodHintLiftsAMatchingArtist() {
        val tuner = FlowTuner()
        val match = FlowEngine.score(
            song("a", artist = "Khruangbin"), emptySet(), emptyMap(), emptyMap(), tuner, listOf("khruangbin"),
        )
        val other = FlowEngine.score(
            song("b", artist = "Portishead"), emptySet(), emptyMap(), emptyMap(), tuner, listOf("khruangbin"),
        )

        assertTrue(match > other, "a mood that names the artist should lift it")
    }

    // ---- Exclusion ---------------------------------------------------------

    @Test
    fun aBannedTrackIsExcluded() {
        val tuner = FlowTuner()
        assertTrue(FlowEngine.isExcluded(song("a"), bannedIds = setOf("a"), emptySet(), emptyMap(), tuner))
    }

    @Test
    fun aDislikedTrackIsExcluded() {
        val tuner = FlowTuner()
        assertTrue(FlowEngine.isExcluded(song("a"), emptySet(), setOf("a"), emptyMap(), tuner))
    }

    @Test
    fun aTrackSkippedThreeTimesIsExcluded() {
        val tuner = FlowTuner()
        assertFalse(FlowEngine.isExcluded(song("a"), emptySet(), emptySet(), mapOf("a" to 2), tuner))
        assertTrue(FlowEngine.isExcluded(song("a"), emptySet(), emptySet(), mapOf("a" to 3), tuner))
    }

    @Test
    fun aGenreSwitchedOffInTheTunerIsExcluded() {
        val tuner = FlowTuner(excludedGenres = setOf("metal"))
        val excluded = FlowEngine.isExcluded(
            song("a", artist = "Metallica"), emptySet(), emptySet(), emptyMap(), tuner,
            genreOf = { listOf("Metal") },
        )
        assertTrue(excluded, "a metal track should be out when metal is switched off")
    }

    // ---- Ordering ----------------------------------------------------------

    @Test
    fun fullDiscoveryDropsEverythingFamiliar() {
        val tuner = FlowTuner(discovery = 0.95f)
        val pool = listOf(song("fav"), song("old"), song("new"))
        val ordered = FlowEngine.orderLocalPool(
            pool = pool,
            favIds = setOf("fav"),
            history = listOf(song("old")),
            skipCounts = emptyMap(),
            tuner = tuner,
            mood = FlowMood.FLOW,
        )

        assertEquals(listOf("new"), ordered.map { it.videoId })
    }

    @Test
    fun fullPersonalDropsEverythingFresh() {
        val tuner = FlowTuner(discovery = 0.05f)
        val pool = listOf(song("fav"), song("old"), song("new"))
        val ordered = FlowEngine.orderLocalPool(
            pool = pool,
            favIds = setOf("fav"),
            history = listOf(song("old")),
            skipCounts = emptyMap(),
            tuner = tuner,
            mood = FlowMood.FLOW,
        )

        assertEquals(setOf("fav", "old"), ordered.map { it.videoId }.toSet())
    }

    @Test
    fun theMixDoesNotRepeatAnArtistThreeTimesInARow() {
        val pool = (1..8).map { song("a$it", artist = "Same") } + (1..8).map { song("b$it", artist = "Other") }
        val ordered = FlowEngine.orderLocalPool(
            pool = pool,
            favIds = emptySet(),
            history = emptyList(),
            skipCounts = emptyMap(),
            tuner = FlowTuner(),
            mood = FlowMood.FLOW,
        )

        var run = 1
        for (i in 1 until ordered.size) {
            run = if (ordered[i].artist == ordered[i - 1].artist) run + 1 else 1
            assertTrue(run <= 2, "three tracks by one artist in a row at $i")
        }
    }

    // ---- The queue ---------------------------------------------------------

    @Test
    fun theQueueIsBuiltFromTheFavouritesAndTheRadio() = runTest {
        val favourites = listOf(song("fav1"), song("fav2"))
        val mix = FlowEngine.buildInitialQueue(
            radio = radio("r1", "r2"),
            seeds = FlowEngine.SeedPool(favorites = favourites),
            limit = 10,
        )

        assertTrue(mix.map { it.videoId }.containsAll(listOf("fav1", "fav2")), "the favourites should be in the mix")
        assertTrue(mix.map { it.videoId }.contains("r1"), "the radio's tracks should be in the mix")
        assertEquals(mix.map { it.videoId }.distinct().size, mix.size, "the mix should hold no track twice")
  
    }

    @Test
    fun aBannedTrackNeverReachesTheMix() = runTest {
        val mix = FlowEngine.buildInitialQueue(
            radio = radio("banned", "kept"),
            seeds = FlowEngine.SeedPool(favorites = listOf(song("fav"))),
            bannedIds = setOf("banned"),
            limit = 10,
        )

        assertFalse(mix.any { it.videoId == "banned" })
        assertTrue(mix.any { it.videoId == "kept" })
  
    }

    @Test
    fun aFailedRadioCallLeavesTheLocalPoolStanding() = runTest {
        val radio = FlowEngine.FlowRadio { error("no network") }
        val mix = FlowEngine.buildInitialQueue(
            radio = radio,
            seeds = FlowEngine.SeedPool(favorites = listOf(song("fav"))),
            limit = 10,
        )

        assertEquals(listOf("fav"), mix.map { it.videoId })
  
    }

    @Test
    fun growingAppendsToTheMix() = runTest {
        val grown = FlowEngine.grow(
            radio = radio("new1", "new2"),
            current = listOf(song("a"), song("b")),
            limit = 10,
        )

        assertEquals(listOf("a", "b"), grown.take(2).map { it.videoId })
        assertTrue(grown.any { it.videoId == "new1" })
  
    }

    // ---- The store's codecs ------------------------------------------------

    @Test
    fun aSnapshotSurvivesTheRoundTrip() {
        val songs = listOf(
            song("a", artist = "Artist", title = "Title"),
            song("b", artist = "Other", title = "Second"),
        )
        val decoded = FlowStore.decodeFlowSnapshot(FlowStore.encodeFlowSnapshot(songs))

        assertEquals(songs.map { it.videoId }, decoded.map { it.videoId })
        assertEquals("Title", decoded.first().title)
        assertEquals("Other", decoded.last().artist)
    }

    @Test
    fun aSnapshotRowWithoutAnIdIsDropped() {
        val decoded = FlowStore.decodeFlowSnapshot("\u001F" + "Title" + "\u001F" + "Artist")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun aSetSurvivesTheRoundTrip() {
        val genres = setOf("pop", "rock", "r&b")
        assertEquals(genres, FlowStore.decodeSet(FlowStore.encodeSet(genres)))
    }

    @Test
    fun skipCountsSurviveTheRoundTrip() {
        val skips = mapOf("a" to 1, "b" to 3)
        assertEquals(skips, FlowStore.decodeSkips(FlowStore.encodeSkips(skips)))
    }

    @Test
    fun aBrokenSkipCountIsIgnored() {
        assertEquals(emptyMap(), FlowStore.decodeSkips("a=not-a-number"))
    }

    // ---- The rules ---------------------------------------------------------

    @Test
    fun flowUnlocksWithEnoughFavourites() {
        assertFalse(FlowRules.isUnlocked(15, 0))
        assertTrue(FlowRules.isUnlocked(16, 0))
    }

    @Test
    fun flowUnlocksWithEnoughFavouriteArtists() {
        assertFalse(FlowRules.isUnlocked(0, 9))
        assertTrue(FlowRules.isUnlocked(0, 10))
    }

    @Test
    fun aMoodThatIsNotOneWeKnowFallsBackToFlow() {
        assertEquals(FlowMood.FLOW, FlowMood.fromNameOrFlow("DISCO"))
        assertEquals(FlowMood.PARTY, FlowMood.fromNameOrFlow("PARTY"))
    }
}
