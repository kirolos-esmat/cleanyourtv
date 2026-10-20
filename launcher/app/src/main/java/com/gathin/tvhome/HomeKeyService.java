package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_HOME or KEYCODE_ALL_APPS,
 * and opens the Google TV quick-settings dashboard panel on a long press of the Home button.
 * Also handles the Apps button (KEYCODE_ALL_APPS):
 * - Click while on launcher: does nothing (no glitch, no stock apps flashing).
 * - Click while in another app: returns to launcher.
 * - Long press: opens Google Play Store directly.
 */
public class HomeKeyService extends AccessibilityService {

    private static final long LONG_PRESS_MS = 400L;

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean homeDown;
    private boolean homeLongHandled;
    private final Runnable homeLongPressRunnable = () -> {
        if (homeDown && !homeLongHandled) {
            homeLongHandled = true;
            openDashboard();
        }
    };

    private boolean appsDown;
    private boolean appsLongHandled;
    private final Runnable appsLongPressRunnable = () -> {
        if (appsDown && !appsLongHandled) {
            appsLongHandled = true;
            openPlayStore();
        }
    };

    private String foregroundPackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private void openHome() {
        Intent home = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(home);
        } catch (Exception ignored) {
        }
    }

    private void openPlayStore() {
        Intent play = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.vending");
        if (play == null) {
            play = getPackageManager().getLaunchIntentForPackage("com.android.vending");
        }
        if (play == null) {
            play = new Intent(Intent.ACTION_MAIN).setPackage("com.android.vending");
        }
        try {
            startActivity(play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private void openDashboard() {
        if (performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) {
            return;
        }
        Intent dash = new Intent("android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(dash);
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (settingsPanel) {
                    startActivity(new Intent(this, QuickSettingsActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    openDashboard();
                }
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }

        int code = event.getKeyCode();

        // Apps button (KEYCODE_ALL_APPS):
        if (code == KeyEvent.KEYCODE_ALL_APPS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    appsDown = true;
                    appsLongHandled = false;
                    handler.removeCallbacks(appsLongPressRunnable);
                    handler.postDelayed(appsLongPressRunnable, LONG_PRESS_MS);
                } else if (!appsLongHandled) {
                    appsLongHandled = true;
                    handler.removeCallbacks(appsLongPressRunnable);
                    openPlayStore();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(appsLongPressRunnable);
                boolean shouldAct = appsDown && !appsLongHandled && !event.isCanceled();
                appsDown = false;
                appsLongHandled = false;
                if (shouldAct) {
                    // Click while in launcher: do nothing. Click while in another app: go home.
                    if (!getPackageName().equals(foregroundPackage)) {
                        openHome();
                    }
                }
                return true;
            }
            return true;
        }

        // Home button (KEYCODE_HOME):
        if (code == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    homeDown = true;
                    homeLongHandled = false;
                    handler.removeCallbacks(homeLongPressRunnable);
                    handler.postDelayed(homeLongPressRunnable, LONG_PRESS_MS);
                } else if (!homeLongHandled) {
                    homeLongHandled = true;
                    handler.removeCallbacks(homeLongPressRunnable);
                    openDashboard();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(homeLongPressRunnable);
                boolean shouldGoHome = homeDown && !homeLongHandled && !event.isCanceled();
                homeDown = false;
                homeLongHandled = false;
                if (shouldGoHome) {
                    openHome();
                }
                return true;
            }
            return true;
        }

        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg != null) {
            foregroundPackage = pkg.toString();
        }
        // Redirect stock Google TV launcher HomeActivity to our launcher while allowing DashboardActivity to stay open.
        if (pkg != null && cls != null && "com.google.android.apps.tv.launcherx".contentEquals(pkg)) {
            if (cls.toString().contains("HomeActivity")) {
                openHome();
            }
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(homeLongPressRunnable);
        handler.removeCallbacks(appsLongPressRunnable);
    }
}
