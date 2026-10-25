package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(HomeKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(HomeKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(HomeKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(HomeKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(HomeKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 11, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(HomeKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(HomeKeyService.powerOnRedirect(TV, TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, HomeKeyService.BOOT_WINDOW_MS));
        assertFalse(HomeKeyService.powerOnRedirect(TV, null, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, HomeKeyService.BOOT_WINDOW_MS, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(HomeKeyService.powerOnRedirect("", TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test
    public void testIsStockHomeActivity() {
        assertTrue(HomeKeyService.isStockHomeActivity("com.google.android.apps.tv.launcherx.home.HomeActivity"));
        assertTrue(HomeKeyService.isStockHomeActivity("com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity"));
        
        assertFalse(HomeKeyService.isStockHomeActivity("com.google.android.apps.tv.launcherx.dashboard.DashboardHandler"));
        assertFalse(HomeKeyService.isStockHomeActivity("com.google.android.apps.tv.launcherx.dashboard.SleepTimerActivity"));
        assertFalse(HomeKeyService.isStockHomeActivity("android.widget.LinearLayout"));
        assertFalse(HomeKeyService.isStockHomeActivity("android.view.ViewGroup"));
        assertFalse(HomeKeyService.isStockHomeActivity(""));
        assertFalse(HomeKeyService.isStockHomeActivity(null));
    }

    @Test
    public void testIsAllowedLauncherxActivity() {
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.dashboard.DashboardHandler"));
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.dashboard.SleepTimer"));
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.dashboard.GoogleHome"));
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.profile.ProfileActivity"));
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.AccountActivity"));
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.ReauthDialog"));
        assertTrue(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.PinLockActivity"));

        assertFalse(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.home.HomeActivity"));
        assertFalse(HomeKeyService.isAllowedLauncherxActivity("com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity"));
        assertFalse(HomeKeyService.isAllowedLauncherxActivity("android.widget.LinearLayout"));
        assertFalse(HomeKeyService.isAllowedLauncherxActivity("android.widget.ImageView"));
        assertFalse(HomeKeyService.isAllowedLauncherxActivity(""));
        assertFalse(HomeKeyService.isAllowedLauncherxActivity(null));
    }
}
