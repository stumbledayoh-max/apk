#!/usr/bin/env python3
"""Build the experimental standalone XR launcher with its pinned Monado runtime.

The result is one launcher APK. A user-selected game is installed separately by
Android and must itself support Android OpenXR runtime discovery.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from questcraft_mobile import build_apk as tools  # noqa: E402
from questcraft_mobile import integrated  # noqa: E402

PACKAGE = "com.xrpocket.mobile"
REQUIRED_APP_CLASSES = {
    "Lcom/xrpocket/mobile/MainActivity;",
    "Lcom/xrpocket/mobile/HandService;",
    "Lorg/freedesktop/monado/ipc/MonadoService;",
    "Lorg/freedesktop/monado/auxiliary/ActivityLifecycleListener;",
    "Lorg/freedesktop/monado/auxiliary/SystemUiController;",
    "Lcom/google/mediapipe/tasks/vision/handlandmarker/HandLandmarker;",
}


def install_native(decoded: Path, runtime: Path) -> None:
    target = decoded / "lib" / "arm64-v8a"
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(runtime) as archive:
        for name in ("libopenxr_monado.so", "libmonado-service.so"):
            try:
                data = archive.read("lib/arm64-v8a/" + name)
            except KeyError as exc:
                raise integrated.IntegrationError(f"Runtime sem biblioteca {name}") from exc
            if len(data) < 64 or data[:4] != b"\x7fELF" or data[4:6] != b"\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
                raise integrated.IntegrationError(f"Biblioteca não é ELF arm64: {name}")
            data = integrated.replace_cstring(data, integrated.OLD_RUNTIME, PACKAGE.encode("ascii"))
            if name == "libopenxr_monado.so":
                data = integrated.guard_optional_egl_probe(data)
            path = target / name
            if path.exists(): raise integrated.IntegrationError(f"Biblioteca em conflito: {name}")
            path.write_bytes(data)


def validate_host(apk: Path) -> set[str]:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise tools.BuildError("APK do launcher inválido ou corrompido")
        try:
            model = archive.read("assets/hand_landmarker.task")
        except KeyError as exc:
            raise tools.BuildError("Modelo de mãos não foi integrado") from exc
        if hashlib.sha256(model).hexdigest() != integrated.MODEL_SHA256:
            raise tools.BuildError("Modelo de mãos não corresponde ao arquivo fixado")
        if archive.getinfo("assets/hand_landmarker.task").compress_type != zipfile.ZIP_STORED:
            raise tools.BuildError("Modelo de mãos deve ficar sem compressão")
        if not any(n.startswith("lib/arm64-v8a/") and "mediapipe" in n.lower() and n.endswith(".so") for n in names):
            raise tools.BuildError("Biblioteca nativa MediaPipe ausente")
        classes: set[str] = set()
        for name in names:
            if integrated.dex_index(name) is not None:
                found = integrated.dex_classes(archive.read(name))
                if classes & found:
                    raise tools.BuildError("Classes DEX duplicadas no launcher")
                classes.update(found)
        missing = REQUIRED_APP_CLASSES - classes
        if missing:
            raise tools.BuildError("Classes ausentes: " + ", ".join(sorted(missing)))
        return classes


def build(args: argparse.Namespace) -> None:
    java = tools.find_program("java")
    keytool = tools.find_program("keytool")
    align, signer = tools.build_tools(args.build_tools)
    if not args.apktool_jar.is_file(): raise tools.BuildError("apktool.jar não encontrado")
    app = args.app.resolve()
    runtime = args.runtime.resolve()
    output = args.output.resolve()
    if output.suffix.lower() != ".apk" or output in (app, runtime):
        raise tools.BuildError("Saída deve ser um APK diferente dos arquivos de entrada")
    tools.verify_hash(runtime, integrated.RUNTIME_SHA256, False, "Runtime fixado")
    app_classes = validate_host(app)
    apktool = [java, "-jar", str(args.apktool_jar.resolve())]
    with tempfile.TemporaryDirectory(prefix="xr-pocket-") as tmp:
        work = Path(tmp); decoded = work / "app"; runtime_decoded = work / "runtime"
        tools.run(apktool + ["d", "-f", "-s", str(app), "-o", str(decoded)])
        tools.run(apktool + ["d", "-f", str(runtime), "-o", str(runtime_decoded)])
        runtime_classes = integrated.install_runtime_smali(decoded, runtime_decoded)
        collisions = app_classes & runtime_classes
        if collisions: raise tools.BuildError("Classes de runtime duplicadas: " + ", ".join(sorted(collisions)))
        install_native(decoded, runtime)
        integrated.prepare_apktool_yml(decoded / "apktool.yml")
        unsigned, aligned, signed = work / "unsigned.apk", work / "aligned.apk", work / "signed.apk"
        tools.run(apktool + ["b", str(decoded), "-o", str(unsigned)])
        tools.run([align, "-P", "16", "-f", "4", str(unsigned), str(aligned)])
        keystore = args.debug_keystore.resolve()
        if not keystore.exists():
            keystore.parent.mkdir(parents=True, exist_ok=True)
            previous_umask = os.umask(0o077)
            try:
                env = {**os.environ, "XR_DEBUG_PASSWORD": "android"}
                tools.run([keytool, "-genkeypair", "-keystore", str(keystore), "-storetype", "PKCS12",
                           "-storepass:env", "XR_DEBUG_PASSWORD", "-keypass:env", "XR_DEBUG_PASSWORD",
                           "-alias", "xr-debug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                           "-dname", "CN=XR de Bolso Debug, O=Local Debug, C=BR"], env=env)
            finally:
                os.umask(previous_umask)
        env = {**os.environ, "XR_DEBUG_PASSWORD": "android"}
        tools.run([signer, "sign", "--ks", str(keystore), "--ks-key-alias", "xr-debug", "--ks-pass", "env:XR_DEBUG_PASSWORD",
                   "--key-pass", "env:XR_DEBUG_PASSWORD", "--out", str(signed), str(aligned)], env=env)
        tools.run([signer, "verify", "--verbose", "--print-certs", str(signed)])
        tools.run([align, "-c", "-P", "16", "4", str(signed)])
        final_classes = validate_host(signed)
        if not runtime_classes <= final_classes:
            raise tools.BuildError("Classes do runtime ausentes do APK final")
        with zipfile.ZipFile(signed) as archive:
            for name in ("libopenxr_monado.so", "libmonado-service.so"):
                data = archive.read("lib/arm64-v8a/" + name)
                if PACKAGE.encode("ascii") + b"\0" not in data or integrated.OLD_RUNTIME + b"\0" in data:
                    raise tools.BuildError("Biblioteca do runtime não aponta para o launcher")
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(signed, output)
        digest = tools.sha256(output)
        output.with_name(output.name + ".sha256").write_text(f"{digest}  {output.name}\n", encoding="ascii")
        print(f"Launcher experimental: {output}\nSHA-256: {digest}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--apktool-jar", type=Path, required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--debug-keystore", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("xr-mobile-build/XR-de-Bolso-debug.apk"))
    try:
        build(parser.parse_args())
    except (tools.BuildError, integrated.IntegrationError, OSError, zipfile.BadZipFile) as error:
        print(f"Erro: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
