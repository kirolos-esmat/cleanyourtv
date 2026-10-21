package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Full-screen pure black window for audio-only listening.
 * Screen remains dark while background audio plays; any remote button press wakes it up immediately.
 */
public final class BlackoutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View black = new View(this);
        black.setBackgroundColor(0xFF000000);
        setContentView(black);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return true;
    }
}
