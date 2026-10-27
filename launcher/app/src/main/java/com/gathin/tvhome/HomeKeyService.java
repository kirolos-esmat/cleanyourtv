package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.net.Uri;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * With power_on_home_always set, Home comes up at every power-on whatever is in front, for TVs that resume their
 * last source: a Philips back on an HDMI input sends no window event until the input has a picture.
 *
 * With home_long_press_home set, it takes every Home press, so one held past the long-press timeout opens this launcher
 * instead of Android's ALL_APPS action, which nothing answers once the stock launcher is disabled (Philips TPM191E).
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
    /** home_long_press_home: this service takes every Home press, held ones included. */
    private boolean homeLongPressHome;
    /** app_keys "front:" entries, and the package of the activity in front, from window changes while there are any. */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    private String frontPackage = "";
    /** power_on_home_always: Home at every power-on, not only over Live TV. */
    private boolean powerOnAlways;
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

    private static final Set<String> FORCE_STOP_IGNORED = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.apps.tv.dreamx",
            "com.google.android.apps.mediashell",
            "com.google.android.tungsten.setupwraith",
            "com.android.tv.settings",
            "com.android.settings",
            "com.google.android.katniss",
            "com.google.android.inputmethod.latin",
            "com.android.shell",
            "com.android.providers.settings",
            "com.gathin.tvhome"
    ));

    private final List<String> forceStopQueue = new ArrayList<>();
    private String currentForceStopPkg = null;
    private long currentForceStopStartTime = 0;
    private int forceStopStep = 0;
    private Runnable onForceStopComplete = null;
    private boolean suppressingKeyBack = false;

    private void performBackAction() {
        suppressingKeyBack = true;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> suppressingKeyBack = false, 350);
    }

    private final Runnable forceStopTimeoutRunnable = () -> {
        if (!forceStopQueue.isEmpty()) {
            processNextForceStop();
        } else {
            currentForceStopPkg = null;
            forceStopStep = 0;
            performBackAction();
            handler.postDelayed(this::openHome, 100);
            if (onForceStopComplete != null) {
                onForceStopComplete.run();
                onForceStopComplete = null;
            }
        }
    };

    public void forceStopPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        List<String> list = new ArrayList<>();
        list.add(packageName);
        forceStopPackages(list, null);
    }

    public void forceStopPackages(List<String> packages, Runnable onComplete) {
        if (packages == null || packages.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        handler.post(() -> {
            cancelForceStopQueue();
            for (String p : packages) {
                if (p != null && !p.isEmpty() && !p.equals(getPackageName()) && !FORCE_STOP_IGNORED.contains(p)) {
                    forceStopQueue.add(p);
                }
            }
            onForceStopComplete = onComplete;
            if (forceStopQueue.isEmpty()) {
                if (onForceStopComplete != null) {
                    onForceStopComplete.run();
                    onForceStopComplete = null;
                }
                return;
            }
            processNextForceStop();
        });
    }

    private void cancelForceStopQueue() {
        handler.removeCallbacks(forceStopTimeoutRunnable);
        forceStopQueue.clear();
        currentForceStopPkg = null;
        forceStopStep = 0;
        if (onForceStopComplete != null) {
            onForceStopComplete = null;
        }
    }

    private void processNextForceStop() {
        handler.removeCallbacks(forceStopTimeoutRunnable);
        if (forceStopQueue.isEmpty()) {
            currentForceStopPkg = null;
            forceStopStep = 0;
            performBackAction();
            handler.postDelayed(this::openHome, 100);
            if (onForceStopComplete != null) {
                onForceStopComplete.run();
                onForceStopComplete = null;
            }
            return;
        }

        currentForceStopPkg = forceStopQueue.remove(0);
        forceStopStep = 1;
        currentForceStopStartTime = System.currentTimeMillis();

        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(currentForceStopPkg);
            }
        } catch (Exception ignored) {}

        handler.postDelayed(forceStopTimeoutRunnable, 1500);

        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", currentForceStopPkg, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            handler.postDelayed(() -> {
                if (forceStopStep == 1) {
                    handleForceStopDetails();
                }
            }, 100);
        } catch (Exception e) {
            handler.removeCallbacks(forceStopTimeoutRunnable);
            handler.post(this::processNextForceStop);
        }
    }

    private boolean isScreenForPackage(AccessibilityNodeInfo root, String expectedPkg) {
        if (root == null || expectedPkg == null) return false;
        List<AccessibilityNodeInfo> summaries = root.findAccessibilityNodeInfosByViewId("android:id/summary");
        if (summaries != null && !summaries.isEmpty()) {
            for (AccessibilityNodeInfo s : summaries) {
                CharSequence text = s.getText();
                s.recycle();
                if (text != null && text.toString().trim().equalsIgnoreCase(expectedPkg)) {
                    return true;
                }
            }
        }
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(expectedPkg);
        if (found != null && !found.isEmpty()) {
            for (AccessibilityNodeInfo f : found) f.recycle();
            return true;
        }
        return false;
    }

    private boolean isScreenForApp(AccessibilityNodeInfo root, String expectedPkg) {
        if (root == null || expectedPkg == null) return false;
        if (isScreenForPackage(root, expectedPkg)) return true;
        try {
            PackageManager pm = getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(expectedPkg, 0));
            if (label != null) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label.toString());
                if (found != null && !found.isEmpty()) {
                    for (AccessibilityNodeInfo f : found) f.recycle();
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isForceStopText(CharSequence cs) {
        if (cs == null) return false;
        String t = cs.toString().trim().toLowerCase(Locale.ROOT);
        return t.equals("force stop")
                || t.contains("force stop")
                || t.contains("فرض الإيقاف")
                || t.contains("إيقاف إجباري")
                || t.contains("forcer l'arrêt")
                || t.contains("forzar detención")
                || t.contains("beenden erzwingen")
                || t.contains("остановить")
                || t.contains("zorla durdur")
                || t.contains("forçar parada")
                || t.contains("强行停止")
                || t.contains("termina")
                || t.contains("forza interruzione");
    }

    private static boolean isConfirmText(CharSequence cs) {
        if (cs == null) return false;
        String t = cs.toString().trim().toLowerCase(Locale.ROOT);
        return t.equals("ok")
                || t.equals("موافق")
                || t.equals("حسنا")
                || t.equals("حسناً")
                || t.equals("نعم")
                || t.equals("yes")
                || t.equals("oui")
                || t.equals("aceptar")
                || t.equals("да")
                || t.equals("tamam")
                || t.equals("确定")
                || t.equals("conferma");
    }

    private AccessibilityNodeInfo findForceStopNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isForceStopText(node.getText()) || isForceStopText(node.getContentDescription())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findForceStopNode(child);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findConfirmNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isConfirmText(node.getText()) || isConfirmText(node.getContentDescription())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findConfirmNode(child);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            if (cur.isClickable()) {
                return cur;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != node) cur.recycle();
            cur = parent;
        }
        return null;
    }

    private void handleForceStopDetails() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            handler.postDelayed(() -> {
                if (forceStopStep == 1) handleForceStopDetails();
            }, 60);
            return;
        }
        try {
            long elapsed = System.currentTimeMillis() - currentForceStopStartTime;
            if (!isScreenForApp(root, currentForceStopPkg) && elapsed < 400) {
                handler.postDelayed(() -> {
                    if (forceStopStep == 1) handleForceStopDetails();
                }, 60);
                return;
            }
            AccessibilityNodeInfo forceStopNode = findForceStopNode(root);
            if (forceStopNode != null) {
                AccessibilityNodeInfo clickable = findClickableAncestor(forceStopNode);
                if (forceStopNode != clickable) forceStopNode.recycle();
                if (clickable != null) {
                    if (clickable.isEnabled()) {
                        forceStopStep = 2;
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        clickable.recycle();
                        handler.postDelayed(() -> {
                            if (forceStopStep == 2) handleForceStopConfirm();
                        }, 60);
                        return;
                    } else {
                        clickable.recycle();
                        handler.removeCallbacks(forceStopTimeoutRunnable);
                        handler.postDelayed(this::processNextForceStop, 50);
                        return;
                    }
                }
            }
            handler.postDelayed(() -> {
                if (forceStopStep == 1) handleForceStopDetails();
            }, 60);
        } finally {
            root.recycle();
        }
    }

    private void handleForceStopConfirm() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            handler.postDelayed(() -> {
                if (forceStopStep == 2) handleForceStopConfirm();
            }, 60);
            return;
        }
        try {
            AccessibilityNodeInfo confirmNode = findConfirmNode(root);
            if (confirmNode != null) {
                AccessibilityNodeInfo clickable = findClickableAncestor(confirmNode);
                if (confirmNode != clickable) confirmNode.recycle();
                if (clickable != null) {
                    forceStopStep = 3;
                    handler.removeCallbacks(forceStopTimeoutRunnable);
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    clickable.recycle();
                    handler.postDelayed(this::processNextForceStop, 100);
                    return;
                }
            }
            handler.postDelayed(() -> {
                if (forceStopStep == 2) handleForceStopConfirm();
            }, 60);
        } finally {
            root.recycle();
        }
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
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
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
        homeLongPressHome = config.homeLongPressHome;
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        boolean watchFront = !frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty();
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
        powerOnAlways = config.powerOnHomeAlways && !config.bootToLiveTv && openOnStart.isEmpty();
        if (!powerOnLiveTv.isEmpty() || powerOnAlways) {
            armPowerOnHome();
        } else if (screenOnReceiver != null) {
            unregisterReceiver(screenOnReceiver);
            screenOnReceiver = null;
            powerOnUntil = 0;
        }
        // home_key_service.xml subscribes to no events, so the redirects ask for window changes only when one is on.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (!stockHome.isEmpty() || !powerOnLiveTv.isEmpty() || watchFront)) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            setServiceInfo(info);
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

    /** A tap's target: the "front:" entry while the button's own app is already in front, else the usual one. */
    private String inFront(Map<Integer, String> keys, Map<Integer, String> front, int code) {
        String target = keys.get(code);
        String alt = front.get(code);
        return alt != null && RemoteKeys.inFront(target, frontPackage) ? alt : target;
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
        if (forceStopStep > 0 && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!suppressingKeyBack && (event.getKeyCode() == KeyEvent.KEYCODE_HOME || event.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
                cancelForceStopQueue();
            }
        }
        // power_on_home: the first key after power-on hands the TV to the user (power and wake keys don't count).
        if (powerOnUntil != 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP) {
            powerOnUntil = 0;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;

        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, inFront(appScancodes, frontAppScancodes, event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
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
        if (doubleApp == null && RemoteKeys.handle(this, event, inFront(appKeys, frontAppKeys, event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;

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

        // home_long_press_home: Android never sees the Home key, so a held press cannot turn into ALL_APPS.
        if (homeLongPressHome && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
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

        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            redirectHomeKey(event);
            return false;
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
        cancelForceStopQueue();
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

        if (forceStopStep > 0 && currentForceStopPkg != null) {
            if (pkg != null && ("com.android.tv.settings".contentEquals(pkg) || "com.android.settings".contentEquals(pkg))) {
                if (forceStopStep == 1) {
                    handleForceStopDetails();
                } else if (forceStopStep == 2) {
                    handleForceStopConfirm();
                }
                return;
            }
        }
        if (pkg != null) {
            foregroundPackage = pkg.toString();
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                TaskManager.recordActiveApp(this, foregroundPackage);
            }
        }

        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // app_keys "front:" entries: only activities count, so a toast or the volume bar doesn't change the app in front.
        if ((!frontAppKeys.isEmpty() || !frontAppScancodes.isEmpty()) && pkg != null && cls != null) {
            try {
                ComponentName window = new ComponentName(pkg.toString(), cls.toString());
                getPackageManager().getActivityInfo(window, 0);
                frontPackage = window.getPackageName();
            } catch (PackageManager.NameNotFoundException notAnActivity) {
                // a dialog, panel or overlay: the app under it is still in front
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
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            powerOnUntil = BOOT_WINDOW_MS;
            if (powerOnAlways) handler.postDelayed(this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
        }
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                powerOnUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                if (powerOnAlways) handler.postDelayed(HomeKeyService.this::homeAtPowerOn, POWER_ON_ALWAYS_DELAY_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /** power_on_home_always: after the firmware has brought its last source back (Philips: within 0.5 s). */
    static final long POWER_ON_ALWAYS_DELAY_MS = 800L;

    /** power_on_home_always: Home, unless a key was pressed since the screen came on (the user has the TV). */
    private void homeAtPowerOn() {
        if (stillArmed(SystemClock.elapsedRealtime(), powerOnUntil)) openHome();
    }

    static boolean stillArmed(long now, long armedUntil) {
        return now < armedUntil;
    }

    /** The settings menu offers Restart only while this helper is bound: it opens the system power menu. */
    static boolean canOpenPowerMenu() {
        return running != null;
    }

    /** The system power menu (Restart, and Power off where the firmware has it); the TV asks before it acts. */
    static void openPowerMenu() {
        HomeKeyService service = running;
        if (service != null) service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
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

    private static final long HOME_KEY_DELAY_MS = 250;

    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.postDelayed(this::openHome, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    @Override
    public void onInterrupt() {
    }
}
