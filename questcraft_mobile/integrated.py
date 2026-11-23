"""Embed the pinned PhoneXR/Monado runtime and our camera bridge into QuestCraft.

This assembles one APK. It does not establish that QuestCraft will actually run
on a specific phone; the Unity/OpenXR and controller paths must still be tested.
"""
from __future__ import annotations

import hashlib
import re
import shutil
import struct
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID + "}"
ET.register_namespace("android", ANDROID)
PACKAGE = "com.qcxr.qcxr"
BROKER = "com.qcxr.qcxr.openxr_broker"
BOOTSTRAP = "com.questcraft.mobile.BootstrapActivity"
HAND_SERVICE = "com.questcraft.mobile.HandService"
MONADO_SERVICE = "org.freedesktop.monado.ipc.MonadoService"
BROKER_CLASS = "org.freedesktop.monado.phonexr.RuntimeBroker"
TARGET_META = "com.questcraft.mobile.TARGET_ACTIVITY"
OLD_RUNTIME = b"org.freedesktop.monado.openxr_runtime.out_of_process"
OLD_BROKERS = (b"org.khronos.openxr.runtime_broker", b"org.khronos.openxr.system_runtime_broker")
MODEL_SHA256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
RUNTIME_SHA256 = "56dd0f53317e1e70d240d4de39217f7553011a04b1d4cf22a637de6d1ebf7fa8"
REQUIRED_PERMISSIONS = (
    "android.permission.CAMERA", "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_CAMERA",
    "android.permission.HIGH_SAMPLING_RATE_SENSORS",
)
# Only the runtime's Java IPC/broker pieces actually needed for OpenXR. The
# Hilt application and runtime UI from the separate APK are deliberately not used.
SMALI_PATTERNS = {
    "org/freedesktop/monado/ipc": ("Client*.smali", "IMonado*.smali", "MonadoImpl.smali"),
    "org/freedesktop/monado/auxiliary": ("MonadoView*.smali", "NativeCounterpart.smali"),
    "org/freedesktop/monado/phonexr": ("RuntimeBroker.smali",),
}
HELPER_REQUIRED = {
    "Lcom/questcraft/mobile/BootstrapActivity;",
    "Lcom/questcraft/mobile/HandService;",
    "Lorg/freedesktop/monado/ipc/MonadoService;",
    "Lorg/freedesktop/monado/auxiliary/ActivityLifecycleListener;",
    "Lorg/freedesktop/monado/auxiliary/SystemUiController;",
    "Lcom/google/mediapipe/tasks/vision/handlandmarker/HandLandmarker;",
}


class IntegrationError(Exception):
    pass


def absolute_component(name: str) -> str:
    if not name:
        raise IntegrationError("Activity principal sem nome.")
    if name.startswith("."):
        return PACKAGE + name
    return PACKAGE + "." + name if "." not in name else name


def _names(parent: ET.Element, tag: str) -> set[str]:
    return {e.get(A + "name", "") for e in parent.findall(tag)}


def patch_manifest(path: Path) -> list[str]:
    """Replace only the launcher entry; keep the real QuestCraft activity intact."""
    try:
        tree = ET.parse(path)
    except (OSError, ET.ParseError) as exc:
        raise IntegrationError(f"Manifesto inválido: {exc}") from exc
    root = tree.getroot()
    if root.get("package") != PACKAGE:
        raise IntegrationError("Pacote não é QuestCraft.")
    app = root.find("application")
    if app is None:
        raise IntegrationError("Manifesto sem aplicação.")

    matches: list[tuple[ET.Element, ET.Element]] = []
    existing = None
    for component in list(app.findall("activity")) + list(app.findall("activity-alias")):
        if absolute_component(component.get(A + "name", "")) == BOOTSTRAP:
            existing = component
            continue
        for filt in component.findall("intent-filter"):
            if "android.intent.action.MAIN" in _names(filt, "action") and "android.intent.category.LAUNCHER" in _names(filt, "category"):
                matches.append((component, filt))
    targets = {absolute_component(comp.get(A + "name", "")) for comp, _ in matches}
    if existing is None and len(targets) != 1:
        raise IntegrationError(f"Esperada uma activity inicial do QuestCraft; encontradas {len(targets)}.")
    if existing is not None and targets:
        raise IntegrationError("Mais de um launcher depois de integrar o port.")

    changes: list[str] = []
    if existing is None:
        target = next(iter(targets))
        for _, filt in matches:
            for cat in list(filt.findall("category")):
                if cat.get(A + "name") == "android.intent.category.LAUNCHER":
                    filt.remove(cat)
        activity = ET.SubElement(app, "activity", {
            A + "name": BOOTSTRAP, A + "exported": "true",
            A + "theme": "@android:style/Theme.Material.NoActionBar",
        })
        ET.SubElement(activity, "meta-data", {A + "name": TARGET_META, A + "value": target})
        filt = ET.SubElement(activity, "intent-filter")
        ET.SubElement(filt, "action", {A + "name": "android.intent.action.MAIN"})
        ET.SubElement(filt, "category", {A + "name": "android.intent.category.LAUNCHER"})
        changes.append(f"Tela de permissão integrada antes de {target}")
    else:
        metadata = {m.get(A + "name"): m.get(A + "value") for m in existing.findall("meta-data")}
        if not metadata.get(TARGET_META) or metadata[TARGET_META] == BOOTSTRAP:
            raise IntegrationError("Tela integrada sem destino válido.")

    for permission in REQUIRED_PERMISSIONS:
        if permission not in _names(root, "uses-permission") | _names(root, "uses-permission-sdk-23"):
            root.insert(list(root).index(app), ET.Element("uses-permission", {A + "name": permission}))
            changes.append(f"Permissão adicionada: {permission}")
    if app.get(A + "extractNativeLibs") != "true":
        app.set(A + "extractNativeLibs", "true")
        changes.append("Bibliotecas nativas extraídas para o runtime integrado")

    def add_component(tag: str, name: str, attrs: dict[str, str]) -> ET.Element:
        found = [x for x in app.findall(tag) if x.get(A + "name") == name]
        if len(found) > 1:
            raise IntegrationError(f"Componente duplicado: {name}")
        if found:
            for key, value in attrs.items():
                if found[0].get(A + key) != value:
                    raise IntegrationError(f"Componente pré-existente incompatível: {name}")
            return found[0]
        element = ET.SubElement(app, tag, {A + "name": name, **{A + k: v for k, v in attrs.items()}})
        changes.append(f"Componente integrado: {name}")
        return element

    add_component("provider", BROKER_CLASS, {"authorities": BROKER, "exported": "false"})
    add_component("service", HAND_SERVICE, {"exported": "false", "process": ":hands", "foregroundServiceType": "camera"})
    service = add_component("service", MONADO_SERVICE, {"exported": "false", "process": ":monado"})
    if not any("org.freedesktop.monado.ipc.CONNECT" in _names(f, "action") for f in service.findall("intent-filter")):
        filt = ET.SubElement(service, "intent-filter")
        ET.SubElement(filt, "action", {A + "name": "org.freedesktop.monado.ipc.CONNECT"})
        changes.append("Entrada IPC do runtime adicionada")

    tree.write(path, encoding="utf-8", xml_declaration=True)
    return changes


def replace_cstring(data: bytes, old: bytes, new: bytes) -> bytes:
    """Replace one known ELF C string in-place without changing offsets."""
    if not new or len(new) > len(old) or b"\0" in new:
        raise IntegrationError("Substituição de string nativa inválida.")
    needle = old + b"\0"
    if data.count(needle) != 1:
        raise IntegrationError(f"String nativa esperada exatamente uma vez: {old!r}")
    result = data.replace(needle, new + b"\0" * (len(needle) - len(new)), 1)
    if len(result) != len(data):
        raise IntegrationError("O tamanho da biblioteca nativa foi alterado.")
    return result


def patch_loader(path: Path) -> None:
    data = path.read_bytes()
    for old in OLD_BROKERS:
        data = replace_cstring(data, old, BROKER.encode("ascii"))
    path.write_bytes(data)


def install_runtime_libs(decoded: Path, runtime_apk: Path) -> None:
    out = decoded / "lib" / "arm64-v8a"
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(runtime_apk) as archive:
        for name in ("libopenxr_monado.so", "libmonado-service.so"):
            try:
                data = archive.read("lib/arm64-v8a/" + name)
            except KeyError as exc:
                raise IntegrationError(f"Runtime sem {name}") from exc
            if data[:4] != b"\x7fELF" or len(data) < 64 or struct.unpack_from("<H", data, 18)[0] != 183:
                raise IntegrationError(f"Biblioteca de runtime não é ELF arm64: {name}")
            data = replace_cstring(data, OLD_RUNTIME, PACKAGE.encode("ascii"))
            destination = out / name
            if destination.exists():
                raise IntegrationError(f"Conflito de biblioteca do QuestCraft: {name}")
            destination.write_bytes(data)


def _uleb(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 35, 7):
        if offset >= len(data):
            raise IntegrationError("DEX truncado.")
        byte = data[offset]; offset += 1
        value |= (byte & 0x7f) << shift
        if not byte & 0x80:
            return value, offset
    raise IntegrationError("ULEB128 inválido no DEX.")


def dex_classes(data: bytes) -> set[str]:
    """Read class descriptors from a DEX without executing any code."""
    try:
        if len(data) < 112 or not data.startswith(b"dex\n"):
            raise IntegrationError("DEX inválido.")
        string_count, string_off, type_count, type_off = struct.unpack_from("<IIII", data, 56)
        class_count, class_off = struct.unpack_from("<II", data, 96)
        if string_count > 2_000_000 or type_count > 1_000_000 or class_count > 1_000_000:
            raise IntegrationError("DEX excede os limites de segurança.")
        if string_off + string_count * 4 > len(data) or type_off + type_count * 4 > len(data) or class_off + class_count * 32 > len(data):
            raise IntegrationError("Tabelas DEX truncadas.")
        classes = set()
        for i in range(class_count):
            type_index = struct.unpack_from("<I", data, class_off + i * 32)[0]
            if type_index >= type_count: raise IntegrationError("Tipo DEX inválido.")
            string_index = struct.unpack_from("<I", data, type_off + type_index * 4)[0]
            if string_index >= string_count: raise IntegrationError("String DEX inválida.")
            text_off = struct.unpack_from("<I", data, string_off + string_index * 4)[0]
            _, text_off = _uleb(data, text_off)
            end = data.find(b"\0", text_off)
            if end < 0: raise IntegrationError("Nome DEX truncado.")
            descriptor = data[text_off:end].decode("ascii")
            if not descriptor.startswith("L") or not descriptor.endswith(";"):
                raise IntegrationError("Descritor de classe DEX inválido.")
            classes.add(descriptor)
        return classes
    except (struct.error, UnicodeDecodeError, IndexError) as exc:
        raise IntegrationError(f"DEX inválido: {exc}") from exc


def dex_index(name: str) -> int | None:
    match = re.fullmatch(r"classes(\d*)\.dex", name)
    if not match: return None
    return int(match.group(1) or "1")


def next_dex_index(decoded: Path) -> int:
    indexes = [i for p in decoded.iterdir() if (i := dex_index(p.name)) is not None]
    for p in decoded.glob("smali_classes*"):
        if p.name[13:].isdigit(): indexes.append(int(p.name[13:]))
    return max(indexes, default=1) + 1


def install_runtime_smali(decoded: Path, runtime_decoded: Path) -> set[str]:
    destination = decoded / f"smali_classes{next_dex_index(decoded)}"
    classes: set[str] = set()
    for relative, patterns in SMALI_PATTERNS.items():
        matches: list[Path] = []
        for smali_dir in runtime_decoded.glob("smali*"):
            folder = smali_dir / relative
            for pattern in patterns:
                matches.extend(folder.glob(pattern))
        if not matches:
            raise IntegrationError(f"Classes Monado não encontradas: {relative}")
        for source in matches:
            target = destination / relative / source.name
            if target.exists(): raise IntegrationError(f"Classe Monado duplicada: {source.name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            classes.add("L" + relative + "/" + source.stem + ";")
    for expected in ("Lorg/freedesktop/monado/ipc/Client;", "Lorg/freedesktop/monado/ipc/MonadoImpl;",
                     "Lorg/freedesktop/monado/ipc/IMonado;", "Lorg/freedesktop/monado/auxiliary/MonadoView;",
                     "Lorg/freedesktop/monado/phonexr/RuntimeBroker;"):
        if expected not in classes: raise IntegrationError(f"Classe Monado ausente: {expected}")
    return classes


def install_helper(decoded: Path, helper_apk: Path, runtime_classes: set[str]) -> dict[str, str]:
    """Append helper DEX/assets/native libs; fail closed on class/resource conflicts."""
    existing_classes = set(runtime_classes)
    for file in decoded.iterdir():
        if dex_index(file.name) is not None:
            existing_classes.update(dex_classes(file.read_bytes()))
    with zipfile.ZipFile(helper_apk) as archive:
        entries = archive.infolist()
        names = [e.filename for e in entries]
        if len(names) != len(set(names)) or sum(e.file_size for e in entries) > 1024 * 1024 * 1024:
            raise IntegrationError("APK de suporte duplicado ou grande demais.")
        if any(n.startswith("/") or ".." in n.split("/") or "\\" in n for n in names):
            raise IntegrationError("Caminho inseguro no APK de suporte.")
        dex_files = sorted((n for n in names if dex_index(n) is not None), key=lambda n: dex_index(n))
        if not dex_files: raise IntegrationError("APK de suporte sem DEX.")
        helper_classes = set()
        dex_data = []
        for name in dex_files:
            data = archive.read(name)
            classes = dex_classes(data)
            collisions = classes & (existing_classes | helper_classes)
            if collisions:
                raise IntegrationError("Classes duplicadas no suporte: " + ", ".join(sorted(collisions)[:8]))
            helper_classes.update(classes)
            dex_data.append(data)
        missing = HELPER_REQUIRED - helper_classes
        if missing: raise IntegrationError("Classes de suporte ausentes: " + ", ".join(sorted(missing)))
        try:
            model = archive.read("assets/hand_landmarker.task")
        except KeyError as exc:
            raise IntegrationError("Modelo de mãos ausente do APK de suporte.") from exc
        if hashlib.sha256(model).hexdigest() != MODEL_SHA256:
            raise IntegrationError("Hash do modelo de mãos é diferente do fixado.")
        native_names = [n for n in names if n.startswith("lib/arm64-v8a/") and n.endswith(".so")]
        if not any("mediapipe" in n.lower() for n in native_names):
            raise IntegrationError("Biblioteca MediaPipe arm64 ausente no suporte.")
        index = next_dex_index(decoded)
        for data in dex_data:
            (decoded / f"classes{index}.dex").write_bytes(data)
            index += 1
        for name in names:
            if not (name.startswith("assets/") or name in native_names) or name.endswith("/"):
                continue
            target = decoded / name
            data = archive.read(name)
            if target.exists() and target.read_bytes() != data:
                raise IntegrationError(f"Recurso/biblioteca em conflito: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    return {"model_sha256": MODEL_SHA256, "support_classes": str(len(helper_classes))}


def prepare_apktool_yml(path: Path) -> None:
    """Android 10+ and an uncompressed model for MediaPipe's memory mapping."""
    text = path.read_text(encoding="utf-8")
    match = re.search(r"(?m)^(\s*minSdkVersion:\s*['\"]?)(\d+)", text)
    if not match: raise IntegrationError("minSdkVersion ausente do apktool.yml.")
    if int(match.group(2)) < 29:
        text = text[:match.start(2)] + "29" + text[match.end(2):]
    if not re.search(r"(?m)^\s*-\s*task\s*$", text):
        if re.search(r"(?m)^doNotCompress:\s*$", text):
            text = re.sub(r"(?m)^doNotCompress:\s*$", "doNotCompress:\n- task", text, count=1)
        elif re.search(r"(?m)^doNotCompress:\s*\[\s*\]\s*$", text):
            text = re.sub(r"(?m)^doNotCompress:\s*\[\s*\]\s*$", "doNotCompress:\n- task", text, count=1)
        else:
            raise IntegrationError("Lista doNotCompress ausente ou inesperada no apktool.yml.")
    path.write_text(text, encoding="utf-8")
