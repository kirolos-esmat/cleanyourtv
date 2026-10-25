package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {

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
