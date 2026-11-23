#!/usr/bin/env python3
"""APK experimental único do QuestCraft 6.0.0 com runtime e mãos integrados.

O runtime é baseado no Monado/PhoneXR fixado; o rastreamento usa MediaPipe.
A instalação separada do PhoneXR não é necessária. Funcionamento não comprovado.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

if __package__:
    from . import integrated
else:
    import integrated

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID + "}"
ET.register_namespace("android", ANDROID)

EXPECTED_PACKAGE = "com.qcxr.qcxr"
OFFICIAL_URL = "https://github.com/QuestCraftPlusPlus/QuestCraft/releases/download/6.0.0/QCXR-6.0.0.apk"
OFFICIAL_SHA256 = "4d474dff57ba131a8719e073078c9212c739c18841716f37968dfee6732b783c"
# Source and corresponding PhoneXR runtime: pinned, not a moving 'main' URL.
PHONEXR_COMMIT = "f393ea8c3f4579995efb7402d61a2108121b0900"
LOADER_URL = (
    "https://api.github.com/repos/samrat1games/phonexr/contents/"
    "app/src/main/assets/libopenxr_loader.so?ref=" + PHONEXR_COMMIT
)
LOADER_SHA256 = "763141820ead68e92543db0fefadca3dc5687e893072add86ab2fada7f95b3ff"
RUNTIME_URL = (
    "https://api.github.com/repos/samrat1games/phonexr/contents/"
    "app/src/main/assets/runtime/phonexr-runtime.apk?ref=" + PHONEXR_COMMIT
)
PHONEXR_RELEASE = "https://github.com/samrat1games/phonexr/releases/tag/v2.0.1"
RUNTIME_PACKAGE = "org.freedesktop.monado.openxr_runtime.out_of_process"
BROKERS = ("org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker")
OPENXR_PERMISSIONS = (
    "android.permission.INTERNET",
    "org.khronos.openxr.permission.OPENXR",
    "org.khronos.openxr.permission.OPENXR_SYSTEM",
)
VISIBLE_PACKAGES = (RUNTIME_PACKAGE, "org.khronos.openxr.runtime_broker", "com.samrat.cardboardhands")
OPENXR_SERVICES = (
    "org.khronos.openxr.OpenXRRuntimeService",
    "org.khronos.openxr.OpenXRApiLayerService",
)
IMMERSIVE_CATEGORY = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"
# Making these optional only permits installation. It does not implement any of them.
HEADSET_FEATURE_PREFIXES = (
    "android.hardware.vr", "android.hardware.xr.", "android.software.xr.",
    "oculus.", "com.oculus.", "picovr", "wave.feature",
)
OPTIONAL_FEATURES = {"android.hardware.vulkan.version", "android.hardware.vulkan.level"}
MAX_UNPACKED_APK = 2 * 1024 * 1024 * 1024


class BuildError(Exception):
    """Erro esperado com instruções ao usuário."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_checked(url: str, destination: Path, expected_hash: str, *, github_raw: bool = False) -> None:
    headers = {"User-Agent": "QuestCraft-Mobile-Experimental/1.0"}
    if github_raw:
        headers["Accept"] = "application/vnd.github.raw+json"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
    except (OSError, urllib.error.URLError) as exc:
        destination.unlink(missing_ok=True)
        raise BuildError(f"Não foi possível baixar {url}: {exc}") from exc
    actual = sha256(destination)
    if actual != expected_hash:
        destination.unlink(missing_ok=True)
        raise BuildError(f"SHA-256 inesperado para {url}: {actual}. Download descartado.")


def verify_hash(path: Path, expected: str, allow_unverified: bool, description: str) -> str:
    if not path.is_file():
        raise BuildError(f"{description} não encontrado: {path}")
    actual = sha256(path)
    if actual != expected and not allow_unverified:
        raise BuildError(
            f"{description}: SHA-256 {actual} não corresponde à versão fixada. "
            "Verifique a procedência; só para experimentar outra versão, use a opção --allow-unverified correspondente."
        )
    if actual != expected:
        print(f"AVISO: {description} não é o arquivo oficial fixado ({actual}).", flush=True)
    return actual


def validate_loader(path: Path) -> None:
    with path.open("rb") as handle:
        header = handle.read(64)
    if (len(header) < 64 or header[:4] != b"\x7fELF" or header[4:6] != b"\x02\x01"
            or struct.unpack_from("<H", header, 16)[0] != 3
            or struct.unpack_from("<H", header, 18)[0] != 183):
        raise BuildError("O loader precisa ser uma biblioteca ELF Android arm64-v8a (AArch64).")


def validate_apk(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            seen: set[str] = set()
            total = 0
            for entry in entries:
                name = entry.filename
                parts = name.replace("\\", "/").split("/")
                if name.startswith(("/", "\\")) or ".." in parts or "\\" in name:
                    raise BuildError(f"Caminho inseguro no APK: {name}")
                if name in seen:
                    raise BuildError(f"Entrada duplicada no APK: {name}")
                seen.add(name)
                total += entry.file_size
                if total > MAX_UNPACKED_APK:
                    raise BuildError("APK descompactado excede o limite de segurança de 2 GiB.")
            if "AndroidManifest.xml" not in seen:
                raise BuildError("APK sem AndroidManifest.xml.")
            if "lib/arm64-v8a/libopenxr_loader.so" not in seen:
                raise BuildError("APK sem libopenxr_loader.so arm64-v8a. Esta adaptação é específica para QuestCraft/OpenXR.")
            bad = archive.testzip()
            if bad:
                raise BuildError(f"Entrada corrompida no APK: {bad}")
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise BuildError(f"APK inválido: {exc}") from exc


def _children_named(parent: ET.Element, tag: str) -> set[str]:
    return {child.get(A + "name", "") for child in parent.findall(tag)}


def patch_manifest(manifest_path: Path) -> list[str]:
    """Altera XML decodificado pelo apktool; idempotente e sem mexer no pacote."""
    try:
        tree = ET.parse(manifest_path)
    except (ET.ParseError, OSError) as exc:
        raise BuildError(f"Não foi possível ler o manifesto decodificado: {exc}") from exc
    root = tree.getroot()
    if root.tag != "manifest" or root.get("package") != EXPECTED_PACKAGE:
        raise BuildError(f"Pacote inesperado: esperado {EXPECTED_PACKAGE}, obtido {root.get('package')!r}.")
    app = root.find("application")
    if app is None:
        raise BuildError("Manifesto sem elemento <application>.")

    # Check for a real launchable activity before writing any changes.
    launch_filters: list[ET.Element] = []
    for component in list(app.findall("activity")) + list(app.findall("activity-alias")):
        for intent_filter in component.findall("intent-filter"):
            if ("android.intent.action.MAIN" in _children_named(intent_filter, "action")
                    and "android.intent.category.LAUNCHER" in _children_named(intent_filter, "category")):
                launch_filters.append(intent_filter)
    if not launch_filters:
        raise BuildError("Manifesto não tem uma activity MAIN/LAUNCHER; adaptação interrompida.")

    changes: list[str] = []
    for feature in root.findall("uses-feature"):
        name = feature.get(A + "name", "")
        if (name in OPTIONAL_FEATURES or name.startswith(HEADSET_FEATURE_PREFIXES)) and feature.get(A + "required") != "false":
            feature.set(A + "required", "false")
            changes.append(f"Requisito de headset tornado opcional: {name}")

    existing_permissions = _children_named(root, "uses-permission") | _children_named(root, "uses-permission-sdk-23")
    insert_at = list(root).index(app)
    for permission in OPENXR_PERMISSIONS:
        if permission not in existing_permissions:
            element = ET.Element("uses-permission", {A + "name": permission})
            root.insert(insert_at, element)
            insert_at += 1
            changes.append(f"Permissão adicionada: {permission}")

    queries = root.find("queries")
    if queries is None:
        queries = ET.Element("queries")
        root.insert(list(root).index(app), queries)
    existing_brokers: set[str] = set()
    for provider in queries.findall("provider"):
        existing_brokers.update(provider.get(A + "authorities", "").split(";"))
    missing_brokers = [broker for broker in BROKERS if broker not in existing_brokers]
    if missing_brokers:
        ET.SubElement(queries, "provider", {A + "authorities": ";".join(missing_brokers)})
        changes.append("Visibilidade dos brokers OpenXR adicionada")
    packages = _children_named(queries, "package")
    for package in VISIBLE_PACKAGES:
        if package not in packages:
            ET.SubElement(queries, "package", {A + "name": package})
            changes.append(f"Visibilidade de pacote adicionada: {package}")
    actions = {action.get(A + "name", "") for intent in queries.findall("intent") for action in intent.findall("action")}
    for service in OPENXR_SERVICES:
        if service not in actions:
            intent = ET.SubElement(queries, "intent")
            ET.SubElement(intent, "action", {A + "name": service})
            changes.append(f"Visibilidade de serviço adicionada: {service}")
    for intent_filter in launch_filters:
        if IMMERSIVE_CATEGORY not in _children_named(intent_filter, "category"):
            ET.SubElement(intent_filter, "category", {A + "name": IMMERSIVE_CATEGORY})
            changes.append("Categoria OpenXR adicionada à activity de lançamento")

    # The Java VM used by QuestCraft/Pojlib may abort with Android's heap pointer tagging.
    if app.get(A + "allowNativeHeapPointerTagging") != "false":
        app.set(A + "allowNativeHeapPointerTagging", "false")
        changes.append("Marcação de ponteiros do heap nativo desativada")
    tree.write(manifest_path, encoding="utf-8", xml_declaration=True)
    return changes


def find_program(name: str, requested: str | None = None) -> str:
    target = requested or shutil.which(name)
    if not target or not Path(target).is_file():
        raise BuildError(f"{name} não encontrado. Veja os pré-requisitos no README.")
    return str(Path(target).resolve())


def build_tools(directory: str | None) -> tuple[str, str]:
    if directory:
        folders = [Path(directory)]
    else:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise BuildError("Defina ANDROID_HOME ou passe --build-tools com o diretório Android build-tools 35+.")
        root = Path(sdk) / "build-tools"
        folders = sorted((p for p in root.glob("*") if p.is_dir()), key=lambda p: p.name, reverse=True)
    for folder in folders:
        align, signer = folder / "zipalign", folder / "apksigner"
        if align.is_file() and signer.is_file():
            return str(align.resolve()), str(signer.resolve())
    raise BuildError("zipalign/apksigner não encontrados; instale Android SDK build-tools 35 ou superior.")


def run(command: list[str], *, env: dict[str, str] | None = None) -> None:
    # Commands never include a user's password; the generated key is for debug only.
    print("+ " + " ".join(command), flush=True)
    try:
        subprocess.run(command, check=True, env=env)
    except FileNotFoundError as exc:
        raise BuildError(f"Ferramenta não encontrada: {command[0]}") from exc
    except subprocess.CalledProcessError as exc:
        raise BuildError(f"Comando falhou (código {exc.returncode}): {command[0]}") from exc


def ensure_debug_keystore(path: Path, keytool: str, env: dict[str, str]) -> None:
    if path.exists():
        if not path.is_file():
            raise BuildError(f"Caminho da chave não é um arquivo: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Gerando chave de teste local em {path} (não serve para produção).", flush=True)
    old_umask = os.umask(0o077)
    try:
        run([keytool, "-genkeypair", "-keystore", str(path), "-storetype", "PKCS12",
             "-storepass:env", "QUESTCRAFT_DEBUG_PASSWORD", "-keypass:env", "QUESTCRAFT_DEBUG_PASSWORD",
             "-alias", "questcraft-debug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=QuestCraft Mobile Debug, O=Local Debug, C=BR"], env=env)
    except BuildError:
        path.unlink(missing_ok=True)
        raise
    finally:
        os.umask(old_umask)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Gera um APK experimental único de QuestCraft para Android arm64.")
    parser.add_argument("--apk", type=Path, help="APK do usuário; sem esta opção baixa o release oficial 6.0.0")
    parser.add_argument("--loader", type=Path, help="loader OpenXR local; sem esta opção baixa o loader fixado")
    parser.add_argument("--runtime-apk", type=Path, help="APK de origem do runtime Monado/PhoneXR fixado (não será instalado)")
    parser.add_argument("--support-apk", type=Path, default=Path("questcraft_mobile/embedded/app/build/outputs/apk/release/app-release-unsigned.apk"), help="suporte Android compilado para integrar ao APK")
    parser.add_argument("--allow-unverified-apk", action="store_true", help="aceita APK de hash diferente (confie na origem)")
    parser.add_argument("--allow-unverified-loader", action="store_true", help="aceita loader de hash diferente (confie na origem)")
    parser.add_argument("--apktool-jar", type=Path, help="apktool.jar; sem esta opção procura apktool no PATH")
    parser.add_argument("--build-tools", help="diretório Android SDK build-tools (35+)")
    parser.add_argument("--debug-keystore", type=Path, default=Path.home() / ".android/questcraft-mobile-debug.p12")
    parser.add_argument("-o", "--output", type=Path, default=Path("questcraft-build/QuestCraft-Mobile-debug.apk"))
    return parser.parse_args(argv)


def build(args: argparse.Namespace) -> Path:
    java = find_program("java")
    keytool = find_program("keytool")
    if args.apktool_jar:
        if not args.apktool_jar.is_file():
            raise BuildError(f"apktool.jar não encontrado: {args.apktool_jar}")
        apktool = [java, "-jar", str(args.apktool_jar.resolve())]
    else:
        apktool = [find_program("apktool")]
    zipalign, apksigner = build_tools(args.build_tools)
    output = args.output.expanduser().resolve()
    if output.suffix.lower() != ".apk":
        raise BuildError("O arquivo de saída precisa terminar em .apk.")
    if args.apk and output == args.apk.expanduser().resolve():
        raise BuildError("A saída não pode substituir o APK de entrada.")
    if args.loader and output == args.loader.expanduser().resolve():
        raise BuildError("A saída não pode substituir o loader de entrada.")
    if args.runtime_apk and output == args.runtime_apk.expanduser().resolve():
        raise BuildError("A saída não pode substituir o runtime de entrada.")
    support = args.support_apk.expanduser().resolve()
    if output == support:
        raise BuildError("A saída não pode substituir o APK de suporte.")
    if not support.is_file():
        raise BuildError(f"APK de suporte não encontrado: {support}. Compile questcraft_mobile/embedded primeiro.")

    with tempfile.TemporaryDirectory(prefix="questcraft-mobile-") as tmp:
        work = Path(tmp)
        source = args.apk.expanduser().resolve() if args.apk else work / "QCXR-6.0.0.apk"
        if not args.apk:
            print("Baixando o QuestCraft 6.0.0 oficial...", flush=True)
            download_checked(OFFICIAL_URL, source, OFFICIAL_SHA256)
        source_hash = verify_hash(source, OFFICIAL_SHA256, args.allow_unverified_apk, "APK de origem")
        validate_apk(source)

        loader = args.loader.expanduser().resolve() if args.loader else work / "libopenxr_loader.so"
        if not args.loader:
            print("Baixando o loader OpenXR fixado do PhoneXR...", flush=True)
            download_checked(LOADER_URL, loader, LOADER_SHA256, github_raw=True)
        loader_hash = verify_hash(loader, LOADER_SHA256, args.allow_unverified_loader, "Loader OpenXR")
        validate_loader(loader)

        runtime = args.runtime_apk.expanduser().resolve() if args.runtime_apk else work / "phonexr-runtime.apk"
        if not args.runtime_apk:
            print("Baixando o runtime Monado/PhoneXR fixado para integração...", flush=True)
            download_checked(RUNTIME_URL, runtime, integrated.RUNTIME_SHA256, github_raw=True)
        runtime_hash = verify_hash(runtime, integrated.RUNTIME_SHA256, False, "Runtime")

        decoded = work / "decoded"
        runtime_decoded = work / "runtime-decoded"
        run(apktool + ["d", "-f", "-s", str(source), "-o", str(decoded)])
        run(apktool + ["d", "-f", str(runtime), "-o", str(runtime_decoded)])
        changes = patch_manifest(decoded / "AndroidManifest.xml")
        changes += integrated.patch_manifest(decoded / "AndroidManifest.xml")
        native = decoded / "lib"
        target_loader = native / "arm64-v8a" / "libopenxr_loader.so"
        if not target_loader.is_file():
            raise BuildError("Loader arm64 ausente depois da decodificação; o APK não foi alterado.")
        shutil.copyfile(loader, target_loader)
        integrated.patch_loader(target_loader)
        # Avoid installing an unpatched 32-bit/x86 loader on a device with a different ABI.
        for abi in native.iterdir():
            if abi.is_dir() and abi.name != "arm64-v8a":
                shutil.rmtree(abi)
                changes.append(f"ABI removida (build somente arm64): {abi.name}")
        integrated.install_runtime_libs(decoded, runtime)
        runtime_classes = integrated.install_runtime_smali(decoded, runtime_decoded)
        helper_report = integrated.install_helper(decoded, support, runtime_classes)
        integrated.prepare_apktool_yml(decoded / "apktool.yml")
        changes += ["Loader OpenXR ajustado para broker privado dentro do APK",
                    "Runtime Monado, sensores e ponte de câmera integrados no mesmo APK",
                    "Guarda para extensão EGL opcional ausente no runtime Monado fixado"]

        unsigned, aligned, signed = work / "unsigned.apk", work / "aligned.apk", work / "signed.apk"
        # Apktool 2.9+ uses aapt2 by default; --use-aapt2 is no longer a valid flag.
        run(apktool + ["b", str(decoded), "-o", str(unsigned)])
        # Build-tools 35+ aligns .so files for 16 KiB page-size devices as well as 4 KiB.
        run([zipalign, "-P", "16", "-f", "4", str(unsigned), str(aligned)])
        env = os.environ.copy()
        env["QUESTCRAFT_DEBUG_PASSWORD"] = "android"  # disposable DEBUG key only
        keystore = args.debug_keystore.expanduser().resolve()
        ensure_debug_keystore(keystore, keytool, env)
        run([apksigner, "sign", "--ks", str(keystore), "--ks-key-alias", "questcraft-debug",
             "--ks-pass", "env:QUESTCRAFT_DEBUG_PASSWORD", "--key-pass", "env:QUESTCRAFT_DEBUG_PASSWORD",
             "--out", str(signed), str(aligned)], env=env)
        run([apksigner, "verify", "--verbose", "--print-certs", str(signed)])
        run([zipalign, "-c", "-P", "16", "4", str(signed)])
        validate_apk(signed)
        with zipfile.ZipFile(signed) as archive:
            for name in ("lib/arm64-v8a/libopenxr_monado.so", "lib/arm64-v8a/libmonado-service.so", "assets/hand_landmarker.task"):
                try:
                    entry = archive.getinfo(name)
                except KeyError as exc:
                    raise BuildError(f"Arquivo integrado ausente do APK final: {name}") from exc
                if name.endswith(".task") and entry.compress_type != zipfile.ZIP_STORED:
                    raise BuildError("O modelo de mãos precisa estar sem compressão para o MediaPipe.")
        result_hash = sha256(signed)
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(signed, output)
        checksum = output.with_name(output.name + ".sha256")
        checksum.write_text(f"{result_hash}  {output.name}\n", encoding="utf-8")
        report = {
            "created_utc": datetime.now(timezone.utc).isoformat(),
            "package": EXPECTED_PACKAGE,
            "source": str(args.apk) if args.apk else OFFICIAL_URL,
            "source_sha256": source_hash,
            "loader_source": str(args.loader) if args.loader else LOADER_URL,
            "loader_sha256": loader_hash,
            "runtime_source": str(args.runtime_apk) if args.runtime_apk else RUNTIME_URL,
            "runtime_sha256": runtime_hash,
            "support_sha256": sha256(support),
            "hand_model_sha256": helper_report["model_sha256"],
            "phonexr_source": PHONEXR_RELEASE,
            "separate_phonexr_required": False,
            "output": output.name,
            "output_sha256": result_hash,
            "signing": "generated debug key; not the original QuestCraft signing key",
            "changes": changes,
        }
        output.with_name("build-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\nAPK único experimental: {output}\nSHA-256: {result_hash}\nAbra pelo ícone do QuestCraft; câmera opcional.", flush=True)
    return output


def main(argv: list[str] | None = None) -> int:
    try:
        build(parse_args(argv))
    except (BuildError, integrated.IntegrationError, OSError, zipfile.BadZipFile) as exc:
        print(f"Erro: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
