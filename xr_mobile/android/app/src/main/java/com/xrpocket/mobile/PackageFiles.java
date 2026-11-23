package com.xrpocket.mobile;

import java.util.regex.Pattern;

/** Validates names before any access to another package's OBB directory. */
final class PackageFiles {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    static boolean validPackage(String name) {
        return name != null && name.length() <= 255 && PACKAGE.matcher(name).matches();
    }

    static boolean validObb(String name, String packageName) {
        if (!validPackage(packageName) || name == null || name.length() > 255) return false;
        return Pattern.matches("(?:main|patch)\\.[0-9]{1,20}\\." + Pattern.quote(packageName) + "\\.obb", name);
    }

    private PackageFiles() { }
}
