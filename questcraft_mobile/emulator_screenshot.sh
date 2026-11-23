#!/usr/bin/env bash
# Smoke test visual sem login/conta. Não confirma funcionamento em celular real.
set -u
mkdir -p capture
REPORT=capture/diagnostico.txt
{
  echo 'Captura automatizada em emulador Android. Não equivale a teste em dispositivo físico.'
  echo "ABI: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo '--- instalação do runtime ---'
  adb install -r "$RUNNER_TEMP/phonexr-runtime.apk" || true
  echo '--- instalação do PhoneXR ---'
  adb install -r "$RUNNER_TEMP/PhoneXR.apk" || true
  echo '--- instalação do port ---'
  adb install -r "$RUNNER_TEMP/questcraft-apk/QuestCraft-Mobile-debug.apk" || true
  echo '--- dispensar aviso imersivo do emulador ---'
  adb shell settings put secure immersive_mode_confirmations confirmed || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell wm dismiss-keyguard || true
  echo '--- lançamento do port ---'
  adb shell monkey -p com.qcxr.qcxr -c android.intent.category.LAUNCHER 1 || true
} > "$REPORT" 2>&1

for delay in 8 22 30; do
  sleep "$delay"
  elapsed=$(( ${elapsed:-0} + delay ))
  if [ "$elapsed" -eq 8 ]; then
    # Algumas imagens do Android ainda exibem a confirmação de tela cheia.
    adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1 || true
  fi
  {
    echo "--- foco após ${elapsed}s ---"
    adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true
    adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -4 || true
  } >> "$REPORT" 2>&1
  adb exec-out screencap -p > "capture/tela-${elapsed}s.png" || true
  if [ "$elapsed" -eq 8 ]; then
    # Registrar eventuais diálogos nativos para entender o resultado da captura.
    timeout 15s adb shell uiautomator dump /sdcard/questcraft-window.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/questcraft-window.xml > capture/window.xml 2>/dev/null || true
    python3 - <<'PY' >> "$REPORT" 2>&1
import xml.etree.ElementTree as ET
from pathlib import Path
try:
    root = ET.parse('capture/window.xml').getroot()
    print('--- textos acessíveis na tela ---')
    for node in root.iter('node'):
        text = node.get('text', '') or node.get('content-desc', '')
        if text:
            print(text[:250], node.get('bounds', ''))
except (ET.ParseError, OSError):
    print('Nenhum diálogo acessível via UIAutomator.')
PY
  fi
done
adb logcat -d -t 4000 -v brief > capture/logcat.txt || true

# Prévia compacta para verificar se a tela realmente pertence ao port. Não há
# dados pessoais: o emulador é novo e não faz login.
python3 -m pip install --quiet --user --break-system-packages Pillow
python3 - <<'PY'
import base64
from pathlib import Path
from PIL import Image

image = Image.open('capture/tela-30s.png').convert('RGB')
image.thumbnail((800, 800))
output = Path('capture/preview.jpg')
for quality in (60, 50, 40, 30, 20):
    image.save(output, 'JPEG', quality=quality, optimize=True)
    if output.stat().st_size < 20000:
        break
encoded = base64.b64encode(output.read_bytes()).decode('ascii')
# As mensagens de anotação do GitHub são truncadas em 4096 caracteres.
if len(encoded) <= 30000:
    for i in range(0, len(encoded), 3300):
        print(f'::notice title=PREVIA_EMULADOR_{i // 3300:02d}::' + encoded[i:i + 3300])
PY
# Um resumo pequeno do diagnóstico fica visível nas anotações da execução.
report_note=$(tr '\n' ' ' < "$REPORT" | cut -c1-1500)
printf '::notice title=Diagnóstico do emulador::%s\n' "$report_note"
cat "$REPORT"
