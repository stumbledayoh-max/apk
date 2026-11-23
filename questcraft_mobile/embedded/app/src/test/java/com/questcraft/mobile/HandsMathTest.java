package com.questcraft.mobile;

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
            String[] fields = HandsMath.packet(left, new HandsMath.State()).split(" ");
            assertEquals(30, fields.length); // PH5 + 29 values
            assertEquals("PH5", fields[0]);
            assertEquals("1", fields[1]);
            assertEquals("1", fields[2]);
            assertEquals("0.5000", fields[5]);
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
    }
}
