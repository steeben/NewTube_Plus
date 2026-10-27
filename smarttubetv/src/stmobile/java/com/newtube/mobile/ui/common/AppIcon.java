package com.newtube.mobile.ui.common;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * NEWTUBE(app-icon): the launcher's icon follows Settings > Appearance > App icon. An app's icon
 * cannot be changed while it is installed, so the launcher entry is one of two activity-aliases
 * of the splash screen (stmobile AndroidManifest), each with its own icon: one is enabled, the
 * other disabled. Glass is enabled in the manifest (the default); this only writes a change.
 *
 * <p>Switching makes the launcher drop the old entry and add the new one - a moment's flicker in
 * the app drawer, and a home-screen shortcut to the old entry goes with it.</p>
 */
public final class AppIcon {
    private static final String GLASS = "com.newtube.mobile.LauncherGlass";
    private static final String FLAT = "com.newtube.mobile.LauncherFlat";

    private AppIcon() {
    }

    /** Enable the alias of the chosen icon and disable the other, if they are not already so. */
    public static void apply(Context context) {
        boolean glass = UiPrefs.isGlassAppIcon(context);
        try {
            // The new one first: never a moment with no launcher entry at all.
            set(context, glass ? GLASS : FLAT, true);
            set(context, glass ? FLAT : GLASS, false);
        } catch (RuntimeException e) {
            // A component missing from this build: the launcher keeps the icon it has.
        }
    }

    private static void set(Context context, String alias, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context.getPackageName(), alias);
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int current = pm.getComponentEnabledSetting(component);
        boolean on = current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && GLASS.equals(alias));
        if (on != enabled) {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
        }
    }
}
