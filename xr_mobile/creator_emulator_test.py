#!/usr/bin/env python3
"""Exercise the actual BigXR creator and generated standalone APK on an emulator.

The fixture is our own diagnostic APK, not a game. Nothing here proves Quest
compatibility, visual rendering, physical hand tracking or physical head motion.
"""
from __future__ import annotations

import binascii
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from questcraft_mobile import integrated
from xr_mobile import emulator_test as ui

OUT = Path('bigxr-creator-capture'); OUT.mkdir(exist_ok=True)
ui.OUT = OUT
PACKAGE = 'com.xrpocket.probe'
OBB_NAME = 'main.1.com.xrpocket.probe.obb'
OBB_CONTENT = b'BigXR synthetic OBB fixture; not a game.\n'
CREATED_NAME = 'BigXR-BigXRProbe.apk'


def png_fixture(path: Path) -> None:
    import zlib
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', binascii.crc32(tag + data) & 0xffffffff)
    width, height = 72, 48
    raw = b''.join(b'\0' + bytes((34, 180, 130, 255)) * width for _ in range(height))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
                     + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def top():
    for _ in range(6): ui.swipe(down=True)


def click_from_top(label: str, name: str) -> None:
    top()
    if not ui.find_button(label, start=name): raise RuntimeError('Botão não encontrado: ' + label)


def create_ui(host: Path, probe: Path, temp: Path) -> Path:
    ui.note('Instalando BigXR experimental e fornecendo APK, foto e OBB de teste próprios.')
    ui.adb('install', '-r', str(host), timeout=90)
    ui.adb('shell', 'appops', 'set', 'com.xrpocket.mobile', 'REQUEST_INSTALL_PACKAGES', 'allow', check=False)
    ui.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', check=False)
    ui.adb('shell', 'wm', 'dismiss-keyguard', check=False)
    image = temp / 'bigxr-photo.png'; png_fixture(image)
    obb = temp / OBB_NAME; obb.write_bytes(OBB_CONTENT)
    for source, name in ((probe, 'XR-Probe.apk'), (image, image.name), (obb, obb.name)):
        ui.adb('push', str(source), '/sdcard/Download/' + name, timeout=90)
    ui.adb('shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher', check=False)
    ui.adb('shell', 'am', 'start', '-W', '-n', 'com.xrpocket.mobile/com.xrpocket.mobile.MainActivity', timeout=90)
    time.sleep(5)
    nodes = ui.dump('bigxr-start'); ui.screenshot('01-bigxr.png')
    if not any('BIGXR' in n.get('text', '') for n in nodes): raise RuntimeError('BigXR não abriu: ' + ui.labels(nodes))
    click_from_top('Selecionar APK', 'select-apk')
    ui.choose_download('XR-Probe.apk', 'apk')
    time.sleep(7); nodes = ui.dump('apk-selected')
    if not any(PACKAGE in n.get('text', '') for n in nodes): raise RuntimeError('APK da fixture não foi selecionado: ' + ui.labels(nodes))
    click_from_top('Selecionar OBB (opcional)', 'select-obb')
    ui.choose_download(OBB_NAME, 'obb'); time.sleep(3)
    click_from_top('Escolher foto para o ícone', 'select-image')
    ui.choose_download('bigxr-photo.png', 'photo'); time.sleep(7)
    top()
    for attempt in range(5):
        nodes = ui.dump(f'name-{attempt}')
        fields = [n for n in nodes if n.get('class') == 'android.widget.EditText']
        if ui.tap(fields, 'Nome do jogo'): break
        ui.swipe()
    else: raise RuntimeError('Campo de nome não encontrado')
    ui.adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    for _ in range(18): ui.adb('shell', 'input', 'keyevent', 'KEYCODE_DEL')
    ui.adb('shell', 'input', 'text', 'BigXRProbe')
    ui.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK', check=False)
    ui.screenshot('02-personalizacao.png')
    click_from_top('Criar APK com BigXR', 'create')
    time.sleep(2); nodes = ui.dump('confirm-create')
    if not ui.tap(nodes, 'Criar cópia'): raise RuntimeError('Confirmação não encontrada: ' + ui.labels(nodes))
    # The status sits between the create and save buttons. A blind full swipe
    # can skip past it, so only reposition when neither is in the viewport.
    time.sleep(1)
    for attempt in range(80):
        time.sleep(4)
        nodes = ui.dump(f'build-{attempt:02}')
        text = ui.labels(nodes)
        if 'APK pronto para' in text:
            ui.note('Criador confirmou: ' + text[-1000:]); break
        if 'Não foi possível criar' in text or 'Memória insuficiente' in text:
            raise RuntimeError('Falha no criador: ' + text)
        create_visible = 'Criar APK com BigXR' in text
        save_visible = 'Salvar APK criado' in text
        if not create_visible and not save_visible:
            below = any(section in text for section in ('03 / ALTERNATIVO', '04 / SENSORES', '05 / ABRIR'))
            ui.swipe(down=below)
        elif create_visible and not save_visible:
            ui.adb('shell', 'input', 'swipe', '540', '1550', '540', '1100', '350')
        elif save_visible and not create_visible:
            ui.adb('shell', 'input', 'swipe', '540', '1100', '540', '1550', '350')
    else: raise RuntimeError('Criação demorou mais de 320 segundos: ' + ui.labels(nodes))
    ui.screenshot('03-criado.png')
    click_from_top('Salvar APK criado', 'save')
    time.sleep(3); nodes = ui.dump('save-picker')
    # DocumentsUI normally remains in Downloads after the input selections.
    if not ui.tap(nodes, 'Save', 'Salvar'):
        raise RuntimeError('Botão Salvar não encontrado: ' + ui.labels(nodes))
    time.sleep(10)
    matches = ui.adb('shell', 'find', '/sdcard/Download', '-name', CREATED_NAME, check=False, timeout=40).splitlines()
    matches = [line.strip() for line in matches if line.strip().endswith('/' + CREATED_NAME)]
    if len(matches) != 1: raise RuntimeError('Arquivo salvo não encontrado em Downloads: ' + repr(matches))
    output = OUT / 'BigXR-fixture-generated.apk'
    ui.adb('pull', matches[0], str(output), timeout=180)
    if not output.is_file() or output.stat().st_size < 1_000_000: raise RuntimeError('APK salvo está vazio ou truncado')
    ui.note('APK salvo via seletor Android: ' + str(output.stat().st_size) + ' bytes')
    return output


def inspect_apk(apk: Path) -> None:
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    ui.note('Verificando assinatura, alinhamento, nome, ícone e conteúdo do APK salvo.')
    signature = ui.command(str(sdk / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk), timeout=120)
    alignment = ui.command(str(sdk / 'zipalign'), '-c', '-P', '16', '4', str(apk), timeout=120)
    badging = ui.command(str(sdk / 'aapt'), 'dump', 'badging', str(apk), timeout=60)
    (OUT / 'signature.txt').write_text(signature, encoding='utf-8')
    (OUT / 'badging.txt').write_text(badging, encoding='utf-8')
    if "package: name='com.xrpocket.probe'" not in badging or 'BigXRProbe' not in badging:
        raise RuntimeError('Nome/pacote incorretos: ' + badging[:1200])
    if 'bigxr_custom_icon' not in badging: raise RuntimeError('Ícone personalizado não aparece no badging')
    if "uses-feature-not-required: name='android.hardware.camera'" not in badging or "uses-implied-feature: name='android.hardware.camera'" in badging:
        raise RuntimeError('Câmera opcional se tornou um requisito inesperado: ' + badging[:2200])
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in signature and 'Verified using v3 scheme (APK Signature Scheme v3): true' not in signature:
        raise RuntimeError('APK gerado não está assinado com v2/v3')
    classes: set[str] = set()
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)): raise RuntimeError('Entradas ZIP duplicadas')
        required_files = {
            'assets/hand_landmarker.task', 'assets/bigxr_obb/manifest.json', 'assets/bigxr_obb/' + OBB_NAME,
            'lib/arm64-v8a/libopenxr_loader.so', 'lib/arm64-v8a/libopenxr_monado.so',
            'lib/arm64-v8a/libmonado-service.so', 'res/drawable-nodpi/bigxr_custom_icon.png',
        }
        if required_files - set(names): raise RuntimeError('Arquivos integrados ausentes: ' + repr(required_files - set(names)))
        if archive.getinfo('assets/hand_landmarker.task').compress_type != zipfile.ZIP_STORED: raise RuntimeError('Modelo precisa estar sem compressão')
        if archive.read('assets/bigxr_obb/' + OBB_NAME) != OBB_CONTENT: raise RuntimeError('OBB embutido é diferente do selecionado')
        obb_meta = json.loads(archive.read('assets/bigxr_obb/manifest.json'))
        if obb_meta['sha256'] != hashlib.sha256(OBB_CONTENT).hexdigest(): raise RuntimeError('Hash do OBB incorreto')
        png = archive.read('res/drawable-nodpi/bigxr_custom_icon.png')
        if png[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', png[16:24]) != (512, 512): raise RuntimeError('Imagem personalizada não foi convertida em ícone 512×512')
        broker = ('xr.b' + hashlib.sha256(PACKAGE.encode()).hexdigest()[:24]).encode()
        loader = archive.read('lib/arm64-v8a/libopenxr_loader.so')
        if loader.count(broker + b'\0') != 2 or any(old + b'\0' in loader for old in integrated.OLD_BROKERS):
            raise RuntimeError('Loader não aponta ao broker privado')
        for path in ('libopenxr_monado.so', 'libmonado-service.so'):
            native = archive.read('lib/arm64-v8a/' + path)
            if PACKAGE.encode() + b'\0' not in native or integrated.OLD_RUNTIME + b'\0' in native:
                raise RuntimeError('Runtime não aponta ao pacote gerado')
            if path == 'libopenxr_monado.so':
                if native.count(integrated.EGL_PROBE_GUARD) != 1 or native.count(struct.pack('<QQ', 0x6ffffffb, 9)) != 1:
                    raise RuntimeError('Guardas EGL/NODELETE ausentes')
        for name in names:
            if integrated.dex_index(name) is not None:
                found = integrated.dex_classes(archive.read(name))
                if classes & found: raise RuntimeError('Classes DEX duplicadas')
                classes.update(found)
        for required in ('Lcom/bigxr/embedded/BootstrapActivity;', 'Lcom/bigxr/embedded/HandService;',
                         'Lorg/freedesktop/monado/ipc/MonadoService;', 'Lorg/freedesktop/monado/phonexr/RuntimeBroker;'):
            if required not in classes: raise RuntimeError('Classe integrada ausente: ' + required)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    ui.note('APK próprio: assinatura v2/v3, zipalign 16 KB, ícone, OBB, DEX e runtime conferidos. SHA-256: ' + digest)


def open_bootstrap(hands: bool):
    ui.adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.bigxr.embedded.BootstrapActivity', timeout=90)
    time.sleep(4); nodes = ui.dump('bootstrap-' + ('hands' if hands else 'no-hands'))
    if not any('BigXR integrado' in n.get('text', '') for n in nodes): raise RuntimeError('Tela integrada não abriu: ' + ui.labels(nodes))
    ui.screenshot('04-bootstrap.png')
    if not ui.tap(nodes, 'Permitir câmera e abrir' if hands else 'Abrir sem câmera'):
        raise RuntimeError('Botão da tela integrada não encontrado')


def install_and_exercise():
    click_from_top('Instalar APK criado', 'install-created')
    time.sleep(5); nodes = ui.dump('installer')
    if not ui.tap(nodes, 'Install', 'Instalar'): raise RuntimeError('Instalador Android não confirmou: ' + ui.labels(nodes))
    time.sleep(12); ui.tap(ui.dump('installed'), 'Done', 'Concluído', 'Concluída')
    if PACKAGE not in ui.adb('shell', 'pm', 'list', 'packages', PACKAGE): raise RuntimeError('APK gerado não foi instalado')
    ui.adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.CAMERA', check=False)
    ui.adb('uninstall', 'com.xrpocket.mobile', timeout=60)
    if 'com.xrpocket.mobile' in ui.adb('shell', 'pm', 'list', 'packages', 'com.xrpocket.mobile'):
        raise RuntimeError('BigXR criador ainda está instalado')
    ui.note('Criador desinstalado; testando somente o APK gerado.')
    ui.adb('logcat', '-c', check=False)
    open_bootstrap(True)
    for _ in range(35):
        time.sleep(2)
        log = ui.adb('logcat', '-d', '-v', 'brief', check=False, timeout=40)
        if 'Modelo de mãos carregado' in log and 'Câmera de mãos iniciada' in log: break
    else: raise RuntimeError('Câmera/MediaPipe não iniciaram no APK autônomo: ' + log[-6000:])
    copied = ui.adb('shell', 'cat', '/sdcard/Android/obb/' + PACKAGE + '/' + OBB_NAME, check=False)
    if copied.encode() != OBB_CONTENT: raise RuntimeError('OBB embutido não foi extraído corretamente: ' + repr(copied))
    ui.note('APK autônomo iniciou câmera e modelo, e extraiu o OBB de teste.')
    (OUT / 'hands-log.txt').write_text('\n'.join(x for x in log.splitlines() if 'BigXRHands' in x or 'XRProbe' in x) + '\n', encoding='utf-8')
    ui.adb('shell', 'am', 'force-stop', PACKAGE, check=False)
    ui.adb('emu', 'sensor', 'set', 'gyroscope', '0:0:0', check=False)
    ui.adb('logcat', '-c', check=False)
    open_bootstrap(False)
    for _ in range(100):
        time.sleep(1)
        log = ui.adb('logcat', '-d', '-s', 'XRProbe:I', '*:S', check=False, timeout=30)
        if 'INPUT_GYRO_BASELINE' in log: break
    else: raise RuntimeError('OpenXR autônomo não chegou à orientação inicial: ' + log[-2500:])
    if 'INPUT_HEAD_MOTION_OK' in log: raise RuntimeError('Movimento observado antes de alterar o sensor virtual')
    ui.adb('emu', 'sensor', 'set', 'gyroscope', '0.4:0.2:0.1')
    for _ in range(30):
        time.sleep(1)
        log = ui.adb('logcat', '-d', '-v', 'brief', timeout=40)
        if 'INPUTS_OK' in log: break
    (OUT / 'standalone-log.txt').write_text('\n'.join(x for x in log.splitlines() if any(tag in x for tag in ('XRProbe', 'monado', 'AndroidRuntime', 'Fatal signal'))) + '\n', encoding='utf-8')
    for cycle in range(1, 4):
        for operation in ('CREATE_INSTANCE', 'GET_SYSTEM', 'DESTROY_INSTANCE'):
            if f'{operation} cycle={cycle} result=0' not in log: raise RuntimeError('OpenXR autônomo falhou: ' + operation)
    for marker in ('ALL_CYCLES_OK', 'INPUT_PRESS_OK', 'INPUT_RELEASE_OK', 'INPUT_POSE_OK', 'INPUT_HEAD_MOTION_OK', 'INPUTS_OK'):
        if marker not in log: raise RuntimeError('Entrada sintética ou sensor virtual não confirmado: ' + marker)
    if not ui.adb('shell', 'pidof', PACKAGE, check=False).strip(): raise RuntimeError('Processo da fixture morreu')
    if ('FATAL EXCEPTION' in log and PACKAGE in log) or ('Fatal signal' in log and 'xrpocket.probe' in log):
        raise RuntimeError('Crash observado na fixture autônoma')
    ui.screenshot('05-standalone.png')
    ui.note('Sem BigXR instalado: três ciclos OpenXR, entradas PH5 sintéticas e movimento do giroscópio virtual passaram. Não é gameplay.')


def main():
    temp = Path(os.environ['RUNNER_TEMP'])
    host = temp / 'bigxr-apk/BigXR-debug.apk'
    probe = Path('xr_mobile/probe/app/build/outputs/apk/debug/app-debug.apk')
    ui.note('Fixture própria em emulador: não representa um jogo Meta nem valida um celular físico.')
    apk = create_ui(host, probe, temp)
    inspect_apk(apk)
    install_and_exercise()


if __name__ == '__main__':
    code = 0
    try:
        main()
    except Exception as error:
        code = 1; ui.note('FALHA: ' + str(error))
        try:
            ui.note('Última tela: ' + ui.labels(ui.dump('failure')))
            ui.screenshot('failure.png')
        except Exception: pass
    finally:
        try:
            log = ui.adb('logcat', '-d', '-v', 'brief', check=False, timeout=50)
            (OUT / 'logcat.txt').write_text(log, encoding='utf-8')
            selected = [line for line in log.splitlines() if any(tag in line for tag in ('BigXRCreator', 'BigXRHands', 'XRProbe', 'AndroidRuntime', 'Fatal signal'))]
            ui.note('Log selecionado: ' + ' '.join(selected[-20:])[-4000:])
        except Exception: pass
        (OUT / 'resultado.txt').write_text('\n'.join(ui.LOG) + '\n', encoding='utf-8')
        print('::notice title=Teste do criador BigXR::' + ' | '.join(ui.LOG)[-6000:].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'), flush=True)
    sys.exit(code)
