#!/usr/bin/env python3
"""Exercise the launcher only on a disposable Android emulator, not a real device.

The small fixture APK is not a game. Successful tests do not establish that
rendering, hand input, or head tracking works on physical phones.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path('xr-capture'); OUT.mkdir(exist_ok=True)
LOG = []


def command(*args, check=True, timeout=40):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError(f"{args[0]} falhou ({p.returncode}): {p.stdout[-600:]}")
    return p.stdout


def adb(*args, check=True, timeout=40): return command('adb', *args, check=check, timeout=timeout)


def note(text):
    print(text, flush=True); LOG.append(text)


def dump(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/xr-window.xml', check=False, timeout=30)
    xml = adb('shell', 'cat', '/sdcard/xr-window.xml', check=False)
    (OUT / f'{name}.xml').write_text(xml, encoding='utf-8')
    try:
        nodes = list(ET.fromstring(xml).iter('node'))
    except ET.ParseError:
        return []
    return nodes


def labels(nodes):
    return ' | '.join((n.get('text') or n.get('content-desc') or '')[:100] for n in nodes if n.get('text') or n.get('content-desc'))[:3000]


def tap(nodes, *labels_to_find):
    wanted = [s.casefold() for s in labels_to_find]
    for n in nodes:
        for val in (n.get('text', ''), n.get('content-desc', '')):
            if val.casefold() not in wanted: continue
            m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
            if not m: continue
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 <= x1 or y2 <= y1: continue
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return True
    return False


def swipe(down=False):
    if down: adb('shell', 'input', 'swipe', '540', '600', '540', '1950', '400')
    else: adb('shell', 'input', 'swipe', '540', '1950', '540', '600', '400')


def find_button(*names, start='current'):
    for index in range(5):
        nodes = dump(f'{start}-{index}')
        if tap(nodes, *names): return True
        swipe()
    return False


def choose_download(filename, key):
    time.sleep(3)
    nodes = dump(key + '-picker')
    if tap(nodes, filename): return
    if tap(nodes, 'Show roots', 'Show navigation drawer', 'Browse', 'Abrir menu de navegação'):
        time.sleep(1); nodes = dump(key + '-roots')
        if not tap(nodes, 'Downloads', 'Download'):
            raise RuntimeError('Seletor sem Downloads: ' + labels(nodes))
        time.sleep(2); nodes = dump(key + '-downloads')
        if tap(nodes, filename): return
    raise RuntimeError('Arquivo não encontrado no seletor: ' + labels(nodes))


def screenshot(name):
    with (OUT / name).open('wb') as f:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f, check=False, timeout=30)


def verify_openxr(log):
    xr = [line for line in log.splitlines() if 'XRProbe' in line or 'XrPocketHands' in line or 'monado-ipc-client' in line or 'MonadoImpl' in line]
    (OUT / 'logcat-resumo.txt').write_text('\n'.join(xr) + '\n', encoding='utf-8')
    relevant = [line for line in xr if 'XRProbe' in line or 'Modelo de mãos' in line or 'Câmera de mãos' in line]
    note('Runtime e mãos: ' + ' '.join(relevant)[-4500:])
    if not any('BROKER com.xrpocket.mobile' in line for line in xr): raise RuntimeError('Fixture não consultou o broker.')
    for cycle in range(1, 4):
        for operation in ('CREATE_INSTANCE', 'GET_SYSTEM', 'DESTROY_INSTANCE'):
            marker = f'{operation} cycle={cycle} result=0'
            if not any(marker in line for line in xr): raise RuntimeError('OpenXR não concluiu: ' + marker)
    if not any('ALL_CYCLES_OK' in line for line in xr): raise RuntimeError('OpenXR não concluiu todos os ciclos.')
    if not any('INPUTS_OK' in line for line in xr):
        detail = ' | '.join(line for line in xr if 'INPUT_' in line)[-1000:]
        raise RuntimeError('Teste OpenXR sem renderização não confirmou entradas sintéticas e movimento: ' + detail)
    if not adb('shell', 'pidof', 'com.xrpocket.probe', check=False).strip():
        raise RuntimeError('Processo de teste morreu após encerrar o OpenXR.')
    if any(('Fatal signal' in line and '(.xrpocket.probe)' in line)
           or ('F/DEBUG' in line and 'Cmdline: com.xrpocket.probe' in line)
           for line in log.splitlines()):
        raise RuntimeError('O cliente de teste sofreu falha nativa.')
    return xr


def quick_probe(host, probe):
    note('Diagnóstico rápido: somente OpenXR com entrada sintética e giroscópio virtual.')
    adb('install', '-r', str(host))
    adb('install', '-r', str(probe))
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', check=False)
    adb('shell', 'wm', 'dismiss-keyguard', check=False)
    adb('emu', 'sensor', 'set', 'gyroscope', '0.4:0.2:0.1', check=False)
    adb('logcat', '-c', check=False)
    adb('shell', 'am', 'start', '-W', '-n', 'com.xrpocket.probe/com.xrpocket.probe.ProbeActivity', timeout=60)
    time.sleep(90)
    screenshot('probe.png')
    verify_openxr(adb('logcat', '-d', '-v', 'brief', timeout=50))
    note('Instâncias, entrada sintética e movimento virtual confirmados; não é gameplay.')


def main():
    temp = Path(os.environ['RUNNER_TEMP'])
    host = temp / 'xr-apk/XR-de-Bolso-debug.apk'
    probe = Path('xr_mobile/probe/app/build/outputs/apk/debug/app-debug.apk')
    note('Teste automatizado em emulador descartável; não é teste físico nem gameplay.')
    note('ABI: ' + adb('shell', 'getprop', 'ro.product.cpu.abilist').strip())
    if os.environ.get('XR_TEST_QUICK') == '1':
        quick_probe(host, probe)
        return
    # Run the actual MediaPipe model and HandsMath on a reference photo in the
    # debug test target. It does not validate a live camera or physical gestures.
    debug_host = Path('xr_mobile/android/app/build/outputs/apk/debug/app-debug.apk')
    hand_test = Path('xr_mobile/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    adb('install', '-r', str(debug_host))
    adb('install', '-r', str(hand_test))
    result = adb('shell', 'am', 'instrument', '-w', '-r',
                 'com.xrpocket.mobile.test/androidx.test.runner.AndroidJUnitRunner', timeout=180, check=False)
    (OUT / 'teste-maos.txt').write_text(result, encoding='utf-8')
    note('Modelo em foto de referência: ' + result[-600:].replace('\n', ' | '))
    if not re.search(r'OK \(1 test\)', result): raise RuntimeError('Teste de detecção em foto de referência falhou.')
    adb('uninstall', 'com.xrpocket.mobile.test', check=False)
    adb('uninstall', 'com.xrpocket.mobile')
    note('Instalação do launcher: ' + adb('install', '-r', str(host)).strip())
    adb('shell', 'pm', 'grant', 'com.xrpocket.mobile', 'android.permission.CAMERA', check=False)
    adb('shell', 'pm', 'grant', 'com.xrpocket.mobile', 'android.permission.POST_NOTIFICATIONS', check=False)
    adb('shell', 'appops', 'set', 'com.xrpocket.mobile', 'REQUEST_INSTALL_PACKAGES', 'allow', check=False)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', check=False)
    adb('shell', 'wm', 'dismiss-keyguard', check=False)
    adb('logcat', '-c', check=False)
    broker = adb('shell', 'content', 'query', '--uri', 'content://org.khronos.openxr.system_runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active/0', check=False)
    note('Broker: ' + broker.strip())
    if 'com.xrpocket.mobile' not in broker or 'libopenxr_monado.so' not in broker:
        raise RuntimeError('Broker não publicou o runtime arm64.')
    # A freshly booted Pixel Launcher occasionally ANRs on a busy CI runner.
    # Start the activity explicitly and dismiss only that system ANR if it
    # covers the test; do not hide crashes from the app under test.
    adb('shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher', check=False)
    for attempt in range(3):
        adb('shell', 'am', 'start', '-W', '-n', 'com.xrpocket.mobile/com.xrpocket.mobile.MainActivity', timeout=60)
        time.sleep(8)
        nodes = dump(f'launcher-{attempt}')
        if any('XR DE BOLSO' in n.get('text', '') for n in nodes): break
        if any('Pixel Launcher' in n.get('text', '') and 'respond' in n.get('text', '').lower() for n in nodes):
            tap(nodes, 'Close app', 'Fechar app', 'Wait', 'Aguardar')
            time.sleep(3)
    screenshot('launcher.png')
    note('Tela inicial: ' + labels(nodes))
    if not any('XR DE BOLSO' in n.get('text', '') for n in nodes): raise RuntimeError('Launcher não apareceu.')
    # Activate the camera while the launcher is visible, as required by Android.
    if not find_button('Ativar e testar mãos', start='hands'):
        raise RuntimeError('Botão de mãos não encontrado.')
    time.sleep(18)
    screenshot('hands.png')
    note('Tela de mãos: ' + labels(dump('hands-active')))
    adb('emu', 'sensor', 'set', 'gyroscope', '0.4:0.2:0.1', check=False)
    time.sleep(3)
    gyro_labels = labels(dump('gyro'))
    note('Após alterar sensor virtual: ' + gyro_labels)
    if '0.40 / 0.20 / 0.10' not in gyro_labels:
        raise RuntimeError('Launcher não refletiu o giroscópio virtual.')
    # Test the real document picker and Package Installer with a harmless fixture.
    adb('push', str(probe), '/sdcard/Download/XR-Probe.apk')
    obb = temp / 'main.1.com.xrpocket.probe.obb'; obb.write_bytes(b'XR Pocket test fixture OBB\n')
    adb('push', str(obb), '/sdcard/Download/' + obb.name)
    for _ in range(3): swipe(down=True)
    time.sleep(1)
    if not find_button('Selecionar APK', start='apk-button'): raise RuntimeError('Botão de APK não encontrado.')
    choose_download('XR-Probe.apk', 'apk')
    time.sleep(8)
    nodes = dump('apk-selected'); note('Após selecionar APK: ' + labels(nodes))
    if not any('com.xrpocket.probe' in n.get('text', '') for n in nodes): raise RuntimeError('APK de teste não foi importado.')
    if not find_button('Selecionar OBB (opcional)', start='obb-button'): raise RuntimeError('Botão de OBB não encontrado.')
    choose_download(obb.name, 'obb')
    time.sleep(3)
    note('Após selecionar OBB: ' + labels(dump('obb-selected')))
    if not find_button('Instalar jogo', start='install-button'): raise RuntimeError('Botão de instalação não encontrado.')
    time.sleep(5); nodes = dump('installer'); note('Instalador: ' + labels(nodes))
    if not tap(nodes, 'Install', 'Instalar'):
        raise RuntimeError('Confirmação do instalador não encontrada: ' + labels(nodes))
    time.sleep(12); nodes = dump('installed'); note('Após confirmação: ' + labels(nodes))
    tap(nodes, 'Done', 'Concluído', 'Concluída')
    time.sleep(3)
    if 'com.xrpocket.probe' not in adb('shell', 'pm', 'list', 'packages', 'com.xrpocket.probe'):
        raise RuntimeError('Fixture não foi instalada pelo Package Installer.')
    note('Instalação com confirmação: OK')
    for _ in range(3): swipe(down=True)
    if not find_button('Copiar OBB para o jogo', start='copy-button'): raise RuntimeError('Botão de OBB não encontrado.')
    time.sleep(8); note('Após cópia: ' + labels(dump('obb-copy')))
    copied = adb('shell', 'cat', '/sdcard/Android/obb/com.xrpocket.probe/' + obb.name, check=False)
    if copied.strip() != 'XR Pocket test fixture OBB': raise RuntimeError('OBB não foi copiado ou Android bloqueou a pasta: ' + copied[:300])
    note('Cópia OBB: OK')
    # Stop the real camera sender so the fixture's synthetic PH5 packets are
    # not mixed with empty camera results during the OpenXR input test.
    if not find_button('Parar câmera', start='stop-hands'): raise RuntimeError('Botão de parar câmera não encontrado.')
    time.sleep(5)
    stopped_labels = labels(dump('hands-stopped'))
    note('Após parar câmera: ' + stopped_labels)
    stop_log = adb('logcat', '-d', '-v', 'brief', timeout=50)
    if 'Câmera parada' not in stopped_labels or 'Câmera de mãos parada' not in stop_log:
        raise RuntimeError('Câmera ou interface não confirmaram a parada.')
    if not find_button('Abrir jogo instalado', start='play-button'): raise RuntimeError('Botão de abrir não encontrado.')
    time.sleep(4); nodes = dump('open-dialog')
    if not tap(nodes, 'Abrir'): raise RuntimeError('Confirmação de abrir sem câmera não encontrada: ' + labels(nodes))
    time.sleep(65); screenshot('probe.png')
    note('Tela da fixture: ' + labels(dump('probe')))
    log = adb('logcat', '-d', '-v', 'brief', timeout=50)
    xr = verify_openxr(log)
    if not all(any(marker in line for line in xr) for marker in ('Modelo de mãos carregado', 'Câmera de mãos iniciada')):
        raise RuntimeError('Câmera ou modelo de mãos não iniciaram.')
    note('OpenXR: 3 instâncias criadas, consultadas e encerradas sem crash observado; renderização não testada.')
    note('Teste headless: comandos de mão sintéticos e movimento do sensor virtual chegaram ao OpenXR.')
    note('Câmera e modelo iniciaram; sem validação de gestos reais no emulador.')


if __name__ == '__main__':
    code = 0
    try:
        main()
    except Exception as error:
        code = 1
        note('FALHA: ' + str(error))
        try:
            note('Última tela: ' + labels(dump('failure')))
            screenshot('failure.png')
        except Exception: pass
    finally:
        try:
            log = adb('logcat', '-d', '-v', 'brief', check=False, timeout=50)
            (OUT / 'logcat.txt').write_text(log, encoding='utf-8')
            relevant = [line for line in log.splitlines() if any(tag in line for tag in ('XRProbe', 'XrPocketHands', 'monado-ipc-client', 'MonadoImpl', 'AndroidRuntime', 'Fatal signal'))]
            note('Log selecionado: ' + ' '.join(relevant[-20:])[:3500])
        except Exception: pass
        (OUT / 'diagnostico.txt').write_text('\n'.join(LOG) + '\n', encoding='utf-8')
        print('::notice title=Teste XR de Bolso::' + ' | '.join(LOG)[-6000:].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'), flush=True)
    sys.exit(code)
