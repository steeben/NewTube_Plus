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
    /** NEWTUBE(frosted): the same at the top edge (the bottom navigation's soft upper edge). */
    private int mFadeTopPx;
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

    /**
     * NEWTUBE(tab-slide): a second view the glass shows over the first, while it is there (the
     * page sliding in beside the one leaving); null for none.
     */
    public void setExtraSource(@Nullable View extra) {
        if (mExtra != extra) {
            mExtra = extra;
            invalidateSelf();
        }
    }

    @Nullable
    private View mExtra;
    private final int[] mExtraOffset = new int[2];

    /**
     * NEWTUBE(frosted): everything under the glass, bottom to top - each drawn where it is on screen
     * now (moved, sliding, fading), so the glass is a live blur of exactly what is behind it.
     * Replaces the single source (and the extra) while set.
     */
    public interface Layers {
        void collect(java.util.List<View> out);

        /** How much of {@code layer} the glass shows (0-1): a layer easing in under it. */
        default float alphaOf(View layer) {
            return 1f;
        }
    }

    /**
     * NEWTUBE(mini-underlap): a lighter glass - its tint and blur times these (0-1) - over a video
     * that should show through a little more (the video bar under the navigation).
     */
    private float mTintScale = 1f;
    private float mBlurScale = 1f;

    public void setLightness(float tintScale, float blurScale) {
        if (tintScale != mTintScale || blurScale != mBlurScale) {
            mTintScale = tintScale;
            mBlurScale = blurScale;
            invalidateSelf();
        }
    }

    @Nullable
    private Layers mLayers;
    private final java.util.ArrayList<View> mLayerViews = new java.util.ArrayList<>();

    public void setLayers(@Nullable Layers layers) {
        mLayers = layers;
        invalidateSelf();
    }

    /** What the glass shows (the feed, or the You panel over it). */
    public void setSource(View source) {
        if (mSource != source) {
            mSource = source;
            invalidateSelf();
        }
    }

    /** The glass fades out over its first {@code px} (the bottom navigation's soft upper edge). */
    public void setFadeTop(int px) {
        mFadeTopPx = px;
        invalidateSelf();
    }

    /** Opaque to clear over the fade, eased (a cosine) so neither end shows where it starts. */
    private static final int[] FADE_COLORS = new int[9];
    private static final float[] FADE_STOPS = new float[9];

    static {
        for (int i = 0; i < 9; i++) {
            float t = i / 8f;
            FADE_STOPS[i] = t;
            int alpha = Math.round(255 * (0.5f + 0.5f * (float) Math.cos(Math.PI * t)));
            FADE_COLORS[i] = alpha << 24;
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
        if ((mFadeBottomPx > 0 || mFadeTopPx > 0) && bounds.height() > mFadeBottomPx + mFadeTopPx) {
            int save = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);
            drawGlass(canvas, bounds);
            if (mFadePaint == null) {
                mFadePaint = new android.graphics.Paint();
                mFadePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            }
            if (mFadeBottomPx > 0) {
                float fadeTop = bounds.bottom - mFadeBottomPx;
                mFadePaint.setShader(new android.graphics.LinearGradient(0, fadeTop, 0, bounds.bottom,
                        FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
                canvas.drawRect(bounds.left, fadeTop, bounds.right, bounds.bottom, mFadePaint);
            }
            if (mFadeTopPx > 0) {
                float fadeEnd = bounds.top + mFadeTopPx;
                mFadePaint.setShader(new android.graphics.LinearGradient(0, fadeEnd, 0, bounds.top,
                        FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP));
                canvas.drawRect(bounds.left, bounds.top, bounds.right, fadeEnd, mFadePaint);
            }
            canvas.restoreToCount(save);
            return;
        }
        drawGlass(canvas, bounds);
    }

    private void drawGlass(Canvas canvas, Rect bounds) {
        if (mLayers != null) {
            if (Build.VERSION.SDK_INT >= 31 && mNode != null && canvas.isHardwareAccelerated()) {
                mLayerViews.clear();
                mLayers.collect(mLayerViews);
                mHost.getLocationInWindow(mHostOffset);
                Api31.drawLayers(mNode, canvas, bounds, mLayerViews, mLayers, mHostOffset, mPageColor,
                        Math.max(1f, mBlurPx * mBlurScale), mAlpha);
                mLayerViews.clear();
            }
            drawTint(canvas, bounds);
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && mNode != null && canvas.isHardwareAccelerated()
                && mSource.getWidth() > 0 && mSource.isShown()) {
            mHost.getLocationInWindow(mHostOffset);
            mSource.getLocationInWindow(mSourceOffset);
            View extra = mExtra != null && mExtra.isShown() && mExtra.getWidth() > 0 ? mExtra : null;
            float ex = 0f;
            float ey = 0f;
            if (extra != null) {
                extra.getLocationInWindow(mExtraOffset);
                ex = mExtraOffset[0] - mHostOffset[0] - bounds.left;
                ey = mExtraOffset[1] - mHostOffset[1] - bounds.top;
            }
            Api31.drawBlur(mNode, canvas, bounds, mSource, mPageColor,
                    mSourceOffset[0] - mHostOffset[0] - bounds.left,
                    mSourceOffset[1] - mHostOffset[1] - bounds.top, extra, ex, ey, mBlurPx, mAlpha);
        }
        drawTint(canvas, bounds);
    }

    private void drawTint(Canvas canvas, Rect bounds) {
        int tintAlpha = mTint.getAlpha();
        mTint.setAlpha(Math.round(tintAlpha * mAlpha / 255f * mTintScale));
        canvas.drawRect(bounds, mTint);
        mTint.setAlpha(tintAlpha);
    }

    /** Android 12's classes, kept out of the drawable so older platforms never resolve them. */
    @androidx.annotation.RequiresApi(31)
    private static final class Api31 {
        static Object newNode() {
            return new RenderNode("newtube-backdrop");
        }

        static void drawLayers(Object nodeObject, Canvas canvas, Rect bounds, java.util.List<View> layers,
                               Layers source, int[] hostOffset, int pageColor, float blurPx, int alpha) {
            RenderNode node = (RenderNode) nodeObject;
            int width = bounds.width();
            int height = bounds.height();
            node.setPosition(0, 0, width, height);
            RecordingCanvas recording = node.beginRecording(width, height);
            int[] at = new int[2];
            try {
                recording.drawColor(pageColor);
                for (int i = 0; i < layers.size(); i++) {
                    View layer = layers.get(i);
                    float layerAlpha = Math.max(0f, Math.min(1f, layer.getAlpha() * source.alphaOf(layer)));
                    if (layerAlpha <= 0f || layer.getWidth() <= 0 || layer.getHeight() <= 0
                            || !layer.isAttachedToWindow()) {
                        continue;
                    }
                    layer.getLocationInWindow(at);
                    float x = at[0] - hostOffset[0] - bounds.left;
                    float y = at[1] - hostOffset[1] - bounds.top;
                    if (x >= width || y >= height || x + layer.getWidth() <= 0 || y + layer.getHeight() <= 0) {
                        continue; // nowhere under this glass
                    }
                    int save = recording.save();
                    recording.translate(x, y);
                    recording.clipRect(0, 0, layer.getWidth(), layer.getHeight());
                    if (layerAlpha < 1f) {
                        recording.saveLayerAlpha(0f, 0f, layer.getWidth(), layer.getHeight(),
                                Math.round(255 * layerAlpha));
                    }
                    layer.draw(recording);
                    recording.restoreToCount(save);
                }
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

        static void drawBlur(Object nodeObject, Canvas canvas, Rect bounds, View source, int pageColor,
                             float dx, float dy, @Nullable View extra, float ex, float ey,
                             float blurPx, int alpha) {
            RenderNode node = (RenderNode) nodeObject;
            int width = bounds.width();
            int height = bounds.height();
            node.setPosition(0, 0, width, height);
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                recording.drawColor(pageColor);
                recording.save();
                recording.translate(dx, dy);
                source.draw(recording);
                recording.restore();
                if (extra != null) {
                    recording.translate(ex, ey);
                    // A picture fading out (the tab slide's) is drawn as faint as it is.
                    float extraAlpha = Math.max(0f, Math.min(1f, extra.getAlpha()));
                    if (extraAlpha < 1f) {
                        recording.saveLayerAlpha(0f, 0f, extra.getWidth(), extra.getHeight(),
                                Math.round(255 * extraAlpha));
                        extra.draw(recording);
                        recording.restore();
                    } else {
                        extra.draw(recording);
                    }
                }
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
