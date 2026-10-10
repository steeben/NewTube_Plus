package com.newtube.mobile.ui.playback;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(frosted): the dim over the video under the player's controls, as a vignette - none at
 * the centre, the full tint along the outside edges, easing in between. The gradient is an ellipse
 * fitted to the view's bounds, so it follows a wide or an upright video alike.
 */
final class VignetteDrawable extends android.graphics.drawable.Drawable {
    /** The share of the half-size (centre to edge) that is clear. */
    private static final float CLEAR_UNTIL = 0.3f;

    private final Paint mPaint = new Paint();
    private final Matrix mMatrix = new Matrix();
    private final RadialGradient mShader;

    /** {@code rgb}: the tint's colour; {@code edgeAlpha}: its opacity (0-255) at the edges. */
    VignetteDrawable(int rgb, int edgeAlpha) {
        int base = rgb & 0x00FFFFFF;
        // Eased in (smoothstep) rather than linear, so there is no visible start to the dark.
        int steps = 8;
        int[] colors = new int[steps + 2];
        float[] stops = new float[steps + 2];
        colors[0] = base;
        stops[0] = 0f;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float eased = t * t * (3f - 2f * t);
            colors[i + 1] = base | (Math.round(edgeAlpha * eased) << 24);
            stops[i + 1] = CLEAR_UNTIL + (1f - CLEAR_UNTIL) * t;
        }
        mShader = new RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP);
        mPaint.setShader(mShader);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mMatrix.setScale(Math.max(1f, bounds.width() / 2f), Math.max(1f, bounds.height() / 2f));
        mMatrix.postTranslate(bounds.exactCenterX(), bounds.exactCenterY());
        mShader.setLocalMatrix(mMatrix);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawRect(getBounds(), mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
