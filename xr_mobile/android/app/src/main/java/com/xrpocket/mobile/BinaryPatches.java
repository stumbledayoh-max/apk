package com.xrpocket.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Narrow, length-preserving edits only; unknown native formats fail closed. */
final class BinaryPatches {
    static final String OLD_RUNTIME = "org.freedesktop.monado.openxr_runtime.out_of_process";
    static final String[] OLD_BROKERS = {
            "org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker"
    };

    static String privateBroker(String packageName) throws Exception {
        if (!PackageFiles.validPackage(packageName)) throw new IOException("Nome de pacote inválido");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(packageName.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder("xr.b");
        for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", hash[i] & 255));
        return out.toString();
    }

    static void requireArm64Elf(byte[] data) throws IOException {
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F'
                || data[4] != 2 || data[5] != 1 || (data[18] & 255) != 183 || data[19] != 0) {
            throw new IOException("Biblioteca não é um ELF ARM64 little-endian");
        }
    }

    static byte[] replaceCString(byte[] data, String oldValue, String newValue) throws IOException {
        byte[] old = (oldValue + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = newValue.getBytes(StandardCharsets.US_ASCII);
        if (newValue.isEmpty() || newValue.indexOf('\0') >= 0 || replacement.length >= old.length
                || !newValue.equals(new String(replacement, StandardCharsets.US_ASCII))) {
            throw new IOException("Nome longo ou inválido para a biblioteca nativa");
        }
        int at = -1, count = 0;
        outer: for (int i = 0; i <= data.length - old.length; i++) {
            for (int n = 0; n < old.length; n++) if (data[i + n] != old[n]) continue outer;
            at = i; count++;
        }
        if (count != 1) throw new IOException("Loader/runtime incompatível: identificador nativo não corresponde ao esperado");
        byte[] copy = data.clone();
        Arrays.fill(copy, at, at + old.length, (byte) 0);
        System.arraycopy(replacement, 0, copy, at, replacement.length);
        return copy;
    }

    static byte[] patchLoader(byte[] data, String broker) throws IOException {
        requireArm64Elf(data);
        for (String old : OLD_BROKERS) data = replaceCString(data, old, broker);
        return data;
    }

    static byte[] patchRuntime(byte[] data, String packageName) throws IOException {
        requireArm64Elf(data);
        if (!PackageFiles.validPackage(packageName)) throw new IOException("Nome de pacote inválido");
        return replaceCString(data, OLD_RUNTIME, packageName);
    }
    private BinaryPatches() { }
}
