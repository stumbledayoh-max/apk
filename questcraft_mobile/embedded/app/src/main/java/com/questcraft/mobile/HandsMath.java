package com.questcraft.mobile;

import java.util.Locale;

/** Converts 21 normalized hand landmarks to the bundled Monado PH5 controller protocol. */
final class HandsMath {
    static final class State {
        boolean present, trigger, index, thumb, squeeze;
        float x = 0.5f, y = 0.5f, z = 0.5f;
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
        if (x.length < 21 || y.length < 21) throw new IllegalArgumentException("21 landmarks required");
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
        return state;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static int bit(boolean value) { return value ? 1 : 0; }

    static String packet(State left, State right) {
        // PH5: present, trigger, index, thumb, position, quaternion, buttons, stick; twice + flags.
        // Bit 0 enables camera-derived position. Hand orientation and movement mapping are approximate.
        String side = "%d %d %d %d %.4f %.4f %.4f 0.00000 0.00000 0.00000 1.00000 %d 0.000 0.000";
        return String.format(Locale.US, "PH5 " + side + " " + side + " 1",
                bit(left.present), bit(left.trigger), bit(left.index), bit(left.thumb), left.x, left.y, left.z, left.squeeze ? 8 : 0,
                bit(right.present), bit(right.trigger), bit(right.index), bit(right.thumb), right.x, right.y, right.z, right.squeeze ? 8 : 0);
    }
}
