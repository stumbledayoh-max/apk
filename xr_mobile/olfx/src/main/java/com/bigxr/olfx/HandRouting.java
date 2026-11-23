// Copyright 2026, OLFX contributors
// Copyright 2021, Collabora, Ltd.
// Copyright 2024-2025, NVIDIA CORPORATION.
// SPDX-License-Identifier: BSL-1.0
package com.bigxr.olfx;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts OLFX's upright-frame geometry, global wrist association and palm-relative
 * pinch hysteresis to BigXR's approximate PH5 controller input. It does not
 * implement OLFX's OpenXR hand-joint device. All calls are on one camera thread.
 */
public final class HandRouting {
    public static final int UNKNOWN = -1, LEFT = 0, RIGHT = 1;
    private static final long TRACK_TIMEOUT_NS = 400_000_000L;
    private final Track[] tracks = {new Track(), new Track()};
    private int lastRotation = -1, lastWidth, lastHeight;
    private long lastNow = Long.MIN_VALUE;

    /** Coordinates are the unrotated MediaPipe image landmarks. */
    public static final class Detection {
        final float[] x, y, z;
        final int physicalSide;
        final float confidence;

        public Detection(float[] x, float[] y, float[] z, int physicalSide, float confidence) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.physicalSide = physicalSide == LEFT || physicalSide == RIGHT ? physicalSide : UNKNOWN;
            this.confidence = confidence;
        }
    }

    /** One upright hand for a PH5 controller slot, or null if no hand was seen. */
    public static final class Hand {
        public final float[] x, y, z;
        public final boolean trigger;
        // Scale x/y into the original image-width units used by MediaPipe depth.
        public final float metricScaleX, metricScaleY;

        private Hand(Upright input, boolean trigger, float sx, float sy) {
            this.x = input.x;
            this.y = input.y;
            this.z = input.z;
            this.trigger = trigger;
            this.metricScaleX = sx;
            this.metricScaleY = sy;
        }
    }

    private static final class Track {
        boolean active, pinch;
        int slot = UNKNOWN;
        float wristX, wristY;
        long lastSeen;

        void clear() { active = false; pinch = false; slot = UNKNOWN; }
    }

    private static final class Upright {
        float[] x = new float[21], y = new float[21], z = new float[21];
        int side;
        float confidence;
    }

    /**
     * Returns left and right slots. Tracks retain their slots across reordered
     * detections and short occlusions; an unseen hand is always released.
     * Rotation or camera-size changes invalidate the previous associations.
     */
    public Hand[] route(Detection[] input, int rotation, int width, int height, long nowNs) {
        if (input == null || width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw new IllegalArgumentException("Invalid frame geometry");
        }
        if ((lastRotation != -1 && (lastRotation != rotation || lastWidth != width || lastHeight != height))
                || (lastNow != Long.MIN_VALUE && nowNs < lastNow)) {
            reset();
        }
        lastRotation = rotation;
        lastWidth = width;
        lastHeight = height;
        lastNow = nowNs;
        for (Track track : tracks) {
            if (track.active && nowNs - track.lastSeen > TRACK_TIMEOUT_NS) track.clear();
        }

        List<Upright> detections = new ArrayList<>(2);
        for (Detection detection : input) {
            Upright converted = upright(detection, rotation);
            if (converted != null) detections.add(converted);
            if (detections.size() == 2) break;
        }
        int n = detections.size();
        float[][] distances = new float[n][2];
        for (int d = 0; d < n; d++) {
            Upright hand = detections.get(d);
            for (int t = 0; t < 2; t++) {
                distances[d][t] = tracks[t].active
                        ? (float) Math.hypot(hand.x[0] - tracks[t].wristX, hand.y[0] - tracks[t].wristY)
                        : Float.POSITIVE_INFINITY;
            }
        }
        int[] assignment = HandAssociation.match(distances);
        Track[] matched = new Track[n];
        boolean[] used = new boolean[2];
        for (int d = 0; d < n; d++) {
            if (assignment[d] >= 0) {
                int index = assignment[d];
                matched[d] = tracks[index];
                used[index] = true;
            }
        }
        // OLFX prefers an inactive track, then reuses an unmatched track.
        for (int d = 0; d < n; d++) {
            if (matched[d] != null) continue;
            int index = -1;
            for (int t = 0; t < 2; t++) if (!used[t] && !tracks[t].active) { index = t; break; }
            if (index < 0) for (int t = 0; t < 2; t++) if (!used[t]) { index = t; break; }
            Track track = tracks[index];
            track.clear();
            track.active = true;
            matched[d] = track;
            used[index] = true;
        }
        for (int d = 0; d < n; d++) {
            Track track = matched[d];
            Upright hand = detections.get(d);
            track.wristX = hand.x[0];
            track.wristY = hand.y[0];
            track.lastSeen = nowNs;
            track.active = true;
        }

        if (n == 2) {
            Track a = matched[0], b = matched[1];
            if (a.slot != UNKNOWN && a.slot == b.slot) { b.slot = UNKNOWN; b.pinch = false; }
            if (a.slot != UNKNOWN && b.slot == UNKNOWN) b.slot = 1 - a.slot;
            else if (b.slot != UNKNOWN && a.slot == UNKNOWN) a.slot = 1 - b.slot;
            else if (a.slot == UNKNOWN && b.slot == UNKNOWN) {
                Upright da = detections.get(0), db = detections.get(1);
                if (reliable(da) && reliable(db) && da.side != db.side) {
                    a.slot = da.side;
                    b.slot = db.side;
                } else {
                    a.slot = da.x[0] <= db.x[0] ? LEFT : RIGHT;
                    b.slot = 1 - a.slot;
                }
            }
        } else if (n == 1 && matched[0].slot == UNKNOWN) {
            Track track = matched[0];
            Track other = track == tracks[0] ? tracks[1] : tracks[0];
            Upright hand = detections.get(0);
            if (other.active && other.slot != UNKNOWN) track.slot = 1 - other.slot;
            else track.slot = reliable(hand) ? hand.side : hand.x[0] <= 0.5f ? LEFT : RIGHT;
        }

        boolean quarter = rotation == 90 || rotation == 270;
        int uprightWidth = quarter ? height : width, uprightHeight = quarter ? width : height;
        float sx = uprightWidth / (float) width, sy = uprightHeight / (float) width;
        Hand[] result = new Hand[2];
        for (int d = 0; d < n; d++) {
            Upright hand = detections.get(d);
            Track track = matched[d];
            // Adapted from OLFX's decide_ext_pinch: 0.30 to close, 0.46 to open.
            // Use pixel-correct 2D distances because PH5 has no calibrated joints.
            float palm = distance(hand, 0, 9, uprightWidth, uprightHeight);
            float tips = distance(hand, 4, 8, uprightWidth, uprightHeight);
            if (palm > 1e-4f && Float.isFinite(palm) && Float.isFinite(tips)) {
                track.pinch = tips / palm <= (track.pinch ? 0.46f : 0.30f);
            } else {
                track.pinch = false;
            }
            result[track.slot] = new Hand(hand, track.pinch, sx, sy);
        }
        // A disappeared hand must not leave a trigger pressed during its grace period.
        for (int t = 0; t < 2; t++) if (!used[t]) tracks[t].pinch = false;
        return result;
    }

    public void reset() {
        for (Track track : tracks) track.clear();
        lastRotation = -1;
        lastNow = Long.MIN_VALUE;
    }

    private static boolean reliable(Upright hand) {
        return hand.side != UNKNOWN && Float.isFinite(hand.confidence) && hand.confidence >= 0.5f;
    }

    private static float distance(Upright h, int a, int b, int width, int height) {
        return (float) Math.hypot((h.x[a] - h.x[b]) * width, (h.y[a] - h.y[b]) * height);
    }

    private static Upright upright(Detection d, int rotation) {
        if (d == null || d.x == null || d.y == null || d.z == null
                || d.x.length < 21 || d.y.length < 21 || d.z.length < 21) return null;
        Upright out = new Upright();
        for (int i = 0; i < 21; i++) {
            float x = d.x[i], y = d.y[i], z = d.z[i];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || Math.abs(x) > 10f || Math.abs(y) > 10f || Math.abs(z) > 10f) return null;
            out.x[i] = HandImageGeometry.rotateX(x, y, rotation);
            out.y[i] = HandImageGeometry.rotateY(x, y, rotation);
            out.z[i] = z;
        }
        out.side = d.physicalSide;
        out.confidence = d.confidence;
        return out;
    }
}
