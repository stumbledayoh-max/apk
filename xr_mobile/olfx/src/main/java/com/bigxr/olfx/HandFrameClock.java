// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package com.bigxr.olfx;

/** Pure clock math for rear-camera frames. OpenXR and Monado use CLOCK_MONOTONIC nanoseconds. */
public final class HandFrameClock {
    private HandFrameClock() {}

    /**
     * Camera2 {@code Image.getTimestamp()} is normally CLOCK_MONOTONIC, but sensors advertising
     * SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME use CLOCK_BOOTTIME. Feeding the latter directly to
     * OpenXR makes every sample look future-dated/stale after a suspend and can hide both hands
     * from the game even though the preview sees landmarks.
     */
    public static long normalizeTimestampNs(long imageTimestampNs, long monotonicNowNs,
                                            long boottimeNowNs, boolean realtimeTimestampSource) {
        if (imageTimestampNs <= 0) {
            return monotonicNowNs;
        }
        if (!realtimeTimestampSource) {
            return imageTimestampNs > monotonicNowNs ? monotonicNowNs : imageTimestampNs;
        }
        long bootMinusMono = boottimeNowNs - monotonicNowNs;
        long converted = imageTimestampNs - bootMinusMono;
        if (converted <= 0 || converted > monotonicNowNs) {
            return monotonicNowNs;
        }
        return converted;
    }

    /** MediaPipe VIDEO mode requires strictly increasing millisecond timestamps. */
    public static long nextMediaPipeTimestampMs(long lastTimestampMs, long normalizedTimestampNs) {
        long timestampMs = normalizedTimestampNs / 1_000_000L;
        if (timestampMs <= lastTimestampMs) {
            return lastTimestampMs + 1L;
        }
        return timestampMs;
    }
}
