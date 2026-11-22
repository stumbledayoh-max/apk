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
  echo '--- lançamento do port ---'
  adb shell monkey -p com.qcxr.qcxr -c android.intent.category.LAUNCHER 1 || true
} > "$REPORT" 2>&1

for delay in 8 22 30; do
  sleep "$delay"
  elapsed=$(( ${elapsed:-0} + delay ))
  {
    echo "--- foco após ${elapsed}s ---"
    adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true
    adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -4 || true
  } >> "$REPORT" 2>&1
  adb exec-out screencap -p > "capture/tela-${elapsed}s.png" || true
done
adb logcat -d -t 4000 -v brief > capture/logcat.txt || true

# O check contém uma prévia compacta, oculta em comentário, para inspeção no
# repositório. Não há dados pessoais: o emulador é novo e não faz login.
python3 -m pip install --quiet --user --break-system-packages Pillow
python3 - <<'PY'
import base64
import json
import os
from pathlib import Path

from PIL import Image

report = Path('capture/diagnostico.txt').read_text(errors='replace')[:9000]
image = Image.open('capture/tela-30s.png') if Path('capture/tela-30s.png').exists() else Image.open('capture/tela-60s.png')
image.thumbnail((800, 800))
output = Path('capture/preview.jpg')
for quality in (65, 55, 45, 35, 25):
    image.save(output, 'JPEG', quality=quality, optimize=True)
    if output.stat().st_size < 40000:
        break
encoded = base64.b64encode(output.read_bytes()).decode('ascii')
summary = 'Captura em emulador sem conta; não representa teste real em celular.\n\n```\n' + report + '\n```\n\n<!--CAPTURE_BASE64:' + encoded + '-->'
if len(summary) > 65000:
    summary = 'Imagem grande demais para prévia. Veja o artefato do workflow.\n\n```\n' + report + '\n```'
payload = {
    'name': 'Captura de inicialização — emulador',
    'head_sha': os.environ['GITHUB_SHA'],
    'status': 'completed',
    'conclusion': 'neutral',
    'output': {'title': 'Tentativa de captura do port', 'summary': summary},
}
Path('capture/check.json').write_text(json.dumps(payload), encoding='utf-8')
PY
if [ -f capture/check.json ]; then
  gh api --method POST "repos/$GITHUB_REPOSITORY/check-runs" --input capture/check.json > capture/check-response.json || true
  rm -f capture/check.json
fi
# Um resumo pequeno do diagnóstico fica visível nas anotações da execução.
report_note=$(tr '\n' ' ' < "$REPORT" | cut -c1-1500)
printf '::notice title=Diagnóstico do emulador::%s\n' "$report_note"
cat "$REPORT"
