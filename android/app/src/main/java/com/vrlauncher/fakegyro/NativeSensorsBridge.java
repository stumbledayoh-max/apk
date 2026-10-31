package com.vrlauncher.fakegyro;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;

/**
 * Ponte entre os sensores nativos do Android e o JavaScript do WebView.
 *
 * Como os WebViews antigos do Android muitas vezes têm bugs na entrega
 * de `deviceorientation`/`devicemotion`, lemos os sensores diretamente
 * via Android SensorManager com taxa configurável (até 120Hz) e
 * empurramos os dados para o JS via `window.__onNativeSensors(...)`.
 */
public class NativeSensorsBridge implements SensorEventListener {

    private final SensorManager sensorManager;
    private final WebView webView;

    private final Sensor accelSensor;
    private final Sensor gyroSensor;
    private final Sensor magSensor;
    private final Sensor rotVectorSensor;

    private boolean isListening = false;
    private int samplingRateUs = 8333; // ~120 Hz por padrão

    private float[] lastAccel = new float[3];
    private float[] lastGyro = new float[3];
    private float[] lastMag = new float[3];
    private float[] lastRotVec = new float[4];
    private boolean hasRotVec = false;

    private long lastPushTime = 0;
    private final long minPushIntervalMs = 7; // ~140 FPS max

    public NativeSensorsBridge(Context context, WebView webView) {
        this.webView = webView;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        this.accelSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        this.gyroSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        this.magSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) : null;
        this.rotVectorSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
    }

    @JavascriptInterface
    public synchronized void start() {
        if (isListening || sensorManager == null) return;
        isListening = true;

        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, samplingRateUs);
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, samplingRateUs);
        }
        if (magSensor != null) {
            sensorManager.registerListener(this, magSensor, samplingRateUs);
        }
        if (rotVectorSensor != null) {
            sensorManager.registerListener(this, rotVectorSensor, samplingRateUs);
        }
    }

    @JavascriptInterface
    public synchronized void stop() {
        if (!isListening || sensorManager == null) return;
        isListening = false;
        sensorManager.unregisterListener(this);
    }

    @JavascriptInterface
    public void setRate(int hz) {
        int safeHz = Math.max(15, Math.min(240, hz));
        this.samplingRateUs = 1000000 / safeHz;
        if (isListening) {
            stop();
            start();
        }
    }

    @JavascriptInterface
    public boolean hasGyro() {
        return gyroSensor != null;
    }

    @JavascriptInterface
    public boolean hasMag() {
        return magSensor != null;
    }

    @JavascriptInterface
    public boolean hasRotationVector() {
        return rotVectorSensor != null;
    }

    @JavascriptInterface
    public String info() {
        return String.format(Locale.US,
                "Device: %s %s (Android %s) | Gyro: %b | Mag: %b | RV: %b",
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
                hasGyro(), hasMag(), hasRotationVector());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isListening) return;

        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastAccel, 0, 3);
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            System.arraycopy(event.values, 0, lastGyro, 0, 3);
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, lastMag, 0, 3);
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            float[] q = new float[4];
            SensorManager.getQuaternionFromVector(q, event.values);
            // q = [w, x, y, z] -> converte para [x, y, z, w]
            lastRotVec[0] = q[1];
            lastRotVec[1] = q[2];
            lastRotVec[2] = q[3];
            lastRotVec[3] = q[0];
            hasRotVec = true;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastPushTime >= minPushIntervalMs) {
            lastPushTime = now;
            dispatchToJs(now);
        }
    }

    private void dispatchToJs(long t) {
        final StringBuilder js = new StringBuilder();
        js.append("if(window.__onNativeSensors){window.__onNativeSensors({");
        js.append(String.format(Locale.US, "a:[%.4f,%.4f,%.4f],", lastAccel[0], lastAccel[1], lastAccel[2]));
        if (hasGyro()) {
            js.append(String.format(Locale.US, "g:[%.4f,%.4f,%.4f],", lastGyro[0], lastGyro[1], lastGyro[2]));
        }
        if (hasMag()) {
            js.append(String.format(Locale.US, "m:[%.4f,%.4f,%.4f],", lastMag[0], lastMag[1], lastMag[2]));
        }
        if (hasRotVec) {
            js.append(String.format(Locale.US, "rv:[%.5f,%.5f,%.5f,%.5f],",
                    lastRotVec[0], lastRotVec[1], lastRotVec[2], lastRotVec[3]));
        }
        js.append(String.format(Locale.US, "t:%d});}", t));

        webView.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(js.toString(), null);
            } else {
                webView.loadUrl("javascript:" + js.toString());
            }
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // sem alteração
    }
}
