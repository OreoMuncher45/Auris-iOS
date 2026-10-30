package com.music.auris.data.flow

import com.music.auris.data.model.Song
import com.music.auris.data.settings.PlatformSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Flow's own state: the tuner, the bans, the skip counts, and the mix
 * snapshot.
 *
 * Upstream Android keeps this in a dedicated `SharedPreferences` file so a
 * Flow session survives a settings import/export. Here it lives in the
 * ordinary [PlatformSettings] store under `flow_`-prefixed keys, which is the
 * same seam every other setting uses on Apple.
 */
object FlowStore {

    private const val KEY_MOOD = "flow_mood"
    private const val KEY_DISCOVERY = "flow_discovery"
    private const val KEY_MEMORY = "flow_memory"
    private const val KEY_EXCLUDED_GENRES = "flow_excluded_genres"
    private const val KEY_BANNED = "flow_banned"
    private const val KEY_SKIPS = "flow_skips"
    private const val KEY_SEED_CURSOR = "flow_seed_cursor"

    /** A snapshot older than this rebuilds instead of restoring. */
    const val FLOW_TTL_MS = 24L * 60 * 60 * 1000

    /** A mix older than this rebuilds itself in the background, hourly. */
    const val FLOW_AUTO_REFRESH_MS = 60L * 60 * 1000

    private const val MAX_BANNED = 200
    private const val MAX_SKIPS = 300

    private val settings = PlatformSettings

    private val _mood = MutableStateFlow(FlowMood.fromNameOrFlow(settings.getString(KEY_MOOD, FlowMood.FLOW.name)))
    val mood: StateFlow<FlowMood> = _mood.asStateFlow()

    private val _tuner = MutableStateFlow(
        FlowTuner(
            discovery = settings.getFloat(KEY_DISCOVERY, 0.35f),
            memory = settings.getFloat(KEY_MEMORY, 0.5f),
            excludedGenres = decodeSet(settings.getString(KEY_EXCLUDED_GENRES, "")),
        )
    )
    val tuner: StateFlow<FlowTuner> = _tuner.asStateFlow()

    val config: FlowConfig get() = FlowConfig(_mood.value, _tuner.value)

    private val _bannedIds = MutableStateFlow(decodeSet(settings.getString(KEY_BANNED, "")))
    val bannedIds: StateFlow<Set<String>> = _bannedIds.asStateFlow()

    private val _skipCounts = MutableStateFlow(decodeSkips(settings.getString(KEY_SKIPS, "")))
    val skipCounts: StateFlow<Map<String, Int>> = _skipCounts.asStateFlow()

    /** Rotating radio-seed cursor for endless top-ups. */
    var seedCursor: Int = settings.getInt(KEY_SEED_CURSOR, 0)
        private set

    fun setMood(mood: FlowMood) {
        _mood.value = mood
        settings.putString(KEY_MOOD, mood.name)
    }

    fun setDiscovery(value: Float) {
        _tuner.value = _tuner.value.withDiscovery(value)
        settings.putFloat(KEY_DISCOVERY, _tuner.value.discovery)
    }

    fun setMemory(value: Float) {
        _tuner.value = _tuner.value.withMemory(value)
        settings.putFloat(KEY_MEMORY, _tuner.value.memory)
    }

    fun toggleGenre(genre: String, enabled: Boolean) {
        _tuner.value = _tuner.value.toggleGenre(genre, enabled)
        settings.putString(KEY_EXCLUDED_GENRES, encodeSet(_tuner.value.excludedGenres))
    }

    fun setExcludedGenres(genres: Set<String>) {
        val lowered = genres.map { it.lowercase() }.toSet()
        _tuner.value = _tuner.value.copy(excludedGenres = lowered)
        settings.putString(KEY_EXCLUDED_GENRES, encodeSet(lowered))
    }

    fun ban(videoId: String) {
        if (videoId.isBlank()) return
        val next = (_bannedIds.value + videoId).toList().takeLast(MAX_BANNED).toSet()
        _bannedIds.value = next
        settings.putString(KEY_BANNED, encodeSet(next))
    }

    fun unban(videoId: String) {
        if (videoId.isBlank()) return
        val next = _bannedIds.value - videoId
        _bannedIds.value = next
        settings.putString(KEY_BANNED, encodeSet(next))
    }

    fun clearBans() {
        _bannedIds.value = emptySet()
        settings.putString(KEY_BANNED, "")
    }

    /**
     * Record a skip: counts toward demotion/exclusion in [FlowEngine].
     * Call only for user-initiated skips, not for natural track ends.
     */
    fun recordSkip(videoId: String) {
        if (videoId.isBlank() || videoId in _bannedIds.value) return
        val next = _skipCounts.value.toMutableMap()
        next[videoId] = (next[videoId] ?: 0) + 1
        // Evict smallest counts first when over cap.
        if (next.size > MAX_SKIPS) {
            next.entries.sortedBy { it.value }.take(next.size - MAX_SKIPS)
                .forEach { next.remove(it.key) }
        }
        _skipCounts.value = next
        settings.putString(KEY_SKIPS, encodeSkips(next))
    }

    /** A full listen forgives one skip — the track wasn't that bad. */
    fun recordFullListen(videoId: String) {
        val cur = _skipCounts.value[videoId] ?: return
        if (cur <= 1) {
            _skipCounts.value = _skipCounts.value - videoId
        } else {
            _skipCounts.value = _skipCounts.value + (videoId to cur - 1)
        }
        settings.putString(KEY_SKIPS, encodeSkips(_skipCounts.value))
    }

    fun advanceSeedCursor() {
        seedCursor += 1
        settings.putInt(KEY_SEED_CURSOR, seedCursor)
    }

    // ---- Persisted mix -----------------------------------------------------

    private const val KEY_FLOW_SNAPSHOT = "flow_snapshot"
    private const val KEY_FLOW_BUILT_AT = "flow_built_at"

    /** Age of the stored mix, or null when there is none. */
    fun snapshotAgeMs(): Long? {
        val builtAt = settings.getLong(KEY_FLOW_BUILT_AT, 0L)
        if (builtAt <= 0L) return null
        return nowMillis() - builtAt
    }

    fun saveSnapshot(songs: List<Song>) {
        if (songs.isEmpty()) return
        settings.putString(KEY_FLOW_SNAPSHOT, encodeFlowSnapshot(songs))
        settings.putLong(KEY_FLOW_BUILT_AT, nowMillis())
    }

    /** The stored mix and when it was built, or null when there is none. */
    fun readSnapshot(): List<Song>? {
        val text = settings.getString(KEY_FLOW_SNAPSHOT, "")
        if (text.isEmpty()) return null
        return decodeFlowSnapshot(text).ifEmpty { null }
    }

    fun clearSnapshot() {
        settings.putString(KEY_FLOW_SNAPSHOT, "")
        settings.putLong(KEY_FLOW_BUILT_AT, 0L)
    }

    /** Wall clock, isolated so the codec stays testable. */
    internal var nowMillis: () -> Long = { kotlin.system.getTimeMillis() }

    // ---- Codecs ------------------------------------------------------------

    /**
     * The persisted mix as plain text, split from the storage so it unit
     * tests without a device: control separators can never appear in a field
     * because encoding blanks them, so splitting never misparses.
     */
    fun encodeFlowSnapshot(songs: List<Song>): String =
        songs.take(200).joinToString("\u001e") { s ->
            listOf(
                s.videoId, s.title, s.artist,
                s.albumName.orEmpty(), s.thumbnailUrl.orEmpty(),
            ).joinToString("\u001f") { it.replace("\u001e", " ").replace("\u001f", " ") }
        }

    /** Inverse of [encodeFlowSnapshot]: rows without an id are dropped. */
    fun decodeFlowSnapshot(text: String): List<Song> {
        if (text.isBlank()) return emptyList()
        return text.split("\u001e").mapNotNull { row ->
            val f = row.split("\u001f")
            val id = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Song(
                videoId = id,
                title = f.getOrNull(1).orEmpty(),
                artist = f.getOrNull(2).orEmpty(),
                thumbnailUrl = f.getOrNull(4)?.takeIf { it.isNotBlank() },
                albumName = f.getOrNull(3)?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** A set as one delimited string — the store has no string-set type. */
    internal fun encodeSet(values: Set<String>): String =
        values.joinToString("\u001f") { it.replace("\u001f", " ") }

    internal fun decodeSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split("\u001f").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    internal fun encodeSkips(map: Map<String, Int>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    internal fun decodeSkips(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Int>()
        raw.split(";").forEach { part ->
            val kv = part.split("=")
            if (kv.size != 2 || kv[0].isBlank()) return@forEach
            val count = kv[1].toIntOrNull() ?: return@forEach
            if (count > 0) out[kv[0]] = count
        }
        return out
    }
}
