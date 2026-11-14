package com.music.auris.data.settings

import com.music.auris.data.sources.SourceKind

/**
 * Whether a stream started under this ceiling may be served by [kind].
 *
 * Asked per stream rather than written into
 * [SourceConfig.enabled][com.music.auris.data.sources.SourceConfig.enabled],
 * which is what this used to do — an `applyQualityPreset` call flipped the
 * module and JioSaavn switches the moment a rung was picked. Two things
 * were wrong with that and both were reported together: picking a rung for
 * *mobile data* turned the sources off while sitting on Wi-Fi, and nothing
 * turned them back on when the connection changed, so a Wi-Fi ceiling of
 * Lossless still had no lossless source to reach. A ceiling is a property
 * of the connection in force; the switches on the Sources screen are the
 * user's standing choice. Storing the first in the second lost the second.
 *
 * [SourceKind.YOUTUBE] is permitted on every rung: it is what [maxKbps]
 * caps, and it is the only source that can answer at all when the ones
 * above it are skipped.
 *
 * ### What the rung may exclude, and what it may not
 *
 * A rung used to exclude a source by what it *could* serve rather than by
 * what it would cost: High dropped every source that could serve lossless,
 * and Medium and Low dropped everything that was not YouTube. That made an
 * addon a lower-priority source than JioSaavn on the rung where JioSaavn was
 * allowed at all, which is the opposite of what the source list says — the
 * sources the user added come first, and they come first among themselves,
 * with YouTube last — and it left the High rung unable to play a 320kbps
 * copy from an addon that had one, which is the best copy available.
 *
 * What is excluded now is only what the rung cannot pay for. Low asks for
 * 64kbps, and YouTube's own Opus ladder is the only source that can be
 * asked for *that* tier — an addon is asked for its best lossy rendition,
 * which is 128kbps at the floor, twice the data. Every other rung asks for
 * the best copy available and lets every enabled source answer, which is
 * what [SourceResolver] then weighs: the highest-quality result available
 * plays first, and YouTube is last in the list rather than excluded from it.
 */
fun AudioQuality.permits(kind: SourceKind): Boolean = when (this) {
    // Atmos is additive rather than a cap: the immersive mix is looked for
    // first, and every source that could serve the lossless ladder below it
    // is still in the walk.
    AudioQuality.DOLBY, AudioQuality.LOSSLESS -> true
    // The best copy on offer, whoever holds it. An addon is asked for its
    // high tier here rather than its lossless one — the rung is what asks
    // for bit-exact audio, not the source's ability to supply it.
    AudioQuality.HIGH, AudioQuality.MEDIUM -> true
    // The one rung with a byte budget too small for a source's own floor.
    AudioQuality.LOW -> kind == SourceKind.YOUTUBE
}

