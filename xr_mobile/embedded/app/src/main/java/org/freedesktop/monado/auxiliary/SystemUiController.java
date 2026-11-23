package org.freedesktop.monado.auxiliary;

import android.view.View;

/** Minimal replacement for Monado's UI helper, preserving its JNI-visible API. */
public final class SystemUiController {
    private final View view;
    public SystemUiController(View view) { this.view = view; }
    @SuppressWarnings("deprecation")
    public void hide() {
        // Client.blockingConnect may invoke this from a worker thread.
        view.post(() -> view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
    }
}
