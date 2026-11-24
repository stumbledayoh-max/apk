package com.xrpocket.probe;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Disposable test APK: checks the broker and attempts to create an OpenXR instance. */
public final class ProbeActivity extends Activity {
    private static native String probe(Activity activity, Context applicationContext);
    private static native String probeInput(Activity activity, String pressPacket, String releasePacket);
    private TextView status;

    /**
     * In an integrated copy, feed synthetic landmarks through the actual
     * OLFX-derived router and PH5 serializer before testing OpenXR actions.
     * The separate-launcher test intentionally falls back to fixed packets.
     */
    private static String[] syntheticOlfxPackets() {
        try {
            Class<?> routerType = Class.forName("com.bigxr.olfx.HandRouting");
            Class<?> detectionType = Class.forName("com.bigxr.olfx.HandRouting$Detection");
            Class<?> mathType = Class.forName("com.bigxr.embedded.HandsMath");
            Class<?> stateType = Class.forName("com.bigxr.embedded.HandsMath$State");
            Object router = routerType.getConstructor().newInstance();
            Object array = java.lang.reflect.Array.newInstance(detectionType, 1);
            java.lang.reflect.Constructor<?> detection = detectionType.getConstructor(
                    float[].class, float[].class, float[].class, int.class, float.class);
            java.lang.reflect.Method route = routerType.getMethod("route", array.getClass(),
                    int.class, int.class, int.class, long.class);
            java.lang.reflect.Method classify = mathType.getDeclaredMethod("classify", float[].class,
                    float[].class, float[].class, boolean.class, float.class, float.class);
            java.lang.reflect.Method packet = mathType.getDeclaredMethod("packet", stateType, stateType);
            java.lang.reflect.Constructor<?> emptyState = stateType.getDeclaredConstructor();
            java.lang.reflect.Field trigger = stateType.getDeclaredField("trigger");
            classify.setAccessible(true); packet.setAccessible(true);
            emptyState.setAccessible(true); trigger.setAccessible(true);
            String[] output = new String[2];
            for (int stage = 0; stage < 2; stage++) {
                float[] x = new float[21], y = new float[21], z = new float[21];
                java.util.Arrays.fill(x, .2f); java.util.Arrays.fill(y, .5f);
                y[9] = .7f; x[5] = .24f; x[17] = .16f;
                x[8] = stage == 0 ? .2f : .34f; // pinched, then open
                java.lang.reflect.Array.set(array, 0, detection.newInstance(x, y, z, 0, .99f));
                Object[] hands = (Object[]) route.invoke(router, array, 0, 640, 640,
                        1_000_000_000L + stage * 100_000_000L);
                Object hand = hands[0];
                if (hand == null) throw new IllegalStateException("synthetic left hand missing");
                Class<?> handType = hand.getClass();
                Object left = classify.invoke(null, handType.getField("x").get(hand), handType.getField("y").get(hand),
                        handType.getField("z").get(hand), true, handType.getField("metricScaleX").getFloat(hand),
                        handType.getField("metricScaleY").getFloat(hand));
                trigger.setBoolean(left, handType.getField("trigger").getBoolean(hand));
                output[stage] = (String) packet.invoke(null, left, emptyState.newInstance());
                String[] fields = output[stage].split(" ");
                if (fields.length != 30 || !fields[1].equals("1")
                        || !fields[2].equals(stage == 0 ? "1" : "0")) {
                    throw new IllegalStateException("unexpected PH5 packet");
                }
            }
            Log.i("XRProbe", "OLFX_PH5_PACKETS_READY");
            return output;
        } catch (ClassNotFoundException missing) {
            Log.i("XRProbe", "OLFX classes absent; using separate-launcher static PH5 fixture");
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e("XRProbe", "OLFX synthetic routing failed", error);
            return null;
        }
    }

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
            try {
                System.loadLibrary("xrprobe");
                result = probe(this, getApplicationContext());
                if (result.startsWith("3 instances")) {
                    String[] packets = syntheticOlfxPackets();
                    result += "\nInputs: " + probeInput(this,
                            packets == null ? null : packets[0], packets == null ? null : packets[1]);
                }
            } catch (Throwable error) { result = "JNI error: " + error; }
            final String message = "Broker: " + resolved + "\nOpenXR: " + result;
            Log.i("XRProbe", message);
            runOnUiThread(() -> status.setText(message));
        }, "xr-probe").start();
    }
}
