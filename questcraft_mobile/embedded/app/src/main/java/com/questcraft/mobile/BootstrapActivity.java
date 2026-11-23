package com.questcraft.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Build;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** First-run permission screen inside the QuestCraft APK, not a separate launcher app. */
public final class BootstrapActivity extends Activity {
    private static final int CAMERA_REQUEST = 214;
    private static final String TARGET = "com.questcraft.mobile.TARGET_ACTIVITY";
    private static boolean monitoring;
    private static boolean handsEnabled;

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
        title.setText("QuestCraft Mobile");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        layout.addView(title);
        TextView description = new TextView(this);
        description.setText("Port experimental: o rastreamento de mãos usa a câmera traseira enquanto o jogo está aberto. Os dados ficam no aparelho. Você pode continuar sem câmera. O funcionamento ainda não foi confirmado em todos os celulares.");
        description.setTextColor(Color.LTGRAY);
        description.setTextSize(16);
        description.setPadding(0, padding / 2, 0, padding / 2);
        layout.addView(description);
        Button withHands = new Button(this);
        withHands.setText("Permitir câmera e abrir");
        withHands.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openGame(true);
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            }
        });
        layout.addView(withHands);
        Button withoutHands = new Button(this);
        withoutHands.setText("Abrir sem câmera");
        withoutHands.setOnClickListener(view -> openGame(false));
        layout.addView(withoutHands);
        setContentView(layout);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_REQUEST) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) Toast.makeText(this, "Câmera não permitida: abrindo sem mãos.", Toast.LENGTH_LONG).show();
            openGame(granted);
        }
    }

    private void openGame(boolean hands) {
        handsEnabled = hands;
        if (hands) startHands(this);
        else stopService(new Intent(this, HandService.class));
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            String target = info.metaData == null ? null : info.metaData.getString(TARGET);
            if (target == null || target.isEmpty() || target.equals(getClass().getName())) {
                throw new IllegalStateException("Activity original do QuestCraft não encontrada");
            }
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), target);
            startActivity(intent);
            finish();
        } catch (Exception error) {
            stopService(new Intent(this, HandService.class));
            Toast.makeText(this, "Não foi possível abrir o QuestCraft: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void startHands(Context context) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            context.startForegroundService(new Intent(context, HandService.class));
        } catch (RuntimeException error) {
            android.util.Log.e("QuestCraftHands", "Não foi possível iniciar a câmera", error);
        }
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
                stop = () -> {
                    if (started == 0) app.stopService(new Intent(app, HandService.class));
                };
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
