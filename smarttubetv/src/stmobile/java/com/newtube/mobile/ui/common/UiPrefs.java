package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(ui-options): the look-and-feel switches of Settings > General and the Playlists tab of
 * Settings > Tabs and feeds > Tabs. A private preferences file, like the flavor's other one-off
 * stores (ThemeMode, PlayerGesturePrefs).
 *
 * <ul>
 *   <li>Branding - the top bar's logo (none, NewTube's, or a picture of the user's) and header
 *   text (none, NewTube, or the user's own).</li>
 *   <li>Accent - the colour of progress bars, the selected tab and switches.</li>
 *   <li>Full-width thumbnails - in a one-column feed the video cards run edge to edge.</li>
 *   <li>Card menu - the ⋮ under each card; its menu is still a long press away when hidden.</li>
 *   <li>Rounded thumbnails - the cards' 12dp corners, or square ones.</li>
 *   <li>Playlists in the bottom bar.</li>
 * </ul>
 *
 * <p>Views that draw from these register a {@link Listener} and are told when one changes, so a
 * screen already open (Home under Settings) is up to date when the user comes back to it.</p>
 */
public final class UiPrefs {
    /** NEWTUBE(branding): the logo before the top bar's header text. */
    public static final int LOGO_NONE = 0;
    public static final int LOGO_NEWTUBE = 1;
    public static final int LOGO_CUSTOM = 2;
    /** A play triangle in a circle of the accent colour. */
    public static final int LOGO_PLAY = 3;
    /** NEWTUBE(branding): the top bar's header text (in League Gothic). */
    public static final int HEADER_NONE = 0;
    public static final int HEADER_DEFAULT = 1;
    public static final int HEADER_CUSTOM = 2;
    /** NEWTUBE(ambient): the player's ambient mode (AmbientMode). */
    public static final int AMBIENT_OFF = 0;
    public static final int AMBIENT_ON = 1;
    public static final int AMBIENT_NOT_FULLSCREEN = 2;
    /** NEWTUBE(accent): the accent until one is chosen - the seek bar's red (mobile_color_progress). */
    public static final int DEFAULT_ACCENT = 0xFFFF0033;

    private static final String PREFS_NAME = "newtube_ui";
    /** The first Branding setting (wordmark with or without the icon), read once for the Logo. */
    private static final String KEY_BRANDING = "branding";
    private static final String KEY_LOGO = "logo";
    private static final String KEY_HEADER = "header";
    private static final String KEY_HEADER_TEXT = "header_text";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_AMBIENT = "ambient";
    private static final String KEY_FROSTED = "frosted_glass";
    private static final String KEY_NAV_LABELS = "nav_labels";
    private static final String KEY_DEFAULTS = "fresh_install_defaults";
    private static final String KEY_HIDE_ERROR_TOASTS = "hide_error_toasts";
    private static final String KEY_SEGMENT_TOASTS = "segment_skip_toasts";
    private static final String CUSTOM_LOGO_FILE = "brand_logo.png";
    private static final String KEY_FULL_WIDTH_THUMBS = "full_width_thumbnails";
    private static final String KEY_HIDE_CARD_MENU = "hide_card_menu";
    private static final String KEY_ROUNDED_THUMBS = "rounded_thumbnails";
    private static final String KEY_PLAYLISTS_TAB = "playlists_in_bottom_bar";
    private static final String KEY_DOWNLOADS_TAB = "downloads_in_bottom_bar";

    /** Something drawn from these changed: redraw. Main thread. */
    public interface Listener {
        void onUiPrefsChanged();
    }

    private static final List<WeakReference<Listener>> sListeners = new ArrayList<>();

    private UiPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getLogo(Context context) {
        SharedPreferences prefs = prefs(context);
        int fallback = prefs.getInt(KEY_BRANDING, 0) == 1 ? LOGO_NEWTUBE : LOGO_NONE;
        int logo = prefs.getInt(KEY_LOGO, fallback);
        if (logo == LOGO_CUSTOM && !customLogoFile(context).isFile()) {
            return LOGO_NONE; // the picked picture is gone
        }
        return logo == LOGO_NEWTUBE || logo == LOGO_CUSTOM || logo == LOGO_PLAY ? logo : LOGO_NONE;
    }

    public static void setLogo(Context context, int logo) {
        prefs(context).edit().putInt(KEY_LOGO, logo).apply();
        notifyChanged();
    }

    /** Where the picture picked for the Custom logo is kept (a square PNG, see BrandLogoPickActivity). */
    public static java.io.File customLogoFile(Context context) {
        return new java.io.File(context.getApplicationContext().getFilesDir(), CUSTOM_LOGO_FILE);
    }

    public static int getHeader(Context context) {
        int header = prefs(context).getInt(KEY_HEADER, HEADER_DEFAULT);
        return header == HEADER_NONE || header == HEADER_CUSTOM ? header : HEADER_DEFAULT;
    }

    public static void setHeader(Context context, int header) {
        prefs(context).edit().putInt(KEY_HEADER, header).apply();
        notifyChanged();
    }

    public static String getHeaderText(Context context) {
        return prefs(context).getString(KEY_HEADER_TEXT, "");
    }

    public static void setHeaderText(Context context, String text) {
        prefs(context).edit().putString(KEY_HEADER_TEXT, text).apply();
        notifyChanged();
    }

    private static volatile int sAccent;
    private static volatile boolean sAccentRead;

    /** NEWTUBE(accent): the user's accent colour (opaque), or {@link #DEFAULT_ACCENT}. Cached. */
    public static int getAccent(Context context) {
        if (!sAccentRead) {
            sAccent = 0xFF000000 | prefs(context).getInt(KEY_ACCENT, DEFAULT_ACCENT);
            sAccentRead = true;
        }
        return sAccent;
    }

    public static void setAccent(Context context, int color) {
        sAccent = 0xFF000000 | color;
        sAccentRead = true;
        prefs(context).edit().putInt(KEY_ACCENT, sAccent).apply();
        notifyChanged();
    }

    public static boolean isFullWidthThumbnails(Context context) {
        return prefs(context).getBoolean(KEY_FULL_WIDTH_THUMBS, false);
    }

    public static void setFullWidthThumbnails(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_FULL_WIDTH_THUMBS, on).apply();
        notifyChanged();
    }

    public static boolean isCardMenuHidden(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_CARD_MENU, false);
    }

    public static void setCardMenuHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_HIDE_CARD_MENU, hidden).apply();
        notifyChanged();
    }

    public static boolean isRoundedThumbnails(Context context) {
        return prefs(context).getBoolean(KEY_ROUNDED_THUMBS, true);
    }

    public static void setRoundedThumbnails(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ROUNDED_THUMBS, on).apply();
        notifyChanged();
    }

    public static boolean isPlaylistsInBottomBar(Context context) {
        return prefs(context).getBoolean(KEY_PLAYLISTS_TAB, false);
    }

    public static void setPlaylistsInBottomBar(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_PLAYLISTS_TAB, on).apply();
        notifyChanged();
    }

    /** Downloads is a bottom-bar tab (the default) rather than a row of the You tab. */
    public static boolean isDownloadsInBottomBar(Context context) {
        return prefs(context).getBoolean(KEY_DOWNLOADS_TAB, true);
    }

    public static void setDownloadsInBottomBar(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_DOWNLOADS_TAB, on).apply();
        notifyChanged();
    }

    /** NEWTUBE(nav-labels): the bottom bar's tab names (on by default). */
    public static boolean isNavLabels(Context context) {
        return prefs(context).getBoolean(KEY_NAV_LABELS, true);
    }

    public static void setNavLabels(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_NAV_LABELS, on).apply();
        notifyChanged();
    }

    /**
     * NEWTUBE(defaults): a new install starts with the look this fork is meant to have - frosted
     * glass, the ambient player (not in full screen), full-width cards with rounded corners and no
     * more button, the play logo, and the video-background mini bar. Written once, on the first
     * launch of a new install only: an install updated from an earlier version keeps what it had
     * (the getters' own defaults are the earlier behaviour).
     */
    public static void applyFreshInstallDefaults(Context context, boolean existingInstall) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_DEFAULTS, false)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_DEFAULTS, true);
        if (!existingInstall) {
            editor.putBoolean(KEY_FROSTED, true)
                    .putInt(KEY_AMBIENT, AMBIENT_NOT_FULLSCREEN)
                    .putBoolean(KEY_FULL_WIDTH_THUMBS, true)
                    .putBoolean(KEY_ROUNDED_THUMBS, true)
                    .putBoolean(KEY_HIDE_CARD_MENU, true)
                    .putInt(KEY_LOGO, LOGO_PLAY)
                    // The bottom bar: Playlists in, Downloads to the You tab.
                    .putBoolean(KEY_PLAYLISTS_TAB, true)
                    .putBoolean(KEY_DOWNLOADS_TAB, false);
            // Playback > Under the video > Show dislike counts.
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData.instance(context)
                    .setReturnYouTubeDislikeEnabled(true);
        }
        editor.apply();
    }

    /** NEWTUBE(you-explore): the You list's Explore rows are folded away (as last left). */
    public static boolean isYouExploreCollapsed(Context context) {
        return prefs(context).getBoolean("you_explore_collapsed", false);
    }

    public static void setYouExploreCollapsed(Context context, boolean collapsed) {
        prefs(context).edit().putBoolean("you_explore_collapsed", collapsed).apply();
    }

    /** NEWTUBE(error-toasts): Settings > Advanced - failure messages are not shown. */
    public static boolean isErrorToastsHidden(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_ERROR_TOASTS, false);
    }

    public static void setErrorToastsHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_HIDE_ERROR_TOASTS, hidden).apply();
        applyToastPrefs(context);
    }

    /** NEWTUBE(segment-toasts): Settings > SponsorBlock - a skipped segment is announced (on by default). */
    public static boolean isSegmentToasts(Context context) {
        return prefs(context).getBoolean(KEY_SEGMENT_TOASTS, true);
    }

    public static void setSegmentToasts(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_SEGMENT_TOASTS, on).apply();
        applyToastPrefs(context);
    }

    /** The two toast switches, handed to the shared code that shows those toasts. App start + on change. */
    public static void applyToastPrefs(Context context) {
        com.liskovsoft.smartyoutubetv2.common.misc.ErrorMessages.setEnabled(!isErrorToastsHidden(context));
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SponsorBlockController
                .setSkipToastsEnabled(isSegmentToasts(context));
    }

    /** NEWTUBE(frosted): glass bars on Home and glass buttons on the watch page. */
    public static boolean isFrostedGlass(Context context) {
        return prefs(context).getBoolean(KEY_FROSTED, false);
    }

    public static void setFrostedGlass(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_FROSTED, on).apply();
        notifyChanged();
    }

    public static int getAmbient(Context context) {
        int mode = prefs(context).getInt(KEY_AMBIENT, AMBIENT_OFF);
        return mode == AMBIENT_ON || mode == AMBIENT_NOT_FULLSCREEN ? mode : AMBIENT_OFF;
    }

    public static void setAmbient(Context context, int mode) {
        prefs(context).edit().putInt(KEY_AMBIENT, mode).apply();
        notifyChanged();
    }

    public static void addListener(Listener listener) {
        synchronized (sListeners) {
            prune();
            sListeners.add(new WeakReference<>(listener));
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (sListeners) {
            for (int i = sListeners.size() - 1; i >= 0; i--) {
                Listener live = sListeners.get(i).get();
                if (live == null || live == listener) {
                    sListeners.remove(i);
                }
            }
        }
    }

    private static void notifyChanged() {
        List<Listener> listeners = new ArrayList<>();
        synchronized (sListeners) {
            prune();
            for (WeakReference<Listener> ref : sListeners) {
                Listener listener = ref.get();
                if (listener != null) {
                    listeners.add(listener);
                }
            }
        }
        for (Listener listener : listeners) {
            listener.onUiPrefsChanged();
        }
    }

    private static void prune() {
        for (int i = sListeners.size() - 1; i >= 0; i--) {
            if (sListeners.get(i).get() == null) {
                sListeners.remove(i);
            }
        }
    }
}
