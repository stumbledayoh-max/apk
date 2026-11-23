package com.xrpocket.mobile;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class BinaryPatchesTest {
    private static byte[] elf(String... strings) {
        byte[] bytes = new byte[512]; bytes[0] = 0x7f; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F';
        bytes[4] = 2; bytes[5] = 1; bytes[18] = (byte) 183;
        int offset = 80;
        for (String value : strings) {
            byte[] text = (value + "\0").getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, bytes, offset, text.length); offset += text.length + 5;
        }
        return bytes;
    }
    @Test public void patchedLoaderUsesPrivateDeterministicAuthority() throws Exception {
        String broker = BinaryPatches.privateBroker("org.example.game");
        assertEquals(broker, BinaryPatches.privateBroker("org.example.game"));
        assertNotEquals(broker, BinaryPatches.privateBroker("org.example.game2"));
        byte[] original = elf(BinaryPatches.OLD_BROKERS);
        byte[] changed = BinaryPatches.patchLoader(original, broker);
        assertEquals(original.length, changed.length);
        assertFalse(Arrays.equals(original, changed));
        String raw = new String(changed, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(BinaryPatches.OLD_BROKERS[0]));
        assertFalse(raw.contains(BinaryPatches.OLD_BROKERS[1]));
        assertEquals(raw.indexOf(broker), raw.lastIndexOf(broker) - (BinaryPatches.OLD_BROKERS[0].length() + 6));
    }
    @Test public void refusesUnknownOrAmbiguousLibraries() throws Exception {
        try { BinaryPatches.patchLoader(elf(BinaryPatches.OLD_BROKERS[0]), "xr.test"); fail(); } catch (IOException expected) { }
        try { BinaryPatches.patchLoader(elf(BinaryPatches.OLD_BROKERS[0], BinaryPatches.OLD_BROKERS[0], BinaryPatches.OLD_BROKERS[1]), "xr.test"); fail(); } catch (IOException expected) { }
        try { BinaryPatches.patchRuntime(elf(BinaryPatches.OLD_RUNTIME), "com.invalid/../game"); fail(); } catch (IOException expected) { }
        try { BinaryPatches.patchRuntime(new byte[30], "com.example.game"); fail(); } catch (IOException expected) { }
    }
    @Test public void runtimePatchNeverChangesFileSizeOrOtherBytes() throws Exception {
        byte[] input = elf(BinaryPatches.OLD_RUNTIME);
        byte[] output = BinaryPatches.patchRuntime(input, "com.example.game");
        assertEquals(input.length, output.length);
        assertTrue(new String(output, StandardCharsets.ISO_8859_1).contains("com.example.game\0"));
        assertEquals(input[79], output[79]);
        assertEquals(input[200], output[200]);
    }
}
