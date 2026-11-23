import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from questcraft_mobile import build_apk, integrated
from xr_mobile import build_payload, build_runtime

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "olfx/src/main/java/com/bigxr/olfx"
LICENSE = ROOT / "olfx/src/main/assets/bigxr_licenses/OLFX-BSL-1.0.txt"
# Original upstream source bytes at OLFX commit 32d457f9782127933bd5f9be7275d270bb70e73c.
UPSTREAM = {
    "HandFrameClock.java": "07ab25cbe4f26bfe8a5e2c2e7612696626fdab27bc28a899563143f6806e0f92",
    "HandImageGeometry.java": "b7e6fcdf1842a429c6247eecd9484d4a9542ad226a9fb5e263243e161007a7cf",
    "HandAssociation.java": "2ef6d24d4a656d6ab8f5ef7626ddda6df9cfe08afd46f60836bfb21157a52c8b",
}


class OlfxIntegrationTests(unittest.TestCase):
    def test_vendored_sources_match_pinned_upstream_except_package(self):
        for name, expected in UPSTREAM.items():
            source = (JAVA / name).read_bytes()
            old_package = b"package com.bigxr.olfx;"
            self.assertEqual(source.count(old_package), 1)
            upstream = source.replace(old_package, b"package org.freedesktop.monado.ipc;")
            self.assertEqual(hashlib.sha256(upstream).hexdigest(), expected, name)
            self.assertIn(b"SPDX-License-Identifier: BSL-1.0", source)

    def test_attribution_and_components_required_in_both_outputs(self):
        self.assertEqual(hashlib.sha256(LICENSE.read_bytes()).hexdigest(), build_runtime.OLFX_LICENSE_SHA256)
        self.assertIn("32d457f9782127933bd5f9be7275d270bb70e73c", LICENSE.read_text())
        self.assertTrue(build_runtime.OLFX_COMPONENT_CLASSES <= build_runtime.REQUIRED_APP_CLASSES)
        self.assertTrue(build_runtime.OLFX_COMPONENT_CLASSES <= build_payload.REQUIRED)
        for module in ("android", "embedded"):
            gradle = (ROOT / module / "app/build.gradle.kts").read_text()
            proguard = (ROOT / module / "app/proguard-rules.pro").read_text()
            self.assertIn('../../olfx/src/main/java', gradle)
            self.assertIn('../../olfx/src/main/assets', gradle)
            self.assertIn('-keep class com.bigxr.olfx.**', proguard)

    def test_ph5_math_is_identical_in_creator_and_standalone_copy(self):
        host = (ROOT / "android/app/src/main/java/com/xrpocket/mobile/HandsMath.java").read_text().split("\n", 1)[1]
        embedded = (ROOT / "embedded/app/src/main/java/com/bigxr/embedded/HandsMath.java").read_text().split("\n", 1)[1]
        self.assertEqual(host, embedded)

    def test_host_validation_rejects_missing_attribution_or_component(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "host.apk"
            model = b"fixture model"
            def make_apk(license_bytes):
                with zipfile.ZipFile(apk, "w") as z:
                    z.writestr("assets/hand_landmarker.task", model, compress_type=zipfile.ZIP_STORED)
                    if license_bytes is not None:
                        z.writestr(build_runtime.OLFX_LICENSE_PATH, license_bytes)
                    z.writestr("lib/arm64-v8a/libmediapipe_fixture.so", b"fixture")
                    z.writestr("classes.dex", b"fixture")
            with mock.patch.object(integrated, "MODEL_SHA256", hashlib.sha256(model).hexdigest()):
                with mock.patch.object(integrated, "dex_classes", return_value=build_runtime.REQUIRED_APP_CLASSES):
                    make_apk(LICENSE.read_bytes())
                    build_runtime.validate_host(apk)
                    for license_bytes in (None, b"different license"):
                        make_apk(license_bytes)
                        with self.assertRaises(build_apk.BuildError):
                            build_runtime.validate_host(apk)
                make_apk(LICENSE.read_bytes())
                without_router = build_runtime.REQUIRED_APP_CLASSES - {"Lcom/bigxr/olfx/HandRouting;"}
                with mock.patch.object(integrated, "dex_classes", return_value=without_router):
                    with self.assertRaises(build_apk.BuildError):
                        build_runtime.validate_host(apk)


if __name__ == "__main__":
    unittest.main()
