package com.newtube.mobile.ui.playback;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(black-bars): finds black bars burnt into a video's picture (a 2.39:1 film letterboxed in
 * a 16:9 upload, a square video pillarboxed in one...). Looks at one frame, only when the user asks
 * from the gear menu.
 */
final class BlackBars {
    /** Brighter than this is picture, not bar (0-255 luma). Compression noise in a bar stays below. */
    private static final int LUMA_MAX = 30;
    /** A row or column is bar while at most this share of it is brighter. */
    private static final float NOISE_SHARE = 0.015f;
    /** Bars thinner than this share of the picture are ignored. */
    private static final float MIN_BAR = 0.02f;
    /** Never crop away more than this share on one side. */
    private static final float MAX_BAR = 0.3f;

    /** The crop, as shares of the picture: top, bottom, left, right. */
    static final class Crop {
        final float top;
        final float bottom;
        final float left;
        final float right;

        Crop(float top, float bottom, float left, float right) {
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }

        boolean isNone() {
            return top <= 0f && bottom <= 0f && left <= 0f && right <= 0f;
        }

        /** The cropped picture's shape, for a picture of {@code aspect} (width / height). */
        float aspect(float aspect) {
            return aspect * (1f - left - right) / Math.max(0.01f, 1f - top - bottom);
        }

        float[] toArray() {
            return new float[] {top, bottom, left, right};
        }

        static final Crop NONE = new Crop(0f, 0f, 0f, 0f);
    }

    private BlackBars() {
    }

    /**
     * The bars in {@code frame}, {@link Crop#NONE} for none, or null when the frame says nothing
     * (all dark: a fade, a black intro) - look again a little later.
     */
    @Nullable
    static Crop detect(Bitmap frame) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        if (w < 8 || h < 8) {
            return null;
        }
        int[] pixels = new int[w * h];
        frame.getPixels(pixels, 0, w, 0, 0, w, h);
        boolean[] bright = new boolean[w * h];
        int brightCount = 0;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int luma = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
            bright[i] = luma > LUMA_MAX;
            if (bright[i]) {
                brightCount++;
            }
        }
        if (brightCount < pixels.length * 0.05f) {
            return null; // too dark to tell picture from bar
        }
        int rowNoise = Math.max(1, Math.round(w * NOISE_SHARE));
        int top = 0;
        while (top < h && countRow(bright, w, top, 0, w) <= rowNoise) {
            top++;
        }
        int bottom = 0;
        while (bottom < h - top && countRow(bright, w, h - 1 - bottom, 0, w) <= rowNoise) {
            bottom++;
        }
        int innerTop = top;
        int innerBottom = h - bottom;
        int colNoise = Math.max(1, Math.round((innerBottom - innerTop) * NOISE_SHARE));
        int left = 0;
        while (left < w && countColumn(bright, w, left, innerTop, innerBottom) <= colNoise) {
            left++;
        }
        int right = 0;
        while (right < w - left && countColumn(bright, w, w - 1 - right, innerTop, innerBottom) <= colNoise) {
            right++;
        }
        // Real bars come in pairs of the same size; a dark sky over a bright street does not. The
        // smaller of each pair is the bar.
        float vertical = share(Math.min(top, bottom), h);
        float horizontal = share(Math.min(left, right), w);
        return new Crop(vertical, vertical, horizontal, horizontal);
    }

    private static float share(int px, int of) {
        float share = px / (float) of;
        if (share < MIN_BAR) {
            return 0f;
        }
        // One pixel in from the edge of the bar: a soft edge left behind would show as a line.
        return Math.min(MAX_BAR, share + 1f / of);
    }

    private static int countRow(boolean[] bright, int w, int y, int from, int to) {
        int count = 0;
        int base = y * w;
        for (int x = from; x < to; x++) {
            if (bright[base + x]) {
                count++;
            }
        }
        return count;
    }

    private static int countColumn(boolean[] bright, int w, int x, int from, int to) {
        int count = 0;
        for (int y = from; y < to; y++) {
            if (bright[y * w + x]) {
                count++;
            }
        }
        return count;
    }
}
