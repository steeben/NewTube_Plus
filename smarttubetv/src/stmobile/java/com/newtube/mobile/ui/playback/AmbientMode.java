package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.ThemeMode;
import com.newtube.mobile.ui.common.UiPrefs;

/**
 * NEWTUBE(ambient): Settings > Appearance > Ambient mode, like YouTube's: the colours at the edges
 * of the video bleed out around the player - up into the status bar and down into the page under
 * the video in portrait, into the bars beside the video in full screen ("On, except in full
 * screen" leaves full screen black).
 *
 * <p>Every {@link #SAMPLE_MS} the player's TextureView is read into a tiny bitmap and blurred; the
 * window background (the status bar band), the watch page's background and, in full screen, the
 * video area's background draw that picture stretched past the video's box, cross-fading to each
 * new one. A soft vignette at the very top keeps the clock readable over bright colours (black in
 * the dark themes, white in the light one, whose status bar icons turn dark over it). The glow in
 * the status bar fades in once the page is open, rather than appearing with it.</p>
 */
final class AmbientMode {
    interface Host {
        @Nullable
        TextureView videoTexture();

        View videoArea();

        @Nullable
        View watchScroll();
    }

    private static final long SAMPLE_MS = 900;
    private static final long CROSSFADE_MS = 500;
    private static final long BAND_FADE_MS = 700;
    private static final int SAMPLE_WIDTH = 32;
    private static final int SAMPLE_HEIGHT = 18;
    /** How strong the glow is over the page: a wash, not a picture. */
    private static final float GLOW_DARK = 0.55f;
    private static final float GLOW_LIGHT = 0.4f;
    /** How far below the video the glow reaches, in video heights. */
    private static final float REACH = 0.9f;

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSample = this::sample;
    private final Paint mGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mFillPaint = new Paint();
    private final int[] mPixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final int[] mScratch = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final int[] mWindowOffset = new int[2];
    private final int[] mVideoOffset = new int[2];
    private final RectF mGlowRect = new RectF();
    @Nullable
    private Bitmap mSampleBitmap;
    /** What is drawn: the pixels between {@link #mFrom} and {@link #mTo}, blended into one picture. */
    @Nullable
    private Bitmap mCurrent;
    private final int[] mFrom = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final int[] mTo = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final int[] mBlend = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    @Nullable
    private ValueAnimator mCrossfadeAnimator;
    private float mBandAlpha;
    @Nullable
    private ValueAnimator mBandAnimator;
    private boolean mRunning;
    private int mMode = -1; // 0 off, 1 portrait, 2 full screen

    @Nullable
    private WindowGlow mWindowGlow;
    @Nullable
    private PageGlow mPageGlow;
    @Nullable
    private VideoGlow mVideoGlow;
    @Nullable
    private Drawable mPageBackground;
    @Nullable
    private Drawable mVideoBackground;

    AmbientMode(Activity activity, Host host) {
        mActivity = activity;
        mHost = host;
    }

    /** The setting asks for the glow in this orientation. */
    boolean isWanted(int orientation) {
        int setting = UiPrefs.getAmbient(mActivity);
        if (setting == UiPrefs.AMBIENT_OFF) {
            return false;
        }
        return orientation != Configuration.ORIENTATION_LANDSCAPE || setting == UiPrefs.AMBIENT_ON;
    }

    /**
     * After the player set its window background for {@code orientation} (applySystemBars...):
     * put the glow on - or take it off - the backgrounds that show it.
     */
    void apply(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        int mode = !isWanted(orientation) ? 0 : landscape ? 2 : 1;
        View decor = mActivity.getWindow().getDecorView();
        View scroll = mHost.watchScroll();
        View video = mHost.videoArea();
        // Put back what an earlier mode replaced.
        if (scroll != null && mPageGlow != null && scroll.getBackground() == mPageGlow) {
            scroll.setBackground(mPageBackground);
        }
        if (mVideoGlow != null && video.getBackground() == mVideoGlow) {
            video.setBackground(mVideoBackground);
        }
        mMode = mode;
        if (mode == 1) {
            int page = ContextCompat.getColor(mActivity, R.color.mobile_color_background);
            mWindowGlow = new WindowGlow(page);
            decor.setBackground(mWindowGlow);
            if (scroll != null) {
                mPageBackground = scroll.getBackground();
                mPageGlow = new PageGlow(page);
                scroll.setBackground(mPageGlow);
            }
        } else if (mode == 2) {
            mVideoBackground = video.getBackground();
            mVideoGlow = new VideoGlow();
            video.setBackground(mVideoGlow);
        }
        if (mode != 0) {
            start();
        } else {
            stop();
        }
    }

    void start() {
        if (mMode <= 0 || mRunning) {
            return;
        }
        mRunning = true;
        mHandler.removeCallbacks(mSample);
        mHandler.post(mSample);
    }

    void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mSample);
    }

    /** The page is fully open: the status bar's share of the glow fades in. */
    void showBand() {
        mBandMorphing = false;
        mBandMorphAlpha = 1f;
        animateBand(1f, BAND_FADE_MS);
    }

    /** The page is leaving (a minimize): the status bar's glow goes at once. */
    void hideBand() {
        if (mBandAnimator != null) {
            mBandAnimator.cancel();
        }
        mBandAlpha = 0f;
        invalidateAll();
    }

    /**
     * The page is leaving under a minimize: the status bar's band (its glow over the page colour)
     * fades with the gesture - steeply at first (a logarithmic curve), gone by
     * {@code BAND_MORPH_SPAN} of the way - and comes back if the minimize is called off. Drawn as
     * a strip of its own, so the window backdrop under it can already be gone (the bar slide
     * clears it at once to show the screen behind).
     */
    void setBandMorph(float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction / BAND_MORPH_SPAN));
        float alpha = 1f - (float) (Math.log1p(BAND_MORPH_CURVE * t) / Math.log1p(BAND_MORPH_CURVE));
        if (!mBandMorphing || alpha != mBandMorphAlpha) {
            mBandMorphing = fraction > 0f;
            mBandMorphAlpha = alpha;
            if (mWindowGlow != null) {
                mWindowGlow.invalidateSelf();
            }
        }
    }

    /** A minimize is fading the band (drawn on its own, at {@link #mBandMorphAlpha}). */
    private boolean mBandMorphing;
    private float mBandMorphAlpha = 1f;

    private static final float BAND_MORPH_SPAN = 0.15f;
    /** How steep the start of the fade is (log(1 + k t)): most of it is gone in the first few %. */
    private static final float BAND_MORPH_CURVE = 24f;

    private void animateBand(float to, long durationMs) {
        if (mBandAnimator != null) {
            mBandAnimator.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mBandAlpha, to);
        animator.setDuration(durationMs);
        animator.setStartDelay(120);
        animator.addUpdateListener(a -> {
            mBandAlpha = (float) a.getAnimatedValue();
            if (mWindowGlow != null) {
                mWindowGlow.invalidateSelf();
            }
        });
        mBandAnimator = animator;
        animator.start();
    }

    /** Take the colours from the video now (a new picture just appeared), not at the next tick. */
    void sampleSoon() {
        if (!mRunning) {
            return;
        }
        mHandler.removeCallbacks(mSample);
        mHandler.post(mSample);
    }

    private void sample() {
        if (!mRunning) {
            return;
        }
        mHandler.postDelayed(mSample, SAMPLE_MS);
        TextureView texture = mHost.videoTexture();
        if (texture == null || !texture.isAvailable() || !texture.isShown()) {
            return;
        }
        try {
            if (mSampleBitmap == null) {
                mSampleBitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
            }
            texture.getBitmap(mSampleBitmap);
        } catch (RuntimeException e) {
            return;
        }
        mSampleBitmap.getPixels(mPixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(mPixels, mScratch, SAMPLE_WIDTH, SAMPLE_HEIGHT, 3);
        }
        if (mCurrent == null) {
            // The first colours fade in from the plain page (a glow of the page's own colour shows
            // nothing) instead of popping in.
            mCurrent = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
            mCurrent.eraseColor(0xFF000000 | ContextCompat.getColor(mActivity, R.color.mobile_color_background));
        }
        // Fade from what is on screen now (even part-way through the last fade) to the new frame.
        // The two are blended pixel by pixel into one picture: drawing both with alpha (1 - t)
        // and t composites to less than either alone half-way, a dip that pulsed on a still picture.
        mCurrent.getPixels(mFrom, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        System.arraycopy(mPixels, 0, mTo, 0, mPixels.length);
        if (mCrossfadeAnimator != null) {
            mCrossfadeAnimator.cancel();
        }
        ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(CROSSFADE_MS);
        fade.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < mBlend.length; i++) {
                mBlend[i] = lerp(mFrom[i], mTo[i], t);
            }
            mCurrent.setPixels(mBlend, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
            invalidateAll();
        });
        mCrossfadeAnimator = fade;
        fade.start();
    }

    private static int lerp(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) * (1f - t) + ((to >> 16) & 0xFF) * t);
        int g = Math.round(((from >> 8) & 0xFF) * (1f - t) + ((to >> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1f - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void invalidateAll() {
        if (mWindowGlow != null) {
            mWindowGlow.invalidateSelf();
        }
        if (mPageGlow != null) {
            mPageGlow.invalidateSelf();
        }
        if (mVideoGlow != null) {
            mVideoGlow.invalidateSelf();
        }
    }

    private float strength() {
        return ThemeMode.isLight(mActivity) ? GLOW_LIGHT : GLOW_DARK;
    }

    /**
     * Draw the glow into {@code canvas} for a view at {@code viewX,viewY} in the window: the
     * blurred frame stretched over the video's box grown past it on every side.
     */
    private void drawGlow(Canvas canvas, float viewX, float viewY, int alpha) {
        View video = mHost.videoArea();
        if (mCurrent == null || video.getWidth() <= 0 || alpha <= 0) {
            return;
        }
        video.getLocationInWindow(mVideoOffset);
        float left = mVideoOffset[0] - viewX;
        float top = mVideoOffset[1] - viewY;
        float width = video.getWidth();
        float height = video.getHeight();
        mGlowRect.set(left - width * 0.3f, top - height * REACH, left + width * 1.3f, top + height * (1f + REACH));
        mGlowPaint.setAlpha(alpha);
        canvas.drawBitmap(mCurrent, null, mGlowRect, mGlowPaint);
    }

    /** The glow's fall-off below the video: the page colour, clear at the video, solid at the end. */
    private void drawFalloff(Canvas canvas, float viewX, float viewY, int pageColor, Rect bounds, int alpha) {
        View video = mHost.videoArea();
        video.getLocationInWindow(mVideoOffset);
        float videoBottom = mVideoOffset[1] - viewY + video.getHeight();
        float end = videoBottom + video.getHeight() * REACH;
        int solid = (pageColor & 0x00FFFFFF) | (Math.round(255 * alpha / 255f) << 24);
        mFillPaint.setShader(new LinearGradient(0, videoBottom, 0, end,
                pageColor & 0x00FFFFFF, solid, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.left, Math.max(bounds.top, videoBottom), bounds.right, bounds.bottom, mFillPaint);
        mFillPaint.setShader(null);
    }

    private int statusBand() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
        if (insets == null) {
            return 0;
        }
        Insets top = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
        return top.top;
    }

    /** The window background in portrait: the page colour, the glow, and the vignette at the top. */
    private final class WindowGlow extends GlowDrawable {
        WindowGlow(int pageColor) {
            super(pageColor);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            mFillPaint.setColor(mPageColor);
            mFillPaint.setAlpha(mAlpha);
            canvas.drawRect(bounds, mFillPaint);
            int band = statusBand();
            if (band <= 0) {
                return;
            }
            float shown = band;
            // While a minimize fades it the band is its own opaque strip (page colour and glow),
            // whatever became of the backdrop around it.
            boolean withdrawing = mBandMorphing;
            if (withdrawing && mBandMorphAlpha <= 0.004f) {
                return;
            }
            int alpha = withdrawing ? Math.round(255 * mBandMorphAlpha) : mAlpha;
            canvas.save();
            canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.top + shown);
            if (withdrawing) {
                mFillPaint.setColor(mPageColor);
                mFillPaint.setAlpha(alpha);
                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + shown, mFillPaint);
            }
            drawGlow(canvas, 0f, 0f, Math.round(255 * strength() * mBandAlpha * alpha / 255f));
            // The vignette: the theme's darkest (or lightest) at the top edge, clear at the band's end.
            int edge = ThemeMode.isLight(mActivity) ? 0x99FFFFFF : 0x8C000000;
            int edgeAlpha = Math.round((edge >>> 24) * alpha / 255f);
            mFillPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + band,
                    (edge & 0x00FFFFFF) | (edgeAlpha << 24), edge & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + band, mFillPaint);
            mFillPaint.setShader(null);
            canvas.restore();
        }
    }

    /** The watch page's background in portrait: the page colour, and the glow fading down it. */
    private final class PageGlow extends GlowDrawable {
        private final int[] mOffset = new int[2];

        PageGlow(int pageColor) {
            super(pageColor);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            mFillPaint.setColor(mPageColor);
            mFillPaint.setAlpha(mAlpha);
            canvas.drawRect(bounds, mFillPaint);
            View scroll = mHost.watchScroll();
            if (scroll == null) {
                return;
            }
            scroll.getLocationInWindow(mOffset);
            // A view's background is drawn where the view sits, whatever its scroll (View#draw
            // undoes the scroll for it), so the glow stays put under the video as the page scrolls.
            float x = mOffset[0];
            float y = mOffset[1];
            canvas.save();
            canvas.clipRect(bounds);
            drawGlow(canvas, x, y, Math.round(255 * strength() * mAlpha / 255f));
            drawFalloff(canvas, x, y, mPageColor, bounds, mAlpha);
            canvas.restore();
        }
    }

    /** The video area's background in full screen: black, with the glow in the bars beside the video. */
    private final class VideoGlow extends GlowDrawable {
        VideoGlow() {
            super(Color.BLACK);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            mFillPaint.setColor(Color.BLACK);
            mFillPaint.setAlpha(mAlpha);
            canvas.drawRect(bounds, mFillPaint);
            if (mCurrent == null) {
                return;
            }
            mGlowRect.set(bounds);
            int alpha = Math.round(255 * 0.5f * mAlpha / 255f);
            mGlowPaint.setAlpha(alpha);
            canvas.drawBitmap(mCurrent, null, mGlowRect, mGlowPaint);
        }
    }

    private abstract static class GlowDrawable extends Drawable {
        final int mPageColor;
        int mAlpha = 255;

        GlowDrawable(int pageColor) {
            mPageColor = pageColor;
        }

        @Override
        public void setAlpha(int alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return mAlpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT; // the minimize fades it (see WatchBackdropDrawable)
        }
    }

    /** One horizontal and one vertical box-blur pass over {@code pixels}, in place. */
    private static void boxBlur(int[] pixels, int[] scratch, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = pixels[row + Math.max(0, Math.min(width - 1, x + k))];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                scratch[row + x] = 0xFF000000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = scratch[Math.max(0, Math.min(height - 1, y + k)) * width + x];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                pixels[y * width + x] = 0xFF000000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
            }
        }
    }
}
