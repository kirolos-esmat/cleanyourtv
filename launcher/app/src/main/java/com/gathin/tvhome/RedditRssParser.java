package com.gathin.tvhome;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RedditRssParser {
    private RedditRssParser() {}

    static final class Entry {
        final String title;
        final String author;
        final String imageUrl;
        final String thumbnailUrl;

        Entry(String title, String author, String imageUrl, String thumbnailUrl) {
            this.title = title;
            this.author = author;
            this.imageUrl = imageUrl;
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    private static final Pattern ENTRY_PATTERN = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>");
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("<author>\\s*<name>(.*?)</name>", Pattern.DOTALL);
    private static final Pattern THUMB_PATTERN = Pattern.compile("<media:thumbnail[^>]+url=[\"']([^\"']+)[\"']");
    private static final Pattern REDDIT_IMG_PATTERN = Pattern.compile("(?:href=[\"']|&quot;)(https://i\\.redd\\.it/[^\"'&\\s<>]+)");
    private static final Pattern FALLBACK_IMG_PATTERN = Pattern.compile("(?:href=[\"']|&quot;)(https://[^\"'&\\s<>]+\\.(?:jpg|jpeg|png|webp))", Pattern.CASE_INSENSITIVE);

    static List<Entry> parse(InputStream in) {
        if (in == null) return Collections.emptyList();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parse(sb.toString());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static List<Entry> parse(String xml) {
        if (xml == null || xml.isEmpty()) return Collections.emptyList();
        List<Entry> list = new ArrayList<>();
        Matcher m = ENTRY_PATTERN.matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            if (isNsfw(block)) continue;

            String title = "";
            Matcher tm = TITLE_PATTERN.matcher(block);
            if (tm.find()) title = unescape(tm.group(1).trim());

            String author = "";
            Matcher am = AUTHOR_PATTERN.matcher(block);
            if (am.find()) author = unescape(am.group(1).trim());

            String thumbnailUrl = "";
            Matcher thm = THUMB_PATTERN.matcher(block);
            if (thm.find()) thumbnailUrl = unescape(thm.group(1).trim());

            String imageUrl = "";
            Matcher lm = REDDIT_IMG_PATTERN.matcher(block);
            if (lm.find()) {
                imageUrl = unescape(lm.group(1).trim());
            } else {
                Matcher fim = FALLBACK_IMG_PATTERN.matcher(block);
                if (fim.find()) {
                    imageUrl = unescape(fim.group(1).trim());
                }
            }

            if (!imageUrl.isEmpty()) {
                if (thumbnailUrl.isEmpty()) {
                    thumbnailUrl = imageUrl;
                }
                list.add(new Entry(title, author, imageUrl, thumbnailUrl));
            }
        }
        return list;
    }

    private static boolean isNsfw(String block) {
        String lower = block.toLowerCase(Locale.ROOT);
        return lower.contains("nsfw") || lower.contains("over18") || lower.contains("over 18");
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#32;", " ");
    }
}
