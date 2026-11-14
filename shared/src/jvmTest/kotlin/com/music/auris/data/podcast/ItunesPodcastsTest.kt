package com.music.auris.data.podcast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The podcast feed reader.
 *
 * Feeds are untrusted XML with entity-escaped titles, CDATA, namespace
 * prefixes and dates in several shapes; these are the real ones, checked to
 * make sure every episode comes back, in the right order, and with no limit.
 */
class ItunesPodcastsTest {

    private val show = ItunesShow(
        collectionId = "1",
        name = "Test Show",
        artist = "Someone",
        artworkUrl = "https://example.com/art.jpg",
        feedUrl = "https://example.com/feed.xml",
        genre = "News",
        trackCount = 0,
    )

    @Test
    fun rssFeedYieldsEveryEpisodeNewestFirstWithEntitiesDecoded() {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Test Show</title>
                <item>
                  <title>Ep 1 & the Beginning</title>
                  <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>1800</itunes:duration>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg" length="1"/>
                </item>
                <item>
                  <title><![CDATA[Ep 3 <live>]]></title>
                  <pubDate>Wed, 03 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>1:02:03</itunes:duration>
                  <enclosure url="https://cdn.example.com/3.mp3" type="audio/mpeg" length="1"/>
                </item>
                <item>
                  <title>Ep 2</title>
                  <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>25:00</itunes:duration>
                  <enclosure url="https://cdn.example.com/2.m4a" type="audio/x-m4a" length="1"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = ItunesPodcasts.parseEpisodes(feed, show)

        assertEquals(3, episodes.size)
        // Newest first, regardless of the order the feed listed them in.
        assertEquals(listOf("Ep 3 <live>", "Ep 2", "Ep 1 & the Beginning"), episodes.map { it.title })
        assertEquals("30:00", episodes.first { it.title == "Ep 1 & the Beginning" }.durationText)
        assertEquals("1:02:03", episodes.first { it.title == "Ep 3 <live>" }.durationText)
        assertEquals("25:00", episodes.first { it.title == "Ep 2" }.durationText)
        assertEquals("Test Show", episodes.first().artist)
        assertTrue(episodes.all { it.videoId.startsWith("https://cdn.example.com/") })
    }

    @Test
    fun theFeedDateIsKeptOnTheRow() {
        val feed = """
            <rss version="2.0"><channel>
              <item>
                <title>Ep 1</title>
                <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
                <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()

        val episode = ItunesPodcasts.parseEpisodes(feed, show).single()
        // The source's own wording, not a reformatted date.
        assertEquals("Mon, 01 Jan 2024 10:00:00 +0000", episode.publishedDate)
    }

    @Test
    fun nonAudioEnclosuresAreSkipped() {
        val feed = """
            <rss version="2.0"><channel>
              <item>
                <title>Notes PDF</title>
                <enclosure url="https://cdn.example.com/notes.pdf" type="application/pdf"/>
              </item>
              <item>
                <title>Real episode</title>
                <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
                <enclosure url="https://cdn.example.com/real.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()

        assertEquals(listOf("Real episode"), ItunesPodcasts.parseEpisodes(feed, show).map { it.title })
    }

    @Test
    fun atomFeedYieldsEpisodes() {
        val feed = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Test Show</title>
              <entry>
                <title>Atom Ep 1</title>
                <published>2024-01-01T10:00:00Z</published>
                <link rel="enclosure" type="audio/mpeg" href="https://cdn.example.com/a1.mp3"/>
              </entry>
              <entry>
                <title>Atom Ep 2</title>
                <published>2024-01-02T10:00:00Z</published>
                <link rel="enclosure" type="audio/mpeg" href="https://cdn.example.com/a2.mp3"/>
              </entry>
            </feed>
        """.trimIndent()

        assertEquals(listOf("Atom Ep 2", "Atom Ep 1"), ItunesPodcasts.parseEpisodes(feed, show).map { it.title })
    }

    @Test
    fun noLimitLongFeedComesBackWhole() {
        val items = buildString {
            append("<rss version=\"2.0\"><channel>")
            repeat(420) { i ->
                append("<item><title>Ep $i</title>")
                append("<pubDate>Mon, 01 Jan 2024 ${(i % 24).toString().padStart(2, '0')}:00:00 +0000</pubDate>")
                append("<enclosure url=\"https://cdn.example.com/$i.mp3\" type=\"audio/mpeg\"/></item>")
            }
            append("</channel></rss>")
        }
        assertEquals(420, ItunesPodcasts.parseEpisodes(items, show).size)
    }

    @Test
    fun feedWithNoDatesKeepsItsOwnOrder() {
        val feed = """
            <rss version="2.0"><channel>
              <item><title>First</title><enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/></item>
              <item><title>Second</title><enclosure url="https://cdn.example.com/b.mp3" type="audio/mpeg"/></item>
            </channel></rss>
        """.trimIndent()
        assertEquals(listOf("First", "Second"), ItunesPodcasts.parseEpisodes(feed, show).map { it.title })
    }

    @Test
    fun aFeedWithCommentsAndADoctypeStillReads() {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE rss [ <!ENTITY x "y"> ]>
            <rss version="2.0">
              <!-- a channel comment -->
              <channel>
                <item>
                  <title>Ep &#8212; one</title>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = ItunesPodcasts.parseEpisodes(feed, show)
        assertEquals(1, episodes.size)
        assertEquals("Ep — one", episodes.single().title)
    }
}

/** The hand-written XML reader underneath the feed reader. */
class PodcastFeedXmlTest {

    @Test
    fun elementsAttributesAndTextAreRead() {
        val root = PodcastFeedXml.parse(
            """<feed><entry id="7"><title>Hello</title></entry></feed>""",
        )
        assertNotNull(root)
        assertEquals("feed", root.name)
        val entry = root.child("entry")
        assertNotNull(entry)
        assertEquals("7", entry.attributes["id"])
        assertEquals("Hello", entry.childText("title"))
    }

    @Test
    fun cdataIsTakenLiterally() {
        val root = PodcastFeedXml.parse("""<t><![CDATA[a < b & c]]></t>""")
        assertEquals("a < b & c", root?.text)
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals(
            "a & b < c > d \" e ' f",
            PodcastFeedXml.decode("a & b < c > d \" e &apos; f"),
        )
        // A numeric reference, including one above the BMP.
        assertEquals("—", PodcastFeedXml.decode("&#8212;"))
        assertEquals("—", PodcastFeedXml.decode("&#x2014;"))
        assertEquals("🎧", PodcastFeedXml.decode("&#x1F3A7;"))
        // An unknown entity is left as it was found rather than swallowed.
        assertEquals("&nbsp;", PodcastFeedXml.decode("&nbsp;"))
    }

    @Test
    fun selfClosingElementsHaveNoChildren() {
        val root = PodcastFeedXml.parse("""<rss><item/><item/></rss>""")
        assertEquals(2, root?.children?.size)
        assertEquals(0, root?.children?.first()?.children?.size)
    }

    @Test
    fun commentsAndProcessingInstructionsAreSkipped() {
        val root = PodcastFeedXml.parse(
            """<?xml version="1.0"?><!-- c --><rss><!-- d --><channel/></rss>""",
        )
        assertEquals("rss", root?.name)
        assertEquals(1, root?.children?.size)
    }
}

/** The date parser, on the shapes feeds really use. */
class PodcastDateTest {

    @Test
    fun rfc1123DatesParse() {
        // 2024-01-01T10:00:00Z is 1704103200000.
        assertEquals(1704103200000L, ItunesPodcasts.parseEpochMs("Mon, 01 Jan 2024 10:00:00 +0000"))
    }

    @Test
    fun isoDatesParse() {
        assertEquals(1704103200000L, ItunesPodcasts.parseEpochMs("2024-01-01T10:00:00Z"))
        assertEquals(1704103200000L, ItunesPodcasts.parseEpochMs("2024-01-01T10:00:00"))
    }

    @Test
    fun anOffsetIsHonoured() {
        // 10:00 at +02:00 is 08:00 UTC.
        assertEquals(1704096000000L, ItunesPodcasts.parseEpochMs("2024-01-01T10:00:00+02:00"))
        assertEquals(1704096000000L, ItunesPodcasts.parseEpochMs("2024-01-01T10:00:00+0200"))
        assertEquals(1704103200000L, ItunesPodcasts.parseEpochMs("Mon, 01 Jan 2024 10:00:00 +0000"))
        assertEquals(1704103200000L, ItunesPodcasts.parseEpochMs("Mon, 01 Jan 2024 12:00:00 +0200"))
    }

    @Test
    fun anUnreadableDateIsNullRatherThanWrong() {
        assertNull(ItunesPodcasts.parseEpochMs(""))
        assertNull(ItunesPodcasts.parseEpochMs("last Tuesday"))
        // A date on its own is a real feed shape: midnight UTC.
        assertEquals(1704067200000L, ItunesPodcasts.parseEpochMs("2024-01-01"))
    }
}
