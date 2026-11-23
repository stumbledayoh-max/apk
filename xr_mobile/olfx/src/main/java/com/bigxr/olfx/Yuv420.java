// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package com.bigxr.olfx;

import java.nio.ByteBuffer;

/**
 * Java adaptation of OLFX's olfx_yuv420_to_rgba for Android YUV_420_888 planes.
 * Unlike a rectangular-capacity check, it tests the last byte actually read:
 * the last chroma row of an NV12/NV21 plane can legitimately be truncated.
 * Output is Android ARGB, so no JPEG round-trip is needed.
 */
public final class Yuv420 {
    // Keep the temporary int array and Bitmap bounded even on unusual cameras.
    public static final int MAX_FRAME_PIXELS = 1920 * 1080;
    private static final int MAX_DIMENSION = 4096;

    private static void checkPlane(ByteBuffer plane, int rows, int columns, int rowStride, int pixelStride) {
        if (plane == null || rows <= 0 || columns <= 0 || rowStride <= 0 || pixelStride <= 0) {
            throw new IllegalArgumentException("Invalid camera plane");
        }
        long rowBytes = (long) (columns - 1) * pixelStride + 1;
        long last = (long) (rows - 1) * rowStride + rowBytes - 1;
        if (rowStride < rowBytes || last >= plane.remaining()) {
            throw new IllegalArgumentException("Truncated or inconsistent camera plane");
        }
    }

    /** Returns ARGB pixels without changing the position of any input buffer. */
    public static int[] toArgb(int width, int height,
                               ByteBuffer y, int yRow, int yPixel,
                               ByteBuffer u, int uRow, int uPixel,
                               ByteBuffer v, int vRow, int vPixel) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_FRAME_PIXELS || yPixel != 1
                || uPixel < 1 || uPixel > 2 || vPixel < 1 || vPixel > 2) {
            throw new IllegalArgumentException("Unsupported YUV_420_888 frame");
        }
        int chromaWidth = (width + 1) / 2, chromaHeight = (height + 1) / 2;
        checkPlane(y, height, width, yRow, yPixel);
        checkPlane(u, chromaHeight, chromaWidth, uRow, uPixel);
        checkPlane(v, chromaHeight, chromaWidth, vRow, vPixel);

        int[] out = new int[width * height];
        int yBase = y.position(), uBase = u.position(), vBase = v.position();
        for (int row = 0; row < height; row++) {
            int yOffset = yBase + row * yRow;
            int uOffset = uBase + (row / 2) * uRow;
            int vOffset = vBase + (row / 2) * vRow;
            for (int x = 0; x < width; x += 2) {
                int U = (u.get(uOffset + (x / 2) * uPixel) & 255) - 128;
                int V = (v.get(vOffset + (x / 2) * vPixel) & 255) - 128;
                int red = (91881 * V) >> 16;
                int green = (22554 * U + 46802 * V) >> 16;
                int blue = (116130 * U) >> 16;
                for (int k = 0; k < 2 && x + k < width; k++) {
                    int value = y.get(yOffset + x + k) & 255;
                    out[row * width + x + k] = 0xff000000
                            | (clamp(value + red) << 16)
                            | (clamp(value - green) << 8)
                            | clamp(value + blue);
                }
            }
        }
        return out;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    private Yuv420() {}
}
