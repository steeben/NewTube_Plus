package com.newtube.mobile.ui.playback;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.newtube.mobile.ui.common.UiPrefs;

/**
 * NEWTUBE(accent): the mini player's progress line, drawn like the player's seek bar
 * (PlayerTimeBar): the played part in the accent, lightening over its last stretch into white
 * at the very tip, which seems to glow. The line is the bottom {@code lineHeight} of the bounds;
 * the room above it is for the glow. The ProgressBar's level (0-10000) is the position.
 */
final class MiniProgressDrawable extends android.graphics.drawable.Drawable {
    private final float mLineHeight;
    private final float mTailPx;
    private final float mGlowRadius;
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlayed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private int mAccent;

    MiniProgressDrawable(float density) {
        mLineHeight = 2 * density;
        mTailPx = 48 * density;
        mGlowRadius = 6 * density;
        setColors(UiPrefs.DEFAULT_ACCENT, 0x33FFFFFF);
    }

    void setColors(int accent, int track) {
        mTrack.setColor(track);
        if (accent == mAccent) {
            invalidateSelf();
            return;
        }
        mAccent = accent;
        int tail = accent == UiPrefs.DEFAULT_ACCENT ? PlayerTimeBar.PLAYED_TAIL_COLOR
                : ColorUtils.blendARGB(accent, 0xFFFFFFFF, 0.3f);
        mPlayed.setShader(PlayerTimeBar.tipShader(accent, tail, mTailPx));
        invalidateSelf();
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        float bottom = b.bottom;
        float top = bottom - mLineHeight;
        canvas.drawRect(b.left, top, b.right, bottom, mTrack);
        float x = b.left + b.width() * (getLevel() / 10000f);
        if (x <= b.left) {
            return;
        }
        // The trail's gradient ends at the tip, wherever the tip is.
        mMatrix.setTranslate(x - mTailPx, 0f);
        mPlayed.getShader().setLocalMatrix(mMatrix);
        canvas.drawRect(b.left, top, x, bottom, mPlayed);
    }

    @Override
    public void setAlpha(int alpha) {
        mTrack.setAlpha(alpha);
        mPlayed.setAlpha(alpha);
        mGlow.setAlpha(alpha);
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
