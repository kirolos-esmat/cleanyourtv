package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView ambientGlowChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean ambientGlow;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        ambientGlow = config.ambientGlow;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        ambientGlowChoice = findViewById(R.id.ambientGlowChoice);
        ambientGlowChoice.setOnClickListener(v -> {
            ambientGlow = !ambientGlow;
            choices.edit().putBoolean(Config.AMBIENT_GLOW_KEY, ambientGlow).apply();
            setResult(RESULT_OK);
            refresh();
        });
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.AMBIENT_GLOW_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            ambientGlow = current.ambientGlow;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wallpaper_picker, null);
        RecyclerView grid = dialogView.findViewById(R.id.wallpaperGrid);
        Button btnInterval = dialogView.findViewById(R.id.btnInterval);

        int[] intervals = {0, 5, 15, 30, 60, 360, 1440};
        String[] intervalLabels = {"Off", "5 min", "15 min", "30 min", "1 hour", "6 hours", "Daily"};
        int currentMins = choices.getInt("wallpaper_rotation_minutes", 0);
        int currentIdx = 0;
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i] == currentMins) {
                currentIdx = i;
                break;
            }
        }
        final int[] selectedIntervalIdx = {currentIdx};
        btnInterval.setText(intervalLabels[selectedIntervalIdx[0]]);
        btnInterval.setOnClickListener(v -> {
            selectedIntervalIdx[0] = (selectedIntervalIdx[0] + 1) % intervals.length;
            int newMins = intervals[selectedIntervalIdx[0]];
            choices.edit().putInt("wallpaper_rotation_minutes", newMins).apply();
            btnInterval.setText(intervalLabels[selectedIntervalIdx[0]]);
            setResult(RESULT_OK);
        });

        List<WallpaperPickerAdapter.Item> items = new java.util.ArrayList<>();

        // 1. Reddit r/wallpapers (Hot)
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_REDDIT,
                "Reddit Wallpapers",
                "r/wallpapers (Hot Feed)",
                "https://preview.redd.it/c7p9uw1miowh1.jpeg?width=640"
        ));

        // 2. Daily Bing
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_BING,
                "Daily Bing",
                "8 Daily Archive Images",
                OnlineWallpaper.CHOICE_BING
        ));

        // 3. Curated Unsplash Photos
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_NATURE,
                "Nature Gallery",
                "18 Curated Wallpapers",
                OnlineWallpaper.URL_NATURE
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_AURORA,
                "Aurora Gallery",
                "15 Curated Wallpapers",
                OnlineWallpaper.URL_AURORA
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_SPACE,
                "Deep Space Gallery",
                "12 Curated Wallpapers",
                OnlineWallpaper.URL_SPACE
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_DARK,
                "Minimal Dark Gallery",
                "12 Curated Wallpapers",
                OnlineWallpaper.URL_DARK
        ));

        // 4. Local theme wallpapers / scenes
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);

        for (File img : images) {
            items.add(new WallpaperPickerAdapter.Item(
                    img.getName(),
                    img.getName().replace(".jpg", "").replace(".png", "").replace(".webp", ""),
                    "Local / Scene",
                    img.getAbsolutePath()
            ));
        }

        // 5. None
        items.add(new WallpaperPickerAdapter.Item(
                "none",
                getString(R.string.wallpaper_none),
                "Solid Dark",
                ""
        ));

        String currentChoice = choices.getString(Config.WALLPAPER_CHOICE_KEY, OnlineWallpaper.CHOICE_BING);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        grid.setLayoutManager(new GridLayoutManager(this, 3));
        WallpaperPickerAdapter adapter = new WallpaperPickerAdapter(this, items, currentChoice, item -> {
            dialog.dismiss();
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, item.key).apply();
            setResult(RESULT_OK);
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            refresh();
        });
        grid.setAdapter(adapter);

        dialog.show();
        grid.post(() -> {
            View first = grid.getChildAt(0);
            if (first != null) first.requestFocus();
        });

        // Background thread: enrich Reddit thumbnail from live feed if available
        new Thread(() -> {
            java.util.List<RedditRssParser.Entry> redditEntries = OnlineWallpaper.fetchRedditWallpapers();
            if (!redditEntries.isEmpty()) {
                String latestThumb = redditEntries.get(0).thumbnailUrl;
                runOnUiThread(() -> {
                    for (int i = 0; i < items.size(); i++) {
                        if (OnlineWallpaper.CHOICE_REDDIT.equals(items.get(i).key)) {
                            items.set(i, new WallpaperPickerAdapter.Item(
                                    OnlineWallpaper.CHOICE_REDDIT,
                                    "Reddit Wallpapers",
                                    "r/wallpapers (" + redditEntries.size() + " photos)",
                                    latestThumb
                            ));
                            adapter.notifyItemChanged(i);
                            break;
                        }
                    }
                });
            }
        }).start();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        ambientGlowChoice.setText(getString(R.string.ambient_glow_state,
                getString(ambientGlow ? R.string.on : R.string.off)));
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String wpText;
        if ("none".equals(wallpaperCurrent)) {
            wpText = getString(R.string.wallpaper_none);
        } else if (OnlineWallpaper.isOnlineChoice(wallpaperCurrent)) {
            wpText = OnlineWallpaper.labelFor(this, wallpaperCurrent);
        } else if (wallpaperCurrent != null) {
            wpText = wallpaperCurrent;
        } else {
            wpText = getString(R.string.wallpaper_theme_default);
        }
        wallpaperChoice.setText(getString(R.string.wallpaper_state, wpText));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains(Config.AMBIENT_GLOW_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
