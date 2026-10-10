package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.newtube.mobile.ui.common.UiPrefs;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(nav-mirror): Home's bottom navigation, shown again on the screens opened from it (a
 * playlist's or a channel's video list) so the tabs stay in reach, like YouTube's. Home publishes
 * its tabs here each time it builds them; a mirror shows the same tabs with Home's current one
 * lit, and a tap brings Home back to the front on that tab - closing the list screens it passed
 * through, so Back from Home does not land on them again.
 */
public final class BrowseNavMirror {
    private static final List<Integer> sIds = new ArrayList<>();
    private static final List<CharSequence> sTitles = new ArrayList<>();
    private static final List<Integer> sIcons = new ArrayList<>();
    private static int sSelectedId;
    private static int sRequestedId;
    private static final List<WeakReference<Activity>> sDetails = new ArrayList<>();

    private BrowseNavMirror() {
    }

    /** Home: its tabs, in order, as just built. */
    static void publish(List<Integer> ids, List<CharSequence> titles, List<Integer> icons) {
        sIds.clear();
        sIds.addAll(ids);
        sTitles.clear();
        sTitles.addAll(titles);
        sIcons.clear();
        sIcons.addAll(icons);
    }

    /** Home: the tab that is lit. */
    static void setSelected(int itemId) {
        sSelectedId = itemId;
    }

    /** Home, on resume: the tab a mirror asked for (0 for none), once. */
    static int takeRequestedTab() {
        int id = sRequestedId;
        sRequestedId = 0;
        return id;
    }

    public static boolean hasTabs() {
        return !sIds.isEmpty();
    }

    /**
     * Fill {@code nav} with Home's tabs and make a tap go there. False (and nothing shown) when
     * Home has not built its tabs.
     */
    public static boolean populate(Activity activity, BottomNavigationView nav) {
        if (sIds.isEmpty()) {
            return false;
        }
        nav.setLabelVisibilityMode(UiPrefs.isNavLabels(activity)
                ? NavigationBarView.LABEL_VISIBILITY_LABELED : NavigationBarView.LABEL_VISIBILITY_UNLABELED);
        nav.setOnItemSelectedListener(null);
        nav.setOnItemReselectedListener(null);
        Menu menu = nav.getMenu();
        menu.clear();
        for (int i = 0; i < sIds.size(); i++) {
            MenuItem item = menu.add(Menu.NONE, sIds.get(i), i, sTitles.get(i));
            int icon = sIcons.get(i);
            if (icon > 0) {
                item.setIcon(icon);
            }
        }
        MenuItem selected = menu.findItem(sSelectedId);
        if (selected != null) {
            selected.setChecked(true);
        }
        nav.setOnItemSelectedListener(item -> {
            goTo(activity, item.getItemId());
            return false; // Home lights it; this screen is leaving
        });
        nav.setOnItemReselectedListener(item -> goTo(activity, item.getItemId()));
        return true;
    }

    /** A list screen with a mirror: closed when a tab takes the user back to Home. */
    public static void registerDetail(Activity activity) {
        prune();
        sDetails.add(new WeakReference<>(activity));
    }

    private static void goTo(Activity from, int itemId) {
        sRequestedId = itemId;
        // Home first (its launch is pending while the list screens finish, so their finish does
        // not route to their own parent - MobileActivity.finishReally).
        ViewManager.instance(from).startView(BrowseView.class);
        prune();
        for (WeakReference<Activity> ref : new ArrayList<>(sDetails)) {
            Activity detail = ref.get();
            if (detail != null && !detail.isFinishing()) {
                detail.finish();
            }
        }
        sDetails.clear();
    }

    private static void prune() {
        for (int i = sDetails.size() - 1; i >= 0; i--) {
            Activity activity = sDetails.get(i).get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                sDetails.remove(i);
            }
        }
    }
}
