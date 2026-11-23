// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package com.bigxr.olfx;

/**
 * Pure camera/image geometry helpers. Kept Android-free so the rotation and focal-length contracts
 * can be tested on the JVM. MediaPipe rotates the image for inference but returns normalized
 * landmarks in the ORIGINAL, unrotated image coordinate system.
 */
public final class HandImageGeometry {
    private HandImageGeometry() {}

    public static int clockwiseRotation(int sensorOrientation, int displayRotation) {
        int sensor = quarterTurns(sensorOrientation);
        int display = quarterTurns(displayRotation);
        return (sensor - display + 360) % 360;
    }

    private static int quarterTurns(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return (normalized / 90) * 90;
    }

    /** Rotate a normalized point exactly like Bitmap.postRotate(clockwiseDegrees). */
    public static float rotateX(float x, float y, int clockwiseDegrees) {
        switch (((clockwiseDegrees % 360) + 360) % 360) {
            case 90: return 1f - y;
            case 180: return 1f - x;
            case 270: return y;
            default: return x;
        }
    }

    public static float rotateY(float x, float y, int clockwiseDegrees) {
        switch (((clockwiseDegrees % 360) + 360) % 360) {
            case 90: return x;
            case 180: return 1f - y;
            case 270: return 1f - x;
            default: return y;
        }
    }

    /**
     * Perspective focal lengths in pixels of the upright frame. Accounts for sensor crop/aspect and
     * swaps the axes for 90/270 degree rotation. A single scalar focal length is wrong for cropped or
     * non-square-pixel camera streams and makes the reconstructed hand drift away from the image.
     */
    public static float[] focalPixels(
            float focalMm,
            float physicalWidthMm,
            int pixelArrayW,
            int pixelArrayH,
            int activeArrayW,
            int activeArrayH,
            int frameW,
            int frameH,
            int clockwiseDegrees) {
        float fx;
        float fy;
        if (valid(focalMm) && valid(physicalWidthMm) && pixelArrayW > 0 && pixelArrayH > 0
                && activeArrayW > 0 && activeArrayH > 0 && frameW > 0 && frameH > 0) {
            float mmPerPixel = physicalWidthMm / pixelArrayW;
            float usedW = activeArrayW;
            float usedH = activeArrayH;
            float frameAspect = (float) frameW / frameH;
            float activeAspect = (float) activeArrayW / activeArrayH;
            if (frameAspect > activeAspect) {
                usedH = activeArrayW / frameAspect;
            } else {
                usedW = activeArrayH * frameAspect;
            }
            fx = focalMm * frameW / (usedW * mmPerPixel);
            fy = focalMm * frameH / (usedH * mmPerPixel);
        } else {
            // Conservative 60 degree horizontal FOV fallback, with square pixels.
            fx = (float) (0.5 * frameW / Math.tan(Math.toRadians(30.0)));
            fy = fx;
        }
        if (!valid(fx) || !valid(fy)) {
            fx = fy = Math.max(frameW, frameH);
        }
        boolean quarter = clockwiseDegrees == 90 || clockwiseDegrees == 270;
        return quarter ? new float[] {fy, fx} : new float[] {fx, fy};
    }

    private static boolean valid(float value) {
        return value > 0f && !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
