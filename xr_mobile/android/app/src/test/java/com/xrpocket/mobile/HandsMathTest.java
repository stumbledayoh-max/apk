package com.xrpocket.mobile;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class HandsMathTest {
    @Test public void packetMatchesMonadoPh5ShapeRegardlessOfLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("pt", "BR"));
            HandsMath.State left = new HandsMath.State();
            left.present = true;
            left.trigger = true;
            left.squeeze = true;
            left.qz = 0.70711f;
            left.qw = 0.70711f;
            String[] fields = HandsMath.packet(left, new HandsMath.State()).split(" ");
            assertEquals(30, fields.length); // PH5 + 29 values
            assertEquals("PH5", fields[0]);
            assertEquals("1", fields[1]);
            assertEquals("1", fields[2]);
            assertEquals("0.5000", fields[5]);
            assertEquals("0.70711", fields[10]);
            assertEquals("0.70711", fields[11]);
            assertEquals("8", fields[12]);
            assertEquals("0", fields[15]);
            assertEquals("1", fields[29]);
        } finally { Locale.setDefault(previous); }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompleteLandmarks() { HandsMath.classify(new float[20], new float[21]); }

    @Test public void positionsAreClampedToTheCameraFrame() {
        float[] x = new float[21], y = new float[21];
        for (int i = 0; i < 21; i++) { x[i] = 1.5f; y[i] = -0.5f; }
        HandsMath.State hand = HandsMath.classify(x, y);
        assertTrue(hand.present);
        assertEquals(1f, hand.x, 0.0001f);
        assertEquals(0f, hand.y, 0.0001f);
        assertTrue(hand.z >= 0 && hand.z <= 1);
        // Degenerate landmarks must not generate NaNs in the runtime packet.
        assertEquals(0f, hand.qx, 0.0001f);
        assertEquals(1f, hand.qw, 0.0001f);
    }

    @Test public void rotatedPalmProducesNormalizedOrientationInPacket() {
        float[] x = new float[21], y = new float[21], z = new float[21];
        for (int i = 0; i < 21; i++) { x[i] = 0.5f; y[i] = 0.5f; }
        // Palm side points up and palm axis points left, a 90-degree turn.
        y[5] = 0.25f; y[17] = 0.75f;
        x[0] = 0.75f; x[9] = 0.25f;
        HandsMath.State hand = HandsMath.classify(x, y, z, true);
        assertEquals(0f, hand.qx, 0.0001f);
        assertEquals(0f, hand.qy, 0.0001f);
        assertEquals((float) Math.sqrt(0.5), hand.qz, 0.0001f);
        assertEquals((float) Math.sqrt(0.5), hand.qw, 0.0001f);
        assertEquals("0.70711", HandsMath.packet(hand, new HandsMath.State()).split(" ")[10]);
    }

    @Test public void usesFrameAspectForGesturesButPreservesNormalizedPosition() {
        float[] x = new float[21], y = new float[21], z = new float[21];
        for (int i = 0; i < 21; i++) { x[i] = .5f; y[i] = .5f; }
        x[5] = .53f; x[17] = .47f;
        HandsMath.State square = HandsMath.classify(x, y, z, true, 1f, 1f);
        HandsMath.State scaled = HandsMath.classify(x, y, z, true, .5f, 1f);
        assertEquals(square.x, scaled.x, 1e-6f);
        assertEquals(square.y, scaled.y, 1e-6f);
        assertTrue(scaled.z > square.z);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteLandmarks() {
        float[] x = new float[21]; x[0] = Float.NaN;
        HandsMath.classify(x, new float[21], new float[21], true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompleteDepthLandmarks() {
        HandsMath.classify(new float[21], new float[21], new float[20], true);
    }
}
