package com.newtube.mobile.ui.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * NEWTUBE(app-icon): the launcher's icon follows Settings > Appearance > App icon (Glass or
 * Flat) and the accent. An app's icon cannot be changed while it is installed, so the launcher
 * entry is one of the splash screen's activity-aliases (stmobile AndroidManifest), one for each
 * look in each preset accent, each with its own icon: one is enabled, the rest disabled. A
 * custom accent takes the nearest preset (as the launch screen does: SplashAccent). Glass in red
 * is enabled in the manifest (the default); this only writes changes.
 *
 * <p>Switching makes the launcher drop the old entry and add the new one - a moment's flicker in
 * the app drawer, and a home-screen shortcut to the old entry goes with it.</p>
 */
public final class AppIcon {
    private static final String PREFIX = "com.newtube.mobile.Launcher";
    /** The presets' names, in SplashAccent's order (red has none: the first two aliases). */
    private static final String[] ACCENTS = {"", "Orange", "Amber", "Green", "Teal", "Cyan", "Blue",
            "Violet", "Purple", "Pink", "Grey"};
    private static final String DEFAULT_ALIAS = PREFIX + "Glass";

    private AppIcon() {
    }

    /** Enable the alias of the chosen icon and disable the others, where they are not already so. */
    public static void apply(Context context) {
        String chosen = PREFIX + (UiPrefs.isGlassAppIcon(context) ? "Glass" : "Flat")
                + ACCENTS[SplashAccent.nearestPreset(UiPrefs.getAccent(context))];
        try {
            // The new one first: never a moment with no launcher entry at all.
            set(context, chosen, true);
            for (String look : new String[] {"Glass", "Flat"}) {
                for (String accent : ACCENTS) {
                    String alias = PREFIX + look + accent;
                    if (!alias.equals(chosen)) {
                        set(context, alias, false);
                    }
                }
            }
        } catch (RuntimeException e) {
            // A component missing from this build: the launcher keeps the icon it has.
        }
    }

    private static void set(Context context, String alias, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context.getPackageName(), alias);
        int current = pm.getComponentEnabledSetting(component);
        boolean on = current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && DEFAULT_ALIAS.equals(alias));
        if (on != enabled) {
            pm.setComponentEnabledSetting(component, enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
