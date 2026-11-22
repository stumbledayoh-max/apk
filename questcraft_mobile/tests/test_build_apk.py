import io
import json
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

from questcraft_mobile import build_apk as b


MANIFEST = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="{b.ANDROID}" package="com.qcxr.qcxr">
    <uses-feature android:name="android.hardware.vr.headtracking" android:version="1" />
    <uses-feature android:name="oculus.software.eye_tracking" android:required="true" />
    <uses-feature android:name="android.hardware.vulkan.version" android:required="true" />
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-permission android:name="android.permission.INTERNET" />
    <queries>
        <provider android:authorities="org.khronos.openxr.runtime_broker" />
        <package android:name="com.other.app" />
    </queries>
    <application android:label="QuestCraft" android:extractNativeLibs="false">
        <activity android:name="com.unity3d.player.UnityPlayerActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="com.oculus.intent.category.VR" />
            </intent-filter>
        </activity>
    </application>
</manifest>'''


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "AndroidManifest.xml"
        self.path.write_text(MANIFEST, encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_required_changes_and_idempotence(self):
        changes = b.patch_manifest(self.path)
        self.assertTrue(changes)
        root = ET.parse(self.path).getroot()
        self.assertEqual(root.get("package"), "com.qcxr.qcxr")
        features = {f.get(b.A + "name"): f.get(b.A + "required") for f in root.findall("uses-feature")}
        self.assertEqual(features["android.hardware.vr.headtracking"], "false")
        self.assertEqual(features["oculus.software.eye_tracking"], "false")
        self.assertEqual(features["android.hardware.vulkan.version"], "false")
        self.assertEqual(features["android.hardware.camera"], "true")
        permissions = b._children_named(root, "uses-permission")
        self.assertTrue(set(b.OPENXR_PERMISSIONS) <= permissions)
        queries = root.find("queries")
        self.assertIsNotNone(queries)
        self.assertEqual(len(root.findall("queries")), 1)
        self.assertTrue(set(b.VISIBLE_PACKAGES) <= b._children_named(queries, "package"))
        broker_names = {x for p in queries.findall("provider") for x in p.get(b.A + "authorities").split(";")}
        self.assertEqual(broker_names, set(b.BROKERS))
        services = {action.get(b.A + "name") for action in queries.findall("intent/action")}
        self.assertEqual(services, set(b.OPENXR_SERVICES))
        app = root.find("application")
        self.assertEqual(app.get(b.A + "label"), "QuestCraft")
        self.assertEqual(app.get(b.A + "allowNativeHeapPointerTagging"), "false")
        filt = app.find("activity/intent-filter")
        self.assertIn(b.IMMERSIVE_CATEGORY, b._children_named(filt, "category"))
        before = self.path.read_bytes()
        self.assertEqual(b.patch_manifest(self.path), [])
        self.assertEqual(self.path.read_bytes(), before)

    def test_adds_queries_to_manifest_without_one(self):
        text = MANIFEST.replace('    <queries>\n', '    <queries>\n')
        root = ET.fromstring(text)
        root.remove(root.find("queries"))
        ET.ElementTree(root).write(self.path, encoding="utf-8", xml_declaration=True)
        b.patch_manifest(self.path)
        root = ET.parse(self.path).getroot()
        self.assertEqual(len(root.findall("queries")), 1)
        self.assertLess(list(root).index(root.find("queries")), list(root).index(root.find("application")))

    def test_no_launcher_rejected_without_writing(self):
        self.path.write_text(MANIFEST.replace("android.intent.category.LAUNCHER", "not.a.launcher"), encoding="utf-8")
        before = self.path.read_bytes()
        with self.assertRaisesRegex(b.BuildError, "MAIN/LAUNCHER"):
            b.patch_manifest(self.path)
        self.assertEqual(before, self.path.read_bytes())

    def test_wrong_package_rejected_without_writing(self):
        self.path.write_text(MANIFEST.replace("com.qcxr.qcxr", "com.example.other"), encoding="utf-8")
        before = self.path.read_bytes()
        with self.assertRaisesRegex(b.BuildError, "Pacote inesperado"):
            b.patch_manifest(self.path)
        self.assertEqual(before, self.path.read_bytes())

    def test_multiple_filters_and_alias(self):
        extra = '''<activity-alias android:name=".Launcher" android:targetActivity="com.unity3d.player.UnityPlayerActivity">
          <intent-filter><action android:name="android.intent.action.MAIN"/>
          <category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity-alias>'''
        self.path.write_text(MANIFEST.replace("</application>", extra + "</application>"), encoding="utf-8")
        b.patch_manifest(self.path)
        app = ET.parse(self.path).getroot().find("application")
        for tag in ("activity", "activity-alias"):
            categories = b._children_named(app.find(f"{tag}/intent-filter"), "category")
            self.assertIn(b.IMMERSIVE_CATEGORY, categories)


class FileValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def create_zip(self, entries):
        path = self.base / "input.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, value in entries:
                archive.writestr(name, value)
        return path

    def test_valid_archive(self):
        path = self.create_zip([("AndroidManifest.xml", b"xml"), ("lib/arm64-v8a/libopenxr_loader.so", b"so")])
        b.validate_apk(path)

    def test_missing_loader_rejected(self):
        path = self.create_zip([("AndroidManifest.xml", b"xml")])
        with self.assertRaisesRegex(b.BuildError, "arm64-v8a"):
            b.validate_apk(path)

    def test_zip_slip_rejected(self):
        path = self.create_zip([("AndroidManifest.xml", b"xml"), ("lib/arm64-v8a/libopenxr_loader.so", b"so"), ("../bad", b"x")])
        with self.assertRaisesRegex(b.BuildError, "inseguro"):
            b.validate_apk(path)

    def test_duplicate_entry_rejected(self):
        with self.assertWarns(UserWarning):
            path = self.create_zip([("AndroidManifest.xml", b"a"), ("AndroidManifest.xml", b"b")])
        with self.assertRaisesRegex(b.BuildError, "duplicada"):
            b.validate_apk(path)

    def test_loader_architecture(self):
        loader = self.base / "loader.so"
        hdr = bytearray(64)
        hdr[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", hdr, 16, 3, 183)
        loader.write_bytes(hdr)
        b.validate_loader(loader)
        struct.pack_into("<H", hdr, 18, 62)  # x86_64
        loader.write_bytes(hdr)
        with self.assertRaisesRegex(b.BuildError, "arm64"):
            b.validate_loader(loader)

    def test_hash_mismatch_requires_explicit_opt_in(self):
        path = self.base / "blob"
        path.write_bytes(b"test")
        with self.assertRaisesRegex(b.BuildError, "SHA-256"):
            b.verify_hash(path, "0" * 64, False, "APK")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(b.verify_hash(path, "0" * 64, True, "APK"), b.sha256(path))

    def test_failed_download_does_not_leave_partial_file(self):
        class Response(io.BytesIO):
            def __enter__(self):
                return self
            def __exit__(self, *args):
                self.close()
        output = self.base / "download"
        with patch.object(b.urllib.request, "urlopen", return_value=Response(b"incorrect")):
            with self.assertRaisesRegex(b.BuildError, "SHA-256 inesperado"):
                b.download_checked("https://example.invalid/file", output, "0" * 64)
        self.assertFalse(output.exists())


class CliTests(unittest.TestCase):
    def test_cannot_overwrite_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "same.apk"
            source.write_bytes(b"input")
            args = b.parse_args(["--apk", str(source), "-o", str(source), "--build-tools", str(root)])
            with patch.object(b, "find_program", return_value="/fake"), patch.object(b, "build_tools", return_value=("/fake", "/fake")):
                with self.assertRaisesRegex(b.BuildError, "não pode substituir"):
                    b.build(args)
            self.assertEqual(source.read_bytes(), b"input")

    def test_default_sources_are_pinned(self):
        self.assertEqual(len(b.OFFICIAL_SHA256), 64)
        self.assertEqual(len(b.LOADER_SHA256), 64)
        self.assertIn(b.PHONEXR_COMMIT, b.LOADER_URL)


if __name__ == "__main__":
    unittest.main()
