package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images or streamable online wallpapers while Home is visible. */
final class WallpaperPlayer {
    private final Context context;
    private final FrameLayout root;
    private final ImageView image;
    private ImageView crossfadeImage;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    private final String onlineChoice;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.context = context.getApplicationContext();
        this.root = root;
        this.onlineChoice = config.wallpaperChoice;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        boolean isOnline = OnlineWallpaper.isOnlineChoice(onlineChoice);
        if (config.wallpaperEnabled && (!files.isEmpty() || isOnline)) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        if (OnlineWallpaper.isOnlineChoice(onlineChoice)) {
            String cachedChoice = context.getSharedPreferences("tvhome_online_wp", Context.MODE_PRIVATE)
                    .getString("cached_choice", "");
            File cached = OnlineWallpaper.getCacheFile(context);
            if (onlineChoice.equals(cachedChoice) && cached.exists() && cached.length() > 0) {
                Bitmap bitmap = decode(cached);
                if (bitmap != null) {
                    image.setImageBitmap(bitmap);
                    displayed = onlineChoice;
                    return;
                }
            }
            fetchOnlineWallpaper();
            return;
        }

        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    private void fetchOnlineWallpaper() {
        if (!OnlineWallpaper.isOnlineChoice(onlineChoice) || loading || closed) return;
        loading = true;
        decoder.execute(() -> {
            File downloaded = OnlineWallpaper.downloadWallpaper(context, onlineChoice);
            root.post(() -> {
                loading = false;
                if (closed || !active) return;
                if (downloaded != null && downloaded.exists()) {
                    Bitmap bmp = decode(downloaded);
                    if (bmp != null) {
                        applyBitmapWithCrossfade(bmp);
                        displayed = onlineChoice;
                    }
                }
            });
        });
    }

    private void applyBitmapWithCrossfade(Bitmap bmp) {
        if (bmp == null) return;
        if (image.getDrawable() == null) {
            image.setImageBitmap(bmp);
            return;
        }
        if (crossfadeImage == null) {
            crossfadeImage = new ImageView(context);
            crossfadeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            crossfadeImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int idx = root.indexOfChild(image);
            root.addView(crossfadeImage, idx + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        crossfadeImage.setImageBitmap(bmp);
        crossfadeImage.setAlpha(0f);
        crossfadeImage.setVisibility(View.VISIBLE);
        crossfadeImage.animate()
                .alpha(1f)
                .setDuration(350L)
                .withEndAction(() -> {
                    image.setImageBitmap(bmp);
                    crossfadeImage.setVisibility(View.GONE);
                    crossfadeImage.setImageDrawable(null);
                })
                .start();
    }

    void shuffle() {
        if (closed || loading) return;
        long now = System.currentTimeMillis();
        preferences.edit().putLong("changed", now).apply();
        if (OnlineWallpaper.isOnlineChoice(onlineChoice) || files.isEmpty()) {
            loading = true;
            decoder.execute(() -> {
                String choice = OnlineWallpaper.isOnlineChoice(onlineChoice) ? onlineChoice : OnlineWallpaper.CHOICE_REDDIT;
                File downloaded = OnlineWallpaper.downloadWallpaper(context, choice, true);
                root.post(() -> {
                    loading = false;
                    if (closed || !active) return;
                    if (downloaded != null && downloaded.exists()) {
                        Bitmap bmp = decode(downloaded);
                        if (bmp != null) {
                            applyBitmapWithCrossfade(bmp);
                            displayed = choice;
                            preferences.edit().putLong("changed", System.currentTimeMillis()).apply();
                        }
                    }
                });
            });
            return;
        }
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File next = WallpaperRotation.pick(files, previous, random);
        if (next == null) return;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap != null) {
                    applyBitmapWithCrossfade(bitmap);
                    displayed = next.getName();
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
        if (crossfadeImage != null) {
            crossfadeImage.animate().cancel();
            crossfadeImage.setImageDrawable(null);
        }
    }

    void update() {
        if (!active || closed || loading) return;
        long now = System.currentTimeMillis();
        long changed = preferences.getLong("changed", 0);

        if (OnlineWallpaper.isOnlineChoice(onlineChoice)) {
            // Auto-rotate only when interval is enabled (> 0) and due!
            if (interval > 0 && WallpaperRotation.due(changed, now, interval)) {
                loading = true;
                decoder.execute(() -> {
                    File downloaded = OnlineWallpaper.downloadWallpaper(context, onlineChoice, true);
                    root.post(() -> {
                        loading = false;
                        if (closed || !active) return;
                        if (downloaded != null && downloaded.exists()) {
                            Bitmap bmp = decode(downloaded);
                            if (bmp != null) {
                                applyBitmapWithCrossfade(bmp);
                                displayed = onlineChoice;
                                preferences.edit().putLong("changed", System.currentTimeMillis()).apply();
                            }
                        }
                    });
                });
            }
            return;
        }

        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        if (selected == null || WallpaperRotation.due(changed, now, interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                applyBitmapWithCrossfade(bitmap);
                displayed = next.getName();
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
            });
        });
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
