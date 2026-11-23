package com.xrpocket.probe;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Disposable test APK: checks the broker and attempts to create an OpenXR instance. */
public final class ProbeActivity extends Activity {
    private static native String probe(Activity activity);
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this); status.setTextSize(18); status.setPadding(24, 48, 24, 24);
        status.setTextColor(Color.WHITE); status.setBackgroundColor(Color.rgb(20, 38, 35));
        status.setText("XR Probe: verificando broker e runtime…"); setContentView(status);
        String broker;
        try (Cursor cursor = getContentResolver().query(Uri.parse("content://org.khronos.openxr.system_runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active/0"), null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                broker = cursor.getString(cursor.getColumnIndexOrThrow("package_name")) + " / " + cursor.getString(cursor.getColumnIndexOrThrow("so_filename"));
            } else broker = "sem runtime";
        } catch (Exception error) { broker = "erro: " + error; }
        final String resolved = broker;
        Log.i("XRProbe", "BROKER " + resolved);
        new Thread(() -> {
            String result;
            try { System.loadLibrary("xrprobe"); result = probe(this); }
            catch (Throwable error) { result = "JNI error: " + error; }
            final String message = "Broker: " + resolved + "\nOpenXR: " + result;
            Log.i("XRProbe", message);
            runOnUiThread(() -> status.setText(message));
        }, "xr-probe").start();
    }
}
