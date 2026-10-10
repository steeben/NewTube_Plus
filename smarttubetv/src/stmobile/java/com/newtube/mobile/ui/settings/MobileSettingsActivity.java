package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.transition.MaterialSharedAxis;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.AccountsSheet;
import com.newtube.mobile.ui.common.MobileActivity;

/**
 * Phone Settings (issue #2: "way too convoluted - lots of options per section"). Until 1.14 the
 * Settings were SmartTube's TV categories drawn by the AppDialog renderer: every radio option
 * inline (Player alone ran to ~120 rows), TV-only knobs next to real ones, no current values.
 *
 * <p>Now a short tree in the shape of YouTube's and LibreTube's settings, defined in
 * {@link SettingsPages}: a top level with icons and a line about each section, pages of a dozen
 * rows at most, every choice one row showing its current value and opening a dialog, and the
 * power-user knobs under Advanced. The rows read and write the same prefs classes the TV
 * presenters did, so nothing a user set is lost.</p>
 *
 * <p>One page at a time, as a fragment with the shared-axis motion of Android's own Settings; the
 * fragment back stack is the page stack, so a theme switch (which recreates this screen) comes
 * back on the same page with the same pages behind it.</p>
 */
public class MobileSettingsActivity extends MobileActivity {
    /** Open straight on this page (e.g. Captions from the player), with the top level behind it. */
    public static final String EXTRA_PAGE = "newtube:settings_page";

    public static Intent intent(@NonNull Context context, @Nullable String pageId) {
        Intent intent = new Intent(context, MobileSettingsActivity.class);
        if (pageId != null) {
            intent.putExtra(EXTRA_PAGE, pageId);
        }
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_settings);

        // NEWTUBE(predictive-back): Back on a page is a predictive gesture - the page shrinks and
        // leans toward the swipe as the finger moves, and only goes once it is released. On the
        // top level the callback is off, so the system's own predictive back (to the screen
        // under Settings, or home) plays instead of a plain finish().
        mPageBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                peekStart();
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                peek(backEvent.getProgress(), backEvent.getSwipeEdge() == BackEventCompat.EDGE_LEFT);
            }

            @Override
            public void handleOnBackCancelled() {
                peekCancel();
            }

            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, mPageBack);
        getSupportFragmentManager().addOnBackStackChangedListener(this::updatePageBack);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.settings_container, SettingsPageFragment.newInstance(SettingsPages.ROOT))
                    .commitNow();
            String page = getIntent().getStringExtra(EXTRA_PAGE);
            if (page != null && !SettingsPages.ROOT.equals(page)) {
                openPage(null, page, false, null);
            }
        }
    }

    /** Opens a page of the tree on top of {@code from}, the page whose row was tapped. */
    public void openPage(@NonNull Fragment from, @NonNull String pageId) {
        openPage(from, pageId, true, null);
    }

    /** The search page, on top of the top level like any other page. */
    void openSearch(@NonNull Fragment from) {
        push(from, new SettingsSearchFragment(), "search", true);
    }

    /**
     * A search result: the page it opens, or the page it is on with the row lit up. Either way it
     * goes on top of the search page, so Back comes back to the results.
     */
    void openResult(@NonNull Fragment from, @NonNull SettingsSearch.Entry entry) {
        if (entry.account) {
            AccountsSheet.show(this, () -> { }); // the account row's "page"
        } else if (entry.opens != null) {
            openPage(from, entry.opens, true, null);
        } else {
            openPage(from, entry.pageId, true, String.valueOf(entry.title));
        }
    }

    private void openPage(@Nullable Fragment from, @NonNull String pageId, boolean animate, @Nullable String highlight) {
        push(from, SettingsPageFragment.newInstance(pageId, highlight), pageId, animate);
    }

    /**
     * {@code from}: the page asking, or null. A second tap that lands before the first one's page
     * replaced it, or while that page slides in over it, finds {@code from} no longer on top and is
     * dropped: a double tap opens one page, not two.
     */
    private void push(@Nullable Fragment from, @NonNull Fragment next, @NonNull String name, boolean animate) {
        FragmentManager fragments = getSupportFragmentManager();
        if (fragments.isStateSaved()) {
            return;
        }
        fragments.executePendingTransactions();
        Fragment current = fragments.findFragmentById(R.id.settings_container);
        if (from != null && current != from) {
            return;
        }
        if (animate) {
            next.setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
            next.setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
            if (current != null) {
                current.setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
                current.setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
            }
        }
        fragments.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_container, next)
                .addToBackStack(name)
                .commit();
    }

    private OnBackPressedCallback mPageBack;
    @Nullable
    private View mPeekView;
    private float mPeekRadius;

    private void updatePageBack() {
        if (mPageBack != null) {
            mPageBack.setEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
        }
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updatePageBack(); // a recreated screen comes back with its pages
    }

    private void peekStart() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        mPeekView = current != null ? current.getView() : null;
        if (mPeekView == null) {
            return;
        }
        mPeekView.animate().cancel();
        mPeekView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mPeekRadius);
            }
        });
        mPeekView.setClipToOutline(true);
    }

    /** Material's predictive back for a full page: down to 90%, leaning toward the swipe. */
    private void peek(float progress, boolean fromLeft) {
        if (mPeekView == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float eased = new DecelerateInterpolator().getInterpolation(progress);
        float scale = 1f - 0.1f * eased;
        float shift = Math.max(0f, mPeekView.getWidth() / 20f - 8 * density) * eased;
        mPeekView.setScaleX(scale);
        mPeekView.setScaleY(scale);
        mPeekView.setTranslationX(fromLeft ? shift : -shift);
        mPeekRadius = 28 * density * eased;
        mPeekView.invalidateOutline();
    }

    private void peekCancel() {
        View view = mPeekView;
        if (view == null) {
            return;
        }
        float startRadius = mPeekRadius;
        view.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(200)
                .setUpdateListener(animation -> {
                    mPeekRadius = startRadius * (1f - animation.getAnimatedFraction());
                    view.invalidateOutline();
                })
                .withEndAction(() -> peekReset(view))
                .start();
        mPeekView = null;
    }

    private void peekReset(View view) {
        view.animate().setUpdateListener(null);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationX(0f);
        view.setClipToOutline(false);
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        mPeekRadius = 0f;
    }

    private void handleBack() {
        mPeekView = null; // the page leaves as it is (shrunk), by its own return motion
        FragmentManager fragments = getSupportFragmentManager();
        if (fragments.getBackStackEntryCount() > 0) {
            fragments.popBackStack();
        } else {
            finish();
        }
    }
}
