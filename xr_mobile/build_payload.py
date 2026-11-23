#!/usr/bin/env python3
"""Prepare the private, relocatable helper DEX used by BigXR's on-device creator.

This is a build input, not an installable app. The runtime, model and resulting
APK are never downloaded from a user's phone and no game is bundled here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from questcraft_mobile import build_apk as tools  # noqa: E402
from questcraft_mobile import integrated  # noqa: E402
from xr_mobile import build_runtime  # noqa: E402

REQUIRED = {
    "Lcom/bigxr/embedded/BootstrapActivity;",
    "Lcom/bigxr/embedded/HandService;",
    "Lorg/freedesktop/monado/ipc/MonadoService;",
    "Lorg/freedesktop/monado/auxiliary/ActivityLifecycleListener;",
    "Lorg/freedesktop/monado/auxiliary/SystemUiController;",
    "Lcom/google/mediapipe/tasks/vision/handlandmarker/HandLandmarker;",
    "Lorg/freedesktop/monado/ipc/Client;",
    "Lorg/freedesktop/monado/ipc/MonadoImpl;",
    "Lorg/freedesktop/monado/phonexr/RuntimeBroker;",
} | build_runtime.OLFX_COMPONENT_CLASSES


def read_classes(path: Path) -> set[str]:
    result: set[str] = set()
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if integrated.dex_index(name) is not None:
                found = integrated.dex_classes(archive.read(name))
                if result & found:
                    raise tools.BuildError("Classes duplicadas no suporte")
                result.update(found)
    return result


def build(helper: Path, runtime: Path, apktool_jar: Path, output: Path) -> None:
    tools.verify_hash(runtime, integrated.RUNTIME_SHA256, False, "Runtime fixado")
    java = tools.find_program("java")
    apktool = [java, "-jar", str(apktool_jar.resolve())]
    helper_classes = read_classes(helper)
    with tempfile.TemporaryDirectory(prefix="bigxr-payload-") as tmp:
        root = Path(tmp); decoded = root / "helper"; unpacked = root / "runtime"
        tools.run(apktool + ["d", "-f", "-s", str(helper.resolve()), "-o", str(decoded)])
        tools.run(apktool + ["d", "-f", str(runtime.resolve()), "-o", str(unpacked)])
        runtime_classes = integrated.install_runtime_smali(decoded, unpacked)
        if runtime_classes & helper_classes:
            raise tools.BuildError("Classes do runtime já presentes no suporte")
        integrated.prepare_apktool_yml(decoded / "apktool.yml")
        assembled = root / "payload.apk"
        tools.run(apktool + ["b", str(decoded), "-o", str(assembled)])
        classes = read_classes(assembled)
        missing = REQUIRED - classes
        if missing: raise tools.BuildError("Classes do suporte ausentes: " + ", ".join(sorted(missing)))
        destination = output / "bigxr_payload"
        if destination.exists(): shutil.rmtree(destination)
        destination.mkdir(parents=True)
        manifest: dict[str, object] = {"version": 1, "dex": [], "native": [], "assets": []}

        def add(group: str, apk_path: str, data: bytes, *, stored: bool = False) -> None:
            # Each file is a build-time asset, never a path supplied by a game.
            if not data or len(data) > 128 * 1024 * 1024:
                raise tools.BuildError(f"Arquivo de suporte inválido: {apk_path}")
            local = f"{group}/{len(manifest[group])}.bin"
            path = destination / local
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            manifest[group].append({"source": local, "path": apk_path, "size": len(data),
                                    "sha256": hashlib.sha256(data).hexdigest(), "stored": stored})

        with zipfile.ZipFile(assembled) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise tools.BuildError("Entradas duplicadas no suporte BigXR")
            dex_names = sorted((n for n in names if integrated.dex_index(n) is not None),
                               key=lambda n: integrated.dex_index(n))
            for name in dex_names:
                add("dex", name, archive.read(name))
            for name in sorted(names):
                if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
                    add("native", name, archive.read(name), stored=True)
                elif name.startswith("assets/") and not name.endswith("/"):
                    add("assets", name, archive.read(name), stored=(name == "assets/hand_landmarker.task"))
        if not any("mediapipe" in item["path"].lower() for item in manifest["native"]):
            raise tools.BuildError("Suporte sem MediaPipe ARM64")
        models = [item for item in manifest["assets"] if item["path"] == "assets/hand_landmarker.task"]
        if len(models) != 1 or models[0]["sha256"] != integrated.MODEL_SHA256:
            raise tools.BuildError("Modelo de mãos ausente ou diferente do modelo fixado")
        licenses = [item for item in manifest["assets"] if item["path"] == build_runtime.OLFX_LICENSE_PATH]
        if len(licenses) != 1 or licenses[0]["sha256"] != build_runtime.OLFX_LICENSE_SHA256:
            raise tools.BuildError("Licença dos componentes OLFX ausente ou alterada")
        with zipfile.ZipFile(runtime) as archive:
            for name in ("libopenxr_monado.so", "libmonado-service.so"):
                path = "lib/arm64-v8a/" + name
                data = archive.read(path)
                if data[:6] != b"\x7fELF\x02\x01" or len(data) < 64 or data[18:20] != b"\xb7\x00":
                    raise tools.BuildError("Runtime não é ELF ARM64: " + name)
                if data.count(integrated.OLD_RUNTIME + b"\0") != 1:
                    raise tools.BuildError("Identificador do runtime inesperado: " + name)
                if name == "libopenxr_monado.so":
                    data = build_runtime.keep_client_mapped(integrated.guard_optional_egl_probe(data))
                add("native", path, data, stored=True)
        (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"Suporte BigXR preparado: {len(manifest['dex'])} DEX, {len(classes)} classes", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--helper", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--apktool-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    try: build(**vars(parser.parse_args()))
    except (tools.BuildError, integrated.IntegrationError, OSError, zipfile.BadZipFile) as error:
        print(f"Erro: {error}", file=sys.stderr); return 1
    return 0


if __name__ == "__main__": raise SystemExit(main())
