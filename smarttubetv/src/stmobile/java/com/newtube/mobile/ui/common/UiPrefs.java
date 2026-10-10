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
 *   <li>Branding - the top bar's wordmark alone, or with the NewTube icon before it.</li>
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
    public static final int BRANDING_NONE = 0;
    public static final int BRANDING_ICON = 1;

    private static final String PREFS_NAME = "newtube_ui";
    private static final String KEY_BRANDING = "branding";
    private static final String KEY_FULL_WIDTH_THUMBS = "full_width_thumbnails";
    private static final String KEY_HIDE_CARD_MENU = "hide_card_menu";
    private static final String KEY_ROUNDED_THUMBS = "rounded_thumbnails";
    private static final String KEY_PLAYLISTS_TAB = "playlists_in_bottom_bar";

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

    public static int getBranding(Context context) {
        return prefs(context).getInt(KEY_BRANDING, BRANDING_NONE) == BRANDING_ICON ? BRANDING_ICON : BRANDING_NONE;
    }

    public static void setBranding(Context context, int branding) {
        prefs(context).edit().putInt(KEY_BRANDING, branding).apply();
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
