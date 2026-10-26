package com.newtube.mobile.ui.common;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(nav-avatar): the signed-in account's picture as a navigation tab's icon - a circle with a
 * thin white ring. It keeps its own colours: the navigation tints its icons, and a tinted photo
 * would be a flat silhouette, so tints and colour filters are ignored.
 */
public final class AvatarIconDrawable extends Drawable {
    private final Bitmap mBitmap;
    private final int mSizePx;
    private final float mRingPx;
    private final Paint mPhoto = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();

    public AvatarIconDrawable(Bitmap bitmap, int sizePx, float ringPx) {
        mBitmap = bitmap;
        mSizePx = sizePx;
        mRingPx = ringPx;
        mPhoto.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(ringPx);
        mRing.setColor(Color.WHITE);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || mBitmap.isRecycled()) {
            return;
        }
        float size = Math.min(bounds.width(), bounds.height());
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float radius = size / 2f;
        // The photo, centre-cropped into the circle.
        float scale = size / Math.min(mBitmap.getWidth(), mBitmap.getHeight());
        mMatrix.setScale(scale, scale);
        mMatrix.postTranslate(cx - mBitmap.getWidth() * scale / 2f, cy - mBitmap.getHeight() * scale / 2f);
        mPhoto.getShader().setLocalMatrix(mMatrix);
        canvas.drawCircle(cx, cy, radius, mPhoto);
        canvas.drawCircle(cx, cy, radius - mRingPx / 2f, mRing);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSizePx;
    }

    @Override
    public void setAlpha(int alpha) {
        mPhoto.setAlpha(alpha);
        mRing.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // its own colours (see the class comment)
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        // its own colours
    }

    @Override
    public void setTint(int tintColor) {
        // its own colours
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
