package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

/**
 * NEWTUBE(mini-style): how a minimized video docks on Home, Search and the channel screens.
 *
 * <ul>
 *   <li>{@link #CARD} - the floating 180x102dp card in a bottom corner (the default, and the only
 *   style before this setting); {@link #CARD_GESTURE} - the same card with no buttons.</li>
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
    /** The card without buttons: a tap plays or pauses, everything else is a gesture. */
    public static final int CARD_GESTURE = 1;
    public static final int BAR = 2;
    public static final int BAR_VIDEO = 3;

    public static final int CARD_WIDTH_DP = 180;
    public static final int CARD_HEIGHT_DP = 102;
    public static final int CARD_MARGIN_DP = 12;
    public static final float CARD_CORNER_DP = 12f;
    public static final int BAR_HEIGHT_DP = 64;
    /** NEWTUBE(mini-style): the video-background bar is 40% taller - it is mostly picture. */
    public static final int BAR_VIDEO_HEIGHT_DP = 90;
    /**
     * The video-background bar's top corners: the video cards' radius while their rounded
     * corners are on (General > Video cards, read from that setting's own store so this works
     * whether or not it exists in this build), square otherwise.
     */
    private static final String UI_PREFS_NAME = "newtube_ui";
    private static final String UI_KEY_ROUNDED = "rounded_thumbnails";

    private static final String PREFS_NAME = "newtube_mini_player";
    private static final String KEY_STYLE = "style";
    private static final String KEY_CARD_LEFT = "card_left";

    private MiniPlayerStyle() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int get(Context context) {
        int style = prefs(context).getInt(KEY_STYLE, CARD);
        switch (style) {
            case CARD_GESTURE:
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

    /** The floating card is docked in the bottom-left corner (else the bottom-right). */
    public static boolean isCardLeft(Context context) {
        return prefs(context).getBoolean(KEY_CARD_LEFT, false);
    }

    public static void setCardLeft(Context context, boolean left) {
        prefs(context).edit().putBoolean(KEY_CARD_LEFT, left).apply();
    }

    /** Height of {@code style}'s bar, in dp. */
    public static int barHeightDp(int style) {
        return style == BAR_VIDEO ? BAR_VIDEO_HEIGHT_DP : BAR_HEIGHT_DP;
    }

    /** The video-background bar's top corners are rounded (see {@link #UI_KEY_ROUNDED}). */
    public static boolean isBarRounded(Context context) {
        return context.getApplicationContext().getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(UI_KEY_ROUNDED, true);
    }

    /**
     * NEWTUBE(accent): the progress line's colour - the accent from Appearance (read from that
     * setting's own store, like the rounded corners above), the red by default.
     */
    public static int accent(Context context) {
        return 0xFF000000 | context.getApplicationContext().getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("accent", 0xFFFF0033);
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
     * overlay hosts) between the mini player and the content bottom; {@code cardLeft} - the card
     * is docked in the bottom-left corner.
     */
    public static Target target(int style, float width, float height, float bottomOffsetPx, float density,
            boolean cardLeft) {
        float bottom = height - bottomOffsetPx;
        switch (style) {
            case BAR: {
                float barHeight = BAR_HEIGHT_DP * density;
                RectF rect = new RectF(0f, bottom - barHeight, barThumbWidthPx(density), bottom);
                return new Target(rect, false, 0f);
            }
            case BAR_VIDEO: {
                float barHeight = BAR_VIDEO_HEIGHT_DP * density;
                RectF rect = new RectF(0f, bottom - barHeight, width, bottom);
                return new Target(rect, true, 0f);
            }
            default: {
                float margin = CARD_MARGIN_DP * density;
                float cardWidth = CARD_WIDTH_DP * density;
                float cardBottom = bottom - margin;
                float left = cardLeft ? margin : width - margin - cardWidth;
                RectF rect = new RectF(left, cardBottom - CARD_HEIGHT_DP * density, left + cardWidth, cardBottom);
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
