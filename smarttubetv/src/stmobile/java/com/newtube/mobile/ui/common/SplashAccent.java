package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(splash): the launch screen shows the Play logo in the accent. A launch screen is drawn
 * by the system from a theme before the app runs, so it can only be one of a set made in advance:
 * the preset accents. From Android 13 the app tells the system which (the nearest to the accent);
 * before that it is the red.
 */
public final class SplashAccent {
    private static final int[] COLORS = {0xFFFF0033, 0xFFFF6D00, 0xFFFFC400, 0xFF00C853, 0xFF00BFA5, 0xFF00B8D4, 0xFF2979FF, 0xFF651FFF, 0xFFD500F9, 0xFFF50057, 0xFF9E9E9E};
    private static final int[] THEMES = {R.style.Theme_NewTube_Launch, R.style.Theme_NewTube_Launch_Orange, R.style.Theme_NewTube_Launch_Amber, R.style.Theme_NewTube_Launch_Green, R.style.Theme_NewTube_Launch_Teal, R.style.Theme_NewTube_Launch_Cyan, R.style.Theme_NewTube_Launch_Blue, R.style.Theme_NewTube_Launch_Violet, R.style.Theme_NewTube_Launch_Purple, R.style.Theme_NewTube_Launch_Pink, R.style.Theme_NewTube_Launch_Grey};
    /** NEWTUBE(app-icon): the same, with the Glass app icon's logo. */
    private static final int[] GLASS_THEMES = {R.style.Theme_NewTube_Launch_Glass, R.style.Theme_NewTube_Launch_Glass_Orange, R.style.Theme_NewTube_Launch_Glass_Amber, R.style.Theme_NewTube_Launch_Glass_Green, R.style.Theme_NewTube_Launch_Glass_Teal, R.style.Theme_NewTube_Launch_Glass_Cyan, R.style.Theme_NewTube_Launch_Glass_Blue, R.style.Theme_NewTube_Launch_Glass_Violet, R.style.Theme_NewTube_Launch_Glass_Purple, R.style.Theme_NewTube_Launch_Glass_Pink, R.style.Theme_NewTube_Launch_Glass_Grey};
    private static int sApplied;

    private SplashAccent() {
    }

    public static void apply(Activity activity) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        int accent = UiPrefs.getAccent(activity);
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < COLORS.length; i++) {
            long dr = ((accent >> 16) & 0xFF) - ((COLORS[i] >> 16) & 0xFF);
            long dg = ((accent >> 8) & 0xFF) - ((COLORS[i] >> 8) & 0xFF);
            long db = (accent & 0xFF) - (COLORS[i] & 0xFF);
            long distance = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        int theme = (UiPrefs.isGlassAppIcon(activity) ? GLASS_THEMES : THEMES)[best];
        if (theme == sApplied) {
            return;
        }
        try {
            activity.getSplashScreen().setSplashScreenTheme(theme);
            sApplied = theme;
        } catch (RuntimeException e) {
            // Not this time; the launch screen keeps the theme it had.
        }
    }
}
