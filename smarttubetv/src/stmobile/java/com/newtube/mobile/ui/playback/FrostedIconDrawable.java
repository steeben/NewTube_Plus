package com.newtube.mobile.ui.playback;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(mini-style): an icon made of frosted glass - the video-background bar's play/pause and
 * close. The glyph itself is the glass: the blurred picture of the video under it
 * (MiniPlayerViews#updateFrost), washed nearly white, cut to the icon's shape. No circle behind.
 */
final class FrostedIconDrawable extends Drawable {
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMaskPaint = new Paint();
    private final Matrix mMatrix = new Matrix();
    private final RectF mBounds = new RectF();
    private final RectF mSourceF = new RectF();
    private final Rect mSource = new Rect();
    private final int mTintAlpha;
    private Drawable mIcon;
    @Nullable
    private Bitmap mBitmap;
    @Nullable
    private BitmapShader mShader;
    private int mAlpha = 255;

    FrostedIconDrawable(Drawable icon, int tintColor) {
        mIcon = icon.mutate();
        mTintPaint.setColor(tintColor);
        mTintAlpha = mTintPaint.getAlpha();
        mMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    void setIcon(Drawable icon) {
        mIcon = icon.mutate();
        invalidateSelf();
    }

    /** The blurred frame, and the part of it ({@code source}, in its pixels) under this icon. */
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
        int layer = canvas.saveLayer(mBounds, null);
        if (mShader != null && mSource.width() > 0 && mSource.height() > 0) {
            mSourceF.set(mSource);
            mMatrix.setRectToRect(mSourceF, mBounds, Matrix.ScaleToFit.FILL);
            mShader.setLocalMatrix(mMatrix);
            mGlassPaint.setAlpha(mAlpha);
            canvas.drawRect(mBounds, mGlassPaint);
        }
        mTintPaint.setAlpha(Math.round(mTintAlpha * mAlpha / 255f));
        canvas.drawRect(mBounds, mTintPaint);
        // Keep only what lies under the glyph.
        int mask = canvas.saveLayer(mBounds, mMaskPaint);
        mIcon.setBounds(bounds);
        mIcon.draw(canvas);
        canvas.restoreToCount(mask);
        canvas.restoreToCount(layer);
    }

    @Override
    public int getIntrinsicWidth() {
        return mIcon.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mIcon.getIntrinsicHeight();
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
