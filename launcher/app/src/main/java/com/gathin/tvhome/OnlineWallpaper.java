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

    private static final String PREF_NAME = "tvhome_online_wp";
    private static final Random RANDOM = new Random();

    // Reddit Wallpapers RSS feed
    private static final String REDDIT_RSS_URL = "https://www.reddit.com/r/wallpapers/hot.rss?limit=50";
    private static volatile List<RedditRssParser.Entry> cachedRedditEntries = new ArrayList<>();
    private static volatile long cachedRedditTime = 0;

    // Bing Daily 8-day archive
    private static volatile List<String> cachedBingUrls = new ArrayList<>();
    private static volatile long cachedBingTime = 0;

    // Curated 1080p/4K Unsplash pools - completely unique across sources
    static final String[] POOL_NATURE = {
            "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=1920&q=80",
            "https://images.unsplash.com/photo-1469474968028-56623f02e42e?w=1920&q=80",
            "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?w=1920&q=80",
            "https://images.unsplash.com/photo-1426604966848-d7adac402bff?w=1920&q=80",
            "https://images.unsplash.com/photo-1447752875215-b2761acb3c5d?w=1920&q=80",
            "https://images.unsplash.com/photo-1472214103451-9374bd1c798e?w=1920&q=80",
            "https://images.unsplash.com/photo-1501785888041-af3ef285b470?w=1920&q=80",
            "https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=1920&q=80",
            "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1920&q=80",
            "https://images.unsplash.com/photo-1433086966358-54859d0ed716?w=1920&q=80",
            "https://images.unsplash.com/photo-1542332213-31f87348057f?w=1920&q=80",
            "https://images.unsplash.com/photo-1473448912268-2022ce9509d8?w=1920&q=80",
            "https://images.unsplash.com/photo-1519681393784-d120267933ba?w=1920&q=80",
            "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=1920&q=80",
            "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?w=1920&q=80",
            "https://images.unsplash.com/photo-1505765050516-f72dcac9c60e?w=1920&q=80",
            "https://images.unsplash.com/photo-1497436072909-60f360e1d4b1?w=1920&q=80",
            "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=1920&q=80"
    };

    static final String[] POOL_AURORA = {
            "https://images.unsplash.com/photo-1531366936337-7c912a4589a7?w=1920&q=80",
            "https://images.unsplash.com/photo-1517411032315-54ef2cb783bb?w=1920&q=80",
            "https://images.unsplash.com/photo-1579033461380-adb47c3eb938?w=1920&q=80",
            "https://images.unsplash.com/photo-1483347756197-71ef80e95f73?w=1920&q=80",
            "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=1920&q=80",
            "https://images.unsplash.com/photo-1517999144091-3d9dca6d1e43?w=1920&q=80",
            "https://images.unsplash.com/photo-1516339901601-2e1b62dc0c45?w=1920&q=80",
            "https://images.unsplash.com/photo-1529963183134-61a90db47eaf?w=1920&q=80",
            "https://images.unsplash.com/photo-1544947950-fa07a98d237f?w=1920&q=80",
            "https://images.unsplash.com/photo-1532274402911-5a369e4c4bb5?w=1920&q=80",
            "https://images.unsplash.com/photo-1513836279014-a89f7a76ae86?w=1920&q=80",
            "https://images.unsplash.com/photo-1520034475321-cbe63696469a?w=1920&q=80",
            "https://images.unsplash.com/photo-1528722828814-77b9b83aafb2?w=1920&q=80",
            "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=1920&q=80",
            "https://images.unsplash.com/photo-1508739773434-c26b3d09e071?w=1920&q=80"
    };

    static final String[] POOL_SPACE = {
            "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?w=1920&q=80",
            "https://images.unsplash.com/photo-1462331940025-496dfbfc7564?w=1920&q=80",
            "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=1920&q=80",
            "https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?w=1920&q=80",
            "https://images.unsplash.com/photo-1502134249126-9f3755a50d78?w=1920&q=80",
            "https://images.unsplash.com/photo-1447433589675-4aaa569f3e05?w=1920&q=80",
            "https://images.unsplash.com/photo-1543722530-d2c3201371e7?w=1920&q=80",
            "https://images.unsplash.com/photo-1506443432602-ac2fcd6f54e0?w=1920&q=80",
            "https://images.unsplash.com/photo-1446776877081-d282a0f896e2?w=1920&q=80",
            "https://images.unsplash.com/photo-1538370965046-79c0d6907d47?w=1920&q=80",
            "https://images.unsplash.com/photo-1519810755548-39cd217da494?w=1920&q=80",
            "https://images.unsplash.com/photo-1464802686167-b939a6910659?w=1920&q=80"
    };

    static final String[] POOL_DARK = {
            "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1920&q=80",
            "https://images.unsplash.com/photo-1507499739999-097706ad8914?w=1920&q=80",
            "https://images.unsplash.com/photo-1518837695005-2083093ee35b?w=1920&q=80",
            "https://images.unsplash.com/photo-1509114397022-ed747cca3f65?w=1920&q=80",
            "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=1920&q=80",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?w=1920&q=80",
            "https://images.unsplash.com/photo-1492691527719-9d1e07e534b4?w=1920&q=80",
            "https://images.unsplash.com/photo-1513694203232-719a280e022f?w=1920&q=80",
            "https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=1920&q=80",
            "https://images.unsplash.com/photo-1511447333015-45b65e60f6d5?w=1920&q=80",
            "https://images.unsplash.com/photo-1519751138087-5bf79df62d5b?w=1920&q=80",
            "https://images.unsplash.com/photo-1509023464722-18d996393ca8?w=1920&q=80"
    };

    // Primary cover URLs for thumbnails & backward compatibility
    static final String URL_NATURE = POOL_NATURE[0];
    static final String URL_AURORA = POOL_AURORA[0];
    static final String URL_SPACE = POOL_SPACE[0];
    static final String URL_DARK = POOL_DARK[0];

    static boolean isOnlineChoice(String choice) {
        return choice != null && (choice.startsWith("online:") || choice.startsWith("http://") || choice.startsWith("https://"));
    }

    static String labelFor(Context ctx, String choice) {
        if (CHOICE_BING.equals(choice)) return "Daily Bing (Internet)";
        if (CHOICE_REDDIT.equals(choice)) return "Reddit Wallpapers (r/wallpapers)";
        if (CHOICE_NATURE.equals(choice)) return "Nature Gallery (Internet)";
        if (CHOICE_AURORA.equals(choice)) return "Aurora Gallery (Internet)";
        if (CHOICE_SPACE.equals(choice)) return "Deep Space Gallery (Internet)";
        if (CHOICE_DARK.equals(choice)) return "Minimal Dark Gallery (Internet)";
        return "Internet Wallpaper";
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

    private static String pickRandom(List<String> list, String previous) {
        if (list == null || list.isEmpty()) return "";
        if (list.size() == 1) return list.get(0);
        List<String> candidates = new ArrayList<>();
        for (String item : list) {
            if (!item.equals(previous)) candidates.add(item);
        }
        if (candidates.isEmpty()) candidates.addAll(list);
        return candidates.get(RANDOM.nextInt(candidates.size()));
    }

    private static String selectFromPool(Context ctx, String choiceKey, String[] pool, boolean forceRandom) {
        if (pool == null || pool.length == 0) return "";
        String last = getLastUrl(ctx, choiceKey);
        String selected;
        if (forceRandom) {
            List<String> candidates = new ArrayList<>();
            for (String u : pool) {
                if (!u.equals(last)) candidates.add(u);
            }
            if (candidates.isEmpty()) candidates.addAll(Arrays.asList(pool));
            selected = candidates.get(RANDOM.nextInt(candidates.size()));
        } else {
            selected = (!last.isEmpty() && Arrays.asList(pool).contains(last)) ? last : pool[0];
        }
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
                    while ((line = reader.readLine()) != null) sb.append(line);
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
            if (entries.isEmpty()) return POOL_NATURE[0];
            String last = getLastUrl(ctx, CHOICE_REDDIT);
            List<String> urls = new ArrayList<>();
            for (RedditRssParser.Entry e : entries) {
                if (e.imageUrl != null && !e.imageUrl.isEmpty()) urls.add(e.imageUrl);
            }
            if (urls.isEmpty()) return POOL_NATURE[0];
            String selected;
            if (forceRandom) {
                selected = pickRandom(urls, last);
            } else {
                selected = (!last.isEmpty() && urls.contains(last)) ? last : urls.get(0);
            }
            setLastUrl(ctx, CHOICE_REDDIT, selected);
            return selected;
        }

        if (CHOICE_BING.equals(choice)) {
            List<String> bingList = fetchBingDailyUrls();
            if (bingList.isEmpty()) return POOL_NATURE[0];
            String last = getLastUrl(ctx, CHOICE_BING);
            String selected;
            if (forceRandom) {
                selected = pickRandom(bingList, last);
            } else {
                selected = (!last.isEmpty() && bingList.contains(last)) ? last : bingList.get(0);
            }
            setLastUrl(ctx, CHOICE_BING, selected);
            return selected;
        }

        if (CHOICE_NATURE.equals(choice)) {
            return selectFromPool(ctx, CHOICE_NATURE, POOL_NATURE, forceRandom);
        }

        if (CHOICE_AURORA.equals(choice)) {
            return selectFromPool(ctx, CHOICE_AURORA, POOL_AURORA, forceRandom);
        }

        if (CHOICE_SPACE.equals(choice)) {
            return selectFromPool(ctx, CHOICE_SPACE, POOL_SPACE, forceRandom);
        }

        if (CHOICE_DARK.equals(choice)) {
            return selectFromPool(ctx, CHOICE_DARK, POOL_DARK, forceRandom);
        }

        if (choice.startsWith("http://") || choice.startsWith("https://")) {
            return choice;
        }

        return POOL_NATURE[0];
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
        try {
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "tvhome/2.5 (Android TV wallpaper)");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
                if (tempFile.exists() && tempFile.length() > 1000) {
                    if (target.exists()) target.delete();
                    tempFile.renameTo(target);
                    target.setLastModified(now);
                    ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putString("cached_choice", choice).apply();
                    return target;
                }
            }
        } catch (Exception e) {
            Log.w("TvHome.OnlineWallpaper", "Download failed: " + e.getMessage());
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
        return target.exists() ? target : null;
    }
}
