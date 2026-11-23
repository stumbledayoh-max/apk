package com.xrpocket.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Native launcher. APK installation always goes through Android's confirmation UI. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int PICK_APK = 101, PICK_OBB = 102, INSTALL_APK = 103;
    private static final int CAMERA_PERMISSION = 104, STORAGE_PERMISSION = 105;
    private static final int PICK_ICON = 106, SAVE_GENERATED = 107, INSTALL_GENERATED = 108;
    private static final String BUILD_STATUS = "com.xrpocket.mobile.BUILD_STATUS";
    private static final AtomicBoolean CREATING = new AtomicBoolean(false);
    private static final long MAX_APK = 2L * 1024 * 1024 * 1024;
    private static final long MAX_OBB = 4L * 1024 * 1024 * 1024;
    private static final String BROKER = "org.khronos.openxr.system_runtime_broker";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor gyro, rotation;
    private TextView apkInfo, obbInfo, resultInfo, sensorInfo, handsInfo, brokerInfo, iconInfo, createdInfo;
    private HandSkeletonView handView;
    private ImageView iconPreview;
    private EditText gameName;
    private Button installButton, copyButton, playButton, selectApkButton, selectObbButton;
    private Button iconButton, clearIconButton, clearObbButton, createButton, saveButton, installCreatedButton;
    private String selectedPackage, selectedLabel, obbName;
    private Uri obbUri;
    private boolean busy, handsStarted;
    private long lastSensorUi;
    private File stagedApk, stagedIcon, generatedApk;

    private final BroadcastReceiver handsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BUILD_STATUS.equals(intent.getAction())) {
                String message = intent.getStringExtra("message");
                if (message != null) createdInfo.setText(message);
                if (intent.getBooleanExtra("finished", false)) { setBusy(false); refreshCreated(); }
            } else if (HandService.ACTION_STOPPED.equals(intent.getAction())) {
                handsStarted = false;
                handView.update(null, null);
                handsInfo.setText("Câmera parada. Ative novamente para testar ou usar mãos.");
            } else if (HandService.ACTION_PREVIEW.equals(intent.getAction())) {
                float[] left = intent.getFloatArrayExtra("left"), right = intent.getFloatArrayExtra("right");
                handView.update(left, right);
                String count = (left != null ? "esquerda " : "") + (right != null ? "direita" : "");
                handsInfo.setText(count.isEmpty() ? "Câmera ativa · nenhuma mão detectada" :
                        "Câmera ativa · " + count.trim() +
                        ((intent.getBooleanExtra("leftTrigger", false) || intent.getBooleanExtra("rightTrigger", false)) ? " · pinça" : ""));
                handsStarted = true;
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("xr-launcher", MODE_PRIVATE);
        selectedPackage = prefs.getString("package", null);
        selectedLabel = prefs.getString("label", null);
        String savedObb = prefs.getString("obb_uri", null);
        if (savedObb != null) obbUri = Uri.parse(savedObb);
        obbName = prefs.getString("obb_name", null);
        File staged = new File(getCacheDir(), "staged");
        stagedApk = new File(staged, "selected.apk");
        stagedIcon = new File(staged, "icon.png");
        generatedApk = new File(staged, "BigXR-jogo.apk");
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotation == null) rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        buildUi();
        IntentFilter filter = new IntentFilter(HandService.ACTION_PREVIEW);
        filter.addAction(HandService.ACTION_STOPPED);
        filter.addAction(BUILD_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(handsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(handsReceiver, filter);
        refreshSelection(); refreshIcon(); refreshCreated();
        setBusy(CREATING.get());
        if (CREATING.get()) createdInfo.setText("Criando a cópia em segundo plano…");
        refreshBroker();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(3), 1f); return view;
    }
    private void gap(LinearLayout root, int height) { View space = new View(this); root.addView(space, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? Color.rgb(8, 26, 24) : Color.rgb(220, 238, 232));
        b.setBackground(background(primary ? Color.rgb(120, 220, 188) : Color.rgb(38, 57, 57), 10));
        b.setPadding(dp(15), dp(12), dp(15), dp(12)); b.setOnClickListener(action); return b;
    }
    private LinearLayout card(LinearLayout root, String eyebrow, String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18)); card.setBackground(background(Color.rgb(23, 36, 37), 14));
        card.addView(text(eyebrow, 11, Color.rgb(123, 215, 183), true)); gap(card, 5);
        card.addView(text(title, 19, Color.WHITE, true)); gap(card, 10); root.addView(card); gap(root, 12); return card;
    }
    private void addButton(LinearLayout root, Button b) {
        root.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))); gap(root, 8);
    }

    private void buildUi() {
        int white = Color.rgb(237, 246, 243), muted = Color.rgb(176, 194, 188);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(10, 19, 20));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(32), dp(20), dp(32));
        scroll.addView(body); setContentView(scroll);
        body.addView(text("BIGXR  /  EXPERIMENTAL", 11, Color.rgb(123, 215, 183), true)); gap(body, 8);
        body.addView(text("Seus jogos, em XR.", 29, white, true)); gap(body, 8);
        body.addView(text("Crie uma cópia experimental de um APK que você tem direito de usar. O jogo, Monado e a ponte de mãos ficam no mesmo APK criado.", 15, muted, false)); gap(body, 14);

        LinearLayout game = card(body, "01 / JOGO", "Escolha os arquivos");
        apkInfo = text("Nenhum APK selecionado.", 14, muted, false); game.addView(apkInfo); gap(game, 12);
        selectApkButton = button("Selecionar APK", true, v -> chooseFile(false)); addButton(game, selectApkButton);
        obbInfo = text("OBB é opcional.", 14, muted, false); game.addView(obbInfo); gap(game, 10);
        selectObbButton = button("Selecionar OBB (opcional)", false, v -> chooseFile(true)); addButton(game, selectObbButton);
        clearObbButton = button("Remover OBB selecionado", false, v -> clearObb()); addButton(game, clearObbButton);
        resultInfo = text("", 13, muted, false); game.addView(resultInfo);

        LinearLayout creator = card(body, "02 / CRIAR", "Personalize seu APK");
        creator.addView(text("Nome do jogo", 14, white, true));
        gameName = new EditText(this); gameName.setSingleLine(true); gameName.setTextSize(16);
        gameName.setTextColor(white); gameName.setHintTextColor(muted); gameName.setHint("Digite o nome do jogo");
        gameName.setContentDescription("Nome do jogo"); gameName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        gameName.setText(prefs.getString("custom_label", selectedLabel == null ? "" : selectedLabel));
        creator.addView(gameName, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))); gap(creator, 8);
        gameName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("custom_label", s.toString()).apply(); invalidateCreated();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        iconInfo = text("Ícone original do jogo.", 13, muted, false); creator.addView(iconInfo); gap(creator, 6);
        iconPreview = new ImageView(this); iconPreview.setContentDescription("Prévia do ícone escolhido");
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        creator.addView(iconPreview, new LinearLayout.LayoutParams(dp(72), dp(72))); gap(creator, 8);
        iconButton = button("Escolher foto para o ícone", false, v -> chooseIcon()); addButton(creator, iconButton);
        clearIconButton = button("Usar ícone original", false, v -> clearIcon()); addButton(creator, clearIconButton);
        creator.addView(text("A cópia terá outra assinatura. Licenças, integridade e compatibilidade não são garantidas. O OBB selecionado é incluído se couber no APK.", 12, muted, false)); gap(creator, 10);
        createButton = button("Criar APK com BigXR", true, v -> confirmCreate()); addButton(creator, createButton);
        createdInfo = text("Ainda não há APK criado.", 13, muted, false); creator.addView(createdInfo); gap(creator, 10);
        saveButton = button("Salvar APK criado", false, v -> saveCreated()); addButton(creator, saveButton);
        installCreatedButton = button("Instalar APK criado", false, v -> installCreated()); addButton(creator, installCreatedButton);

        LinearLayout legacy = card(body, "03 / ALTERNATIVO", "Instalar APK original");
        legacy.addView(text("Este modo antigo instala o jogo separado, sem integrar o BigXR. Use Criar APK acima para a versão independente.", 12, muted, false)); gap(legacy, 10);
        brokerInfo = text("Verificando runtime…", 13, muted, false); legacy.addView(brokerInfo); gap(legacy, 10);
        installButton = button("Instalar jogo", false, v -> installSelected()); addButton(legacy, installButton);
        copyButton = button("Copiar OBB para o jogo", false, v -> requestObbCopy()); addButton(legacy, copyButton);

        LinearLayout tracking = card(body, "04 / SENSORES", "Mãos e movimento");
        sensorInfo = text("Verificando giroscópio…", 13, muted, false); tracking.addView(sensorInfo); gap(tracking, 10);
        handsInfo = text("Câmera parada.", 13, muted, false); tracking.addView(handsInfo); gap(tracking, 8);
        handView = new HandSkeletonView(this);
        tracking.addView(handView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190))); gap(tracking, 10);
        addButton(tracking, button("Ativar e testar mãos", true, v -> requestHands()));
        addButton(tracking, button("Parar câmera", false, v -> stopHands()));
        tracking.addView(text("A câmera traseira permanece ativa até você pará-la aqui ou na notificação. A prévia acima mostra pontos, não imagens.", 12, muted, false));

        LinearLayout launch = card(body, "05 / ABRIR", "Abrir jogo separado");
        launch.addView(text("Somente jogos ARM64 que descubram runtimes OpenXR podem usar o Monado. Jogos de Quest ou jogos comuns podem não ser compatíveis.", 13, muted, false)); gap(launch, 12);
        playButton = button("Abrir jogo instalado", true, v -> openGame()); addButton(launch, playButton);
        launch.addView(text("As mãos são convertidas em controles aproximados; não são um rastreamento nativo de todos os dedos. Teste em celular físico ainda é necessário.", 12, muted, false));
    }

    @Override protected void onResume() {
        super.onResume();
        if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
        if (sensorInfo != null && gyro == null) sensorInfo.setText("Giroscópio não encontrado neste aparelho.");
        refreshBroker();
    }
    @Override protected void onPause() { sensors.unregisterListener(this); super.onPause(); }
    @Override protected void onDestroy() {
        unregisterReceiver(handsReceiver);
        io.shutdown();
        super.onDestroy();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE || event.values.length < 3) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSensorUi < 250) return;
        lastSensorUi = now;
        sensorInfo.setText(String.format(Locale.US, "Giroscópio: %.2f / %.2f / %.2f rad/s%s",
                event.values[0], event.values[1], event.values[2], rotation == null ? " · sem sensor de orientação" : " · orientação disponível"));
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void refreshBroker() {
        if (brokerInfo == null) return;
        ProviderInfo provider = getPackageManager().resolveContentProvider(BROKER, 0);
        brokerInfo.setText(provider != null && getPackageName().equals(provider.packageName)
                ? "● Runtime OpenXR integrado registrado" : "⚠ Broker OpenXR indisponível ou em conflito");
    }
    private void setBusy(boolean value) {
        busy = value;
        for (Button button : new Button[]{installButton, copyButton, playButton, selectApkButton, selectObbButton,
                iconButton, clearIconButton, clearObbButton, createButton}) if (button != null) button.setEnabled(!value);
        if (gameName != null) gameName.setEnabled(!value);
        refreshCreated();
    }
    private void showResult(String message) { if (!isFinishing() && !isDestroyed()) resultInfo.setText(message); }
    private void refreshSelection() {
        apkInfo.setText(selectedPackage == null ? "Nenhum APK selecionado." :
                (selectedLabel == null ? "Jogo" : selectedLabel) + "\n" + selectedPackage + (stagedApk.isFile() ? " · APK pronto" : " · selecione o APK novamente"));
        obbInfo.setText(obbUri == null ? "OBB é opcional." : "OBB: " + (obbName == null ? "arquivo selecionado" : obbName));
    }
    private void refreshIcon() {
        boolean exists = stagedIcon.isFile();
        iconInfo.setText(exists ? "Foto selecionada como ícone." : "Ícone original do jogo.");
        iconPreview.setImageBitmap(exists ? BitmapFactory.decodeFile(stagedIcon.getAbsolutePath()) : null);
        iconPreview.setVisibility(exists ? View.VISIBLE : View.GONE);
    }
    private void refreshCreated() {
        if (saveButton == null || installCreatedButton == null || generatedApk == null) return;
        String pkg = prefs.getString("generated_package", null);
        boolean ready = generatedApk.isFile() && PackageFiles.validPackage(pkg);
        saveButton.setEnabled(ready && !busy); installCreatedButton.setEnabled(ready && !busy);
        if (ready && !busy) {
            createdInfo.setText("APK pronto para " + pkg + ". SHA-256: " + prefs.getString("generated_sha256", "") +
                    "\nSalve-o antes de limpar o cache do aplicativo.");
        }
    }
    private void invalidateCreated() {
        if (generatedApk == null || prefs == null) return;
        generatedApk.delete();
        prefs.edit().remove("generated_package").remove("generated_sha256").apply();
        if (createdInfo != null) createdInfo.setText("Personalização alterada. Crie uma nova cópia.");
        refreshCreated();
    }
    private void clearObb() {
        if (busy) return;
        obbUri = null; obbName = null;
        prefs.edit().remove("obb_uri").remove("obb_name").apply();
        invalidateCreated(); refreshSelection(); showResult("OBB removido da seleção.");
    }
    private void clearIcon() {
        if (busy) return;
        stagedIcon.delete(); invalidateCreated(); refreshIcon(); showResult("O ícone original será mantido.");
    }
    private void chooseIcon() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, PICK_ICON); }
        catch (RuntimeException error) { showResult("Não foi possível abrir o seletor de imagens."); }
    }
    private void chooseFile(boolean obb) {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType(obb ? "*/*" : "application/vnd.android.package-archive");
        if (!obb) pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, obb ? PICK_OBB : PICK_APK); }
        catch (RuntimeException error) { showResult("Não foi possível abrir o seletor de arquivos."); }
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment();
    }
    private void persistRead(Intent data, Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { /* Some document providers grant only temporary access. */ }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ICON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            persistRead(data, data.getData()); importIcon(data.getData());
        } else if (requestCode == SAVE_GENERATED && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportCreated(data.getData());
        } else if (requestCode == INSTALL_GENERATED) {
            createdInfo.setText(resultCode == RESULT_OK
                    ? "O Android confirmou a instalação. Abra o ícone do jogo; ele não precisa do BigXR separado."
                    : "A instalação não foi concluída. Assinatura, permissões ou compatibilidade podem ter impedido a instalação.");
        } else if ((requestCode == PICK_APK || requestCode == PICK_OBB) && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData(); persistRead(data, uri);
            if (requestCode == PICK_APK) importApk(uri);
            else {
                String name = displayName(uri);
                if (selectedPackage != null && !PackageFiles.validObb(name, selectedPackage)) {
                    showResult("Nome do OBB incompatível. Use main.<versão>." + selectedPackage + ".obb ou patch.<versão>." + selectedPackage + ".obb.");
                    return;
                }
                obbUri = uri; obbName = name;
                prefs.edit().putString("obb_uri", uri.toString()).putString("obb_name", name).apply();
                invalidateCreated(); refreshSelection();
                showResult("OBB selecionado. Ele será incluído no APK criado, se houver espaço.");
            }
        } else if (requestCode == INSTALL_APK) {
            if (resultCode == RESULT_OK && isInstalled()) {
                showResult("Jogo instalado. " + (obbUri == null ? "Você já pode tentar abri-lo." : "Agora copie o OBB antes de abrir."));
            } else {
                showResult("A instalação não foi concluída ou não pôde ser confirmada. O Android pode ter rejeitado a versão ou a assinatura.");
            }
        }
    }
    private void importApk(Uri uri) {
        setBusy(true); showResult("Lendo o APK escolhido…");
        io.execute(() -> {
            File temp = null;
            try {
                File dir = stagedApk.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Não foi possível criar o cache");
                temp = File.createTempFile("import-", ".apk", dir);
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("Arquivo não disponível");
                    copyBounded(in, out, MAX_APK); out.getFD().sync();
                }
                PackageInfo info = getPackageManager().getPackageArchiveInfo(temp.getAbsolutePath(), 0);
                if (info == null || info.applicationInfo == null || !PackageFiles.validPackage(info.packageName)) throw new IOException("APK inválido ou sem pacote reconhecível");
                info.applicationInfo.sourceDir = temp.getAbsolutePath(); info.applicationInfo.publicSourceDir = temp.getAbsolutePath();
                String label = String.valueOf(getPackageManager().getApplicationLabel(info.applicationInfo));
                boolean arm64 = false, anyNative = false, loader = false;
                try (ZipFile zip = new ZipFile(temp)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith("lib/") && name.endsWith(".so")) anyNative = true;
                        if (name.startsWith("lib/arm64-v8a/") && name.endsWith(".so")) arm64 = true;
                        if (name.endsWith("/libopenxr_loader.so")) loader = true;
                    }
                }
                String sha = sha256(temp);
                moveReplace(temp, stagedApk); temp = null;
                final String pkg = info.packageName, appLabel = label;
                final boolean nativeMismatch = anyNative && !arm64, hasLoader = loader;
                prefs.edit().putString("package", pkg).putString("label", appLabel).apply();
                runOnUiThread(() -> {
                    selectedPackage = pkg; selectedLabel = appLabel;
                    invalidateCreated(); gameName.setText(appLabel);
                    if (obbUri != null && !PackageFiles.validObb(obbName, pkg)) {
                        obbUri = null; obbName = null; prefs.edit().remove("obb_uri").remove("obb_name").apply();
                    }
                    refreshSelection(); setBusy(false);
                    showResult((nativeMismatch ? "Aviso: APK sem biblioteca ARM64. " : "") +
                            (hasLoader ? "Loader OpenXR encontrado; compatibilidade não garantida. " : "Loader OpenXR não identificado; compatibilidade desconhecida. ") +
                            "SHA-256: " + sha);
                });
            } catch (Exception error) {
                if (temp != null) temp.delete();
                runOnUiThread(() -> { setBusy(false); showResult("Não foi possível ler o APK: " + error.getMessage()); });
            }
        });
    }
    private void importIcon(Uri uri) {
        setBusy(true); showResult("Preparando foto do ícone…");
        io.execute(() -> {
            File source = null, png = null;
            try {
                File dir = stagedIcon.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Não foi possível criar o cache");
                source = File.createTempFile("image-", ".tmp", dir);
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(source)) {
                    if (in == null) throw new IOException("Imagem não disponível");
                    copyBounded(in, out, 20L * 1024 * 1024);
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 32768 || bounds.outHeight > 32768)
                    throw new IOException("Formato ou dimensões da imagem não são suportados");
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
                if (bitmap == null) throw new IOException("Não foi possível decodificar a imagem");
                Bitmap square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                try {
                    int edge = Math.min(bitmap.getWidth(), bitmap.getHeight());
                    int x = (bitmap.getWidth() - edge) / 2, y = (bitmap.getHeight() - edge) / 2;
                    Canvas canvas = new Canvas(square); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(bitmap, new Rect(x, y, x + edge, y + edge), new Rect(0, 0, 512, 512), paint);
                    png = File.createTempFile("icon-", ".png", dir);
                    try (FileOutputStream out = new FileOutputStream(png)) {
                        if (!square.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Não foi possível criar o ícone");
                        out.getFD().sync();
                    }
                } finally { bitmap.recycle(); square.recycle(); }
                moveReplace(png, stagedIcon); png = null;
                runOnUiThread(() -> { invalidateCreated(); refreshIcon(); setBusy(false); showResult("Foto pronta para usar como ícone."); });
            } catch (OutOfMemoryError error) {
                runOnUiThread(() -> { setBusy(false); showResult("Memória insuficiente para abrir a foto."); });
            } catch (Exception error) {
                runOnUiThread(() -> { setBusy(false); showResult("Não foi possível usar a foto: " + error.getMessage()); });
            } finally {
                if (source != null) source.delete(); if (png != null) png.delete();
            }
        });
    }

    private void confirmCreate() {
        if (busy || CREATING.get()) return;
        if (!PackageFiles.validPackage(selectedPackage) || !stagedApk.isFile()) { showResult("Selecione primeiro um APK válido."); return; }
        String name = gameName.getText().toString().trim();
        if (name.isEmpty()) { showResult("Informe o nome que aparecerá no ícone."); return; }
        new AlertDialog.Builder(this).setTitle("Criar cópia com BigXR?")
                .setMessage("Use somente um APK que você tenha direito de modificar. A cópia será assinada com uma chave local diferente da original. Para instalá-la, pode ser necessário desinstalar o jogo original, o que pode apagar seus dados. Compras, licença e verificações de integridade podem falhar. Apenas alguns loaders OpenXR ARM64 são aceitos; criar o APK não garante que o jogo funcione.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Criar cópia", (dialog, which) -> createGame()).show();
    }
    private static void buildMessage(Context context, String message, boolean finished) {
        context.sendBroadcast(new Intent(BUILD_STATUS).setPackage(context.getPackageName())
                .putExtra("message", message).putExtra("finished", finished));
    }
    private void createGame() {
        if (busy || !CREATING.compareAndSet(false, true)) return;
        final Context context = getApplicationContext();
        final File input = stagedApk, icon = stagedIcon.isFile() ? stagedIcon : null, finalApk = generatedApk;
        final String pkg = selectedPackage, label = gameName.getText().toString(), name = obbName;
        final Uri selectedObb = obbUri;
        if (handsStarted) stopHands();
        setBusy(true); createdInfo.setText("Iniciando a criação… mantenha o aplicativo aberto.");
        io.execute(() -> {
            File candidate = null; String message;
            try {
                File dir = finalApk.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Não foi possível criar o cache");
                candidate = File.createTempFile("new-bigxr-", ".apk", dir);
                if (!candidate.delete()) throw new IOException("Não foi possível preparar o arquivo de saída");
                ApkCreator.Result result = ApkCreator.create(context, input, pkg, label, icon, selectedObb, name,
                        candidate, status -> buildMessage(context, status, false));
                moveReplace(candidate, finalApk); candidate = null;
                context.getSharedPreferences("xr-launcher", MODE_PRIVATE).edit()
                        .putString("generated_package", result.packageName).putString("generated_sha256", result.sha256).apply();
                message = "APK criado e verificado. Salve-o ou peça ao Android para instalá-lo.";
            } catch (OutOfMemoryError error) {
                message = "Memória insuficiente para criar este APK. Tente um arquivo menor.";
            } catch (Exception | LinkageError error) {
                android.util.Log.e("BigXRCreator", "Falha ao criar APK", error);
                message = "Não foi possível criar o APK: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                if (candidate != null) candidate.delete();
                CREATING.set(false);
            }
            buildMessage(context, message, true);
        });
    }
    private void saveCreated() {
        if (busy || !generatedApk.isFile() || !PackageFiles.validPackage(prefs.getString("generated_package", null))) return;
        String filename = gameName.getText().toString().trim().replaceAll("[^a-zA-Z0-9._-]+", "-");
        if (filename.isEmpty()) filename = "jogo";
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_TITLE, "BigXR-" + filename + ".apk");
        try { startActivityForResult(save, SAVE_GENERATED); }
        catch (RuntimeException error) { createdInfo.setText("Não foi possível abrir o seletor para salvar."); }
    }
    private void exportCreated(Uri destination) {
        if (busy || !generatedApk.isFile()) return;
        setBusy(true); createdInfo.setText("Salvando APK criado…");
        io.execute(() -> {
            try (InputStream in = new FileInputStream(generatedApk); OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new IOException("Destino não disponível");
                byte[] block = new byte[256 * 1024]; int n;
                while ((n = in.read(block)) != -1) if (n > 0) out.write(block, 0, n);
                out.flush();
                runOnUiThread(() -> { setBusy(false); createdInfo.setText("APK salvo no local escolhido."); });
            } catch (Exception error) {
                runOnUiThread(() -> { setBusy(false); createdInfo.setText("Não foi possível salvar o APK: " + error.getMessage()); });
            }
        });
    }
    private void installCreated() {
        if (busy || !generatedApk.isFile() || !PackageFiles.validPackage(prefs.getString("generated_package", null))) return;
        if (!installerAllowed()) return;
        if (handsStarted) stopHands();
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", generatedApk);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(install, INSTALL_GENERATED);
        } catch (RuntimeException error) { createdInfo.setText("Não foi possível abrir o instalador: " + error.getMessage()); }
    }

    private static void copyBounded(InputStream in, FileOutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[256 * 1024]; long total = 0; int count;
        while ((count = in.read(buffer)) != -1) {
            if (count == 0) continue;
            total += count; if (total > limit) throw new IOException("Arquivo excede o limite de tamanho");
            out.write(buffer, 0, count);
        }
        if (total == 0) throw new IOException("Arquivo vazio");
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] block = new byte[256 * 1024]; int n;
            while ((n = in.read(block)) != -1) if (n > 0) digest.update(block, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }
    private static void moveReplace(File source, File target) throws IOException {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private boolean isInstalled() {
        if (!PackageFiles.validPackage(selectedPackage)) return false;
        try { getPackageManager().getPackageInfo(selectedPackage, 0); return true; }
        catch (PackageManager.NameNotFoundException error) { return false; }
    }
    private boolean installerAllowed() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        showResult("Autorize este app a solicitar instalações e toque em Instalar novamente.");
        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
        catch (RuntimeException error) { showResult("Ative 'instalar apps desconhecidos' nas configurações do Android."); }
        return false;
    }
    private void installSelected() {
        if (busy) return;
        if (!PackageFiles.validPackage(selectedPackage) || !stagedApk.isFile()) { showResult("Selecione primeiro um APK válido."); return; }
        if (!installerAllowed()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", stagedApk);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(install, INSTALL_APK);
        } catch (RuntimeException error) { showResult("O instalador do Android não pôde ser aberto: " + error.getMessage()); }
    }
    private void requestObbCopy() {
        if (busy) return;
        if (!isInstalled()) { showResult("Instale o APK selecionado antes de copiar o OBB."); return; }
        if (obbUri == null || !PackageFiles.validObb(obbName, selectedPackage)) { showResult("Selecione um OBB com o nome correto para este pacote."); return; }
        if (!installerAllowed()) return;
        if (Build.VERSION.SDK_INT == 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION); return;
        }
        File target = obbTarget();
        if (target.exists()) {
            new AlertDialog.Builder(this).setTitle("Substituir OBB?")
                    .setMessage("Já existe um arquivo com este nome. Ele será substituído somente depois que a cópia terminar.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Substituir", (d, w) -> copyObb(true)).show();
        } else copyObb(false);
    }
    @SuppressWarnings("deprecation")
    private File obbTarget() {
        File root = new File(Environment.getExternalStorageDirectory(), "Android/obb");
        return new File(new File(root, selectedPackage), obbName);
    }
    private void copyObb(boolean replaceExisting) {
        final Uri source = obbUri; final String pkg = selectedPackage, name = obbName;
        if (!PackageFiles.validObb(name, pkg) || source == null) { showResult("OBB inválido."); return; }
        final File target = obbTarget(); setBusy(true); showResult("Copiando OBB… mantenha o aplicativo aberto.");
        io.execute(() -> {
            File temporary = null;
            try {
                File dir = target.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("O Android bloqueou a pasta Android/obb");
                temporary = File.createTempFile(".xrpocket-", ".part", dir);
                try (InputStream in = getContentResolver().openInputStream(source); FileOutputStream out = new FileOutputStream(temporary)) {
                    if (in == null) throw new IOException("OBB não disponível");
                    copyBounded(in, out, MAX_OBB); out.getFD().sync();
                }
                if (replaceExisting) moveReplace(temporary, target);
                else Files.move(temporary.toPath(), target.toPath());
                temporary = null;
                runOnUiThread(() -> { setBusy(false); showResult("OBB copiado para Android/obb/" + pkg + "."); });
            } catch (Exception error) {
                if (temporary != null) temporary.delete();
                runOnUiThread(() -> { setBusy(false); showResult("Não foi possível copiar o OBB: " + error.getMessage() + ". O Android ou o fabricante pode bloquear essa pasta; copie o arquivo manualmente se necessário."); });
            }
        });
    }
    private void requestHands() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), CAMERA_PERMISSION); return; }
        startHands();
    }
    private void startHands() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { handsInfo.setText("Permita a câmera para usar mãos."); return; }
        try {
            startForegroundService(new Intent(this, HandService.class)); handsStarted = true;
            handsInfo.setText("Inicializando modelo e câmera…");
        } catch (RuntimeException error) { handsStarted = false; handsInfo.setText("Não foi possível iniciar a câmera: " + error.getMessage()); }
    }
    private void stopHands() {
        stopService(new Intent(this, HandService.class)); handsStarted = false; handView.update(null, null);
        handsInfo.setText("Câmera parada.");
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION) startHands();
        else if (requestCode == STORAGE_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) requestObbCopy();
            else showResult("Acesso ao armazenamento negado; OBB não copiado.");
        }
    }
    private void openGame() {
        if (busy) return;
        if (!isInstalled()) { showResult("Instale primeiro o APK selecionado."); return; }
        Intent launch = getPackageManager().getLaunchIntentForPackage(selectedPackage);
        if (launch == null) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD").setPackage(selectedPackage);
            android.content.pm.ResolveInfo match = getPackageManager().resolveActivity(query, 0);
            if (match != null && match.activityInfo != null) {
                launch = new Intent(Intent.ACTION_MAIN).setClassName(selectedPackage, match.activityInfo.name);
            }
        }
        if (launch == null) { showResult("Não foi encontrada uma tela inicial para este jogo."); return; }
        final Intent game = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Runnable open = () -> {
            try { startActivity(game); }
            catch (RuntimeException error) { showResult("Não foi possível abrir o jogo: " + error.getMessage()); }
        };
        if (!handsStarted) new AlertDialog.Builder(this).setTitle("Abrir sem mãos?")
                .setMessage("O rastreamento de mãos está desligado. O jogo ainda pode usar o runtime e o giroscópio se for compatível.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Abrir", (d, w) -> open.run()).show();
        else open.run();
    }
}
