package com.music.auris.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.auris.data.model.HomeShelf
import com.music.auris.data.model.ShelfItem
import com.music.auris.data.model.Song
import com.music.auris.data.model.UiState
import com.music.auris.data.podcast.ItunesPodcasts
import com.music.auris.data.podcast.ItunesShow
import com.music.auris.sharedui.resources.Res
import com.music.auris.sharedui.resources.podcast_episodes_empty
import com.music.auris.sharedui.resources.podcast_episodes_loading
import com.music.auris.sharedui.resources.podcast_search_empty
import com.music.auris.sharedui.resources.podcasts
import com.music.auris.sharedui.resources.podcasts_empty
import com.music.auris.sharedui.resources.retry
import com.music.auris.sharedui.resources.search_podcasts
import com.music.auris.ui.components.MessageState
import com.music.auris.ui.components.PAGE_GUTTER
import com.music.auris.ui.components.PillTextField
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The podcasts page.
 *
 * The catalogue behind it is Apple's, not YouTube Music's: the search API is
 * key-less, it names every show's RSS feed, and it does not care whether the
 * listener is signed in. YouTube Music's own podcast pages collapse to the
 * generic Home page for an anonymous client and are empty for a signed-in
 * one that follows nothing, which is no page to ship.
 *
 * The shelves are one per category, each searched for on its own and drawn as
 * soon as its own answer lands — twelve searches run side by side, and a page
 * that waited for all of them before drawing anything read as a page with
 * nothing on it.
 */
@Composable
fun PodcastsScreen(
    shelves: UiState<List<HomeShelf>>,
    onShowClick: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** The big "Podcasts" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
    /**
     * The page's own search box.
     *
     * The main search covers songs and has no podcast category, so podcast
     * search lives here rather than there.
     */
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    /** What the search found; the shelves step aside while a query is active. */
    searchResults: UiState<List<ShelfItem>> = UiState.Success(emptyList()),
    onSearchResultClick: (ShelfItem) -> Unit = onShowClick,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (showTitle) {
            item(key = "title") {
                Text(
                    text = stringResource(Res.string.podcasts),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(
                        start = PAGE_GUTTER,
                        end = PAGE_GUTTER,
                        top = 8.dp,
                        bottom = 14.dp,
                    ),
                )
            }
        }
        item(key = "search") {
            PillTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(Res.string.search_podcasts),
                modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = 14.dp),
            )
        }
        if (searchQuery.isNotBlank()) {
            when (val results = searchResults) {
                UiState.Loading -> item(key = "search-loading") {
                    MessageState(stringResource(Res.string.podcast_episodes_loading))
                }
                is UiState.Error -> item(key = "search-error") {
                    MessageState(
                        results.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = { onSearchQueryChange(searchQuery) },
                    )
                }
                is UiState.Success -> {
                    val hits = results.data.distinctBy { it.browseId ?: it.title }
                    if (hits.isEmpty()) {
                        item(key = "search-empty") {
                            MessageState(stringResource(Res.string.podcast_search_empty))
                        }
                    } else {
                        item(key = "search-results") {
                            Shelf(
                                shelf = HomeShelf(stringResource(Res.string.podcasts), hits),
                                onItemClick = onSearchResultClick,
                            )
                        }
                    }
                }
            }
        } else {
            when (val state = shelves) {
                UiState.Loading -> item(key = "loading") {
                    MessageState(stringResource(Res.string.podcast_episodes_loading))
                }
                is UiState.Error -> item(key = "error") {
                    MessageState(
                        state.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = {},
                    )
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        item(key = "empty") { MessageState(stringResource(Res.string.podcasts_empty)) }
                    } else {
                        items(state.data.size, key = { "shelf:${state.data[it].title}" }) { index ->
                            Shelf(shelf = state.data[index], onItemClick = onShowClick)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A show's own page: its cover, its name, and the episodes it has published.
 *
 * The episode audio comes straight from the show's RSS enclosure, so the rows
 * are the feed's own items and playing one streams it — no download ahead of
 * time, no catalogue copy of the episode to go stale.
 */
@Composable
fun PodcastShowScreen(
    show: ItunesShow?,
    episodes: UiState<List<Song>>,
    onPlay: (List<Song>, Int) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(contentPadding)) {
        if (show != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            ) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    show.artworkUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = show.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (show.artist.isNotBlank()) {
                        Text(
                            text = show.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    show.genre?.takeIf { it.isNotBlank() }?.let { genre ->
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        when (val state = episodes) {
            UiState.Loading -> MessageState(stringResource(Res.string.podcast_episodes_loading))
            is UiState.Error -> MessageState(
                state.message,
                actionLabel = stringResource(Res.string.retry),
                onAction = onRetry,
            )
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    MessageState(stringResource(Res.string.podcast_episodes_empty))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.data, key = { _, song -> song.videoId }) { index, song ->
                            val meta = listOfNotNull(song.publishedDate, song.durationText).joinToString(" · ")
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = PAGE_GUTTER, vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .clickable { onPlay(state.data, index) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (meta.isNotBlank()) {
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The page's shelves: one per category, searched for on its own.
 *
 * The searches run side by side and each shelf is published the moment its
 * own answer lands, in the order the shelves are declared rather than in the
 * order the answers happen to arrive.
 */
suspend fun fetchPodcastShelves(onShelves: (List<HomeShelf>) -> Unit) {
    val searches = buildList {
        add("Recommended" to "podcast")
        ItunesPodcasts.CATEGORIES.forEach { (label, term) -> add(label to "$term podcast") }
    }
    val shelves = LinkedHashMap<String, List<ItunesShow>>()
    val lock = Mutex()
    coroutineScope {
        searches.forEach { (label, term) ->
            launch(Dispatchers.Default) {
                val limit = if (label == "Recommended") 30 else 20
                val shows = runCatching {
                    withContext(Dispatchers.Default) { ItunesPodcasts.search(term, limit = limit) }
                }.getOrDefault(emptyList())
                if (shows.isEmpty()) return@launch
                lock.withLock {
                    shelves[label] = shows
                    onShelves(
                        searches.mapNotNull { (name, _) ->
                            shelves[name]?.let { found -> HomeShelf(name, found.map { it.toShelfItem() }) }
                        },
                    )
                }
            }
        }
    }
}

/** A show from Apple's catalogue, as a card on the podcasts page. */
fun ItunesShow.toShelfItem(): ShelfItem = ShelfItem(
    title = name,
    subtitle = artist,
    thumbnailUrl = artworkUrl,
    videoId = null,
    browseId = "itunes:$collectionId",
)

/** The collection id behind a show card's browse id. */
fun podcastCollectionId(browseId: String?): String? =
    browseId?.takeIf { it.startsWith("itunes:") }?.removePrefix("itunes:")
