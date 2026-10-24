package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Downloads and caches internet wallpapers on demand into temporary cache storage,
 * keeping internal storage usage minimal (< 1 MB) and automatically managed by Android.
 */
final class OnlineWallpaper {
    private OnlineWallpaper() {}

    static final String CHOICE_BING = "online:bing";
    static final String CHOICE_REDDIT = "online:reddit";
    static final String CHOICE_NATURE = "online:nature";
    static final String CHOICE_AURORA = "online:aurora";
    static final String CHOICE_SPACE = "online:space";
    static final String CHOICE_DARK = "online:dark";
    static final String CHOICE_RETRO = "online:retro";
    static final String CHOICE_HOGWARTS = "online:hogwarts";
    static final String CHOICE_MINIMAL = "online:minimal";

    private static final String PREF_NAME = "tvhome_online_wp";
    private static final long MAX_WALLPAPER_BYTES = 15 * 1024 * 1024L; // 15 MB max
    private static final Random RANDOM = new Random();

    // Reddit Wallpapers RSS feed
    private static final String REDDIT_RSS_URL = "https://www.reddit.com/r/wallpapers/hot.rss?limit=50";
    private static volatile List<RedditRssParser.Entry> cachedRedditEntries = new ArrayList<>();
    private static volatile long cachedRedditTime = 0;

    // Bing Daily 8-day archive
    private static volatile List<String> cachedBingUrls = new ArrayList<>();
    private static volatile long cachedBingTime = 0;

    private static String unsplash(String id) {
        return "https://images.unsplash.com/photo-" + id + "?w=1920&q=80";
    }

    // Curated 1080p/4K Unsplash photo IDs
    static final String[] IDS_NATURE = {
            "1506744038136-46273834b3fb",
            "1469474968028-56623f02e42e",
            "1470071459604-3b5ec3a7fe05",
            "1426604966848-d7adac402bff",
            "1447752875215-b2761acb3c5d"
    };

    static final String[] IDS_AURORA = {
            "1531366936337-7c912a4589a7",
            "1517411032315-54ef2cb783bb",
            "1579033461380-adb47c3eb938",
            "1483347756197-71ef80e95f73",
            "1509198397868-475647b2a1e5"
    };

    static final String[] IDS_SPACE = {
            "1506703719100-a0f3a48c0f86",
            "1462331940025-496dfbfc7564",
            "1451187580459-43490279c0fa",
            "1446776811953-b23d57bd21aa",
            "1447433589675-4aaa569f3e05"
    };

    static final String[] IDS_DARK = {
            "1518709268805-4e9042af9f23",
            "1507499739999-097706ad8914",
            "1518837695005-2083093ee35b",
            "1509114397022-ed747cca3f65",
            "1486406146926-c627a92ad1ab"
    };

    static final String[] IDS_RETRO = {
            "1550745165-9bc0b252726f",
            "1526738549149-8e07eca6c147",
            "1518770660439-4636190af475",
            "1461151304267-38535e780c79",
            "1534423861386-85a16f5d13fd"
    };

    static final String[] IDS_HOGWARTS = {
            "1505832018823-50331d70d237",
            "1661353837637-bcd2402f61c6",
            "1791987376991-8291cf5a2476",
            "1780394736614-01e18b8bf128",
            "1741707596390-2f0c75580ca5"
    };

    static final String[] IDS_MINIMAL = {
            "1552832230-c0197dd311b5",
            "1496368077930-c1e31b4e5b44",
            "1427694012323-fb5e8b0c165b",
            "1428194949883-cafda571f3c4",
            "1486652532407-29437ce3b5e2"
    };

    // Primary cover URLs for thumbnails & backward compatibility
    static final String URL_NATURE = unsplash(IDS_NATURE[0]);
    static final String URL_AURORA = unsplash(IDS_AURORA[0]);
    static final String URL_SPACE = unsplash(IDS_SPACE[0]);
    static final String URL_DARK = unsplash(IDS_DARK[0]);
    static final String URL_RETRO = unsplash(IDS_RETRO[0]);
    static final String URL_HOGWARTS = unsplash(IDS_HOGWARTS[0]);
    static final String URL_MINIMAL = unsplash(IDS_MINIMAL[0]);

    static boolean isOnlineChoice(String choice) {
        return choice != null && (choice.startsWith("online:") || choice.startsWith("http://") || choice.startsWith("https://"));
    }

    static String labelFor(Context ctx, String choice) {
        if (CHOICE_BING.equals(choice)) return ctx.getString(R.string.wp_bing_title);
        if (CHOICE_REDDIT.equals(choice)) return ctx.getString(R.string.wp_reddit_title);
        if (CHOICE_NATURE.equals(choice)) return ctx.getString(R.string.wp_nature_title);
        if (CHOICE_AURORA.equals(choice)) return ctx.getString(R.string.wp_aurora_title);
        if (CHOICE_SPACE.equals(choice)) return ctx.getString(R.string.wp_space_title);
        if (CHOICE_DARK.equals(choice)) return ctx.getString(R.string.wp_dark_title);
        if (CHOICE_RETRO.equals(choice)) return ctx.getString(R.string.wp_retro_title);
        if (CHOICE_HOGWARTS.equals(choice)) return ctx.getString(R.string.wp_hogwarts_title);
        if (CHOICE_MINIMAL.equals(choice)) return ctx.getString(R.string.wp_minimal_title);
        return ctx.getString(R.string.wp_internet_generic);
    }

    private static String getLastUrl(Context ctx, String choice) {
        if (ctx == null) return "";
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString("last_url_" + choice, "");
    }

    private static void setLastUrl(Context ctx, String choice, String url) {
        if (ctx == null || url == null) return;
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString("last_url_" + choice, url).apply();
    }

    private static List<String> getHistory(Context ctx, String choiceKey) {
        if (ctx == null) return new ArrayList<>();
        String raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString("hist_" + choiceKey, "");
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split("\\|")));
    }

    private static void recordHistory(Context ctx, String choiceKey, String item, int maxKeep) {
        if (ctx == null || item == null || item.isEmpty()) return;
        List<String> hist = getHistory(ctx, choiceKey);
        hist.remove(item);
        hist.add(item);
        while (hist.size() > maxKeep) {
            hist.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hist.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(hist.get(i));
        }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString("hist_" + choiceKey, sb.toString()).apply();
    }

    private static String pickDeck(Context ctx, String choiceKey, List<String> pool, boolean forceRandom) {
        if (pool == null || pool.isEmpty()) return "";
        if (pool.size() == 1) return pool.get(0);

        String last = getLastUrl(ctx, choiceKey);
        if (!forceRandom) {
            if (!last.isEmpty() && pool.contains(last)) return last;
            setLastUrl(ctx, choiceKey, pool.get(0));
            return pool.get(0);
        }

        List<String> hist = getHistory(ctx, choiceKey);
        List<String> candidates = new ArrayList<>();
        for (String item : pool) {
            if (!hist.contains(item)) {
                candidates.add(item);
            }
        }

        if (candidates.isEmpty()) {
            hist.clear();
            for (String item : pool) {
                if (!item.equals(last)) {
                    candidates.add(item);
                }
            }
            if (candidates.isEmpty()) {
                candidates.addAll(pool);
            }
        }

        String selected = candidates.get(RANDOM.nextInt(candidates.size()));
        recordHistory(ctx, choiceKey, selected, Math.max(1, pool.size() - 1));
        setLastUrl(ctx, choiceKey, selected);
        return selected;
    }

    static List<RedditRssParser.Entry> fetchRedditWallpapers() {
        long now = System.currentTimeMillis();
        if (!cachedRedditEntries.isEmpty() && (now - cachedRedditTime < 1800000L)) {
            return cachedRedditEntries;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(REDDIT_RSS_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "tvhome/2.5 (Android TV wallpaper)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    List<RedditRssParser.Entry> list = RedditRssParser.parse(in);
                    if (!list.isEmpty()) {
                        cachedRedditEntries = list;
                        cachedRedditTime = now;
                        return list;
                    }
                }
            }
        } catch (Exception e) {
            Log.w("TvHome.OnlineWallpaper", "Failed to fetch Reddit wallpapers: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return cachedRedditEntries;
    }

    static List<String> fetchBingDailyUrls() {
        long now = System.currentTimeMillis();
        if (!cachedBingUrls.isEmpty() && (now - cachedBingTime < 3600000L)) {
            return cachedBingUrls;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "tvhome/2.5 (Android TV)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (InputStream in = conn.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        if (sb.length() > 256 * 1024) break;
                    }
                }
                JSONObject json = new JSONObject(sb.toString());
                JSONArray images = json.getJSONArray("images");
                List<String> list = new ArrayList<>();
                for (int i = 0; i < images.length(); i++) {
                    String imgUrl = images.getJSONObject(i).getString("url");
                    if (imgUrl != null && !imgUrl.isEmpty()) {
                        list.add("https://www.bing.com" + imgUrl);
                    }
                }
                if (!list.isEmpty()) {
                    cachedBingUrls = list;
                    cachedBingTime = now;
                    return list;
                }
            }
        } catch (Exception e) {
            Log.w("TvHome.OnlineWallpaper", "Failed to fetch Bing wallpapers: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return cachedBingUrls;
    }

    static String resolveUrl(String choice) {
        return resolveUrl(null, choice, false);
    }

    static String resolveUrl(String choice, boolean forceRandom) {
        return resolveUrl(null, choice, forceRandom);
    }

    static String resolveUrl(Context ctx, String choice, boolean forceRandom) {
        if (choice == null) choice = CHOICE_BING;

        if (CHOICE_REDDIT.equals(choice)) {
            List<RedditRssParser.Entry> entries = fetchRedditWallpapers();
            List<String> urls = new ArrayList<>();
            for (RedditRssParser.Entry e : entries) {
                if (e.imageUrl != null && !e.imageUrl.isEmpty()) urls.add(e.imageUrl);
            }
            if (!urls.isEmpty()) {
                return pickDeck(ctx, CHOICE_REDDIT, urls, forceRandom);
            }
            return unsplash(pickDeck(ctx, CHOICE_NATURE, Arrays.asList(IDS_NATURE), forceRandom));
        }

        if (CHOICE_BING.equals(choice)) {
            List<String> bingList = fetchBingDailyUrls();
            if (!bingList.isEmpty()) {
                return pickDeck(ctx, CHOICE_BING, bingList, forceRandom);
            }
            return unsplash(pickDeck(ctx, CHOICE_NATURE, Arrays.asList(IDS_NATURE), forceRandom));
        }

        if (CHOICE_NATURE.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_NATURE, Arrays.asList(IDS_NATURE), forceRandom));
        }

        if (CHOICE_AURORA.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_AURORA, Arrays.asList(IDS_AURORA), forceRandom));
        }

        if (CHOICE_SPACE.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_SPACE, Arrays.asList(IDS_SPACE), forceRandom));
        }

        if (CHOICE_DARK.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_DARK, Arrays.asList(IDS_DARK), forceRandom));
        }

        if (CHOICE_RETRO.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_RETRO, Arrays.asList(IDS_RETRO), forceRandom));
        }

        if (CHOICE_HOGWARTS.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_HOGWARTS, Arrays.asList(IDS_HOGWARTS), forceRandom));
        }

        if (CHOICE_MINIMAL.equals(choice)) {
            return unsplash(pickDeck(ctx, CHOICE_MINIMAL, Arrays.asList(IDS_MINIMAL), forceRandom));
        }

        if (choice.startsWith("http://") || choice.startsWith("https://")) {
            return choice;
        }

        return unsplash(IDS_NATURE[0]);
    }

    static File getCacheFile(Context ctx) {
        return new File(ctx.getCacheDir(), "online_wallpaper.jpg");
    }

    static File getNextCacheFile(Context ctx) {
        return new File(ctx.getCacheDir(), "online_wallpaper_next.jpg");
    }

    static void clearCache(Context ctx) {
        File c1 = getCacheFile(ctx);
        if (c1.exists()) c1.delete();
        File c2 = getNextCacheFile(ctx);
        if (c2.exists()) c2.delete();
        File thumbs = new File(ctx.getCacheDir(), "wp_thumbs");
        if (thumbs.exists() && thumbs.isDirectory()) {
            File[] files = thumbs.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            thumbs.delete();
        }
    }

    static File downloadWallpaper(Context ctx, String choice) {
        return downloadWallpaper(ctx, choice, false);
    }

    static File downloadWallpaper(Context ctx, String choice, boolean forceRefresh) {
        File target = getCacheFile(ctx);
        boolean isBing = choice == null || CHOICE_BING.equals(choice);
        long now = System.currentTimeMillis();

        if (!forceRefresh && target.exists() && target.length() > 0) {
            String cachedChoice = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString("cached_choice", "");
            if (choice != null && choice.equals(cachedChoice)) {
                if (isBing) {
                    long lastModified = target.lastModified();
                    if (now - lastModified < 12 * 3600_000L) {
                        return target;
                    }
                } else {
                    return target;
                }
            }
        }

        String downloadUrl = resolveUrl(ctx, choice, forceRefresh);
        if (downloadUrl == null || downloadUrl.isEmpty()) return target.exists() ? target : null;

        File tempFile = new File(ctx.getCacheDir(), "online_wallpaper.tmp");
        HttpURLConnection conn = null;
        try {
            URL url = new URL(downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "tvhome/2.5 (Android TV wallpaper)");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() == 200) {
                long contentLength = conn.getContentLengthLong();
                if (contentLength > MAX_WALLPAPER_BYTES) {
                    Log.w("TvHome.OnlineWallpaper", "Wallpaper too large: " + contentLength);
                    return target.exists() ? target : null;
                }
                String contentType = conn.getContentType();
                if (contentType != null) {
                    String lowerType = contentType.toLowerCase(java.util.Locale.ROOT);
                    if (!lowerType.startsWith("image/") && !lowerType.startsWith("application/octet-stream")) {
                        Log.w("TvHome.OnlineWallpaper", "Unexpected content type: " + contentType);
                        return target.exists() ? target : null;
                    }
                }
                long totalRead = 0;
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        totalRead += len;
                        if (totalRead > MAX_WALLPAPER_BYTES) {
                            Log.w("TvHome.OnlineWallpaper", "Exceeded max bytes during download");
                            return target.exists() ? target : null;
                        }
                        out.write(buffer, 0, len);
                    }
                }
                if (tempFile.exists() && tempFile.length() > 1000) {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(tempFile.getAbsolutePath(), opts);
                    if (opts.outWidth >= 100 && opts.outHeight >= 100 && opts.outWidth <= 4096 && opts.outHeight <= 4096) {
                        if (target.exists()) target.delete();
                        tempFile.renameTo(target);
                        target.setLastModified(now);
                        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString("cached_choice", choice).apply();
                        return target;
                    } else {
                        Log.w("TvHome.OnlineWallpaper", "Invalid bitmap bounds: " + opts.outWidth + "x" + opts.outHeight);
                    }
                }
            }
        } catch (Exception e) {
            Log.w("TvHome.OnlineWallpaper", "Download failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
            if (tempFile.exists()) tempFile.delete();
        }
        return target.exists() ? target : null;
    }
}
