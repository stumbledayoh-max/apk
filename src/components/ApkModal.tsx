import { useState } from "react";
import JSZip from "jszip";

interface ApkModalProps {
  onClose: () => void;
}

export default function ApkModal({ onClose }: ApkModalProps) {
  const [tab, setTab] = useState<"download" | "termux" | "studio" | "actions">("download");
  const [generating, setGenerating] = useState(false);
  const [progress, setProgress] = useState("");

  const downloadProjectZip = async () => {
    setGenerating(true);
    setProgress("Preparando arquivos do projeto Android...");
    try {
      const zip = new JSZip();

      // Root files
      zip.file(
        "settings.gradle.kts",
        `pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VRLauncher"
include(":app")
`
      );

      zip.file(
        "build.gradle.kts",
        `plugins {
    id("com.android.application") version "8.3.2" apply false
}
`
      );

      zip.file(
        "gradle/wrapper/gradle-wrapper.properties",
        `distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-8.4-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
`
      );

      // App build.gradle.kts
      zip.file(
        "app/build.gradle.kts",
        `plugins {
    id("com.android.application")
}

android {
    namespace = "com.vrlauncher.fakegyro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vrlauncher.fakegyro"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
}
`
      );

      // AndroidManifest
      zip.file(
        "app/src/main/AndroidManifest.xml",
        `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.vrlauncher.fakegyro">

    <uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
    <uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
    <uses-feature android:name="android.hardware.sensor.compass" android:required="false" />

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:allowBackup="true"
        android:label="VR Launcher"
        android:hardwareAccelerated="true"
        android:theme="@style/Theme.VRLauncher.Fullscreen">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
            android:screenOrientation="sensorLandscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>`
      );

      // Resources
      zip.file(
        "app/src/main/res/values/styles.xml",
        `<resources>
    <style name="Theme.VRLauncher.Fullscreen" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowBackground">#020617</item>
    </style>
</resources>`
      );

      // Java sources
      setProgress("Adicionando classes Java e ponte de sensores...");
      const mainActivityRes = await fetch("/android/app/src/main/java/com/vrlauncher/fakegyro/MainActivity.java").catch(() => null);
      let mainActCode = mainActivityRes?.ok ? await mainActivityRes.text() : "";
      if (!mainActCode) {
        mainActCode = `package com.vrlauncher.fakegyro;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private NativeSensorsBridge sensorsBridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout layout = new FrameLayout(this);
        setContentView(layout);
        webView = new WebView(this);
        layout.addView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        sensorsBridge = new NativeSensorsBridge(this, webView);
        webView.addJavascriptInterface(sensorsBridge, "NativeSensors");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> r.grant(r.getResources()));
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorsBridge != null) sensorsBridge.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorsBridge != null) sensorsBridge.stop();
    }
}`;
      }
      zip.file("app/src/main/java/com/vrlauncher/fakegyro/MainActivity.java", mainActCode);

      const bridgeCode = `package com.vrlauncher.fakegyro;
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

public class NativeSensorsBridge implements SensorEventListener {
    private final SensorManager sensorManager;
    private final WebView webView;
    private final Sensor accelSensor, gyroSensor, magSensor, rotVectorSensor;
    private boolean isListening = false;
    private int samplingRateUs = 8333; // 120Hz
    private float[] lastAccel = new float[3], lastGyro = new float[3], lastMag = new float[3], lastRotVec = new float[4];
    private boolean hasRotVec = false;
    private long lastPush = 0;

    public NativeSensorsBridge(Context ctx, WebView wv) {
        this.webView = wv;
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.accelSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        this.gyroSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        this.magSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) : null;
        this.rotVectorSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
    }

    @JavascriptInterface
    public synchronized void start() {
        if (isListening || sensorManager == null) return;
        isListening = true;
        if (accelSensor != null) sensorManager.registerListener(this, accelSensor, samplingRateUs);
        if (gyroSensor != null) sensorManager.registerListener(this, gyroSensor, samplingRateUs);
        if (magSensor != null) sensorManager.registerListener(this, magSensor, samplingRateUs);
        if (rotVectorSensor != null) sensorManager.registerListener(this, rotVectorSensor, samplingRateUs);
    }

    @JavascriptInterface
    public synchronized void stop() {
        if (!isListening || sensorManager == null) return;
        isListening = false;
        sensorManager.unregisterListener(this);
    }

    @JavascriptInterface
    public void setRate(int hz) {
        this.samplingRateUs = 1000000 / Math.max(15, Math.min(240, hz));
        if (isListening) { stop(); start(); }
    }

    @JavascriptInterface
    public boolean hasGyro() { return gyroSensor != null; }

    @JavascriptInterface
    public boolean hasMag() { return magSensor != null; }

    @JavascriptInterface
    public boolean hasRotationVector() { return rotVectorSensor != null; }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isListening) return;
        int t = event.sensor.getType();
        if (t == Sensor.TYPE_ACCELEROMETER) System.arraycopy(event.values, 0, lastAccel, 0, 3);
        else if (t == Sensor.TYPE_GYROSCOPE) System.arraycopy(event.values, 0, lastGyro, 0, 3);
        else if (t == Sensor.TYPE_MAGNETIC_FIELD) System.arraycopy(event.values, 0, lastMag, 0, 3);
        else if (t == Sensor.TYPE_ROTATION_VECTOR) {
            float[] q = new float[4];
            SensorManager.getQuaternionFromVector(q, event.values);
            lastRotVec[0] = q[1]; lastRotVec[1] = q[2]; lastRotVec[2] = q[3]; lastRotVec[3] = q[0];
            hasRotVec = true;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastPush >= 7) {
            lastPush = now;
            String js = String.format(Locale.US,
                "if(window.__onNativeSensors){window.__onNativeSensors({a:[%.4f,%.4f,%.4f],g:[%.4f,%.4f,%.4f],m:[%.4f,%.4f,%.4f],t:%d});}",
                lastAccel[0], lastAccel[1], lastAccel[2], lastGyro[0], lastGyro[1], lastGyro[2], lastMag[0], lastMag[1], lastMag[2], now);
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}`;
      zip.file("app/src/main/java/com/vrlauncher/fakegyro/NativeSensorsBridge.java", bridgeCode);

      // Embedded Web Bundle Assets
      setProgress("Empacotando jogos e biblioteca para execução offline...");
      const filesToFetch = [
        { url: "index.html", dest: "app/src/main/assets/www/index.html" },
        { url: "gyro-inject.js", dest: "app/src/main/assets/www/gyro-inject.js" },
        { url: "games/Gorilla_Rush.html", dest: "app/src/main/assets/www/games/Gorilla_Rush.html" },
        { url: "games/CraftVR.html", dest: "app/src/main/assets/www/games/CraftVR.html" },
        { url: "games/lib/t128/three.min.js", dest: "app/src/main/assets/www/games/lib/t128/three.min.js" },
        { url: "games/lib/t149/three.min.js", dest: "app/src/main/assets/www/games/lib/t149/three.min.js" },
        { url: "games/lib/GLTFLoader.js", dest: "app/src/main/assets/www/games/lib/GLTFLoader.js" },
        { url: "games/lib/SkeletonUtils.js", dest: "app/src/main/assets/www/games/lib/SkeletonUtils.js" },
        { url: "games/lib/peerjs.min.js", dest: "app/src/main/assets/www/games/lib/peerjs.min.js" },
        { url: "img/gorilla-rush.jpg", dest: "app/src/main/assets/www/img/gorilla-rush.jpg" },
        { url: "img/craft-vr.jpg", dest: "app/src/main/assets/www/img/craft-vr.jpg" },
      ];

      for (const item of filesToFetch) {
        try {
          const r = await fetch(item.url);
          if (r.ok) {
            const blob = await r.blob();
            zip.file(item.dest, blob);
          }
        } catch {
          // segue
        }
      }

      // README
      zip.file(
        "README.md",
        `# VR Launcher — Giroscópio Emulado por Acelerômetro (APK Android)

Este projeto empacota os jogos Gorilla Rush e CraftVR com giroscópio emulado e 6DoF falso.

## Como compilar o APK:

### Opção 1: No Celular pelo Termux (sem PC!)
1. Instale o app Termux no celular.
2. No Termux, rode:
   \`\`\`bash
   pkg update && pkg install -y openjdk-17 gradle
   unzip VRLauncher-Android-Project.zip -d vrlauncher
   cd vrlauncher
   gradle assembleDebug
   \`\`\`
3. O APK estará pronto em \`app/build/outputs/apk/debug/app-debug.apk\`!

### Opção 2: Pelo Android Studio (PC)
1. Abra o Android Studio.
2. Clique em **File > Open** e selecione esta pasta descompactada.
3. Clique em **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Opção 3: Via Linha de Comando (Linux/Mac/Windows)
\`\`\`bash
./gradlew assembleDebug
\`\`\`
`
      );

      setProgress("Compactando arquivo ZIP final...");
      const content = await zip.generateAsync({ type: "blob" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(content);
      a.download = "VRLauncher-Android-Project.zip";
      a.click();
      URL.revokeObjectURL(a.href);
      setProgress("Download concluído com sucesso!");
    } catch (e) {
      setProgress("Erro ao gerar ZIP: " + String(e));
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4 backdrop-blur-md">
      <div className="relative flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-cyan-500/30 bg-slate-900 shadow-2xl shadow-cyan-950/50">
        {/* Cabeçalho */}
        <div className="flex items-center justify-between border-b border-white/10 bg-slate-950/80 px-6 py-4">
          <div className="flex items-center gap-3">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-cyan-500/20 text-cyan-300">
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
                <line x1="12" y1="18" x2="12.01" y2="18" />
              </svg>
            </span>
            <div>
              <h2 className="text-lg font-bold text-white">Central do APK Android</h2>
              <p className="text-xs text-slate-400">Giroscópio emulado + 6DoF falso nativo em 120Hz</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="rounded-lg border border-white/10 p-2 text-slate-400 transition hover:bg-white/5 hover:text-white"
          >
            ✕
          </button>
        </div>

        {/* Abas */}
        <div className="flex border-b border-white/10 bg-slate-950/40 px-6">
          <button
            onClick={() => setTab("download")}
            className={`border-b-2 px-4 py-3 text-xs font-semibold transition ${
              tab === "download"
                ? "border-cyan-400 text-cyan-300"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            📦 Baixar Pacote do Projeto
          </button>
          <button
            onClick={() => setTab("termux")}
            className={`border-b-2 px-4 py-3 text-xs font-semibold transition ${
              tab === "termux"
                ? "border-cyan-400 text-cyan-300"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            📱 Compilar no Celular (Termux)
          </button>
          <button
            onClick={() => setTab("studio")}
            className={`border-b-2 px-4 py-3 text-xs font-semibold transition ${
              tab === "studio"
                ? "border-cyan-400 text-cyan-300"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            💻 Android Studio
          </button>
          <button
            onClick={() => setTab("actions")}
            className={`border-b-2 px-4 py-3 text-xs font-semibold transition ${
              tab === "actions"
                ? "border-cyan-400 text-cyan-300"
                : "border-transparent text-slate-400 hover:text-slate-200"
            }`}
          >
            ☁️ GitHub Actions (Nuvem)
          </button>
        </div>

        {/* Conteúdo */}
        <div className="flex-1 overflow-y-auto p-6 text-sm text-slate-300">
          {tab === "download" && (
            <div className="space-y-4">
              <div className="rounded-xl border border-cyan-500/20 bg-cyan-950/30 p-4">
                <h3 className="font-semibold text-cyan-200">Projeto Android Pronto com Jogos Embutidos</h3>
                <p className="mt-1 text-xs text-slate-300">
                  O pacote inclui todo o código-fonte Java, manifesto com permissões de sensores/câmera,
                  ponte nativa de 120Hz e os jogos <b>Gorilla Rush</b> e <b>CraftVR</b> para rodar 100% offline.
                </p>
              </div>

              <div className="rounded-xl border border-white/10 bg-black/40 p-4">
                <h4 className="font-medium text-white">Conteúdo do Pacote:</h4>
                <ul className="mt-2 list-inside list-disc space-y-1 text-xs text-slate-400">
                  <li><code className="text-cyan-300">NativeSensorsBridge.java</code>: Leitura de acelerômetro e giroscópio a 120Hz via SensorManager.</li>
                  <li><code className="text-cyan-300">MainActivity.java</code>: WebView otimizada em tela cheia imersiva (sensorLandscape).</li>
                  <li><code className="text-cyan-300">app/src/main/assets/www/</code>: Os dois jogos pré-injetados com o motor 6DoF.</li>
                  <li>Scripts de build para Gradle e Termux.</li>
                </ul>
              </div>

              <button
                onClick={downloadProjectZip}
                disabled={generating}
                className="w-full rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 px-5 py-3.5 font-bold text-slate-950 shadow-lg shadow-cyan-500/20 transition hover:from-cyan-400 hover:to-blue-500 active:scale-[0.99] disabled:opacity-50"
              >
                {generating ? "Gerando pacote ZIP..." : "⬇️ Baixar Projeto Android Completo (.zip)"}
              </button>

              {progress && <p className="text-center font-mono text-xs text-cyan-300">{progress}</p>}
            </div>
          )}

          {tab === "termux" && (
            <div className="space-y-4">
              <div className="rounded-xl border border-emerald-500/20 bg-emerald-950/30 p-4">
                <h3 className="font-semibold text-emerald-200">Como Compilar o APK Direto no Celular (Sem PC!)</h3>
                <p className="mt-1 text-xs text-slate-300">
                  Você pode usar o aplicativo <b>Termux</b> no Android para compilar o APK diretamente na memória do seu celular.
                </p>
              </div>

              <div className="space-y-3">
                <p className="text-xs font-semibold text-slate-200">1. Baixe o ZIP na aba ao lado e abra o Termux no celular:</p>
                <div className="relative rounded-xl border border-white/10 bg-black/60 p-3 font-mono text-xs text-emerald-400">
                  <pre className="overflow-x-auto whitespace-pre-wrap">
{`# 1. Instalar Java e Gradle no Termux
pkg update && pkg install -y openjdk-17 gradle

# 2. Descompactar o projeto baixado
unzip ~/downloads/VRLauncher-Android-Project.zip -d ~/vrlauncher
cd ~/vrlauncher

# 3. Compilar o APK
gradle assembleDebug

# 4. O APK gerado estará em:
# app/build/outputs/apk/debug/app-debug.apk`}
                  </pre>
                </div>
                <p className="text-xs text-slate-400">
                  Depois de rodar o comando, basta tocar no arquivo <code className="text-cyan-300">app-debug.apk</code> para instalar no seu aparelho!
                </p>
              </div>
            </div>
          )}

          {tab === "studio" && (
            <div className="space-y-4">
              <h3 className="font-semibold text-white">Compilando pelo Android Studio no Computador</h3>
              <ol className="list-inside list-decimal space-y-2 text-xs text-slate-300">
                <li>Baixe o arquivo <code className="text-cyan-300">VRLauncher-Android-Project.zip</code> na primeira aba e extraia em uma pasta.</li>
                <li>Abra o <b>Android Studio</b>.</li>
                <li>Clique em <b>File &gt; Open...</b> e selecione a pasta extraída.</li>
                <li>Aguarde o Gradle sincronizar as dependências.</li>
                <li>No menu superior, vá em <b>Build &gt; Build Bundle(s) / APK(s) &gt; Build APK(s)</b>.</li>
                <li>O Android Studio exibirá uma notificação com o botão <b>locate</b> para abrir o arquivo <code className="text-cyan-300">.apk</code>.</li>
              </ol>
              <div className="rounded-xl border border-white/10 bg-black/40 p-3 font-mono text-xs text-slate-400">
                Linha de comando: <code className="text-cyan-300">./gradlew assembleDebug</code>
              </div>
            </div>
          )}

          {tab === "actions" && (
            <div className="space-y-4">
              <h3 className="font-semibold text-white">Compilação Automática na Nuvem (GitHub Actions)</h3>
              <p className="text-xs text-slate-300">
                Já incluímos o arquivo de automação <code className="text-cyan-300">.github/workflows/build-apk.yml</code>.
              </p>
              <div className="rounded-xl border border-white/10 bg-black/60 p-3 font-mono text-xs text-cyan-300">
                <pre className="overflow-x-auto whitespace-pre-wrap">
{`1. Suba este repositório para o seu GitHub.
2. Acesse a aba "Actions" no GitHub.
3. Clique em "Compilar APK Android" > "Run workflow".
4. Ao terminar, baixe o APK pronto nos Artifacts da página!`}
                </pre>
              </div>
            </div>
          )}
        </div>

        {/* Rodapé */}
        <div className="flex items-center justify-between border-t border-white/10 bg-slate-950/80 px-6 py-3 text-xs text-slate-400">
          <span>Compatível com Android 5.0 até Android 14+</span>
          <button
            onClick={onClose}
            className="rounded-lg bg-white/10 px-4 py-2 font-medium text-white transition hover:bg-white/20"
          >
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
