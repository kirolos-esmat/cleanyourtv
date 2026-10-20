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

/**
 * Downloads and caches internet wallpapers on demand into temporary cache storage,
 * keeping internal storage usage minimal (< 1 MB) and automatically managed by Android.
 */
final class OnlineWallpaper {
    private OnlineWallpaper() {}

    static final String CHOICE_BING = "online:bing";
    static final String CHOICE_NATURE = "online:nature";
    static final String CHOICE_AURORA = "online:aurora";
    static final String CHOICE_SPACE = "online:space";
    static final String CHOICE_DARK = "online:dark";

    static final String URL_NATURE = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=1920&q=80";
    static final String URL_AURORA = "https://images.unsplash.com/photo-1531366936337-7c912a4589a7?w=1920&q=80";
    static final String URL_SPACE = "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?w=1920&q=80";
    static final String URL_DARK = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=1920&q=80";

    static boolean isOnlineChoice(String choice) {
        return choice != null && (choice.startsWith("online:") || choice.startsWith("http://") || choice.startsWith("https://"));
    }

    static String labelFor(Context ctx, String choice) {
        if (CHOICE_BING.equals(choice)) return "Daily Bing (Internet)";
        if (CHOICE_NATURE.equals(choice)) return "Nature (Internet)";
        if (CHOICE_AURORA.equals(choice)) return "Aurora (Internet)";
        if (CHOICE_SPACE.equals(choice)) return "Deep Space (Internet)";
        if (CHOICE_DARK.equals(choice)) return "Minimal Dark (Internet)";
        return "Internet Wallpaper";
    }

    static String resolveUrl(String choice) {
        if (CHOICE_NATURE.equals(choice)) return URL_NATURE;
        if (CHOICE_AURORA.equals(choice)) return URL_AURORA;
        if (CHOICE_SPACE.equals(choice)) return URL_SPACE;
        if (CHOICE_DARK.equals(choice)) return URL_DARK;
        if (choice != null && (choice.startsWith("http://") || choice.startsWith("https://"))) return choice;
        return fetchBingDailyUrl();
    }

    private static String fetchBingDailyUrl() {
        try {
            URL url = new URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject json = new JSONObject(sb.toString());
                JSONArray images = json.getJSONArray("images");
                if (images.length() > 0) {
                    String imgUrl = images.getJSONObject(0).getString("url");
                    return "https://www.bing.com" + imgUrl;
                }
            }
        } catch (Exception e) {
            Log.w("TvHome.OnlineWallpaper", "Failed to fetch Bing daily URL: " + e.getMessage());
        }
        return URL_NATURE;
    }

    static File getCacheFile(Context ctx) {
        return new File(ctx.getCacheDir(), "online_wallpaper.jpg");
    }

    static File downloadWallpaper(Context ctx, String choice) {
        File target = getCacheFile(ctx);
        boolean isBing = choice == null || CHOICE_BING.equals(choice);
        long now = System.currentTimeMillis();

        if (target.exists() && target.length() > 0) {
            String cachedChoice = ctx.getSharedPreferences("tvhome_online_wp", Context.MODE_PRIVATE)
                    .getString("cached_choice", "");
            if (choice != null && choice.equals(cachedChoice)) {
                if (!isBing) {
                    return target;
                } else {
                    long lastModified = target.lastModified();
                    if (now - lastModified < 12 * 3600_000L) {
                        return target;
                    }
                }
            }
        }

        String downloadUrl = resolveUrl(choice);
        if (downloadUrl == null) return target.exists() ? target : null;

        File tempFile = new File(ctx.getCacheDir(), "online_wallpaper.tmp");
        try {
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                    ctx.getSharedPreferences("tvhome_online_wp", Context.MODE_PRIVATE)
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
