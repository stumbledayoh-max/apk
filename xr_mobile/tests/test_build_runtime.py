import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from questcraft_mobile import integrated as i
from xr_mobile import build_runtime as x


def fake_library(guard=False):
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", header, 18, 183)
    data = bytes(header) + i.OLD_RUNTIME + b"\0"
    if guard:
        gap = i.EGL_PROBE_EPILOGUE_OFFSET - len(i.EGL_PROBE_SIGNATURE)
        data += i.EGL_PROBE_SIGNATURE + b"\0" * gap + i.EGL_PROBE_EPILOGUE
    return data


class NativeRuntimeTests(unittest.TestCase):
    def test_package_and_egl_guard_are_rewritten_without_resizing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); runtime = root / "runtime.apk"
            client, service = fake_library(True), fake_library(False)
            with zipfile.ZipFile(runtime, "w") as z:
                z.writestr("lib/arm64-v8a/libopenxr_monado.so", client)
                z.writestr("lib/arm64-v8a/libmonado-service.so", service)
            x.install_native(root / "decoded", runtime)
            folder = root / "decoded/lib/arm64-v8a"
            patched = (folder / "libopenxr_monado.so").read_bytes()
            self.assertEqual(len(patched), len(client))
            self.assertIn(x.PACKAGE.encode() + b"\0", patched)
            self.assertNotIn(i.OLD_RUNTIME + b"\0", patched)
            self.assertIn(i.EGL_PROBE_GUARD, patched)
            self.assertEqual(len((folder / "libmonado-service.so").read_bytes()), len(service))
            with self.assertRaises(i.IntegrationError): x.install_native(root / "decoded", runtime)

    def test_rejects_wrong_abi_or_missing_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); runtime = root / "runtime.apk"
            with zipfile.ZipFile(runtime, "w") as z:
                z.writestr("lib/arm64-v8a/libopenxr_monado.so", b"not an ELF")
            with self.assertRaises(i.IntegrationError): x.install_native(root / "decoded", runtime)


class ManifestTests(unittest.TestCase):
    def test_runtime_is_in_the_launcher_and_camera_is_private(self):
        root = ET.parse(Path(__file__).parents[1] / "android/app/src/main/AndroidManifest.xml").getroot()
        a = "{http://schemas.android.com/apk/res/android}"
        app = root.find("application")
        providers = {p.get(a + "name"): p for p in app.findall("provider")}
        broker = providers["org.freedesktop.monado.phonexr.RuntimeBroker"]
        self.assertEqual(broker.get(a + "authorities"), "org.khronos.openxr.system_runtime_broker")
        self.assertEqual(broker.get(a + "exported"), "true")
        self.assertEqual(providers["androidx.core.content.FileProvider"].get(a + "exported"), "false")
        services = {s.get(a + "name"): s for s in app.findall("service")}
        self.assertEqual(services["com.xrpocket.mobile.HandService"].get(a + "exported"), "false")
        self.assertEqual(services["org.freedesktop.monado.ipc.MonadoService"].get(a + "exported"), "true")
        permissions = {p.get(a + "name") for p in root.findall("uses-permission")}
        self.assertIn("android.permission.REQUEST_INSTALL_PACKAGES", permissions)
        self.assertNotIn("android.permission.INSTALL_PACKAGES", permissions)
        self.assertNotIn("android.permission.MANAGE_EXTERNAL_STORAGE", permissions)


if __name__ == "__main__": unittest.main()
