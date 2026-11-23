package com.xrpocket.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Lightweight native visualisation of MediaPipe landmarks, not a camera feed. */
final class HandSkeletonView extends View {
    private static final int[][] EDGES = {
            {0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},
            {5,9},{9,10},{10,11},{11,12},{9,13},{13,14},{14,15},{15,16},
            {13,17},{17,18},{18,19},{19,20},{17,0}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] left, right;

    HandSkeletonView(Context context) { super(context); }

    void update(float[] left, float[] right) {
        this.left = valid(left) ? left : null;
        this.right = valid(right) ? right : null;
        invalidate();
    }

    private static boolean valid(float[] points) {
        if (points == null || points.length != 42) return false;
        for (float v : points) if (!Float.isFinite(v)) return false;
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(17, 27, 29));
        drawHand(canvas, left, Color.rgb(118, 219, 192));
        drawHand(canvas, right, Color.rgb(247, 193, 115));
        if (left == null && right == null) {
            paint.setColor(Color.rgb(173, 189, 185));
            paint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Mostre as mãos para a câmera traseira", getWidth()/2f, getHeight()/2f, paint);
        }
    }

    private void drawHand(Canvas canvas, float[] points, int color) {
        if (points == null) return;
        float pad = 16 * getResources().getDisplayMetrics().density;
        float w = Math.max(1, getWidth() - 2*pad), h = Math.max(1, getHeight() - 2*pad);
        paint.setColor(color);
        paint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
        for (int[] edge : EDGES) {
            int a = edge[0] * 2, b = edge[1] * 2;
            canvas.drawLine(pad + clamp(points[a])*w, pad + clamp(points[a+1])*h,
                    pad + clamp(points[b])*w, pad + clamp(points[b+1])*h, paint);
        }
        for (int i=0; i<21; i++) {
            canvas.drawCircle(pad + clamp(points[2*i])*w, pad + clamp(points[2*i+1])*h,
                    3.5f * getResources().getDisplayMetrics().density, paint);
        }
    }

    private static float clamp(float n) { return Math.max(0f, Math.min(1f, n)); }
}
