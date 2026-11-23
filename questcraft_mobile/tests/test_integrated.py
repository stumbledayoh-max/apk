import struct
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

from questcraft_mobile import build_apk, integrated as i
from questcraft_mobile.tests.test_build_apk import MANIFEST


class IntegratedManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "AndroidManifest.xml"
        self.path.write_text(MANIFEST, encoding="utf-8")
        build_apk.patch_manifest(self.path)

    def tearDown(self): self.tmp.cleanup()

    def test_single_apk_launcher_runtime_and_camera(self):
        changes = i.patch_manifest(self.path)
        self.assertTrue(changes)
        root = ET.parse(self.path).getroot()
        app = root.find("application")
        self.assertEqual(app.get(i.A + "extractNativeLibs"), "true")
        boot = next(a for a in app.findall("activity") if a.get(i.A + "name") == i.BOOTSTRAP)
        target = next(m for m in boot.findall("meta-data") if m.get(i.A + "name") == i.TARGET_META)
        self.assertEqual(target.get(i.A + "value"), "com.unity3d.player.UnityPlayerActivity")
        launchers = []
        for comp in app.findall("activity") + app.findall("activity-alias"):
            for filt in comp.findall("intent-filter"):
                if "android.intent.category.LAUNCHER" in i._names(filt, "category"):
                    launchers.append(comp.get(i.A + "name"))
        self.assertEqual(launchers, [i.BOOTSTRAP])
        self.assertTrue(set(i.REQUIRED_PERMISSIONS) <= i._names(root, "uses-permission"))
        provider = next(p for p in app.findall("provider") if p.get(i.A + "name") == i.BROKER_CLASS)
        self.assertEqual(provider.get(i.A + "authorities"), i.BROKER)
        self.assertEqual(provider.get(i.A + "exported"), "false")
        services = {s.get(i.A + "name"): s for s in app.findall("service")}
        self.assertEqual(services[i.HAND_SERVICE].get(i.A + "foregroundServiceType"), "camera")
        self.assertEqual(services[i.MONADO_SERVICE].get(i.A + "process"), ":monado")
        before = self.path.read_bytes()
        self.assertEqual(i.patch_manifest(self.path), [])
        self.assertEqual(self.path.read_bytes(), before)

    def test_ambiguous_launcher_rejected_without_writing(self):
        text = self.path.read_text().replace("</application>", '''<activity android:name=".Other"><intent-filter>
          <action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
          </intent-filter></activity></application>''')
        self.path.write_text(text)
        before = self.path.read_bytes()
        with self.assertRaisesRegex(i.IntegrationError, "uma activity"):
            i.patch_manifest(self.path)
        self.assertEqual(before, self.path.read_bytes())

    def test_relative_alias_target_is_normalized(self):
        text = self.path.read_text().replace("com.unity3d.player.UnityPlayerActivity", ".MainActivity")
        self.path.write_text(text)
        i.patch_manifest(self.path)
        boot = next(a for a in ET.parse(self.path).getroot().find("application").findall("activity") if a.get(i.A + "name") == i.BOOTSTRAP)
        self.assertEqual(boot.find("meta-data").get(i.A + "value"), "com.qcxr.qcxr.MainActivity")


class BinaryTests(unittest.TestCase):
    def test_cstring_replacement_preserves_offsets(self):
        data = b"prefix\0old.long.string\0suffix"
        result = i.replace_cstring(data, b"old.long.string", b"short")
        self.assertEqual(len(result), len(data))
        self.assertEqual(result.index(b"suffix"), data.index(b"suffix"))
        self.assertIn(b"short\0", result)
        with self.assertRaises(i.IntegrationError): i.replace_cstring(data, b"missing", b"x")
        with self.assertRaises(i.IntegrationError): i.replace_cstring(data, b"old.long.string", b"x" * 100)
        with self.assertRaises(i.IntegrationError): i.replace_cstring(data + b"old.long.string\0", b"old.long.string", b"x")

    def test_dex_class_parsing_and_truncation(self):
        descriptor = b"Lcom/example/Test;"
        header = bytearray(112)
        header[:8] = b"dex\n035\0"
        string_off, type_off, class_off = 112, 116, 120
        string_data_off = class_off + 32
        struct.pack_into("<IIII", header, 56, 1, string_off, 1, type_off)
        struct.pack_into("<II", header, 96, 1, class_off)
        data = header + struct.pack("<I", string_data_off) + struct.pack("<I", 0) + bytes(32) + bytes([len(descriptor)]) + descriptor + b"\0"
        self.assertEqual(i.dex_classes(data), {descriptor.decode()})
        with self.assertRaises(i.IntegrationError): i.dex_classes(data[:120])

    def test_apktool_yml_min_sdk_and_uncompressed_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "apktool.yml"
            path.write_text("sdkInfo:\n  minSdkVersion: 23\n  targetSdkVersion: 32\ndoNotCompress:\n- png\n")
            i.prepare_apktool_yml(path)
            text = path.read_text()
            self.assertIn("minSdkVersion: 29", text)
            self.assertIn("- task\n", text)
            i.prepare_apktool_yml(path)
            self.assertEqual(path.read_text(), text)

    def test_next_dex_accounts_for_smali(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "classes.dex").touch()
            (path / "classes2.dex").touch()
            (path / "smali_classes3").mkdir()
            self.assertEqual(i.next_dex_index(path), 4)


if __name__ == "__main__": unittest.main()
