package com.bigxr.olfx;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import java.util.ArrayList;
import java.util.List;

/** Bridges MediaPipe results to the shared OLFX-derived hand routing logic. */
public final class MediaPipeRouting {
    public static HandRouting.Hand[] route(HandRouting routing, HandLandmarkerResult result,
                                            int rotation, int width, int height, long nowNs) {
        List<HandRouting.Detection> detections = new ArrayList<>(2);
        List<List<NormalizedLandmark>> hands = result.landmarks();
        for (int hand = 0; hand < hands.size() && detections.size() < 2; hand++) {
            List<NormalizedLandmark> points = hands.get(hand);
            if (points.size() < 21) continue;
            float[] x = new float[21], y = new float[21], z = new float[21];
            for (int i = 0; i < 21; i++) {
                x[i] = points.get(i).x();
                y[i] = points.get(i).y();
                z[i] = points.get(i).z();
            }
            int side = HandRouting.UNKNOWN;
            float confidence = 0f;
            if (hand < result.handednesses().size() && !result.handednesses().get(hand).isEmpty()) {
                Category category = result.handednesses().get(hand).get(0);
                // MediaPipe's labels assume a mirrored selfie; our rear-camera frames are not mirrored.
                if ("Right".equalsIgnoreCase(category.categoryName())) side = HandRouting.LEFT;
                else if ("Left".equalsIgnoreCase(category.categoryName())) side = HandRouting.RIGHT;
                confidence = category.score();
            }
            detections.add(new HandRouting.Detection(x, y, z, side, confidence));
        }
        return routing.route(detections.toArray(new HandRouting.Detection[0]), rotation, width, height, nowNs);
    }

    private MediaPipeRouting() {}
}
