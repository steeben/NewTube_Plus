package com.newtube.mobile.ui.playback;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.newtube.mobile.ui.common.UiPrefs;

/**
 * NEWTUBE(accent): the mini player's progress line, drawn like the player's seek bar
 * (PlayerTimeBar): the played part a gradient of the accent - the full accent at the current
 * time, fading back to a darker (dark theme) or lighter (light theme) shade behind it. The line is
 * the bottom {@code lineHeight} of the bounds. The ProgressBar's level (0-10000) is the position.
 */
final class MiniProgressDrawable extends android.graphics.drawable.Drawable {
    private final float mLineHeight;
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlayed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private int mAccent;
    private boolean mLight;
    private boolean mHasColors;

    MiniProgressDrawable(float density) {
        mLineHeight = 2 * density;
        setColors(UiPrefs.DEFAULT_ACCENT, 0x33FFFFFF, false);
    }

    void setColors(int accent, int track, boolean light) {
        mTrack.setColor(track);
        if (mHasColors && accent == mAccent && light == mLight) {
            invalidateSelf();
            return;
        }
        mHasColors = true;
        mAccent = accent;
        mLight = light;
        mPlayed.setShader(PlayerTimeBar.trailShader(accent, light));
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
        // The trail's gradient spans the played part: the full accent at the tip.
        mMatrix.setScale(Math.max(1f, x - b.left), 1f);
        mMatrix.postTranslate(b.left, 0f);
        mPlayed.getShader().setLocalMatrix(mMatrix);
        canvas.drawRect(b.left, top, x, bottom, mPlayed);
    }

    @Override
    public void setAlpha(int alpha) {
        mTrack.setAlpha(alpha);
        mPlayed.setAlpha(alpha);
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
