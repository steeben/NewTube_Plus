package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(theme): Settings > User interface > Theme - System default / Light / Dark (issue #8).
 *
 * <p>The palette is a resource split (values/ light, values-night/ dark), so choosing a side is
 * choosing the night bit of each screen's configuration:</p>
 * <ul>
 *     <li>every screen gets it as a configuration override when it is created
 *     ({@link MotherActivity#setNightModeSource}); System default sets none;</li>
 *     <li>{@link AppCompatDelegate#setDefaultNightMode} gets the same mode. Our screens are not
 *     AppCompat activities, but Material's BottomSheetDialog and AlertDialog are AppCompat
 *     dialogs, and each one "applies" AppCompat's night mode to the resources of the screen it
 *     opens on - with a different mode it would flip the screen under it back;</li>
 *     <li>on Android 12+ {@link UiModeManager#setApplicationNightMode} gets it too, which is what
 *     themes the system's launch splash (otherwise a Dark user on a light phone would see a white
 *     splash on every cold start).</li>
 * </ul>
 *
 * <p>Screens already open when the side changes (the setting, or the system switching while on
 * System default) are rebuilt by {@link MobileActivity#checkTheme}: recreated, except the player,
 * which re-colours its watch page in place so the video never stops.</p>
 */
public final class ThemeMode {
    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "newtube_theme";
    private static final String KEY_MODE = "mode";

    private static volatile int sMode = SYSTEM;
    private static final List<WeakReference<MobileActivity>> sScreens = new ArrayList<>();

    private ThemeMode() {
    }

    /**
     * Startup, before any screen exists. The first run of a version with this setting picks its
     * default: System default for a new install, Dark for an install upgrading from the dark-only
     * app, so nobody's app changes colour on update.
     *
     * @param existingInstall this install ran an earlier version (see {@link #isExistingInstall})
     */
    public static void init(@NonNull Application app, boolean existingInstall) {
        SharedPreferences prefs = prefs(app);
        if (!prefs.contains(KEY_MODE)) {
            int initial = existingInstall ? DARK : SYSTEM;
            prefs.edit().putInt(KEY_MODE, initial).apply();
            NetPath.log("theme default mode=" + name(initial) + " existing=" + (existingInstall ? "y" : "n"));
        }
        sMode = sanitize(prefs.getInt(KEY_MODE, SYSTEM));
        MotherActivity.setNightModeSource(context -> forcedNight(sMode));
        applyProcessWide(app, sMode);
    }

    /**
     * An install that ran an earlier version: the one-time migrations file already holds entries
     * (every version since 2026-07 writes one on its first launch), or the package was updated in
     * place since it was installed. Must be read before this launch's own migrations write.
     */
    public static boolean isExistingInstall(@NonNull Context context, @NonNull SharedPreferences migrations) {
        if (!migrations.getAll().isEmpty()) {
            return true;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    public static int get() {
        return sMode;
    }

    /** The Theme setting's choice: saved, applied, and every open screen brought over to it. */
    public static void set(@NonNull Context context, int mode) {
        mode = sanitize(mode);
        if (mode == sMode) {
            return;
        }
        sMode = mode;
        Context app = context.getApplicationContext();
        prefs(app).edit().putInt(KEY_MODE, mode).apply();
        NetPath.log("theme set mode=" + name(mode));
        applyProcessWide(app, mode);
        for (MobileActivity screen : liveScreens()) {
            screen.checkTheme();
        }
    }

    /**
     * The night bits a screen should be drawn with now: the forced side, or on System default the
     * system's, read off the application (never a screen, whose own configuration may still carry
     * the override it was created with).
     */
    public static int desiredNight(@NonNull Context context) {
        int forced = forcedNight(sMode);
        if (forced != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return forced;
        }
        int system = context.getApplicationContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return system == Configuration.UI_MODE_NIGHT_YES ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }

    /** The night bits a context's resources resolve with now. */
    public static int currentNight(@NonNull Context context) {
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }

    public static boolean isLight(@NonNull Context context) {
        return currentNight(context) != Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Brings a screen that is NOT recreated (the player) to {@code night}: its resources and its
     * theme, so what it inflates from here on - sheets, rows, the refreshed watch page - resolves
     * to that side. The framework rebuilds a screen's resources from the override it was created
     * with on every later configuration change, so this is re-run after each one (see
     * {@link MobileActivity#checkTheme}). The same technique AppCompat uses for activities that
     * handle uiMode themselves.
     */
    @SuppressWarnings("deprecation")
    public static void syncResources(@NonNull Activity activity, int night) {
        Resources res = activity.getResources();
        Configuration current = res.getConfiguration();
        if ((current.uiMode & Configuration.UI_MODE_NIGHT_MASK) == night) {
            return;
        }
        Configuration updated = new Configuration(current);
        updated.uiMode = (updated.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        res.updateConfiguration(updated, null);
        if (Build.VERSION.SDK_INT >= 29) {
            activity.getTheme().rebase();
        } else {
            int themeRes = manifestTheme(activity);
            if (themeRes != 0) {
                activity.getTheme().applyStyle(themeRes, true);
            }
        }
    }

    static void register(@NonNull MobileActivity screen) {
        synchronized (sScreens) {
            prune();
            sScreens.add(new WeakReference<>(screen));
        }
    }

    static void unregister(@NonNull MobileActivity screen) {
        synchronized (sScreens) {
            for (int i = sScreens.size() - 1; i >= 0; i--) {
                MobileActivity live = sScreens.get(i).get();
                if (live == null || live == screen) {
                    sScreens.remove(i);
                }
            }
        }
    }

    private static List<MobileActivity> liveScreens() {
        List<MobileActivity> screens = new ArrayList<>();
        synchronized (sScreens) {
            prune();
            for (WeakReference<MobileActivity> ref : sScreens) {
                MobileActivity screen = ref.get();
                if (screen != null) {
                    screens.add(screen);
                }
            }
        }
        return screens;
    }

    private static void prune() {
        for (int i = sScreens.size() - 1; i >= 0; i--) {
            if (sScreens.get(i).get() == null) {
                sScreens.remove(i);
            }
        }
    }

    /** {@link Configuration#UI_MODE_NIGHT_YES}/{@code NO} for a forced side, UNDEFINED for System default. */
    static int forcedNight(int mode) {
        switch (mode) {
            case LIGHT:
                return Configuration.UI_MODE_NIGHT_NO;
            case DARK:
                return Configuration.UI_MODE_NIGHT_YES;
            default:
                return Configuration.UI_MODE_NIGHT_UNDEFINED;
        }
    }

    static int appCompatMode(int mode) {
        switch (mode) {
            case LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    private static void applyProcessWide(Context app, int mode) {
        AppCompatDelegate.setDefaultNightMode(appCompatMode(mode));
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                UiModeManager uiModeManager = app.getSystemService(UiModeManager.class);
                if (uiModeManager != null) {
                    uiModeManager.setApplicationNightMode(mode == LIGHT ? UiModeManager.MODE_NIGHT_NO
                            : mode == DARK ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO);
                }
            } catch (RuntimeException e) {
                // Only the launch splash depends on it; the screens have their own override.
                NetPath.log("theme app-night-mode failed " + e.getClass().getSimpleName());
            }
        }
    }

    private static int manifestTheme(Activity activity) {
        try {
            ActivityInfo info = activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            return info.getThemeResource();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static int sanitize(int mode) {
        return mode == LIGHT || mode == DARK ? mode : SYSTEM;
    }

    static String name(int mode) {
        return mode == LIGHT ? "light" : mode == DARK ? "dark" : "system";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
