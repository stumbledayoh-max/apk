package com.xrpocket.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PackageFilesTest {
    @Test public void acceptsOnlyExpansionFilesForTheSelectedPackage() {
        assertTrue(PackageFiles.validObb("main.12.com.example.game.obb", "com.example.game"));
        assertTrue(PackageFiles.validObb("patch.2.com.example.game.obb", "com.example.game"));
        assertFalse(PackageFiles.validObb("main.12.com.example.other.obb", "com.example.game"));
        assertFalse(PackageFiles.validObb("../main.12.com.example.game.obb", "com.example.game"));
        assertFalse(PackageFiles.validObb("main.12.comXexampleXgame.obb", "com.example.game"));
        assertFalse(PackageFiles.validObb("main.-1.com.example.game.obb", "com.example.game"));
        assertFalse(PackageFiles.validObb("main.1.com.example.game.obb", "../com.example.game"));
        assertFalse(PackageFiles.validObb(null, "com.example.game"));
    }

    @Test public void packageNameCannotEscapeItsDirectory() {
        assertTrue(PackageFiles.validPackage("org.example.game1"));
        assertFalse(PackageFiles.validPackage("org.example/../../secret"));
        assertFalse(PackageFiles.validPackage("one"));
        assertFalse(PackageFiles.validPackage("org..game"));
        assertFalse(PackageFiles.validPackage(""));
    }
}
