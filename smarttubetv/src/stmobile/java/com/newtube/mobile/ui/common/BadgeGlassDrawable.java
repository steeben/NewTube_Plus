package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(frosted): a thumbnail badge (the duration, LIVE, SHORTS) as dark frosted glass: the part
 * of the thumbnail under it, blurred, under a dark tint, in a rounded pill. Dark in both themes -
 * it sits on a picture, not on the page. Before Android 12 it is the tint alone (a little darker).
 */
public final class BadgeGlassDrawable extends Drawable {
    private static final float BLUR_DP = 10f;
    private static final float RADIUS_DP = 6f;

    private final View mSource;
    private final View mHost;
    private final Paint mTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final RectF mRect = new RectF();
    private final float mRadius;
    private final float mBlurPx;
    @Nullable
    private final Object mNode;
    private int mAlpha = 255;

    private BadgeGlassDrawable(View source, View host, int tint) {
        mSource = source;
        mHost = host;
        float density = host.getResources().getDisplayMetrics().density;
        mRadius = RADIUS_DP * density;
        mBlurPx = BLUR_DP * density;
        mTint.setColor(tint);
        mNode = Build.VERSION.SDK_INT >= 31 ? Api31.newNode() : null;
    }

    /** Make {@code badge} glass over {@code thumbnail} (its sibling), in {@code tint}; keeps its padding. */
    public static void apply(TextView badge, View thumbnail, int tint) {
        Drawable current = badge.getBackground();
        if (current instanceof BadgeGlassDrawable && ((BadgeGlassDrawable) current).mTint.getColor() == tint) {
            current.invalidateSelf();
            return;
        }
        int l = badge.getPaddingLeft();
        int t = badge.getPaddingTop();
        int r = badge.getPaddingRight();
        int b = badge.getPaddingBottom();
        boolean glass = Build.VERSION.SDK_INT >= 31;
        int fill = glass ? tint : ((Math.min(255, (tint >>> 24) + 0x33)) << 24) | (tint & 0x00FFFFFF);
        badge.setBackground(new BadgeGlassDrawable(thumbnail, badge, fill));
        badge.setPadding(l, t, r, b);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        mRect.set(bounds);
        mClip.reset();
        mClip.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mClip);
        if (mNode != null && canvas.isHardwareAccelerated() && mSource.getWidth() > 0
                && mSource.getParent() == mHost.getParent()) {
            float dx = mSource.getX() - mHost.getX() - bounds.left;
            float dy = mSource.getY() - mHost.getY() - bounds.top;
            Api31.drawBlur(mNode, canvas, bounds, mSource, dx, dy, mBlurPx, mAlpha);
        }
        int tintAlpha = mTint.getAlpha();
        mTint.setAlpha(Math.round(tintAlpha * mAlpha / 255f));
        canvas.drawRect(bounds, mTint);
        mTint.setAlpha(tintAlpha);
        canvas.restore();
    }

    @androidx.annotation.RequiresApi(31)
    private static final class Api31 {
        static Object newNode() {
            return new android.graphics.RenderNode("newtube-badge");
        }

        static void drawBlur(Object nodeObject, Canvas canvas, Rect bounds, View source,
                             float dx, float dy, float blurPx, int alpha) {
            android.graphics.RenderNode node = (android.graphics.RenderNode) nodeObject;
            int width = bounds.width();
            int height = bounds.height();
            node.setPosition(0, 0, width, height);
            android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
            try {
                recording.drawColor(0xFF000000);
                recording.translate(dx, dy);
                source.draw(recording);
            } finally {
                node.endRecording();
            }
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx,
                    android.graphics.Shader.TileMode.CLAMP));
            node.setAlpha(alpha / 255f);
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.drawRenderNode(node);
            canvas.restore();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
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
