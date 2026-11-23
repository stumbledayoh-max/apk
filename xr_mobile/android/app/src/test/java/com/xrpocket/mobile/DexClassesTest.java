package com.xrpocket.mobile;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DexClassesTest {
    private static byte[] sample(String name) {
        byte[] bytes = new byte[256];
        System.arraycopy("dex\n035\0".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(32, bytes.length); buffer.putInt(36, 112); buffer.putInt(40, 0x12345678);
        buffer.putInt(56, 1); buffer.putInt(60, 112); buffer.putInt(64, 1); buffer.putInt(68, 116);
        buffer.putInt(96, 1); buffer.putInt(100, 120);
        buffer.putInt(112, 152); buffer.putInt(116, 0); buffer.putInt(120, 0);
        byte[] text = name.getBytes(StandardCharsets.US_ASCII);
        bytes[152] = (byte) text.length; System.arraycopy(text, 0, bytes, 153, text.length);
        return bytes;
    }
    @Test public void readsOnlyDefinedClasses() throws Exception {
        assertEquals(java.util.Collections.singleton("Lorg/example/Test;"), DexClasses.read(sample("Lorg/example/Test;")));
    }
    @Test public void rejectsTruncatedTablesAndInvalidDescriptors() throws Exception {
        byte[] bytes = sample("Lorg/example/Test;");
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(100, 250);
        try { DexClasses.read(bytes); fail("truncated table"); } catch (IOException expected) { }
        try { DexClasses.read(sample("not-a-class")); fail("bad descriptor"); } catch (IOException expected) { }
        try { DexClasses.read(new byte[0]); fail("short header"); } catch (IOException expected) { }
    }
}
