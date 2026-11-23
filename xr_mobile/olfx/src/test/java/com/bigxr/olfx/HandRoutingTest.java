package com.bigxr.olfx;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HandRoutingTest {
    private static HandRouting.Detection hand(float wristX, float wristY, int side, float confidence, float ratio) {
        float[] x = new float[21], y = new float[21], z = new float[21];
        for (int i = 0; i < 21; i++) { x[i] = wristX; y[i] = wristY; }
        y[9] += 0.2f;
        x[8] += 0.2f * ratio;
        return new HandRouting.Detection(x, y, z, side, confidence);
    }

    @Test public void rotationAndClockAreThePinnedOlfxAlgorithms() {
        assertEquals(90, HandImageGeometry.clockwiseRotation(90, 0));
        assertEquals(0, HandImageGeometry.clockwiseRotation(90, 90));
        assertEquals(180, HandImageGeometry.clockwiseRotation(90, 270));
        assertEquals(1_000_000_000L, HandFrameClock.normalizeTimestampNs(
                6_000_000_000L, 2_000_000_000L, 7_000_000_000L, true));
        assertEquals(2_000_000_000L, HandFrameClock.normalizeTimestampNs(
                3_000_000_000L, 2_000_000_000L, 7_000_000_000L, false));
        assertEquals(1_001L, HandFrameClock.nextMediaPipeTimestampMs(1_000L, 1_000_000_000L));

        HandRouting router = new HandRouting();
        HandRouting.Hand rotated = router.route(new HandRouting.Detection[]{hand(.2f, .3f, HandRouting.LEFT, .9f, .8f)},
                90, 640, 480, 1L)[HandRouting.LEFT];
        assertNotNull(rotated);
        assertEquals(.7f, rotated.x[0], 1e-6f);
        assertEquals(.2f, rotated.y[0], 1e-6f);
        assertEquals(.75f, rotated.metricScaleX, 1e-6f);
        assertEquals(1f, rotated.metricScaleY, 1e-6f);
    }

    @Test public void retainsTwoSlotsWhenModelOrderAndLabelsChange() {
        HandRouting router = new HandRouting();
        HandRouting.Hand[] first = router.route(new HandRouting.Detection[]{
                hand(.2f, .4f, HandRouting.LEFT, .9f, .8f),
                hand(.8f, .4f, HandRouting.RIGHT, .9f, .8f)}, 0, 640, 640, 100L);
        assertEquals(.2f, first[0].x[0], 1e-6f);
        assertEquals(.8f, first[1].x[0], 1e-6f);
        HandRouting.Hand[] reordered = router.route(new HandRouting.Detection[]{
                hand(.78f, .4f, HandRouting.LEFT, .9f, .8f),
                hand(.22f, .4f, HandRouting.RIGHT, .9f, .8f)}, 0, 640, 640, 200L);
        assertEquals(.22f, reordered[0].x[0], 1e-6f);
        assertEquals(.78f, reordered[1].x[0], 1e-6f);
    }

    @Test public void duplicateLabelsUseBothSlotsAndOccludedSlotIsReserved() {
        HandRouting router = new HandRouting();
        HandRouting.Hand[] both = router.route(new HandRouting.Detection[]{
                hand(.2f, .4f, HandRouting.LEFT, .9f, .8f),
                hand(.8f, .4f, HandRouting.LEFT, .9f, .8f)}, 0, 640, 640, 0L);
        assertNotNull(both[0]); assertNotNull(both[1]);
        assertEquals(.2f, both[0].x[0], 1e-6f);
        assertEquals(.8f, both[1].x[0], 1e-6f);
        HandRouting.Hand[] one = router.route(new HandRouting.Detection[]{
                hand(.78f, .4f, HandRouting.LEFT, .9f, .8f)}, 0, 640, 640, 10L);
        assertNull(one[0]); assertNotNull(one[1]);
        HandRouting.Hand[] again = router.route(new HandRouting.Detection[]{
                hand(.22f, .4f, HandRouting.RIGHT, .9f, .8f),
                hand(.76f, .4f, HandRouting.LEFT, .9f, .8f)}, 0, 640, 640, 20L);
        assertEquals(.22f, again[0].x[0], 1e-6f);
        assertEquals(.76f, again[1].x[0], 1e-6f);
    }

    @Test public void pinchUsesHysteresisAndLosingAHandReleasesIt() {
        HandRouting router = new HandRouting();
        HandRouting.Detection[] close = {hand(.3f, .3f, HandRouting.LEFT, .9f, .2f)};
        HandRouting.Detection[] band = {hand(.3f, .3f, HandRouting.LEFT, .9f, .38f)};
        HandRouting.Detection[] open = {hand(.3f, .3f, HandRouting.LEFT, .9f, .7f)};
        assertTrue(router.route(close, 0, 640, 640, 1L)[0].trigger);
        assertTrue(router.route(band, 0, 640, 640, 2L)[0].trigger);
        assertFalse(router.route(open, 0, 640, 640, 3L)[0].trigger);
        assertFalse(router.route(band, 0, 640, 640, 4L)[0].trigger);
        assertTrue(router.route(close, 0, 640, 640, 5L)[0].trigger);
        assertNull(router.route(new HandRouting.Detection[0], 0, 640, 640, 6L)[0]);
        assertFalse(router.route(band, 0, 640, 640, 7L)[0].trigger);
    }

    @Test public void expirationRotationAndBadInputCannotKeepAStuckPinch() {
        HandRouting router = new HandRouting();
        HandRouting.Detection closed = hand(.3f, .3f, HandRouting.LEFT, .9f, .2f);
        HandRouting.Detection bandRight = hand(.3f, .3f, HandRouting.RIGHT, .9f, .38f);
        assertTrue(router.route(new HandRouting.Detection[]{closed}, 0, 640, 640, 1L)[0].trigger);
        HandRouting.Hand[] expired = router.route(new HandRouting.Detection[]{bandRight},
                0, 640, 640, 500_000_001L);
        assertNull(expired[0]); assertFalse(expired[1].trigger);
        assertFalse(router.route(new HandRouting.Detection[]{bandRight}, 90, 640, 640, 500_000_002L)[1].trigger);
        float[] x = new float[21], y = new float[21], z = new float[21];
        x[0] = Float.NaN;
        HandRouting.Hand[] invalid = router.route(new HandRouting.Detection[]{
                null, new HandRouting.Detection(x, y, z, HandRouting.LEFT, .9f)}, 90, 640, 640, 500_000_003L);
        assertNull(invalid[0]); assertNull(invalid[1]);
    }
}
