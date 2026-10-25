package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_HOME or KEYCODE_ALL_APPS,
 * and opens the Google TV quick-settings dashboard panel on a long press of the Home button (or double tap).
 * Also handles the Apps button (KEYCODE_ALL_APPS) and remapped remote app keys (with single/double tap).
 * - Home single tap: returns to launcher.
 * - Home double tap: opens Settings / Quick Settings.
 * - Home long press: opens Dashboard panel.
 * - Apps click: returns to launcher from other apps, does nothing while on launcher.
 * - Apps long press: opens Google Play Store directly.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * With power_on_home set, a Live TV window (live_tv_package) that the firmware opens by itself at power-on is sent
 * back here too, until the first key press after boot or after the screen turns on (Changhong / Homwee boards).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    public static volatile HomeKeyService instance;
    private static HomeKeyService running;

    private static final long DOUBLE_TAP_MS = 300L;
    private static final long LONG_PRESS_MS = 400L;

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, single tap mapping (keycode -> package). */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    /** app_keys, double tap mapping (keycode -> package). */
    private Map<Integer, String> appDoubleKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** stock_home_redirect, the stock launcher's package. */
    private String stockHomePackage = "";
    /** power_on_home: the Live TV package to send back to Home, empty when off; armed until this uptime or a key. */
    private String powerOnLiveTv = "";
    private long powerOnUntil;
    /** power_on_home: re-arms after the screen turns on; null unless the key is on. */
    private BroadcastReceiver screenOnReceiver;
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    /** Screen-off receiver while guard_off_in_standby is set; null otherwise. */
    private BroadcastReceiver standbyExit;
    /** disableSelf is how this service leaves for standby, not the user turning it off. */
    private boolean steppingOut;

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
    private int lastHandledNavKey = 0;
    private BroadcastReceiver unlockReceiver;

    static boolean isAllowedLauncherxActivity(String className) {
        if (className == null) return false;
        return className.contains("Dashboard")
                || className.contains("Profile")
                || className.contains("Account")
                || className.contains("Reauth")
                || className.contains("Lock")
                || className.contains("SleepTimer")
                || className.contains("GoogleHome")
                || className.contains("Dialog");
    }

    static boolean isStockHomeActivity(String className) {
        if (className == null) return false;
        return className.contains("HomeActivity")
                || className.contains("VanillaModeHomeActivity");
    }

    private boolean isDashboardActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            if (bounds.left > 100 || (bounds.width() > 0 && bounds.width() < screenWidth * 0.85f)) {
                return true;
            }
            CharSequence cls = root.getClassName();
            if (cls != null && isAllowedLauncherxActivity(cls.toString())) {
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            root.recycle();
        }
        return false;
    }

    private final Runnable redirectHomeRunnable = () -> {
        if ("com.google.android.apps.tv.launcherx".equals(foregroundPackage)) {
            if (isDashboardActive()) {
                return;
            }
            openHome();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        running = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);

        UserManager users = getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) {
            unlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    stopWaitingForUnlock();
                    loadConfig();
                }
            };
            registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            if (users.isUserUnlocked()) {
                stopWaitingForUnlock();
                loadConfig();
            }
            return;
        }
        loadConfig();
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {
            }
            unlockReceiver = null;
        }
    }

    /** The opt-in broadcast: arm this binding now, or drop the receiver if the opt-in was turned off. */
    static void applyStandbyOptIn() {
        HomeKeyService service = running;
        if (service == null) return;
        if (GuardKeep.offInStandby(service)) service.armStandbyExit();
        else service.disarmStandbyExit();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        if (instance == this) instance = null;
        stopWaitingForUnlock();
        if (screenOnReceiver != null) unregisterReceiver(screenOnReceiver);
        if (standbyExit != null) {
            try {
                unregisterReceiver(standbyExit);
            } catch (RuntimeException ignored) {
            }
            standbyExit = null;
        }
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    public static boolean standby() {
        if (instance != null) {
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    /** Some TCL boards never wake from the remote while this helper stays bound with the screen off. */
    private void armStandbyExit() {
        if (standbyExit != null || !GuardKeep.offInStandby(this)) return;
        standbyExit = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                leaveForStandby();
            }
        };
        registerReceiver(standbyExit, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) leaveForStandby();
    }

    private void disarmStandbyExit() {
        if (standbyExit == null) return;
        try {
            unregisterReceiver(standbyExit);
        } catch (RuntimeException ignored) {
        }
        standbyExit = null;
    }

    private void leaveForStandby() {
        steppingOut = true;
        GuardKeep.stepOut(this);
    }

    private void loadConfig() {
        Config config = Config.load(this);
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        consumeVolumeKeys = config.consumeVolumeKeys;
        if (consumeVolumeKeys) VolumeKeys.stop();
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appDoubleKeys = RemoteKeys.parseDoubleTapAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);

        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }

        String openOnStart = config.openOnStartChoice
                ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "") : "";
        powerOnLiveTv = config.powerOnHome && !config.bootToLiveTv && !config.liveTvPackage.equals(openOnStart)
                ? config.liveTvPackage : "";
        if (!powerOnLiveTv.isEmpty()) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }

        if (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty()) probeAtBoot();
        armStandbyExit();

        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                CharSequence rootPkg = root.getPackageName();
                if (rootPkg != null) {
                    foregroundPackage = rootPkg.toString();
                }
            } finally {
                root.recycle();
            }
        }
        if ("com.google.android.apps.tv.launcherx".equals(foregroundPackage)) {
            if (!isDashboardActive()) {
                handler.removeCallbacks(redirectHomeRunnable);
                handler.postDelayed(redirectHomeRunnable, 350L);
            }
        }
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private void openHome() {
        Intent open = stockHomeStays() ? new Intent(this, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(getPackageName());
        try {
            startActivity(open.addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ignored) {
        }
    }

    private boolean stockHomeStays() {
        if (stockHome.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && stockHomePackage.equals(home.activityInfo.packageName);
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
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;

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

        // Apps button (KEYCODE_ALL_APPS); pass-through for other keys:
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

        if ("com.google.android.apps.tv.launcherx".equals(foregroundPackage)) {
            if (handleDashboardNavigation(event)) {
                return true;
            }
        }

        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        return false;
    }

    private boolean handleDashboardNavigation(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (lastHandledNavKey == code) {
                lastHandledNavKey = 0;
                return true;
            }
            return false;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) return false;

        try {
            CharSequence desc = focused.getContentDescription();
            CharSequence text = focused.getText();
            boolean isHelp = (desc != null && desc.toString().toLowerCase(java.util.Locale.ROOT).contains("help"))
                    || (text != null && text.toString().toLowerCase(java.util.Locale.ROOT).contains("help"));

            if (isHelp) {
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    Rect helpBounds = new Rect();
                    focused.getBoundsInScreen(helpBounds);
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        try {
                            AccessibilityNodeInfo candidate = findLowestFocusableAbove(root, helpBounds.top);
                            if (candidate != null) {
                                try {
                                    boolean ok = candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                                    if (ok) {
                                        lastHandledNavKey = code;
                                        return true;
                                    }
                                } finally {
                                    candidate.recycle();
                                }
                            }
                        } finally {
                            root.recycle();
                        }
                    }
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    lastHandledNavKey = code;
                    return true;
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                AccessibilityNodeInfo nextDown = focused.focusSearch(View.FOCUS_DOWN);
                if (nextDown != null) {
                    try {
                        CharSequence nextDesc = nextDown.getContentDescription();
                        CharSequence nextText = nextDown.getText();
                        if ((nextDesc != null && nextDesc.toString().toLowerCase(java.util.Locale.ROOT).contains("help"))
                                || (nextText != null && nextText.toString().toLowerCase(java.util.Locale.ROOT).contains("help"))) {
                            // Don't descend into dead footer Help & feedback icon
                            lastHandledNavKey = code;
                            return true;
                        }
                    } finally {
                        nextDown.recycle();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            focused.recycle();
        }

        return false;
    }

    private AccessibilityNodeInfo findLowestFocusableAbove(AccessibilityNodeInfo node, int maxBottom) {
        if (node == null) return null;
        AccessibilityNodeInfo best = null;
        Rect bestBounds = new Rect();

        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(AccessibilityNodeInfo.obtain(node));

        Rect r = new Rect();
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 250) {
            AccessibilityNodeInfo current = queue.remove(0);
            if (current.isFocusable()) {
                current.getBoundsInScreen(r);
                if (r.bottom <= maxBottom && r.width() > 0 && r.height() > 0) {
                    if (best == null || r.bottom > bestBounds.bottom
                            || (r.bottom == bestBounds.bottom && r.right > bestBounds.right)) {
                        if (best != null) best.recycle();
                        best = AccessibilityNodeInfo.obtain(current);
                        bestBounds.set(r);
                    }
                }
            }
            int childCount = current.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = current.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
            current.recycle();
        }
        for (AccessibilityNodeInfo remaining : queue) {
            remaining.recycle();
        }
        return best;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        stopWaitingForUnlock();
        if (repairRecord && !steppingOut) ServiceRepair.disconnected(this, getClass());
        handler.removeCallbacksAndMessages(null);
        pendingDoubleTapKey = 0;
        pendingSingleTapRunnable = null;
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg != null) {
            foregroundPackage = pkg.toString();
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                TaskManager.recordActiveApp(this, foregroundPackage);
            }
        }

        // power_on_home: the firmware opened Live TV by itself at power-on, before anyone pressed a key.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && powerOnRedirect(powerOnLiveTv, pkg, SystemClock.elapsedRealtime(), powerOnUntil)) {
            openHome();
            return;
        }

        if (pkg != null && "com.google.android.apps.tv.launcherx".contentEquals(pkg)) {
            String className = cls != null ? cls.toString() : "";
            if (isAllowedLauncherxActivity(className) || isDashboardActive()) {
                handler.removeCallbacks(redirectHomeRunnable);
            } else if (isStockHomeActivity(className)) {
                handler.removeCallbacks(redirectHomeRunnable);
                handler.postDelayed(redirectHomeRunnable, 350L);
            }
        }

        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (!stockHome.isEmpty() && cls != null && stockHome.contains(cls.toString())) {
            openHome();
        }
    }

    /** How long power_on_home stays armed after the screen turns on without a reboot. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    /** power_on_home: armed for the boot window (if this bind is part of the boot) and after every screen-on. */
    private void armPowerOnHome() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) powerOnUntil = BOOT_WINDOW_MS;
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    static boolean powerOnRedirect(String liveTv, CharSequence pkg, long now, long armedUntil) {
        return !liveTv.isEmpty() && pkg != null && liveTv.contentEquals(pkg) && now < armedUntil;
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    @Override
    public void onInterrupt() {
    }
}
