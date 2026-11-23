package com.questcraft.mobile;

import java.util.Locale;

/** Converts 21 normalized hand landmarks to the bundled Monado PH5 controller protocol. */
final class HandsMath {
    static final class State {
        boolean present, trigger, index, thumb, squeeze;
        float x = 0.5f, y = 0.5f, z = 0.5f;
        float qx, qy, qz, qw = 1f;
    }

    private static float distance(float[] x, float[] y, int a, int b) {
        return (float) Math.hypot(x[a] - x[b], y[a] - y[b]);
    }

    private static boolean extended(float[] x, float[] y, int base, int joint, int tip) {
        float ax = x[base] - x[joint], ay = y[base] - y[joint];
        float bx = x[tip] - x[joint], by = y[tip] - y[joint];
        float length = (float) Math.max(0.0001, Math.hypot(ax, ay) * Math.hypot(bx, by));
        return (ax * bx + ay * by) / length < -0.48f
                && distance(x, y, 0, tip) > distance(x, y, 0, joint) * 1.01f;
    }

    static State classify(float[] x, float[] y) {
        return classify(x, y, new float[21], true);
    }

    static State classify(float[] x, float[] y, float[] z, boolean physicalLeft) {
        if (x.length < 21 || y.length < 21 || z.length < 21) throw new IllegalArgumentException("21 landmarks required");
        State state = new State();
        state.present = true;
        boolean index = extended(x, y, 5, 6, 8);
        boolean middle = extended(x, y, 9, 10, 12);
        boolean ring = extended(x, y, 13, 14, 16);
        boolean pinky = extended(x, y, 17, 18, 20);
        float palm = Math.max(0.035f, distance(x, y, 5, 17));
        boolean thumb = extended(x, y, 2, 3, 4) && distance(x, y, 4, 5) > palm * 0.62f;
        state.index = index && !middle && !ring && !pinky;
        state.thumb = thumb && !index && !middle && !ring && !pinky;
        state.trigger = distance(x, y, 4, 8) < palm * 0.4f; // thumb/index pinch
        int folded = 0;
        int[] tips = {8, 12, 16, 20}, joints = {6, 10, 14, 18};
        for (int i = 0; i < tips.length; i++) {
            if (distance(x, y, 0, tips[i]) < distance(x, y, 0, joints[i]) * 1.04f) folded++;
        }
        state.squeeze = folded >= 3 && !state.index && !state.thumb;
        int[] palmPoints = {0, 5, 9, 13, 17};
        state.x = state.y = 0;
        for (int point : palmPoints) { state.x += x[point] / 5f; state.y += y[point] / 5f; }
        state.x = clamp(state.x); state.y = clamp(state.y);
        state.z = clamp((0.17f - palm) / 0.13f);
        estimateOrientation(state, x, y, z, physicalLeft);
        return state;
    }

    /** Estimate an orthonormal palm basis from MediaPipe's relative 3D landmarks.
     * This is an approximation, not a calibrated six-degree-of-freedom pose. */
    private static void estimateOrientation(State state, float[] x, float[] y, float[] z, boolean left) {
        int from = left ? 17 : 5, to = left ? 5 : 17;
        float[] right = {x[to] - x[from], y[from] - y[to], z[from] - z[to]};
        float[] up = {x[9] - x[0], y[0] - y[9], z[0] - z[9]};
        if (!normalize(right) || !normalize(up)) return;
        float[] forward = cross(right, up);
        if (!normalize(forward)) return;
        up = cross(forward, right);
        if (!normalize(up)) return;
        // Convert the column-major rotation matrix to a quaternion.
        float m00 = right[0], m01 = up[0], m02 = forward[0];
        float m10 = right[1], m11 = up[1], m12 = forward[1];
        float m20 = right[2], m21 = up[2], m22 = forward[2];
        float qx, qy, qz, qw, s;
        float trace = m00 + m11 + m22;
        if (trace > 0f) {
            s = (float) Math.sqrt(trace + 1f) * 2f;
            qw = 0.25f * s; qx = (m21 - m12) / s; qy = (m02 - m20) / s; qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            s = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
            qw = (m21 - m12) / s; qx = 0.25f * s; qy = (m01 + m10) / s; qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            s = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
            qw = (m02 - m20) / s; qx = (m01 + m10) / s; qy = 0.25f * s; qz = (m12 + m21) / s;
        } else {
            s = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
            qw = (m10 - m01) / s; qx = (m02 + m20) / s; qy = (m12 + m21) / s; qz = 0.25f * s;
        }
        float length = (float) Math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw);
        if (!Float.isFinite(length) || length < 0.00001f) return;
        state.qx = qx / length; state.qy = qy / length; state.qz = qz / length; state.qw = qw / length;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1]*b[2] - a[2]*b[1], a[2]*b[0] - a[0]*b[2], a[0]*b[1] - a[1]*b[0]};
    }

    private static boolean normalize(float[] v) {
        float length = (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (!Float.isFinite(length) || length < 0.00001f) return false;
        for (int i = 0; i < 3; i++) v[i] /= length;
        return true;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static int bit(boolean value) { return value ? 1 : 0; }

    static String packet(State left, State right) {
        // PH5: present, trigger, index, thumb, position, quaternion, buttons, stick; twice + flags.
        // Bit 0 enables camera-derived position. Pose and gesture mapping are approximate.
        String side = "%d %d %d %d %.4f %.4f %.4f %.5f %.5f %.5f %.5f %d 0.000 0.000";
        return String.format(Locale.US, "PH5 " + side + " " + side + " 1",
                bit(left.present), bit(left.trigger), bit(left.index), bit(left.thumb), left.x, left.y, left.z,
                left.qx, left.qy, left.qz, left.qw, left.squeeze ? 8 : 0,
                bit(right.present), bit(right.trigger), bit(right.index), bit(right.thumb), right.x, right.y, right.z,
                right.qx, right.qy, right.qz, right.qw, right.squeeze ? 8 : 0);
    }
}
