package com.xrpocket.mobile;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import org.junit.Test;

import java.util.Iterator;

import static org.junit.Assert.*;

public class CameraManifestTest {
    private static AndroidManifestBlock manifest() {
        AndroidManifestBlock result = new AndroidManifestBlock();
        result.setPackageName("com.example.game");
        return result;
    }

    private static ResXmlElement feature(AndroidManifestBlock manifest) {
        Iterator<ResXmlElement> elements = manifest.getManifestElement().getElements("uses-feature");
        while (elements.hasNext()) {
            ResXmlElement element = elements.next();
            ResXmlAttribute name = element.searchAttributeByResourceId(android.R.attr.name);
            if (name != null && "android.hardware.camera".equals(name.getValueAsString())) return element;
        }
        return null;
    }

    @Test public void cameraAddedByBigXrDoesNotRequireCameraHardware() {
        AndroidManifestBlock manifest = manifest();
        ApkCreator.markAddedCameraOptional(manifest);
        ApkCreator.markAddedCameraOptional(manifest);
        ResXmlElement camera = feature(manifest);
        assertNotNull(camera);
        ResXmlAttribute required = camera.searchAttributeByResourceId(android.R.attr.required);
        assertNotNull(required);
        assertFalse(required.getValueAsBoolean());
        Iterator<ResXmlElement> elements = manifest.getManifestElement().getElements("uses-feature");
        assertTrue(elements.hasNext()); elements.next(); assertFalse(elements.hasNext());
    }

    @Test public void preservesGameCameraPermissionAndItsImpliedRequirement() {
        AndroidManifestBlock manifest = manifest();
        manifest.addUsesPermission("android.permission.CAMERA");
        ApkCreator.markAddedCameraOptional(manifest);
        assertNull(feature(manifest));
    }

    @Test public void preservesExplicitRequiredCameraFeature() {
        AndroidManifestBlock manifest = manifest();
        ResXmlElement camera = manifest.getManifestElement().newElement("uses-feature");
        camera.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("android.hardware.camera");
        camera.getOrCreateAndroidAttribute("required", android.R.attr.required).setValueAsBoolean(true);
        ApkCreator.markAddedCameraOptional(manifest);
        assertTrue(feature(manifest).searchAttributeByResourceId(android.R.attr.required).getValueAsBoolean());
    }

    @Test public void preservesSdkSpecificCameraPermission() {
        AndroidManifestBlock manifest = manifest();
        ResXmlElement permission = manifest.getManifestElement().newElement("uses-permission-sdk-23");
        permission.getOrCreateAndroidAttribute("name", android.R.attr.name).setValueAsString("android.permission.CAMERA");
        ApkCreator.markAddedCameraOptional(manifest);
        assertNull(feature(manifest));
    }
}
