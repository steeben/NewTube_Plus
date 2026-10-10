package com.newtube.mobile.ui.playback;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(mini-style): frosted glass behind the video-background bar's title. A view cannot blur
 * the live video behind it (the video is a TextureView fed by the player's codec), so the mini
 * player hands this drawable a small, blurred copy of the current frame
 * (MobileMiniPlayerController#updateFrost) and the part of it that lies under the title; it is
 * drawn stretched over the rounded scrim, under a dark tint for the white text.
 */
final class FrostedScrimDrawable extends Drawable {
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final RectF mBounds = new RectF();
    private final Rect mSource = new Rect();
    private final float mRadius;
    @Nullable
    private Bitmap mBitmap;
    @Nullable
    private BitmapShader mShader;
    private int mAlpha = 255;
    private final int mTintAlpha;

    FrostedScrimDrawable(float radiusPx, int tintColor, int edgeColor, float edgeWidthPx) {
        mRadius = radiusPx;
        mTintPaint.setColor(tintColor);
        mTintAlpha = mTintPaint.getAlpha();
        mEdgePaint.setColor(edgeColor);
        mEdgePaint.setStyle(Paint.Style.STROKE);
        mEdgePaint.setStrokeWidth(edgeWidthPx);
    }

    /** The blurred frame, and the part of it ({@code source}, in its pixels) under this scrim. */
    void setFrame(@Nullable Bitmap bitmap, @Nullable Rect source) {
        if (bitmap != mBitmap) {
            mBitmap = bitmap;
            mShader = bitmap != null ? new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            mGlassPaint.setShader(mShader);
        }
        if (source != null) {
            mSource.set(source);
        }
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        mBounds.set(bounds);
        if (mShader != null && mSource.width() > 0 && mSource.height() > 0) {
            mMatrix.setRectToRect(new RectF(mSource), mBounds, Matrix.ScaleToFit.FILL);
            mShader.setLocalMatrix(mMatrix);
            mGlassPaint.setAlpha(mAlpha);
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mGlassPaint);
        }
        mTintPaint.setAlpha(Math.round(mTintAlpha * mAlpha / 255f));
        canvas.drawRoundRect(mBounds, mRadius, mRadius, mTintPaint);
        float inset = mEdgePaint.getStrokeWidth() / 2f;
        mBounds.inset(inset, inset);
        canvas.drawRoundRect(mBounds, mRadius, mRadius, mEdgePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mGlassPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
