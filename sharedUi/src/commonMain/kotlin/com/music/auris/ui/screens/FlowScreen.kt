package com.music.auris.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.auris.data.flow.FlowDraft
import com.music.auris.data.flow.FlowMood
import com.music.auris.data.flow.FlowUiState
import com.music.auris.data.flow.FLOW_TUNER_GENRES
import com.music.auris.data.model.Song
import com.music.auris.sharedui.resources.*
import com.music.auris.ui.components.MessageState
import com.music.auris.ui.components.PlayingAccent
import com.music.auris.ui.components.SongRow
import com.music.auris.ui.components.songListSkeleton
import org.jetbrains.compose.resources.stringResource

/**
 * Flow — an endless personal mix.
 *
 * The screen is the tuner plus the mix it produces. The engine, the store and
 * the radio call live in the shared module; this screen owns the editing state
 * and hands a committed tuner back for a rebuild.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    state: FlowUiState,
    draft: FlowDraft,
    onPlay: (List<Song>, Int) -> Unit,
    onBuild: () -> Unit,
    onGrow: () -> Unit,
    onBan: (Song) -> Unit,
    onMoodTap: (FlowMood) -> Unit,
    onDraftMood: (FlowMood) -> Unit,
    onDiscovery: (Float) -> Unit,
    onMemory: (Float) -> Unit,
    onGenreToggle: (String, Boolean) -> Unit,
    onApplyTuner: () -> Unit,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    var showTuner by remember { mutableStateOf(false) }
    val songs = state.songs

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "flow:header") {
            FlowHeader(
                state = state,
                onTuner = { showTuner = true },
                onMoodTap = onMoodTap,
            )
        }

        when {
            state.loading && songs.isEmpty() -> {
                songListSkeleton(count = 6, keyPrefix = "flow:skeleton")
            }

            state.error != null && songs.isEmpty() -> {
                item(key = "flow:error") {
                    MessageState(
                        message = "${stringResource(Res.string.flow_error_title)}\n${state.error}",
                        actionLabel = stringResource(Res.string.retry),
                        onAction = onBuild,
                    )
                }
            }

            songs.isEmpty() -> {
                item(key = "flow:empty") {
                    MessageState(
                        message = "${stringResource(Res.string.flow_empty_title)}\n${stringResource(Res.string.flow_empty_subtitle)}",
                        actionLabel = stringResource(Res.string.flow_build),
                        onAction = onBuild,
                    )
                }
            }

            else -> {
                itemsIndexed(songs, key = { index, _ -> "flow:${songs[index].videoId}:$index" }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlay(songs, index) },
                        onLongPress = { onBan(song) },
                        isCurrent = currentSong?.videoId == song.videoId,
                        isPlaying = currentSong?.videoId == song.videoId && isPlaying,
                        searchPlayingStyle = true,
                        activeTint = PlayingAccent,
                    )
                }

                item(key = "flow:grow") {
                    if (state.growing) {
                        Text(
                            stringResource(Res.string.flow_finding_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    } else {
                        TextButton(
                            onClick = onGrow,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.flow_more))
                        }
                    }
                }
            }
        }
    }

    if (showTuner) {
        ModalBottomSheet(onDismissRequest = { showTuner = false }) {
            FlowTunerSheet(
                draft = draft,
                onMood = onDraftMood,
                onDiscovery = onDiscovery,
                onMemory = onMemory,
                onGenreToggle = onGenreToggle,
                onApply = {
                    onApplyTuner()
                    showTuner = false
                },
                onCancel = { showTuner = false },
            )
        }
    }
}

/**
 * The heading: what Flow is, the mood chips, and how much of the library the
 * mix is built from.
 */
@Composable
private fun FlowHeader(
    state: FlowUiState,
    onTuner: () -> Unit,
    onMoodTap: (FlowMood) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.flow_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(Res.string.flow_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.favTrackCount > 0) {
                    Text(
                        stringResource(Res.string.flow_seeded, state.favTrackCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!state.unlocked) {
                    Text(
                        stringResource(Res.string.flow_needs_more, state.neededMore),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onTuner) {
                Icon(Icons.Rounded.Tune, contentDescription = stringResource(Res.string.flow_tuner))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.flow_tuner))
            }
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowMood.entries.forEach { mood ->
                FilterChip(
                    selected = state.mood == mood,
                    onClick = { onMoodTap(mood) },
                    label = { Text(mood.label) },
                    leadingIcon = {
                        Icon(
                            flowMoodIcon(mood),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

/**
 * The tuner: a mood, two sliders, and the genres to keep out of the mix.
 *
 * Nothing here rebuilds the mix by itself. The edits belong to the draft until
 * Apply, so a slider dragged across its whole range costs one rebuild rather
 * than twenty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowTunerSheet(
    draft: FlowDraft,
    onMood: (FlowMood) -> Unit,
    onDiscovery: (Float) -> Unit,
    onMemory: (Float) -> Unit,
    onGenreToggle: (String, Boolean) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            stringResource(Res.string.flow_tuner_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            stringResource(Res.string.flow_mood),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowMood.entries.forEach { mood ->
                FilterChip(
                    selected = draft.mood == mood,
                    onClick = { onMood(mood) },
                    label = { Text(mood.label) },
                    leadingIcon = {
                        Icon(
                            flowMoodIcon(mood),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }

        TunerSlider(
            title = stringResource(Res.string.flow_discovery),
            hint = stringResource(Res.string.flow_discovery_hint),
            value = draft.discovery,
            onValueChange = onDiscovery,
        )

        TunerSlider(
            title = stringResource(Res.string.flow_memory),
            hint = stringResource(Res.string.flow_memory_hint),
            value = draft.memory,
            onValueChange = onMemory,
        )

        Text(
            stringResource(Res.string.flow_genres),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FLOW_TUNER_GENRES.forEach { genre ->
                val on = !draft.excludedGenres.contains(genre.lowercase())
                FilterChip(
                    selected = on,
                    onClick = { onGenreToggle(genre, on) },
                    label = { Text(genre) },
                )
            }
        }

        Text(
            stringResource(Res.string.flow_tuner_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onApply) { Text(stringResource(Res.string.flow_apply)) }
        }
    }
}

@Composable
private fun TunerSlider(
    title: String,
    hint: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onValueChange)
    }
}

/** The glyph each mood borrows from the icon set. */
private fun flowMoodIcon(mood: FlowMood): ImageVector = when (mood) {
    FlowMood.FLOW -> Icons.Rounded.AllInclusive
    FlowMood.LOVE -> Icons.Rounded.Favorite
    FlowMood.WORKOUT -> Icons.Rounded.LocalFireDepartment
    FlowMood.CHILL -> Icons.Rounded.Spa
    FlowMood.SAD -> Icons.Rounded.Cloud
    FlowMood.FOCUS -> Icons.Rounded.DarkMode
    FlowMood.PARTY -> Icons.Rounded.Celebration
}

/**
 * The Home entry point: a card that opens Flow.
 *
 * It sits above the feed rather than inside it, because the mix is the
 * listener's own material and the feed is the catalogue's.
 */
@Composable
fun FlowEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF212E6B), Color(0xFF0D0F24)),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AllInclusive,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.flow_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.flow_entry_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
