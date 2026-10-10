package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

/**
 * NEWTUBE(mini-style): how a minimized video docks on Home, Search and the channel screens.
 *
 * <ul>
 *   <li>{@link #CARD} - the floating 180x102dp card in the bottom corner (the default, and the only
 *   style before this setting).</li>
 *   <li>{@link #BAR} - a full-width bar like the old YouTube app's: the video as a 16:9 thumbnail on
 *   the left, the title and channel beside it, play/pause and close on the right.</li>
 *   <li>{@link #BAR_VIDEO} - the same full-width bar with the live video cropped to fill it, dimmed,
 *   with only the title on a dark scrim and the two buttons over it.</li>
 * </ul>
 *
 * <p>The choice is read when a host shows its mini player and when the player starts a minimize,
 * so a change in Settings applies to the next minimize without a restart. The geometry here is the
 * one place both sides agree on where the minimized video lands: the player morphs its video box
 * onto {@link #target} while the host lays its view out to match.</p>
 */
public final class MiniPlayerStyle {
    public static final int CARD = 0;
    /** Reserved for the gesture-only card (tap = play/pause, no buttons). */
    public static final int CARD_GESTURE = 1;
    public static final int BAR = 2;
    public static final int BAR_VIDEO = 3;

    public static final int CARD_WIDTH_DP = 180;
    public static final int CARD_HEIGHT_DP = 102;
    public static final int CARD_MARGIN_DP = 12;
    public static final float CARD_CORNER_DP = 12f;
    public static final int BAR_HEIGHT_DP = 64;

    private static final String PREFS_NAME = "newtube_mini_player";
    private static final String KEY_STYLE = "style";

    private MiniPlayerStyle() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int get(Context context) {
        int style = prefs(context).getInt(KEY_STYLE, CARD);
        switch (style) {
            case BAR:
            case BAR_VIDEO:
                return style;
            default:
                return CARD;
        }
    }

    public static void set(Context context, int style) {
        prefs(context).edit().putInt(KEY_STYLE, style).apply();
    }

    public static boolean isBar(int style) {
        return style == BAR || style == BAR_VIDEO;
    }

    /** Width of the bar's 16:9 thumbnail slot ({@link #BAR}), in px. */
    public static float barThumbWidthPx(float density) {
        return BAR_HEIGHT_DP * density * 16f / 9f;
    }

    /** Where the minimized video lands. */
    public static final class Target {
        /**
         * The landing rectangle, in the coordinates of a window-sized container. Without
         * {@link #crop} only its left, width and bottom are used: the video keeps its own shape
         * (16:9, so it fills a 16:9 slot), scaled to this width and resting on this bottom edge.
         */
        public final RectF rect;
        /**
         * The video fills {@link #rect}'s width and is cut, top and bottom, to its height (the
         * video-background bar), instead of being scaled to fit.
         */
        public final boolean crop;
        /** Corner radius of the landed video, in dp. */
        public final float cornerDp;

        Target(RectF rect, boolean crop, float cornerDp) {
            this.rect = rect;
            this.crop = crop;
            this.cornerDp = cornerDp;
        }
    }

    /**
     * Where a minimize lands for {@code style}, in a full-window container of {@code width} x
     * {@code height} px whose host keeps {@code bottomOffsetPx} (Home's bottom-nav row, 0 for the
     * overlay hosts) between the mini player and the content bottom.
     */
    public static Target target(int style, float width, float height, float bottomOffsetPx, float density) {
        float bottom = height - bottomOffsetPx;
        switch (style) {
            case BAR: {
                float barHeight = BAR_HEIGHT_DP * density;
                RectF rect = new RectF(0f, bottom - barHeight, barThumbWidthPx(density), bottom);
                return new Target(rect, false, 0f);
            }
            case BAR_VIDEO: {
                float barHeight = BAR_HEIGHT_DP * density;
                RectF rect = new RectF(0f, bottom - barHeight, width, bottom);
                return new Target(rect, true, 0f);
            }
            default: {
                float margin = CARD_MARGIN_DP * density;
                float cardWidth = CARD_WIDTH_DP * density;
                float cardBottom = bottom - margin;
                RectF rect = new RectF(width - margin - cardWidth, cardBottom - CARD_HEIGHT_DP * density,
                        width - margin, cardBottom);
                return new Target(rect, false, CARD_CORNER_DP);
            }
        }
    }

    /**
     * Size, in px, of the picture the mini player shows the video at: the viewport cap for new
     * chunks and the size of the hand-off frame captured at a minimize. The video-background bar
     * shows the full 16:9 frame at the window's width (cropped by the bar).
     */
    public static int[] videoSizePx(int style, float windowWidthPx, float density) {
        switch (style) {
            case BAR: {
                int height = Math.max(1, Math.round(BAR_HEIGHT_DP * density));
                return new int[] {Math.max(1, Math.round(barThumbWidthPx(density))), height};
            }
            case BAR_VIDEO: {
                int width = Math.max(1, Math.round(windowWidthPx));
                return new int[] {width, Math.max(1, Math.round(width * 9f / 16f))};
            }
            default:
                return new int[] {Math.max(1, Math.round(CARD_WIDTH_DP * density)),
                        Math.max(1, Math.round(CARD_HEIGHT_DP * density))};
        }
    }
}
