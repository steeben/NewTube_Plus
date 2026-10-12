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
        // The window is see-through (Theme.NewTube.Settings): the page colour under the bars too.
        findViewById(android.R.id.content).setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.mobile_color_background));

        // NEWTUBE(predictive-back): Back on a page is a predictive gesture - the page blurs and fades
        // as the finger moves, revealing the page under it (which stays loaded beneath), and goes
        // once it is released. On the top level Settings itself goes the same way, over the
        // screen it was opened from (kept drawn underneath: the window is see-through).
        mPageBack = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                peekStart();
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                peek(backEvent.getProgress());
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
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            updatePageBack();
            syncPages(true);
        });

        if (savedInstanceState == null) {
            showLoadingThenBuild();
        }
    }

    /**
     * NEWTUBE(settings-loading): building the top level reads every setting's current value and can
     * take a moment (up to ~2 s on a cold start); until now the screen sat blank meanwhile, as if
     * frozen. The first frame is the page colour with a spinner - the platform one, whose animation
     * runs on the render thread and so keeps turning while the page is built - and the page goes in
     * right after that frame is drawn.
     */
    private void showLoadingThenBuild() {
        android.widget.FrameLayout container = findViewById(R.id.settings_container);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(
                com.newtube.mobile.ui.common.UiPrefs.getAccent(this)));
        int size = Math.round(40 * getResources().getDisplayMetrics().density);
        container.addView(spinner, new android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER));
        View decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                // After this frame (the spinner's) has gone out.
                decor.post(() -> {
                    if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
                        container.removeView(spinner);
                        return;
                    }
                    getSupportFragmentManager().beginTransaction()
                            .setReorderingAllowed(true)
                            .add(R.id.settings_container, SettingsPageFragment.newInstance(SettingsPages.ROOT))
                            .commitNow();
                    container.removeView(spinner);
                    String page = getIntent().getStringExtra(EXTRA_PAGE);
                    if (page != null && !SettingsPages.ROOT.equals(page)) {
                        openPage(null, page, false, null);
                    }
                });
                return true;
            }
        });
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
        }
        // Added over the page it was opened from, not in place of it: that page stays loaded
        // underneath (hidden), so Back can reveal it as the new one goes.
        fragments.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.settings_container, next)
                .addToBackStack(name)
                .commit();
    }

    private OnBackPressedCallback mPageBack;
    @Nullable
    private View mPeekView;
    @Nullable
    private View mPeekUnder;
    private float mPeekProgress;

    private void updatePageBack() {
        // Always ours: a page goes over the one under it, the top level over the screen below.
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updatePageBack(); // a recreated screen comes back with its pages
        syncPages(false);
    }

    /** The pages in the container, bottom to top. */
    private java.util.List<Fragment> pages() {
        java.util.List<Fragment> pages = new java.util.ArrayList<>();
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment.getId() == R.id.settings_container && fragment.isAdded() && !fragment.isRemoving()) {
                pages.add(fragment);
            }
        }
        return pages;
    }

    /**
     * Only the top page shows, takes touches and is read by TalkBack; those under it stay loaded
     * but hidden. {@code popped}: a page just went, so the one now on top shows values that may
     * have changed on it (a summary of the page that closed).
     */
    private void syncPages(boolean popped) {
        java.util.List<Fragment> pages = pages();
        for (int i = 0; i < pages.size(); i++) {
            View view = pages.get(i).getView();
            if (view == null) {
                continue;
            }
            boolean top = i == pages.size() - 1;
            if (top) {
                view.animate().cancel();
                view.setVisibility(View.VISIBLE);
                view.setAlpha(1f);
                clearBlur(view);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            } else if (view != mPeekUnder) {
                // Hidden once the page over it has covered it (its slide-in still shows this one).
                view.postDelayed(() -> {
                    if (pages().indexOf(fragmentOf(view)) < pages().size() - 1) {
                        view.setVisibility(View.INVISIBLE);
                    }
                }, 400);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            view.setClickable(true); // a page never passes a touch to the one under it
        }
        if (popped && !pages.isEmpty() && pages.get(pages.size() - 1) instanceof SettingsPageFragment
                && getSupportFragmentManager().getBackStackEntryCount() < mLastDepth) {
            ((SettingsPageFragment) pages.get(pages.size() - 1)).rebuild();
        }
        mLastDepth = getSupportFragmentManager().getBackStackEntryCount();
    }

    private int mLastDepth;
    /** The top level's own fade-out is under way (a second Back just waits for it). */
    private boolean mLeaving;

    @Nullable
    private Fragment fragmentOf(View view) {
        for (Fragment fragment : pages()) {
            if (fragment.getView() == view) {
                return fragment;
            }
        }
        return null;
    }

    private void peekStart() {
        mPeekProgress = 0f;
        java.util.List<Fragment> pages = pages();
        if (pages.size() < 2) {
            // The top level: all of Settings blurs and fades over the screen below.
            mPeekView = findViewById(android.R.id.content);
            mPeekUnder = null;
            if (mPeekView != null) {
                mPeekView.animate().cancel();
            }
            return;
        }
        mPeekView = pages.get(pages.size() - 1).getView();
        mPeekUnder = pages.get(pages.size() - 2).getView();
        if (mPeekView == null || mPeekUnder == null) {
            mPeekView = null;
            return;
        }
        mPeekView.animate().cancel();
        mPeekUnder.setVisibility(View.VISIBLE);
    }

    /** The page blurs and fades with the swipe, the one under it showing through. */
    private void peek(float progress) {
        if (mPeekView == null) {
            return;
        }
        mPeekProgress = progress;
        applyPeek(mPeekView, new DecelerateInterpolator().getInterpolation(progress));
    }

    private void applyPeek(View view, float eased) {
        float density = getResources().getDisplayMetrics().density;
        view.setAlpha(1f - 0.85f * eased);
        float scale = 1f - 0.04f * eased;
        view.setScaleX(scale);
        view.setScaleY(scale);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            float radius = 24 * density * eased;
            view.setRenderEffect(radius < 0.5f ? null : android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, android.graphics.Shader.TileMode.CLAMP));
        }
    }

    private static void clearBlur(View view) {
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(null);
        }
    }

    private void peekCancel() {
        View view = mPeekView;
        View under = mPeekUnder;
        mPeekView = null;
        mPeekUnder = null;
        if (view == null) {
            return;
        }
        float from = new DecelerateInterpolator().getInterpolation(mPeekProgress);
        android.animation.ValueAnimator back = android.animation.ValueAnimator.ofFloat(from, 0f);
        back.setDuration(200);
        back.addUpdateListener(animation -> applyPeek(view, (float) animation.getAnimatedValue()));
        back.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                view.setAlpha(1f);
                clearBlur(view);
                if (under != null) {
                    under.setVisibility(View.INVISIBLE);
                }
            }
        });
        back.start();
    }

    private void handleBack() {
        FragmentManager fragments = getSupportFragmentManager();
        if (fragments.getBackStackEntryCount() == 0) {
            // Settings goes the way its pages do - finishing the blur and fade from where the
            // gesture left it (or all of it, for the back arrow) - then leaves without a slide.
            View content = findViewById(android.R.id.content);
            float from = mPeekView == content ? new DecelerateInterpolator().getInterpolation(mPeekProgress) : 0f;
            mPeekView = null;
            if (content == null || mLeaving) {
                finish();
                return;
            }
            mLeaving = true;
            android.animation.ValueAnimator out = android.animation.ValueAnimator.ofFloat(from, 1f);
            out.setDuration(Math.round(200 * (1f - from)) + 40);
            out.addUpdateListener(animation -> {
                float t = (float) animation.getAnimatedValue();
                applyPeek(content, t);
                content.setAlpha(Math.min(content.getAlpha(), 1f - t));
            });
            out.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    finish();
                    overridePendingTransition(0, 0);
                }
            });
            out.start();
            return;
        }
        java.util.List<Fragment> pages = pages();
        if (pages.size() >= 2 && pages.get(pages.size() - 2).getView() != null) {
            pages.get(pages.size() - 2).getView().setVisibility(View.VISIBLE); // revealed as this one goes
        }
        View view = mPeekView;
        mPeekView = null;
        mPeekUnder = null;
        if (view != null && !pages.isEmpty()) {
            // A released gesture: the page finishes fading out from where the finger left it, and
            // goes without its slide (the gesture was its exit).
            Fragment top = pages.get(pages.size() - 1);
            top.setReturnTransition(null);
            float from = new DecelerateInterpolator().getInterpolation(mPeekProgress);
            android.animation.ValueAnimator out = android.animation.ValueAnimator.ofFloat(from, 1f);
            out.setDuration(Math.round(160 * (1f - from)) + 40);
            out.addUpdateListener(animation -> {
                float t = (float) animation.getAnimatedValue();
                applyPeek(view, t);
                view.setAlpha(Math.min(view.getAlpha(), 1f - t));
            });
            out.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (!fragments.isStateSaved()) {
                        fragments.popBackStack();
                    }
                }
            });
            out.start();
            return;
        }
        fragments.popBackStack();
    }
}
