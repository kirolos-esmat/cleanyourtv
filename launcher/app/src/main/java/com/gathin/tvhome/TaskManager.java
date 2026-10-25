package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TaskManager {
    private static final String PREFS = "task_manager_recents";
    private static final String PREFS_CLOSED = "task_manager_closed";
    private static final String KEY_RECENT_PACKAGES = "recent_packages";

    // System packages and core frameworks that must never be terminated
    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.apps.tv.dreamx",
            "com.google.android.apps.mediashell",
            "com.google.android.tungsten.setupwraith",
            "com.android.tv.settings",
            "com.google.android.katniss",
            "com.google.android.inputmethod.latin",
            "com.android.shell",
            "com.android.providers.settings",
            "com.gathin.tvhome"
    ));

    private TaskManager() {}

    static class AppEntry {
        final String packageName;
        final String appName;
        final Drawable icon;
        final long lastUsed;

        AppEntry(String packageName, String appName, Drawable icon, long lastUsed) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.lastUsed = lastUsed;
        }
    }

    static void recordActiveApp(Context context, String packageName) {
        if (packageName == null || IGNORED_PACKAGES.contains(packageName) || packageName.equals(context.getPackageName())) {
            return;
        }
        // Unmark as closed when launched or brought to foreground
        try {
            context.getSharedPreferences(PREFS_CLOSED, Context.MODE_PRIVATE).edit().remove(packageName).apply();
        } catch (Exception ignored) {}

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_RECENT_PACKAGES, "");
        List<String> list = new ArrayList<>();
        if (!current.isEmpty()) {
            list.addAll(Arrays.asList(current.split(",")));
        }
        list.remove(packageName);
        list.add(0, packageName);
        while (list.size() > 25) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        prefs.edit().putString(KEY_RECENT_PACKAGES, sb.toString()).apply();
    }

    static void removeActiveApp(Context context, String packageName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(KEY_RECENT_PACKAGES, "");
        if (current.isEmpty()) return;
        List<String> list = new ArrayList<>(Arrays.asList(current.split(",")));
        if (list.remove(packageName)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            prefs.edit().putString(KEY_RECENT_PACKAGES, sb.toString()).apply();
        }
    }

    static List<AppEntry> getRecentApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, Long> candidates = new HashMap<>();

        // 1. Check UsageStatsManager if permitted
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1000L * 60 * 60 * 24, now);
                if (stats != null) {
                    for (UsageStats u : stats) {
                        String pkg = u.getPackageName();
                        if (!IGNORED_PACKAGES.contains(pkg) && !pkg.equals(context.getPackageName()) && u.getLastTimeUsed() > 0) {
                            candidates.put(pkg, u.getLastTimeUsed());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Check locally recorded recents
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String current = prefs.getString(KEY_RECENT_PACKAGES, "");
            if (!current.isEmpty()) {
                String[] pkgs = current.split(",");
                long pseudoTime = System.currentTimeMillis();
                for (String p : pkgs) {
                    if (!p.isEmpty() && !IGNORED_PACKAGES.contains(p) && !p.equals(context.getPackageName())) {
                        if (!candidates.containsKey(p)) {
                            candidates.put(p, pseudoTime--);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. Check LastOpened records
        try {
            SharedPreferences openedPrefs = context.getSharedPreferences("last_opened", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : openedPrefs.getAll().entrySet()) {
                String pkg = entry.getKey();
                if (!IGNORED_PACKAGES.contains(pkg) && !pkg.equals(context.getPackageName())) {
                    if (entry.getValue() instanceof Long) {
                        long time = (Long) entry.getValue();
                        Long existing = candidates.get(pkg);
                        if (existing == null || time > existing) {
                            candidates.put(pkg, time);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Filter out closed apps whose last usage timestamp is before or equal to the time they were closed
        try {
            SharedPreferences closedPrefs = context.getSharedPreferences(PREFS_CLOSED, Context.MODE_PRIVATE);
            Map<String, ?> closedMap = closedPrefs.getAll();
            if (closedMap != null && !closedMap.isEmpty()) {
                for (Iterator<Map.Entry<String, Long>> it = candidates.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, Long> entry = it.next();
                    String pkg = entry.getKey();
                    long lastUsed = entry.getValue();
                    Object closedVal = closedMap.get(pkg);
                    if (closedVal instanceof Long) {
                        long closedTime = (Long) closedVal;
                        if (lastUsed <= closedTime) {
                            it.remove();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        List<AppEntry> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : candidates.entrySet()) {
            String pkg = entry.getKey();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(ai);
                Drawable icon = pm.getApplicationIcon(ai);
                result.add(new AppEntry(pkg, label.toString(), icon, entry.getValue()));
            } catch (Exception ignored) {}
        }

        // Sort descending by last used time
        Collections.sort(result, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));

        // Limit to 12 apps for smooth remote scrolling
        if (result.size() > 12) {
            result = new ArrayList<>(result.subList(0, 12));
        }

        return result;
    }

    static void closeApp(Context context, String packageName) {
        if (packageName == null || packageName.equals(context.getPackageName()) || IGNORED_PACKAGES.contains(packageName)) {
            return;
        }
        removeActiveApp(context, packageName);
        try {
            context.getSharedPreferences(PREFS_CLOSED, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(packageName, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}

        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
            }
        } catch (Exception ignored) {}

        if (HomeKeyService.instance != null) {
            HomeKeyService.instance.forceStopPackage(packageName);
        }
    }

    static void closeAll(Context context, List<AppEntry> apps) {
        if (apps == null || apps.isEmpty()) return;
        long now = System.currentTimeMillis();
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_CLOSED, Context.MODE_PRIVATE).edit();
            for (AppEntry app : apps) {
                editor.putLong(app.packageName, now);
            }
            editor.apply();
        } catch (Exception ignored) {}

        List<String> pkgs = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            for (AppEntry app : apps) {
                removeActiveApp(context, app.packageName);
                if (!IGNORED_PACKAGES.contains(app.packageName)) {
                    pkgs.add(app.packageName);
                    if (am != null) {
                        am.killBackgroundProcesses(app.packageName);
                    }
                }
            }
        } catch (Exception ignored) {}

        if (HomeKeyService.instance != null && !pkgs.isEmpty()) {
            HomeKeyService.instance.forceStopPackages(pkgs, null);
        }
        System.gc();
    }
}
