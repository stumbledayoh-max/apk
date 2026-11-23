#!/usr/bin/env bash
# Smoke test on a fresh emulator. A successful workflow does not mean gameplay works.
set -u
mkdir -p capture
REPORT=capture/diagnostico.txt
{
  echo 'Captura do APK único em emulador Android; não equivale a teste em celular físico.'
  echo "ABI: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
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

for delay in 8 22 30; do
  sleep "$delay"
  elapsed=$(( ${elapsed:-0} + delay ))
  {
    echo "--- foco após ${elapsed}s ---"
    adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true
    adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -4 || true
  } >> "$REPORT" 2>&1
  adb exec-out screencap -p > "capture/tela-${elapsed}s.png" || true
  if [ "$elapsed" -eq 8 ] || [ "$elapsed" -eq 30 ]; then
    # Only press an explicitly identified button in our own setup or Unity's hardware warning.
    timeout 15s adb shell uiautomator dump /sdcard/questcraft-window.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/questcraft-window.xml > "capture/window-${elapsed}.xml" 2>/dev/null || true
    python3 - "$elapsed" <<'PY' >> "$REPORT" 2>&1
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
            print(text[:250], node.get('bounds', ''))
    texts = {node.get('text', '').casefold() for node in nodes}
    # Android's default Button style can display text in uppercase.
    label = 'permitir câmera e abrir' if 'permitir câmera e abrir' in texts else None
    if any('does not match the hardware requirements' in text for text in texts):
        label = 'continue'
    if label:
        for node in nodes:
            if node.get('text', '').casefold() == label:
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], timeout=10)
                    print('Selecionado:', label)
                    break
except (ET.ParseError, OSError, subprocess.TimeoutExpired):
    print('Nenhum diálogo acessível via UIAutomator.')
PY
  fi
done
adb logcat -d -t 8000 -v brief > capture/logcat.txt || true
report_note=$(tr '\n' ' ' < "$REPORT" | cut -c1-1500)
printf '::notice title=Diagnóstico do emulador::%s\n' "$report_note"
focus_note=$(grep 'mCurrentFocus=' "$REPORT" | tr '\n' ' ' | cut -c1-1000 || true)
printf '::notice title=Foco do Android::%s\n' "$focus_note"
log_note=$(grep -E 'QuestCraftHands|QuestCraftRuntime|AndroidRuntime|FATAL EXCEPTION|openxr|OpenXR' capture/logcat.txt | tail -35 | tr '\n' ' ' | cut -c1-3500 || true)
printf '::notice title=Logs relevantes::%s\n' "$log_note"
cat "$REPORT"
