package com.bigxr.olfx;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Yuv420Test {
    private static ByteBuffer plane(int prefix, int length, int value) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(prefix + length);
        for (int i = 0; i < prefix + length; i++) buffer.put((byte) value);
        buffer.position(prefix);
        return buffer;
    }

    @Test public void acceptsTruncatedLastChromaRowAndPreservesPositions() {
        // Same last-byte regression covered by upstream OLFX's tests/olfx/test_yuv.c.
        ByteBuffer y = plane(3, 22, 235); // 4 rows, stride 6, visible width 4
        ByteBuffer u = plane(2, 7, 128), v = plane(1, 7, 128); // stride 4, pixel stride 2
        int[] output = Yuv420.toArgb(4, 4, y, 6, 1, u, 4, 2, v, 4, 2);
        assertEquals(16, output.length);
        for (int pixel : output) assertEquals(0xffebebeb, pixel);
        assertEquals(3, y.position()); assertEquals(2, u.position()); assertEquals(1, v.position());
    }

    @Test public void handlesOddSizesAndIndependentChromaStrides() {
        ByteBuffer y = plane(0, 13, 16); // 3x3 with row stride 5
        ByteBuffer u = plane(0, 7, 128); // 2x2 stride 4, pixel stride 2
        ByteBuffer v = plane(0, 9, 128); // 2x2 stride 6, pixel stride 2
        int[] output = Yuv420.toArgb(3, 3, y, 5, 1, u, 4, 2, v, 6, 2);
        assertArrayEquals(new int[]{0xff101010, 0xff101010, 0xff101010,
                0xff101010, 0xff101010, 0xff101010,
                0xff101010, 0xff101010, 0xff101010}, output);
    }

    @Test public void convertsColorAndClampsChannels() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{(byte) 250});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{(byte) 255});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{(byte) 255});
        int value = Yuv420.toArgb(1, 1, y, 1, 1, u, 1, 1, v, 1, 1)[0];
        assertEquals(0xffff74ff, value); // integer YUV conversion from OLFX
    }

    @Test public void rejectsTruncatedOrInvalidPlanesBeforeReading() {
        ByteBuffer y = plane(0, 16, 16), u = plane(0, 4, 128), v = plane(0, 4, 128);
        u.limit(3);
        try {
            Yuv420.toArgb(4, 4, y, 4, 1, u, 2, 1, v, 2, 1);
            fail("truncated chroma accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            Yuv420.toArgb(4, 4, y, 4, 2, v, 2, 1, v, 2, 1);
            fail("invalid Y pixel stride accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            Yuv420.toArgb(4000, 4000, y, 4000, 1, u, 2000, 1, v, 2000, 1);
            fail("unbounded frame accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals(0, y.position()); assertEquals(0, u.position()); assertEquals(0, v.position());
    }
}
