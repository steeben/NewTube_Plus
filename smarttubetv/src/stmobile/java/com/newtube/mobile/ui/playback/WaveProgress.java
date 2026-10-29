package com.newtube.mobile.ui.playback;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/**
 * NEWTUBE(wave): the seek bar's wave, as BitChord Dial draws it (One UI's media progress) - three
 * soft ranges of humps rising out of the played part of the line and drifting toward the
 * scrubber: a light tint of one colour, a dark shade of it and the colour itself, blended
 * additively, so where they cross their colours add up and brighten. The set tapers to nothing at
 * the start of the bar and again where it meets the scrubber. It does not follow the sound; it is
 * decoration. The colour is the accent, or the video's most colourful one.
 */
final class WaveProgress {
    private static final int LAYERS = 3;
    /** The colours, by index: the main colour, its light tint, its dark shade. */
    private static final int MAIN = 0;
    private static final int LIGHT = 1;
    private static final int DARK = 2;
    // The layers back to front: different wavelengths and speeds, so the humps slide past one
    // another and the overlaps keep changing rather than marching in lockstep.
    private static final int[] TONE = {LIGHT, DARK, MAIN};
    /** Share of the height the tallest hump of each layer reaches. */
    private static final float[] HEIGHT = {0.55f, 0.72f, 1f};
    private static final float[] WAVELENGTH_DP = {92f, 128f, 150f};
    /** Drift toward the scrubber, dp per second. */
    private static final float[] SPEED_DP = {30f, 40f, 48f};
    private static final float[] PHASE = {0.35f, 0.5f, 0f};
    private static final float[] ALPHA = {0.85f, 0.72f, 0.72f};
    /** A slower beat across the humps, so neighbouring ones don't stand at the same height. */
    private static final float[] UNEVENNESS = {0.35f, 0.3f, 0.25f};
    private static final float UNEVEN_RATIO = 0.37f;
    private static final float TAPER_DP = 72f;
    private static final float TAPER_SHARE = 0.4f;
    private static final float STEP_DP = 2f;
    /** A colour this grey has no hue worth saturating: its waves stay grey. */
    private static final float GREY_SATURATION = 0.08f;
    private static final float THUMB_DP = 5f;
    private static final float THUMB_HALO_DP = 10f;
    /** How long a change of colours takes. */
    private static final long BLEND_MS = 900;
    private static final float TWO_PI = (float) (2 * Math.PI);

    /** One clock for every wave drawn, so a wave handed from the player to the mini player goes on. */
    static final long EPOCH = android.os.SystemClock.uptimeMillis();

    private final float mDensity;
    private final Path mPath = new Path();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mHaloMatrix = new Matrix();

    private final int[] mFrom = new int[LAYERS];
    private final int[] mTo = new int[LAYERS];
    private final int[] mNow = new int[LAYERS];
    private long mBlendStart;
    private boolean mHasColors;

    WaveProgress(float density) {
        mDensity = density;
        mFill.setStyle(Paint.Style.FILL);
        // Additive: overlaps add up toward white, the way coloured light does, rather than the
        // front wave covering the ones behind it.
        mFill.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD));
        // The scrubber's soft white halo, one unit wide (scaled to size where drawn).
        mHalo.setShader(new RadialGradient(0f, 0f, 1f, 0x73FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    /** The colour, a light tint of it and a dark shade of it (lifted to read over anything). */
    static int[] accentColors(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        float s = hsl[1] < GREY_SATURATION ? 0f : Math.max(hsl[1], 0.6f);
        return new int[] {
                ColorUtils.HSLToColor(new float[] {hsl[0], s, Math.max(0.56f, Math.min(0.66f, hsl[2]))}) | 0xFF000000,
                ColorUtils.HSLToColor(new float[] {hsl[0], s * 0.75f, 0.8f}) | 0xFF000000,
                ColorUtils.HSLToColor(new float[] {hsl[0], s, 0.36f}) | 0xFF000000};
    }

    /** The colours (see {@link #accentColors}); a change blends over a moment rather than jumping. */
    void setColors(int[] colors, long nowMs) {
        if (!mHasColors) {
            for (int i = 0; i < LAYERS; i++) {
                mFrom[i] = mTo[i] = mNow[i] = colors[i % colors.length];
            }
            mHasColors = true;
            mBlendStart = 0;
            return;
        }
        boolean same = true;
        for (int i = 0; i < LAYERS; i++) {
            same &= mTo[i] == colors[i % colors.length];
        }
        if (same) {
            return;
        }
        for (int i = 0; i < LAYERS; i++) {
            mFrom[i] = mNow[i];
            mTo[i] = colors[i % colors.length];
        }
        mBlendStart = nowMs;
    }

    /** The main colour as it is now (mid-blend too): the bar's own colour goes with it. */
    int currentMain() {
        return mNow[MAIN] | 0xFF000000;
    }

    /** True while the colours are still blending (keep drawing). */
    boolean blending(long nowMs) {
        if (mBlendStart == 0) {
            return false;
        }
        float t = Math.min(1f, (nowMs - mBlendStart) / (float) BLEND_MS);
        float eased = t * t * (3f - 2f * t);
        for (int i = 0; i < LAYERS; i++) {
            mNow[i] = ColorUtils.blendARGB(mFrom[i], mTo[i], eased);
        }
        if (t >= 1f) {
            mBlendStart = 0;
            return false;
        }
        return true;
    }

    /**
     * The humps over the played part, {@code left} to {@code tip}, standing on {@code baseline}, at
     * most {@code amplitude} px tall, as they are at {@code seconds}.
     */
    void drawWaves(Canvas canvas, float left, float tip, float baseline, float amplitude, float seconds) {
        float played = tip - left;
        if (played <= 1f || amplitude < 0.5f || !mHasColors) {
            return;
        }
        float taper = Math.min(played * TAPER_SHARE, TAPER_DP * mDensity);
        float step = Math.max(1f, STEP_DP * mDensity);
        for (int layer = 0; layer < LAYERS; layer++) {
            float wavelength = WAVELENGTH_DP[layer] * mDensity;
            float travel = SPEED_DP[layer] * mDensity * seconds;
            float height = amplitude * HEIGHT[layer];
            mPath.rewind();
            mPath.moveTo(left, baseline);
            float x = 0f;
            while (true) {
                float envelope = smooth(x / taper) * smooth((played - x) / taper);
                float u = (x - travel) / wavelength + PHASE[layer];
                float c = 0.5f + 0.5f * (float) Math.cos(TWO_PI * u);
                float beat = 1f - UNEVENNESS[layer]
                        * (0.5f + 0.5f * (float) Math.sin(TWO_PI * (u * UNEVEN_RATIO + PHASE[layer])));
                mPath.lineTo(left + x, baseline - height * envelope * c * c * beat);
                if (x >= played) {
                    break;
                }
                x = Math.min(played, x + step);
            }
            mPath.lineTo(tip, baseline);
            mPath.close();
            int color = mNow[TONE[layer]];
            mFill.setColor((color & 0x00FFFFFF) | (Math.round(255 * ALPHA[layer]) << 24));
            canvas.drawPath(mPath, mFill);
        }
    }

    /**
     * The scrubber: a white bead with a core of the main colour and a soft white halo.
     * {@code radius} is the bead's; at rest it is about 5 dp.
     */
    void drawThumb(Canvas canvas, float cx, float cy, float radius) {
        if (!mHasColors || radius <= 0.5f) {
            return;
        }
        float scale = radius / (THUMB_DP * mDensity);
        // The halo keeps its size under the finger.
        float halo = THUMB_HALO_DP * mDensity * Math.min(scale, 1f);
        mHaloMatrix.setScale(halo, halo);
        mHaloMatrix.postTranslate(cx, cy);
        mHalo.getShader().setLocalMatrix(mHaloMatrix);
        canvas.drawCircle(cx, cy, halo, mHalo);
        mBead.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, radius, mBead);
        mBead.setColor(currentMain());
        canvas.drawCircle(cx, cy, radius * 0.6f, mBead);
    }

    private static float smooth(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        return t * t * (3f - 2f * t);
    }

    @Nullable
    static int[] colorsFromFrame(android.graphics.Bitmap frame) {
        // Bucket the frame's colourful pixels by hue; the three fullest buckets' mean colours.
        int bins = 12;
        float[] weight = new float[bins];
        float[] r = new float[bins];
        float[] g = new float[bins];
        float[] b = new float[bins];
        float[] hsl = new float[3];
        int w = frame.getWidth();
        int h = frame.getHeight();
        int[] pixels = new int[w * h];
        frame.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pixel : pixels) {
            ColorUtils.colorToHSL(pixel, hsl);
            if (hsl[1] < 0.25f || hsl[2] < 0.12f || hsl[2] > 0.9f) {
                continue;
            }
            int bin = Math.min(bins - 1, (int) (hsl[0] / 360f * bins));
            float weightOf = hsl[1] * (1f - Math.abs(hsl[2] - 0.5f));
            weight[bin] += weightOf;
            r[bin] += ((pixel >> 16) & 0xFF) * weightOf;
            g[bin] += ((pixel >> 8) & 0xFF) * weightOf;
            b[bin] += (pixel & 0xFF) * weightOf;
        }
        // The fullest hue's mean colour; its tint and shade with it (accentColors).
        int best = -1;
        for (int i = 0; i < bins; i++) {
            if (weight[i] > 0f && (best < 0 || weight[i] > weight[best])) {
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        int color = 0xFF000000 | (Math.round(r[best] / weight[best]) << 16)
                | (Math.round(g[best] / weight[best]) << 8) | Math.round(b[best] / weight[best]);
        return accentColors(color);
    }

    /**
     * NEWTUBE(wave): the humps' height while playing (1) or paused (0), eased - they settle down
     * when the video pauses and rise again when it plays.
     */
    static final class Level {
        private float mValue = 1f;
        private long mAt;

        /** Step toward playing/paused; true while still moving. */
        boolean step(boolean playing, long now) {
            float target = playing ? 1f : 0f;
            long dt = mAt == 0 ? 16 : Math.min(100, now - mAt);
            mAt = now;
            if (mValue == target) {
                return false;
            }
            float k = Math.min(1f, dt / 260f);
            mValue += (target - mValue) * k;
            if (Math.abs(target - mValue) < 0.01f) {
                mValue = target;
            }
            return true;
        }

        float value() {
            return mValue * mValue * (3f - 2f * mValue);
        }
    }
}
