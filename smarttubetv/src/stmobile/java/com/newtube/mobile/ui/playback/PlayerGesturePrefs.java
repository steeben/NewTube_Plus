package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NEWTUBE(gestures): the switches for the player's optional swipes (issue #12), both on by default:
 * up and down on the sides of the fullscreen video set the brightness (left) and the volume
 * (right), and sideways anywhere on the video seeks. The fullscreen swipes (up into it, down out
 * of it) and swipe down to minimize are YouTube's own and always on.
 *
 * <p>A private preferences file, like the mobile flavor's other one-off stores (CastPrefs,
 * ThemeMode). The player reads the switches when a swipe starts, so a change applies to the next
 * swipe without a restart.</p>
 */
public final class PlayerGesturePrefs {

    private static final String PREFS_NAME = "newtube_gestures";
    private static final String KEY_LEVEL_SWIPES = "level_swipes";
    private static final String KEY_SEEK_SWIPE = "seek_swipe";
    private static final String KEY_SPEED_SWIPE = "speed_swipe";
    private static final String KEY_SPEED_STOP_AT_NORMAL = "speed_swipe_stop_at_1x";

    private PlayerGesturePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Swipe up and down on the left (brightness) and right (volume) of the fullscreen video. */
    public static boolean isLevelSwipesOn(Context context) {
        return prefs(context).getBoolean(KEY_LEVEL_SWIPES, true);
    }

    public static void setLevelSwipesOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_LEVEL_SWIPES, on).apply();
    }

    /** Swipe sideways on the video to seek. */
    public static boolean isSeekSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_SEEK_SWIPE, true);
    }

    public static void setSeekSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SEEK_SWIPE, on).apply();
    }

    /** NEWTUBE(speed-swipe): sideways across the top of the fullscreen video sets the speed. */
    public static boolean isSpeedSwipeOn(Context context) {
        return prefs(context).getBoolean(KEY_SPEED_SWIPE, true);
    }

    public static void setSpeedSwipeOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SPEED_SWIPE, on).apply();
    }

    /** A speed swipe toward 1x stops there; going past it takes a new swipe. */
    public static boolean isSpeedStopAtNormal(Context context) {
        return prefs(context).getBoolean(KEY_SPEED_STOP_AT_NORMAL, true);
    }

    public static void setSpeedStopAtNormal(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SPEED_STOP_AT_NORMAL, on).apply();
    }
}
