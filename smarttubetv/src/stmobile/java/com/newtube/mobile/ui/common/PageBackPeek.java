package com.newtube.mobile.ui.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.BackEventCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NEWTUBE(predictive-back): a page over the screen it was opened from (a playlist, Search) leaves
 * the way Settings does - it blurs and fades with the back gesture, the screen under it showing
 * through, and a committed back finishes that from where the finger left it. Before this the page
 * stood still through the gesture and faded only after it. Needs a see-through window
 * (Theme.NewTube.Page) so the screen below stays drawn.
 */
public final class PageBackPeek {
    private static final long OUT_MS = 200;
    private static final long CANCEL_MS = 200;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator();

    /** Whether a back now leaves the page (not an in-page step such as closing suggestions). */
    public interface Leaves {
        boolean leaves();
    }

    private final ComponentActivity mActivity;
    private final Leaves mLeaves;
    private final Runnable mBack;
    @Nullable
    private View mView;
    private float mProgress;
    private boolean mLeaving;

    private PageBackPeek(ComponentActivity activity, Leaves leaves, Runnable back) {
        mActivity = activity;
        mLeaves = leaves;
        mBack = back;
    }

    /**
     * {@code back}: what a back does (finish, or an in-page step); when {@code leaves} says it
     * leaves, it runs once the page has faded away.
     */
    public static void install(ComponentActivity activity, Leaves leaves, Runnable back) {
        PageBackPeek peek = new PageBackPeek(activity, leaves, back);
        View content = activity.findViewById(android.R.id.content);
        if (content != null) {
            // The window is see-through: the page colour under the status bar too (the content's
            // inset), not the screen below.
            content.setBackgroundColor(androidx.core.content.ContextCompat.getColor(activity,
                    com.liskovsoft.smartyoutubetv2.tv.R.color.mobile_color_background));
        }
        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                peek.start();
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                peek.progress(backEvent.getProgress());
            }

            @Override
            public void handleOnBackCancelled() {
                peek.cancel();
            }

            @Override
            public void handleOnBackPressed() {
                peek.commit();
            }
        });
    }

    private void start() {
        mProgress = 0f;
        mView = mLeaves.leaves() && !mLeaving ? mActivity.findViewById(android.R.id.content) : null;
        if (mView != null) {
            mView.animate().cancel();
        }
    }

    private void progress(float progress) {
        if (mView == null) {
            return;
        }
        mProgress = progress;
        apply(mView, EASE.getInterpolation(progress));
    }

    private void cancel() {
        View view = mView;
        mView = null;
        if (view == null) {
            return;
        }
        ValueAnimator back = ValueAnimator.ofFloat(EASE.getInterpolation(mProgress), 0f);
        back.setDuration(CANCEL_MS);
        back.addUpdateListener(animation -> apply(view, (float) animation.getAnimatedValue()));
        back.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                reset(view);
            }
        });
        back.start();
    }

    private void commit() {
        View content = mActivity.findViewById(android.R.id.content);
        float from = mView != null && mView == content ? EASE.getInterpolation(mProgress) : 0f;
        mView = null;
        if (!mLeaves.leaves()) {
            if (content != null) {
                reset(content);
            }
            mBack.run();
            return;
        }
        if (content == null || mLeaving) {
            if (!mLeaving) {
                mBack.run();
            }
            return;
        }
        mLeaving = true;
        ValueAnimator out = ValueAnimator.ofFloat(from, 1f);
        out.setDuration(Math.round(OUT_MS * (1f - from)) + 40);
        out.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            apply(content, t);
            content.setAlpha(Math.min(content.getAlpha(), 1f - t));
        });
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBack.run();
                mActivity.overridePendingTransition(0, 0);
                if (!mActivity.isFinishing()) {
                    // Back did not leave after all: the page comes back.
                    mLeaving = false;
                    content.setAlpha(1f);
                    reset(content);
                }
            }
        });
        out.start();
    }

    /** As Settings' page peek: fading, a little smaller, blurring. */
    private void apply(View view, float eased) {
        float density = view.getResources().getDisplayMetrics().density;
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

    private static void reset(View view) {
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(null);
        }
    }
}
