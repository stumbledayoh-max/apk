// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package com.bigxr.olfx;

/** Globally matches at most two wrist detections to existing tracks. */
final class HandAssociation {
    static int[] match(float[][] distance) {
        int n = distance.length;
        int[] best = {-1, -1};
        float bestCost = Float.POSITIVE_INFINITY;
        for (int a = -1; a < 2 && n > 0; a++) {
            for (int b = -1; b < (n > 1 ? 2 : 0); b++) {
                if (a >= 0 && a == b) continue;
                if ((a >= 0 && distance[0][a] >= 0.30f)
                        || (b >= 0 && distance[1][b] >= 0.30f)) continue;
                float cost = (a < 0 ? 0.61f : distance[0][a])
                        + (n < 2 ? 0f : (b < 0 ? 0.61f : distance[1][b]));
                if (cost < bestCost) {
                    bestCost = cost;
                    best[0] = a;
                    best[1] = b;
                }
            }
        }
        return best;
    }
    private HandAssociation() {}
}
