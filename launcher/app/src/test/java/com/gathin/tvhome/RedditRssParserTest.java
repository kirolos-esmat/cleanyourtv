package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class RedditRssParserTest {

    @Test
    public void parsesEntryWithThumbnailAndDirectImage() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\" xmlns:media=\"http://search.yahoo.com/mrss/\">\n"
                + "  <entry>\n"
                + "    <title>Mountain Sunset [3840x2160] &amp; Clouds</title>\n"
                + "    <author><name>/u/nature_photographer</name></author>\n"
                + "    <media:thumbnail url=\"https://preview.redd.it/thumb.jpg?width=640&amp;auto=webp\"/>\n"
                + "    <content type=\"html\">&lt;a href=&quot;https://i.redd.it/sunset123.jpg&quot;&gt;[link]&lt;/a&gt;</content>\n"
                + "  </entry>\n"
                + "</feed>";

        List<RedditRssParser.Entry> entries = RedditRssParser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, entries.size());
        RedditRssParser.Entry entry = entries.get(0);
        assertEquals("Mountain Sunset [3840x2160] & Clouds", entry.title);
        assertEquals("/u/nature_photographer", entry.author);
        assertEquals("https://i.redd.it/sunset123.jpg", entry.imageUrl);
        assertEquals("https://preview.redd.it/thumb.jpg?width=640&auto=webp", entry.thumbnailUrl);
    }

    @Test
    public void skipsNsfwTaggedEntries() {
        String xml = "<feed>\n"
                + "  <entry>\n"
                + "    <title>[NSFW] Risque wallpaper</title>\n"
                + "    <media:thumbnail url=\"https://preview.redd.it/thumb1.jpg\"/>\n"
                + "    <content type=\"html\">&lt;a href=&quot;https://i.redd.it/pic1.jpg&quot;&gt;[link]&lt;/a&gt;</content>\n"
                + "  </entry>\n"
                + "  <entry>\n"
                + "    <title>Safe Ocean View</title>\n"
                + "    <media:thumbnail url=\"https://preview.redd.it/thumb2.jpg\"/>\n"
                + "    <content type=\"html\">&lt;a href=&quot;https://i.redd.it/pic2.jpg&quot;&gt;[link]&lt;/a&gt;</content>\n"
                + "  </entry>\n"
                + "</feed>";

        List<RedditRssParser.Entry> entries = RedditRssParser.parse(xml);
        assertEquals(1, entries.size());
        assertEquals("Safe Ocean View", entries.get(0).title);
    }

    @Test
    public void skipsPostsWithoutImageLinks() {
        String xml = "<feed>\n"
                + "  <entry>\n"
                + "    <title>Announcement: Mod Policy Update</title>\n"
                + "    <author><name>/u/moderator</name></author>\n"
                + "    <content type=\"html\">Please read our text announcement here.</content>\n"
                + "  </entry>\n"
                + "</feed>";

        List<RedditRssParser.Entry> entries = RedditRssParser.parse(xml);
        assertTrue(entries.isEmpty());
    }

    @Test
    public void handlesEmptyOrMalformedXmlGracefully() {
        assertTrue(RedditRssParser.parse("").isEmpty());
        assertTrue(RedditRssParser.parse((String) null).isEmpty());
        assertTrue(RedditRssParser.parse("<corrupted>xml without entries").isEmpty());
    }
}
