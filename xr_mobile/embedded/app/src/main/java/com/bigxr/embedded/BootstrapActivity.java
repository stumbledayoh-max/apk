package com.bigxr.embedded;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/** First-run screen lives inside the generated game APK, not in a second app. */
public final class BootstrapActivity extends Activity {
    private static final int CAMERA_REQUEST = 214, STORAGE_REQUEST = 215;
    private static final String TARGET = "com.bigxr.embedded.TARGET_ACTIVITY";
    private static final String HAS_OBB = "com.bigxr.embedded.HAS_OBB";
    private static boolean monitoring;
    private static volatile boolean handsEnabled;
    private boolean pendingHands, preparing;
    private TextView status;
    private Button withHands, withoutHands;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!monitoring) {
            monitoring = true;
            getApplication().registerActivityLifecycleCallbacks(new AppLifecycle());
            BroadcastReceiver stopped = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { handsEnabled = false; }
            };
            IntentFilter filter = new IntentFilter(HandService.ACTION_STOPPED);
            if (Build.VERSION.SDK_INT >= 33) getApplicationContext().registerReceiver(stopped, filter, Context.RECEIVER_NOT_EXPORTED);
            else getApplicationContext().registerReceiver(stopped, filter);
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(Color.rgb(20, 27, 20));
        TextView title = new TextView(this);
        title.setText("BigXR integrado"); title.setTextColor(Color.WHITE); title.setTextSize(26);
        layout.addView(title);
        TextView description = new TextView(this);
        description.setText("Experimento: o jogo e o runtime estão neste APK. Mãos usam a câmera traseira e são aproximadas como controles. Você pode abrir sem câmera. A compatibilidade depende do jogo e do celular.");
        description.setTextColor(Color.LTGRAY); description.setTextSize(16);
        description.setPadding(0, padding / 2, 0, padding / 2); layout.addView(description);
        withHands = new Button(this); withHands.setText("Permitir câmera e abrir");
        withHands.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) beginOpen(true);
            else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        });
        layout.addView(withHands);
        withoutHands = new Button(this); withoutHands.setText("Abrir sem câmera");
        withoutHands.setOnClickListener(view -> beginOpen(false)); layout.addView(withoutHands);
        status = new TextView(this); status.setTextColor(Color.LTGRAY); status.setPadding(0, padding / 2, 0, 0);
        layout.addView(status); setContentView(layout);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_REQUEST) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) Toast.makeText(this, "Câmera não permitida: abrindo sem mãos.", Toast.LENGTH_LONG).show();
            beginOpen(granted);
        } else if (requestCode == STORAGE_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) prepareAndOpen(pendingHands);
            else status.setText("O OBB precisa de acesso ao armazenamento no Android 10.");
        }
    }

    private Bundle metadata() throws PackageManager.NameNotFoundException {
        ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
        return info.metaData == null ? Bundle.EMPTY : info.metaData;
    }

    private void beginOpen(boolean hands) {
        if (preparing) return;
        pendingHands = hands;
        try {
            if (metadata().getBoolean(HAS_OBB, false) && Build.VERSION.SDK_INT == 29
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            } else prepareAndOpen(hands);
        } catch (Exception error) { status.setText("Não foi possível ler a configuração do jogo: " + error.getMessage()); }
    }

    private void prepareAndOpen(boolean hands) {
        if (preparing) return;
        preparing = true; withHands.setEnabled(false); withoutHands.setEnabled(false);
        status.setText("Preparando dados do jogo…");
        new Thread(() -> {
            try {
                Bundle meta = metadata();
                if (meta.getBoolean(HAS_OBB, false)) installObb();
                String target = meta.getString(TARGET);
                if (target == null || target.isEmpty() || target.equals(getClass().getName()))
                    throw new IOException("Tela original do jogo não encontrada");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    handsEnabled = hands;
                    if (hands) startHands(this); else stopService(new Intent(this, HandService.class));
                    try {
                        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.setClassName(getPackageName(), target); startActivity(intent); finish();
                    } catch (Exception error) {
                        stopService(new Intent(this, HandService.class));
                        preparing = false; withHands.setEnabled(true); withoutHands.setEnabled(true);
                        status.setText("Não foi possível abrir o jogo: " + error.getMessage());
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    preparing = false; withHands.setEnabled(true); withoutHands.setEnabled(true);
                    status.setText("Não foi possível preparar o jogo: " + error.getMessage());
                });
            }
        }, "BigXR-prepare-game").start();
    }

    /** Extract only the explicitly selected OBB, without overwriting existing data. */
    private void installObb() throws Exception {
        byte[] description;
        try (InputStream in = getAssets().open("bigxr_obb/manifest.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int n, size = 0;
            while ((n = in.read(block)) != -1) {
                size += n; if (size > 65536) throw new IOException("Metadados do OBB grandes demais");
                out.write(block, 0, n);
            }
            description = out.toByteArray();
        }
        JSONObject meta = new JSONObject(new String(description, StandardCharsets.UTF_8));
        String name = meta.getString("name"), expected = meta.getString("sha256");
        long size = meta.getLong("size");
        if (name.length() > 255 || !Pattern.matches("(?:main|patch)\\.[0-9]{1,20}\\." + Pattern.quote(getPackageName()) + "\\.obb", name)
                || !expected.matches("[0-9a-f]{64}") || size <= 0 || size > 1_700_000_000L)
            throw new IOException("Metadados do OBB inválidos");
        File dir = getObbDir();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("Android bloqueou a pasta OBB deste jogo");
        File target = new File(dir, name);
        if (!target.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) throw new IOException("Destino OBB inválido");
        if (target.exists()) {
            if (target.isFile() && target.length() == size && hashFile(target).equals(expected)) return;
            throw new IOException("Já existe um OBB diferente. Preserve-o ou remova-o manualmente antes de tentar novamente");
        }
        File temporary = File.createTempFile(".bigxr-", ".part", dir);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long copied = 0;
            try (InputStream in = getAssets().open("bigxr_obb/" + name); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] block = new byte[256 * 1024]; int n;
                while ((n = in.read(block)) != -1) {
                    if (n == 0) continue;
                    copied += n; if (copied > size) throw new IOException("OBB embutido maior que o esperado");
                    digest.update(block, 0, n); out.write(block, 0, n);
                }
                out.getFD().sync();
            }
            if (copied != size || !hex(digest.digest()).equals(expected)) throw new IOException("OBB embutido está corrompido");
            Files.move(temporary.toPath(), target.toPath());
        } finally { temporary.delete(); }
    }
    private static String hashFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] block = new byte[256 * 1024]; int n;
            while ((n = in.read(block)) != -1) if (n > 0) digest.update(block, 0, n);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static void startHands(Context context) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try { context.startForegroundService(new Intent(context, HandService.class)); }
        catch (RuntimeException error) { android.util.Log.e("BigXRHands", "Não foi possível iniciar a câmera", error); }
    }

    /** Stop the camera shortly after all game activities leave the foreground. */
    private static final class AppLifecycle implements Application.ActivityLifecycleCallbacks {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int started;
        private Runnable stop;
        @Override public void onActivityStarted(Activity activity) {
            started++;
            if (stop != null) handler.removeCallbacks(stop);
            if (!(activity instanceof BootstrapActivity) && handsEnabled) startHands(activity);
        }
        @Override public void onActivityStopped(Activity activity) {
            started = Math.max(0, started - 1);
            if (started == 0) {
                Context app = activity.getApplicationContext();
                stop = () -> { if (started == 0) app.stopService(new Intent(app, HandService.class)); };
                handler.postDelayed(stop, 3000);
            }
        }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}
