package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(frosted): frosted glass for Home's top bar and bottom navigation (Settings > Appearance >
 * Frosted glass). The feed scrolls on under the bars, and each bar draws the part of the feed
 * behind it blurred, under a tint of the page colour.
 *
 * <p>From Android 12: the feed is drawn into a RenderNode at the bar's place and blurred with a
 * RenderEffect (the GPU does the work; the feed's own cached drawing is reused). Before that, and
 * on a canvas that is not hardware (a software snapshot), only the tint is drawn - a translucent
 * bar the feed shows through faintly.</p>
 */
public final class BackdropBlurDrawable extends Drawable {
    private static final float BLUR_DP = 24f;

    private View mSource;
    /** NEWTUBE(frosted): the height of a soft bottom edge (the glass fading out), 0 for a sharp one. */
    private int mFadeBottomPx;
    @Nullable
    private android.graphics.Paint mFadePaint;
    private final View mHost;
    private final Paint mTint = new Paint();
    private final int mPageColor;
    private final int[] mHostOffset = new int[2];
    private final int[] mSourceOffset = new int[2];
    @Nullable
    private final Object mNode; // RenderNode, typed loosely so older platforms never load it
    private final float mBlurPx;
    private int mAlpha = 255;

    /**
     * {@code source}: what scrolls under the bar (the feed); {@code host}: the view this is the
     * background of; {@code tintColor}: drawn over the blur (the page colour, part see-through).
     */
    public BackdropBlurDrawable(View source, View host, int pageColor, int tintColor) {
        mSource = source;
        mHost = host;
        mPageColor = pageColor;
        mTint.setColor(tintColor);
        mBlurPx = BLUR_DP * host.getResources().getDisplayMetrics().density;
        mNode = Build.VERSION.SDK_INT >= 31 ? Api31.newNode() : null;
    }

    /** What the glass shows (the feed, or the You panel over it). */
    public void setSource(View source) {
        if (mSource != source) {
            mSource = source;
            invalidateSelf();
        }
    }

    /** The glass fades out over its last {@code px} (the top bar's soft lower edge). */
    public void setFadeBottom(int px) {
        mFadeBottomPx = px;
        mFadePaint = null;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        if (mFadeBottomPx > 0 && bounds.height() > mFadeBottomPx) {
            int save = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
            drawGlass(canvas, bounds);
            if (mFadePaint == null) {
                mFadePaint = new android.graphics.Paint();
                mFadePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            }
            float fadeTop = bounds.bottom - mFadeBottomPx;
            // Eased (a few stops), so the edge melts away rather than ending in a visible ramp.
            mFadePaint.setShader(new android.graphics.LinearGradient(0, fadeTop, 0, bounds.bottom,
                    new int[] {0xFF000000, 0xC0000000, 0x60000000, 0x18000000, 0x00000000},
                    new float[] {0f, 0.3f, 0.6f, 0.85f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds.left, fadeTop, bounds.right, bounds.bottom, mFadePaint);
            canvas.restoreToCount(save);
            return;
        }
        drawGlass(canvas, bounds);
    }

    private void drawGlass(Canvas canvas, Rect bounds) {
        if (Build.VERSION.SDK_INT >= 31 && mNode != null && canvas.isHardwareAccelerated()
                && mSource.getWidth() > 0 && mSource.isShown()) {
            mHost.getLocationInWindow(mHostOffset);
            mSource.getLocationInWindow(mSourceOffset);
            Api31.drawBlur(mNode, canvas, bounds, mSource, mPageColor,
                    mSourceOffset[0] - mHostOffset[0] - bounds.left,
                    mSourceOffset[1] - mHostOffset[1] - bounds.top, mBlurPx, mAlpha);
        }
        int tintAlpha = mTint.getAlpha();
        mTint.setAlpha(Math.round(tintAlpha * mAlpha / 255f));
        canvas.drawRect(bounds, mTint);
        mTint.setAlpha(tintAlpha);
    }

    /** Android 12's classes, kept out of the drawable so older platforms never resolve them. */
    @androidx.annotation.RequiresApi(31)
    private static final class Api31 {
        static Object newNode() {
            return new RenderNode("newtube-backdrop");
        }

        static void drawBlur(Object nodeObject, Canvas canvas, Rect bounds, View source, int pageColor,
                             float dx, float dy, float blurPx, int alpha) {
            RenderNode node = (RenderNode) nodeObject;
            int width = bounds.width();
            int height = bounds.height();
            node.setPosition(0, 0, width, height);
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                recording.drawColor(pageColor);
                recording.translate(dx, dy);
                source.draw(recording);
            } finally {
                node.endRecording();
            }
            node.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
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
        mTint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
