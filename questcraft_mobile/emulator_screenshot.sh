#!/usr/bin/env bash
# Smoke test on a fresh emulator. A successful workflow does not mean gameplay works.
set -u
mkdir -p capture
REPORT=capture/diagnostico.txt
{
  echo 'Captura do APK único em emulador Android; não equivale a teste em celular físico.'
  echo "Build: $(cat "$RUNNER_TEMP/questcraft-apk/build-run-id.txt" 2>/dev/null || echo desconhecida)"
  echo "APK SHA-256: $(sha256sum "$RUNNER_TEMP/questcraft-apk/QuestCraft-Mobile-debug.apk" | cut -d' ' -f1)"
  echo "Renderizador solicitado: ${QUESTCRAFT_CAPTURE_RENDERER:-desconhecido}"
  echo "ABI: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "OpenGL ES anunciado: $(adb shell getprop ro.opengles.version | tr -d '\r')"
  adb shell dumpsys SurfaceFlinger 2>/dev/null | grep -i -m 2 'GLES:' || true
  echo '--- instalação do único APK ---'
} > "$REPORT"
if ! adb install -r "$RUNNER_TEMP/questcraft-apk/QuestCraft-Mobile-debug.apk" >> "$REPORT" 2>&1; then
  echo '::error title=Teste no emulador::Não foi possível instalar o APK integrado.'
  cat "$REPORT"
  exit 1
fi
{
  echo '--- permissão de câmera no emulador descartável ---'
  adb shell pm grant com.qcxr.qcxr android.permission.CAMERA || true
  adb shell settings put secure immersive_mode_confirmations confirmed || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell wm dismiss-keyguard || true
  adb logcat -c || true
  echo '--- lançamento do QuestCraft ---'
  adb shell monkey -p com.qcxr.qcxr -c android.intent.category.LAUNCHER 1 || true
} >> "$REPORT" 2>&1

# The snapshots are before each tap. Only identified permission/setup dialogs
# on this disposable emulator are touched; a green job is not a gameplay claim.
for delay in 8 12 15 20 25 30; do
  sleep "$delay"
  elapsed=$(( ${elapsed:-0} + delay ))
  {
    echo "--- foco após ${elapsed}s ---"
    adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true
    adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -4 || true
  } >> "$REPORT" 2>&1
  adb exec-out screencap -p > "capture/tela-${elapsed}s.png" || true
  timeout 15s adb shell uiautomator dump /sdcard/questcraft-window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/questcraft-window.xml > "capture/window-${elapsed}.xml" 2>/dev/null || true
  python3 - "$elapsed" <<'PY' > "capture/ui-${elapsed}.txt" 2>&1
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
try:
    nodes = list(ET.parse(f'capture/window-{sys.argv[1]}.xml').getroot().iter('node'))
    print('--- textos acessíveis na tela ---')
    for node in nodes:
        text = node.get('text', '') or node.get('content-desc', '')
        if text:
            print(text[:300], node.get('bounds', ''))
    texts = {node.get('text', '').casefold() for node in nodes}
    packages = {node.get('package', '') for node in nodes}
    label = None
    # Android's default Button style can display text in uppercase.
    if 'permitir câmera e abrir' in texts and 'com.qcxr.qcxr' in packages:
        label = 'permitir câmera e abrir'
    elif any('does not match the hardware requirements' in text for text in texts):
        label = 'continue'
    elif any('permissioncontroller' in package for package in packages):
        for candidate in ('while using the app', 'allow only while using the app',
                          'allow', 'permitir', 'durante o uso do app'):
            if candidate in texts:
                label = candidate
                break
    if label:
        for node in nodes:
            if node.get('text', '').casefold() == label:
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], timeout=10, check=True)
                    print('Selecionado:', label)
                    break
except (ET.ParseError, OSError, subprocess.SubprocessError):
    print('Nenhum diálogo acessível via UIAutomator.')
PY
  cat "capture/ui-${elapsed}.txt" >> "$REPORT"
  ui_note=$(tr '\n' ' ' < "capture/ui-${elapsed}.txt" | cut -c1-3000)
  printf '::notice title=Tela aos %ss::%s\n' "$elapsed" "$ui_note"
done
adb logcat -d -v brief > capture/logcat.txt || true
report_note=$(tr '\n' ' ' < "$REPORT" | cut -c1-2500)
printf '::notice title=Diagnóstico do emulador::%s\n' "$report_note"
focus_note=$(grep 'mCurrentFocus=' "$REPORT" | tr '\n' ' ' | cut -c1-1500 || true)
printf '::notice title=Foco do Android::%s\n' "$focus_note"
hands_note=$(grep -E 'QuestCraftHands' capture/logcat.txt | tail -30 | tr '\n' ' ' | cut -c1-3500 || true)
printf '::notice title=Rastreamento de mãos::%s\n' "$hands_note"
runtime_note=$(grep -Ei 'QuestCraftRuntime|monado-ipc-client|MonadoImpl|MonadoView|openxr|Fatal signal' capture/logcat.txt | tail -35 | tr '\n' ' ' | cut -c1-3500 || true)
printf '::notice title=Runtime OpenXR::%s\n' "$runtime_note"
fatal_note=$(grep -A 18 'FATAL EXCEPTION' capture/logcat.txt | tail -40 | tr '\n' ' ' | cut -c1-3500 || true)
printf '::notice title=Exceções fatais::%s\n' "$fatal_note"
cat "$REPORT"
