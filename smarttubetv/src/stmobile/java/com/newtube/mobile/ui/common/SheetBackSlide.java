package com.newtube.mobile.ui.common;

import android.app.Dialog;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * NEWTUBE(pills): the predictive back gesture on a bottom sheet slides it down with the finger,
 * and a committed back slides it the rest of the way off - instead of Material's shrink. Used by
 * the player's sheets (BottomSheetDialog) and the comments panel.
 */
public final class SheetBackSlide {
    /** How far the sheet follows a whole gesture, as a share of its height. */
    private static final float FOLLOW = 0.5f;
    private static final long SETTLE_MS = 200;

    private final View mSheet;
    private float mStart;

    public SheetBackSlide(View sheet) {
        mSheet = sheet;
    }

    public void start() {
        mSheet.animate().cancel();
        mStart = mSheet.getTranslationY();
    }

    /** {@code progress}: the gesture's, 0..1. */
    public void progress(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        mSheet.setTranslationY(mStart + p * FOLLOW * mSheet.getHeight());
    }

    public void cancel() {
        mSheet.animate().translationY(mStart).setDuration(SETTLE_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Committed: down to {@code toY} (off-screen), then {@code end}. */
    public void finish(float toY, @Nullable Runnable end) {
        mSheet.animate().translationY(toY).setDuration(SETTLE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (end != null) {
                        end.run();
                    }
                }).start();
    }

    /**
     * Take the back gesture of {@code dialog}'s sheet (Android 14+, where back is predictive):
     * ahead of Material's own handler, which shrinks it.
     */
    public static void install(Dialog dialog, @Nullable View sheet) {
        if (Build.VERSION.SDK_INT >= 34 && sheet != null) {
            Api34.install(dialog, sheet);
        }
    }

    @RequiresApi(34)
    private static final class Api34 {
        static void install(Dialog dialog, View sheet) {
            SheetBackSlide slide = new SheetBackSlide(sheet);
            OnBackAnimationCallback callback = new OnBackAnimationCallback() {
                @Override
                public void onBackStarted(BackEvent event) {
                    slide.start();
                }

                @Override
                public void onBackProgressed(BackEvent event) {
                    slide.progress(event.getProgress());
                }

                @Override
                public void onBackCancelled() {
                    slide.cancel();
                }

                @Override
                public void onBackInvoked() {
                    View parent = (View) sheet.getParent();
                    float off = parent != null ? parent.getHeight() - sheet.getTop() : sheet.getHeight();
                    slide.finish(Math.max(sheet.getHeight(), off), () -> {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                    });
                }
            };
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
        }
    }
}
