package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private static final java.util.concurrent.ExecutorService BG = java.util.concurrent.Executors.newSingleThreadExecutor();
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView ambientGlowChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean ambientGlow;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        ambientGlow = config.ambientGlow;
        dockOpacity = config.dockOpacity;
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
                changed();
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
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
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
            changed();
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
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove("wallpaper_rotation_minutes")
                    .remove(Config.AMBIENT_GLOW_KEY)
                    .remove(Config.DOCK_OPACITY_KEY)
                    .remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            ambientGlow = current.ambientGlow;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
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
        int[] intervalRes = {
                R.string.rotation_off,
                R.string.interval_5m,
                R.string.interval_15m,
                R.string.interval_30m,
                R.string.interval_1h,
                R.string.interval_6h,
                R.string.interval_daily
        };
        int currentMins = choices.getInt("wallpaper_rotation_minutes", 0);
        int currentIdx = 0;
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i] == currentMins) {
                currentIdx = i;
                break;
            }
        }
        final int[] selectedIntervalIdx = {currentIdx};
        btnInterval.setText(getString(intervalRes[selectedIntervalIdx[0]]));
        btnInterval.setOnClickListener(v -> {
            selectedIntervalIdx[0] = (selectedIntervalIdx[0] + 1) % intervals.length;
            int newMins = intervals[selectedIntervalIdx[0]];
            choices.edit().putInt("wallpaper_rotation_minutes", newMins).apply();
            btnInterval.setText(getString(intervalRes[selectedIntervalIdx[0]]));
            changed();
        });

        List<WallpaperPickerAdapter.Item> items = new java.util.ArrayList<>();

        // 1. Reddit r/wallpapers (Hot)
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_REDDIT,
                getString(R.string.wp_reddit_title),
                getString(R.string.wp_reddit_sub),
                "https://preview.redd.it/c7p9uw1miowh1.jpeg?width=640"
        ));

        // 2. Daily Bing
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_BING,
                getString(R.string.wp_bing_title),
                getString(R.string.wp_bing_sub),
                OnlineWallpaper.CHOICE_BING
        ));

        // 3. Curated Unsplash Photos
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_NATURE,
                getString(R.string.wp_nature_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_NATURE.length),
                OnlineWallpaper.URL_NATURE
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_AURORA,
                getString(R.string.wp_aurora_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_AURORA.length),
                OnlineWallpaper.URL_AURORA
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_SPACE,
                getString(R.string.wp_space_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_SPACE.length),
                OnlineWallpaper.URL_SPACE
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_DARK,
                getString(R.string.wp_dark_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_DARK.length),
                OnlineWallpaper.URL_DARK
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_RETRO,
                getString(R.string.wp_retro_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_RETRO.length),
                OnlineWallpaper.URL_RETRO
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_HOGWARTS,
                getString(R.string.wp_hogwarts_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_HOGWARTS.length),
                OnlineWallpaper.URL_HOGWARTS
        ));
        items.add(new WallpaperPickerAdapter.Item(
                OnlineWallpaper.CHOICE_MINIMAL,
                getString(R.string.wp_minimal_title),
                getString(R.string.wp_curated_count, OnlineWallpaper.IDS_MINIMAL.length),
                OnlineWallpaper.URL_MINIMAL
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
                    getString(R.string.wp_local_scene),
                    img.getAbsolutePath()
            ));
        }

        // 5. None
        items.add(new WallpaperPickerAdapter.Item(
                "none",
                getString(R.string.wallpaper_none),
                getString(R.string.wp_solid_dark),
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
            changed();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            refresh();
        });
        grid.setAdapter(adapter);

        int maxGridHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.65f);
        if (grid.getLayoutParams() != null) {
            grid.getLayoutParams().height = maxGridHeight;
        }

        dialog.show();
        grid.post(() -> {
            View first = grid.getChildAt(0);
            if (first != null) first.requestFocus();
        });

        // Background task: enrich Reddit thumbnail from live feed if available
        BG.execute(() -> {
            java.util.List<RedditRssParser.Entry> redditEntries = OnlineWallpaper.fetchRedditWallpapers();
            if (!redditEntries.isEmpty()) {
                String latestThumb = redditEntries.get(0).thumbnailUrl;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
                    for (int i = 0; i < items.size(); i++) {
                        if (OnlineWallpaper.CHOICE_REDDIT.equals(items.get(i).key)) {
                            items.set(i, new WallpaperPickerAdapter.Item(
                                    OnlineWallpaper.CHOICE_REDDIT,
                                    getString(R.string.wp_reddit_title),
                                    getString(R.string.wp_reddit_sub_count, redditEntries.size()),
                                    latestThumb
                            ));
                            adapter.notifyItemChanged(i);
                            break;
                        }
                    }
                });
            }
        });
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
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
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
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
        if (openOnStartChoice != null) {
            openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        }
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains("wallpaper_rotation_minutes")
                || choices.contains(Config.AMBIENT_GLOW_KEY)
                || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
