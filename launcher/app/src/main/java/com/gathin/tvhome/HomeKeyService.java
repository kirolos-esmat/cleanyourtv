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
 * and opens the Google TV quick-settings dashboard panel on a long press of the Home button (or double tap).
 * Also handles the Apps button (KEYCODE_ALL_APPS) and remapped remote app keys (with single/double tap).
 * - Home single tap: returns to launcher.
 * - Home double tap: opens Settings / Quick Settings.
 * - Home long press: opens Dashboard panel.
 * - Apps click: returns to launcher from other apps, does nothing while on launcher.
 * - Apps long press: opens Google Play Store directly.
 */
public class HomeKeyService extends AccessibilityService {

    public static volatile HomeKeyService instance;

    private static final long DOUBLE_TAP_MS = 300L;
    private static final long LONG_PRESS_MS = 400L;

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, single tap mapping (keycode -> package). */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    /** app_keys, double tap mapping (keycode -> package). */
    private Map<Integer, String> appDoubleKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pendingDoubleTapKey;
    private Runnable pendingSingleTapRunnable;

    private boolean homeDown;
    private boolean homeLongHandled;
    private final Runnable homeLongPressRunnable = () -> {
        if (homeDown && !homeLongHandled) {
            homeLongHandled = true;
            if (pendingSingleTapRunnable != null) {
                handler.removeCallbacks(pendingSingleTapRunnable);
                pendingDoubleTapKey = 0;
                pendingSingleTapRunnable = null;
            }
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
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
        }
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appDoubleKeys = RemoteKeys.parseDoubleTapAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public static boolean standby() {
        if (instance != null) {
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
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

    private void openSettings() {
        if (settingsPanel) {
            try {
                startActivity(new Intent(this, QuickSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        openDashboard();
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
        Intent dash = new Intent("android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(dash);
            return;
        } catch (Exception ignored) {
        }
        if (performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        String scanApp = appScancodes.get(event.getScanCode());
        if (scanApp != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, scanApp);
            return true;
        }
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                openSettings();
            }
            return true;
        }

        int code = event.getKeyCode();
        String singleApp = appKeys.get(code);
        String doubleApp = appDoubleKeys.get(code);

        if (singleApp != null || doubleApp != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (doubleApp != null) {
                    if (pendingDoubleTapKey == code) {
                        // Double tap!
                        if (pendingSingleTapRunnable != null) {
                            handler.removeCallbacks(pendingSingleTapRunnable);
                            pendingSingleTapRunnable = null;
                        }
                        pendingDoubleTapKey = 0;
                        RemoteKeys.launch(this, doubleApp);
                    } else {
                        // First tap
                        if (pendingSingleTapRunnable != null) {
                            handler.removeCallbacks(pendingSingleTapRunnable);
                            pendingSingleTapRunnable = null;
                        }
                        pendingDoubleTapKey = code;
                        pendingSingleTapRunnable = () -> {
                            pendingDoubleTapKey = 0;
                            pendingSingleTapRunnable = null;
                            if (singleApp != null) {
                                RemoteKeys.launch(this, singleApp);
                            }
                        };
                        handler.postDelayed(pendingSingleTapRunnable, DOUBLE_TAP_MS);
                    }
                } else if (singleApp != null) {
                    // No double tap configured for this key: execute immediately
                    if (pendingSingleTapRunnable != null) {
                        handler.removeCallbacks(pendingSingleTapRunnable);
                        pendingSingleTapRunnable = null;
                        pendingDoubleTapKey = 0;
                    }
                    RemoteKeys.launch(this, singleApp);
                }
            }
            return true;
        }

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
                    if (pendingSingleTapRunnable != null) {
                        handler.removeCallbacks(pendingSingleTapRunnable);
                        pendingDoubleTapKey = 0;
                        pendingSingleTapRunnable = null;
                    }
                    openDashboard();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(homeLongPressRunnable);
                boolean shouldAct = homeDown && !homeLongHandled && !event.isCanceled();
                homeDown = false;
                homeLongHandled = false;
                if (shouldAct) {
                    if (pendingDoubleTapKey == KeyEvent.KEYCODE_HOME) {
                        // Double tap on Home: open Settings / Quick Settings!
                        if (pendingSingleTapRunnable != null) {
                            handler.removeCallbacks(pendingSingleTapRunnable);
                            pendingSingleTapRunnable = null;
                        }
                        pendingDoubleTapKey = 0;
                        openSettings();
                    } else {
                        // First tap: delay by DOUBLE_TAP_MS to check for double tap
                        if (pendingSingleTapRunnable != null) {
                            handler.removeCallbacks(pendingSingleTapRunnable);
                            pendingSingleTapRunnable = null;
                        }
                        pendingDoubleTapKey = KeyEvent.KEYCODE_HOME;
                        pendingSingleTapRunnable = () -> {
                            pendingDoubleTapKey = 0;
                            pendingSingleTapRunnable = null;
                            openHome();
                        };
                        handler.postDelayed(pendingSingleTapRunnable, DOUBLE_TAP_MS);
                    }
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
        if (pendingSingleTapRunnable != null) {
            handler.removeCallbacks(pendingSingleTapRunnable);
            pendingDoubleTapKey = 0;
            pendingSingleTapRunnable = null;
        }
    }
}
