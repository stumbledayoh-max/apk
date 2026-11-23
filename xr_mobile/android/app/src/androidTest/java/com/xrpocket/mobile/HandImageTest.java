package com.xrpocket.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bigxr.olfx.HandRouting;
import com.bigxr.olfx.MediaPipeRouting;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.*;

/** Uses Google's held-out photo of hands, not an emulated camera or real-time motion. */
@RunWith(AndroidJUnit4.class)
public final class HandImageTest {
    @Test public void detectsHandAndConvertsLandmarksToPh5() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        Bitmap bitmap;
        try (InputStream input = test.getAssets().open("hand.jpg")) {
            bitmap = BitmapFactory.decodeStream(input);
        }
        assertNotNull("Test hand image missing", bitmap);
        MPImage image = new BitmapImageBuilder(bitmap).build();
        HandLandmarker landmarker = null;
        try {
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(2)
                    .setMinHandDetectionConfidence(0.45f).setMinHandPresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.35f).build();
            landmarker = HandLandmarker.createFromOptions(target, options);
            HandLandmarkerResult result = landmarker.detectForVideo(image, 1L);
            assertFalse("MediaPipe did not detect a hand in its reference photo", result.landmarks().isEmpty());
            HandsMath.State left = new HandsMath.State(), right = new HandsMath.State();
            boolean sawThumb = false;
            for (int hand = 0; hand < result.landmarks().size(); hand++) {
                List<NormalizedLandmark> landmarks = result.landmarks().get(hand);
                assertEquals("Expected 21 points per hand", 21, landmarks.size());
                float[] x = new float[21], y = new float[21], z = new float[21];
                for (int n = 0; n < 21; n++) {
                    x[n] = landmarks.get(n).x(); y[n] = landmarks.get(n).y(); z[n] = landmarks.get(n).z();
                    assertTrue("Invalid landmark", Float.isFinite(x[n]) && Float.isFinite(y[n]) && Float.isFinite(z[n]));
                }
                // Match the non-mirrored rear-camera convention in HandService.
                boolean physicalLeft = hand < result.handednesses().size()
                        && !result.handednesses().get(hand).isEmpty()
                        && "Right".equalsIgnoreCase(result.handednesses().get(hand).get(0).categoryName());
                HandsMath.State state = HandsMath.classify(x, y, z, physicalLeft);
                assertTrue(state.present);
                assertTrue(state.x >= 0f && state.x <= 1f && state.y >= 0f && state.y <= 1f);
                sawThumb |= state.thumb;
                if (physicalLeft) left = state; else right = state;
                Log.i("XRHandImageTest", "hand=" + hand + " left=" + physicalLeft + " thumb=" + state.thumb
                        + " trigger=" + state.trigger + " squeeze=" + state.squeeze);
            }
            String packet = HandsMath.packet(left, right);
            assertEquals("PH5 should contain 29 fields", 30, packet.split(" ").length);
            assertTrue("A detected hand must be present in the controller packet", left.present || right.present);
            assertTrue("Reference photo shows thumbs up; gesture was not classified", sawThumb);
            // Exercise the same OLFX-derived adapter used by both camera services.
            HandRouting.Hand[] routed = MediaPipeRouting.route(new HandRouting(), result, 90,
                    bitmap.getWidth(), bitmap.getHeight(), 1_000_000L);
            HandsMath.State routedLeft = new HandsMath.State(), routedRight = new HandsMath.State();
            for (int side = 0; side < routed.length; side++) {
                HandRouting.Hand hand = routed[side];
                if (hand == null) continue;
                HandsMath.State state = HandsMath.classify(hand.x, hand.y, hand.z, side == HandRouting.LEFT,
                        hand.metricScaleX, hand.metricScaleY);
                state.trigger = hand.trigger;
                if (side == HandRouting.LEFT) routedLeft = state; else routedRight = state;
            }
            assertTrue("OLFX-derived adapter lost all detected hands", routedLeft.present || routedRight.present);
            assertEquals(30, HandsMath.packet(routedLeft, routedRight).split(" ").length);
            Log.i("XRHandImageTest", "HAND_IMAGE_OK count=" + result.landmarks().size() + " packet=" + packet);
        } finally {
            if (landmarker != null) landmarker.close();
            image.close();
            bitmap.recycle();
        }
    }
}
