package com.newtube.mobile.ui.playback;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/**
 * NEWTUBE(wave): the seek bar's wave (One UI's music player style) - over the played part, a few
 * soft, overlapping, translucent humps in the accent and two neighbouring hues (or the video's own
 * colours), drifting slowly while it plays. It does not follow the sound; it is decoration. They
 * rise from nothing at the start of the bar and settle down to the line before the scrubber.
 */
final class WaveProgress {
    private static final int LAYERS = 3;
    /** Wavelengths (dp) and drift speeds (rad/s) of each layer's two components. */
    private static final float[] WAVE_A_DP = {96f, 70f, 128f};
    private static final float[] WAVE_B_DP = {41f, 53f, 33f};
    private static final float[] SPEED_A = {0.9f, -0.7f, 0.5f};
    private static final float[] SPEED_B = {-1.6f, 1.2f, -1.9f};
    private static final float[] PHASE = {0f, 2.1f, 4.2f};
    private static final float[] HEIGHT = {1f, 0.8f, 0.65f};
    /** How long a change of colours takes. */
    private static final long BLEND_MS = 700;

    private final float mDensity;
    private final Path mPath = new Path();
    private final Paint[] mFill = new Paint[LAYERS];
    private final Matrix mMatrix = new Matrix();
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDisc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mGlowMatrix = new Matrix();

    private final int[] mFrom = new int[LAYERS];
    private final int[] mTo = new int[LAYERS];
    private final int[] mNow = new int[LAYERS];
    private long mBlendStart;
    private boolean mHasColors;
    private int mGlowColor;

    WaveProgress(float density) {
        mDensity = density;
        for (int i = 0; i < LAYERS; i++) {
            mFill[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFill[i].setStyle(Paint.Style.FILL);
        }
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setColor(0xFFFFFFFF);
    }

    /** The accent and two neighbouring hues. */
    static int[] accentColors(int accent) {
        return new int[] {accent | 0xFF000000, shiftHue(accent, 38f), shiftHue(accent, -38f)};
    }

    private static int shiftHue(int color, float degrees) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[0] = (hsl[0] + degrees + 360f) % 360f;
        hsl[1] = Math.max(hsl[1], 0.55f);
        hsl[2] = Math.max(0.45f, Math.min(0.65f, hsl[2]));
        return ColorUtils.HSLToColor(hsl) | 0xFF000000;
    }

    /** The layers' colours (three); a change blends over a moment rather than jumping. */
    void setColors(int[] colors, long nowMs) {
        if (!mHasColors) {
            for (int i = 0; i < LAYERS; i++) {
                mFrom[i] = mTo[i] = mNow[i] = colors[i % colors.length];
            }
            mHasColors = true;
            mBlendStart = 0;
            rebuild();
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

    /** The first layer's colour as it is now (mid-blend too): the bar's own colour goes with it. */
    int currentMain() {
        return mNow[0] | 0xFF000000;
    }

    /** True while the colours are still blending (keep drawing). */
    boolean blending(long nowMs) {
        if (mBlendStart == 0) {
            return false;
        }
        float t = Math.min(1f, (nowMs - mBlendStart) / (float) BLEND_MS);
        for (int i = 0; i < LAYERS; i++) {
            mNow[i] = ColorUtils.blendARGB(mFrom[i], mTo[i], t);
        }
        rebuild();
        if (t >= 1f) {
            mBlendStart = 0;
            return false;
        }
        return true;
    }

    private void rebuild() {
        for (int i = 0; i < LAYERS; i++) {
            int c = mNow[i] & 0x00FFFFFF;
            // Faint where the bar starts, fuller toward the current time (one unit wide, scaled).
            mFill[i].setShader(new LinearGradient(0f, 0f, 1f, 0f,
                    new int[] {c | 0x1A000000, c | 0x8C000000, c | 0xB3000000},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }
        mGlowColor = mNow[0];
        mGlow.setShader(new RadialGradient(0f, 0f, 1f,
                new int[] {(mGlowColor & 0x00FFFFFF) | 0x80000000, (mGlowColor & 0x00FFFFFF) | 0x33000000,
                        mGlowColor & 0x00FFFFFF},
                new float[] {0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        mDisc.setColor(mGlowColor | 0xFF000000);
    }

    /**
     * The humps over the played part, {@code left} to {@code tip}, standing on {@code baseline}, at
     * most {@code amplitude} px tall, as they are at {@code seconds}.
     */
    void drawWaves(Canvas canvas, float left, float tip, float baseline, float amplitude, float seconds) {
        float width = tip - left;
        if (width < 2f || amplitude < 0.5f || !mHasColors) {
            return;
        }
        float rampIn = 36f * mDensity;
        // The humps settle down over a long stretch before the scrubber (not just at it).
        float rampOut = Math.max(48f * mDensity, Math.min(150f * mDensity, width * 0.45f));
        float step = Math.max(2f, 2.5f * mDensity);
        mMatrix.setScale(width, 1f);
        mMatrix.postTranslate(left, 0f);
        for (int layer = LAYERS - 1; layer >= 0; layer--) {
            float ka = (float) (2 * Math.PI / (WAVE_A_DP[layer] * mDensity));
            float kb = (float) (2 * Math.PI / (WAVE_B_DP[layer] * mDensity));
            float pa = PHASE[layer] + SPEED_A[layer] * seconds;
            float pb = PHASE[layer] * 1.7f + SPEED_B[layer] * seconds;
            float height = amplitude * HEIGHT[layer];
            mPath.rewind();
            mPath.moveTo(left, baseline);
            for (float x = left; ; x += step) {
                boolean last = x >= tip;
                float at = last ? tip : x;
                float envelope = smooth((at - left) / rampIn) * smooth((tip - at) / rampOut);
                float a = 0.5f + 0.5f * (float) Math.sin(ka * at + pa);
                float b = 0.6f + 0.4f * (float) Math.sin(kb * at + pb);
                mPath.lineTo(at, baseline - height * envelope * a * b);
                if (last) {
                    break;
                }
            }
            mPath.lineTo(tip, baseline);
            mPath.close();
            mFill[layer].getShader().setLocalMatrix(mMatrix);
            canvas.drawPath(mPath, mFill[layer]);
        }
    }

    /** The scrubber: a disc of the first colour in a white ring, with a soft glow of it around. */
    void drawThumb(Canvas canvas, float cx, float cy, float radius) {
        if (!mHasColors || radius <= 0.5f) {
            return;
        }
        float glow = radius * 2.4f;
        mGlowMatrix.setScale(glow, glow);
        mGlowMatrix.postTranslate(cx, cy);
        mGlow.getShader().setLocalMatrix(mGlowMatrix);
        canvas.drawCircle(cx, cy, glow, mGlow);
        canvas.drawCircle(cx, cy, radius, mDisc);
        float ring = Math.max(1.5f, 2f * mDensity * Math.min(1f, radius / (6f * mDensity)));
        mRing.setStrokeWidth(ring);
        canvas.drawCircle(cx, cy, radius - ring / 2f, mRing);
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
        int[] picked = new int[LAYERS];
        int found = 0;
        boolean[] used = new boolean[bins];
        float total = 0f;
        for (float value : weight) {
            total += value;
        }
        if (total <= 0f) {
            return null;
        }
        for (int n = 0; n < LAYERS; n++) {
            int best = -1;
            for (int i = 0; i < bins; i++) {
                if (!used[i] && weight[i] > total * 0.04f && (best < 0 || weight[i] > weight[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            used[best] = true;
            int color = 0xFF000000 | (Math.round(r[best] / weight[best]) << 16)
                    | (Math.round(g[best] / weight[best]) << 8) | Math.round(b[best] / weight[best]);
            ColorUtils.colorToHSL(color, hsl);
            hsl[1] = Math.max(hsl[1], 0.6f); // vivid, like the accent would be
            hsl[2] = Math.max(0.45f, Math.min(0.62f, hsl[2]));
            picked[found++] = ColorUtils.HSLToColor(hsl) | 0xFF000000;
        }
        if (found == 0) {
            return null;
        }
        for (int i = found; i < LAYERS; i++) {
            picked[i] = shiftHue(picked[0], i == 1 ? 38f : -38f);
        }
        return picked;
    }
}
