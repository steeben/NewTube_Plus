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
    // NEWTUBE(wave): the seek bar's wave, smaller (the video-background bar only).
    private final float mDensity;
    @androidx.annotation.Nullable
    private WaveProgress mWave;
    private boolean mWaveOn;
    @androidx.annotation.Nullable
    private int[] mWaveColors;
    private int mLineColor;
    private long mLevelChangedAt;
    private final long mEpoch = WaveProgress.EPOCH;
    /** The humps' height, 0-1: they rise with a minimize (the morph's own line). */
    private float mWaveScale = 1f;

    void setWaveScale(float scale) {
        if (scale != mWaveScale) {
            mWaveScale = scale;
            invalidateSelf();
        }
    }
    private static final long WAVE_MOVING_MS = 4000;

    MiniProgressDrawable(float density) {
        mDensity = density;
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
        setLineColor(accent);
        invalidateSelf();
    }

    private void setLineColor(int color) {
        mLineColor = color;
        mPlayed.setShader(PlayerTimeBar.trailShader(color, mLight));
    }

    /** NEWTUBE(wave): show the wave over the played part; {@code colors} null for the accent's. */
    /**
     * NEWTUBE(wave): show the wave over the played part ({@code on}); {@code colors}, when not
     * null, colour the line (the video's) - with or without the wave.
     */
    void setWave(boolean on, @androidx.annotation.Nullable int[] colors) {
        if (on != mWaveOn || colors != mWaveColors) {
            mWaveOn = on;
            mWaveColors = colors;
            invalidateSelf();
        }
    }

    @Override
    protected boolean onLevelChange(int level) {
        mLevelChangedAt = android.os.SystemClock.uptimeMillis();
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
        long now = android.os.SystemClock.uptimeMillis();
        boolean blending = false;
        int lineColor = mWaveColors != null ? mWaveColors[0] | 0xFF000000 : mAccent;
        if (mWaveOn) {
            if (mWave == null) {
                mWave = new WaveProgress(mDensity);
            }
            mWave.setColors(mWaveColors != null ? mWaveColors : WaveProgress.accentColors(mAccent), now);
            blending = mWave.blending(now);
            if (mWaveColors != null) {
                lineColor = mWave.currentMain();
            }
        }
        if (lineColor != mLineColor) {
            setLineColor(lineColor);
        }
        if (mWaveOn && mWave != null) {
            float amplitude = Math.min(7f * mDensity, b.height() - mLineHeight) * mWaveScale;
            mWave.drawWaves(canvas, b.left, x, top, amplitude, (now - mEpoch) / 1000f);
            if (blending || now - mLevelChangedAt < WAVE_MOVING_MS) {
                invalidateSelf(); // the humps drift while it plays
            }
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
