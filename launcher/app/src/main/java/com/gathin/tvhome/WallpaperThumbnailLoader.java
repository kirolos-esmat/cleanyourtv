package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WallpaperThumbnailLoader {
    private static final int MAX_MEMORY_CACHE_BYTES = 6 * 1024 * 1024; // 6 MB
    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 180;

    private static final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(MAX_MEMORY_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WallpaperThumbnailLoader() {}

    static void load(Context context, String source, ImageView targetView) {
        if (source == null || targetView == null) return;

        Bitmap cached = memoryCache.get(source);
        if (cached != null && !cached.isRecycled()) {
            targetView.setImageBitmap(cached);
            return;
        }

        targetView.setTag(source);
        final Context appCtx = context.getApplicationContext();

        executor.execute(() -> {
            String resolved = source;
            if (OnlineWallpaper.CHOICE_BING.equals(source)) {
                resolved = OnlineWallpaper.resolveUrl(OnlineWallpaper.CHOICE_BING);
            } else if (OnlineWallpaper.CHOICE_REDDIT.equals(source)) {
                java.util.List<RedditRssParser.Entry> entries = OnlineWallpaper.fetchRedditWallpapers();
                if (!entries.isEmpty()) {
                    resolved = entries.get(0).imageUrl;
                }
            }

            Bitmap bmp = null;
            if (resolved != null && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                bmp = loadFromHttp(appCtx, resolved);
            } else if (resolved != null) {
                File file = new File(resolved);
                if (file.exists() && file.isFile()) {
                    bmp = decodeSampled(file.getAbsolutePath());
                }
            }

            if (bmp != null) {
                memoryCache.put(source, bmp);
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    if (source.equals(targetView.getTag())) {
                        targetView.setImageBitmap(finalBmp);
                    }
                });
            }
        });
    }

    private static Bitmap loadFromHttp(Context context, String urlString) {
        File cacheDir = new File(context.getCacheDir(), "wp_thumbs");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        String filename = "thumb_" + Math.abs(urlString.hashCode()) + ".jpg";
        File diskFile = new File(cacheDir, filename);

        if (diskFile.exists() && diskFile.length() > 0) {
            return decodeSampled(diskFile.getAbsolutePath());
        }

        File tempFile = new File(cacheDir, filename + ".tmp");
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "tvhome/2.5 (Android TV)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(diskFile);
                    return decodeSampled(diskFile.getAbsolutePath());
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
        return null;
    }

    private static Bitmap decodeSampled(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

            int sample = 1;
            while ((opts.outWidth / sample) > TARGET_WIDTH * 1.5
                    || (opts.outHeight / sample) > TARGET_HEIGHT * 1.5) {
                sample *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // Saves 50% RAM compared to ARGB_8888
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }
}
