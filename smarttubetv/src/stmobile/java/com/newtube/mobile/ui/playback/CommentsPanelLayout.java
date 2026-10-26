package com.newtube.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import androidx.activity.BackEventCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

/**
 * NEWTUBE(comments-panel): the comments panel's frame - a scrim and a sheet covering the watch page
 * under the video - and all of its motion. Opens with Material's emphasized decelerate (300ms) and
 * closes with emphasized accelerate (200ms, the scrim over 250ms). The sheet follows a finger that
 * drags its header, or pulls down a list that is already at its top (nested scrolling); released
 * past a quarter of its height or on a downward flick it leaves, otherwise it settles back (250ms,
 * standard). Predictive back shrinks it the way Material's own sheets do.
 *
 * <p>NEWTUBE(haptics): a click when a drag carries the sheet past that quarter - letting go now
 * closes it - and the same click if it comes back above; a flick that closes it from above the
 * line clicks as it goes (Haptics.threshold, the Pixel's swipe click).</p>
 *
 * <p>Nothing here knows about comments: {@link CommentsPanel} fills the sheet and decides what a
 * dismissal means.</p>
 */
public class CommentsPanelLayout extends FrameLayout implements NestedScrollingParent3 {

    public interface Callback {
        /** The sheet has started coming up (it covers the watch page from now on). */
        void onPanelOpened();

        /** The sheet has left the screen: a drag, the close button or back finished closing it. */
        void onPanelClosed();
    }

    static final Interpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final Interpolator EMPHASIZED_ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static final float SCRIM_ALPHA = 0.6f;
    private static final long OPEN_MS = 300;
    private static final long CLOSE_MS = 200;
    private static final long SCRIM_OUT_MS = 250;
    private static final long SETTLE_MS = 250;
    private static final long FLICK_CLOSE_MIN_MS = 120;
    private static final float DISMISS_SHARE = 0.25f;
    /** A release faster than this (dp per second, downward) closes the sheet. */
    private static final float FLICK_DP_PER_S = 600f;
    /** ...once it has moved at least this far. */
    private static final float FLICK_MIN_DP = 12f;
    /** NEWTUBE(haptics): how far back above the close line the sheet must come to click again. */
    private static final float HAPTIC_HYSTERESIS_DP = 12f;

    private final NestedScrollingParentHelper mParentHelper = new NestedScrollingParentHelper(this);
    private final int mTouchSlop;
    private final float mDensity;
    private View mScrim;
    private View mSheet;
    private View mHeader;
    @Nullable
    private Callback mCallback;
    @Nullable
    /** NEWTUBE(pills): back slides the sheet down (Material's helper shrank it). */
    private com.newtube.mobile.ui.common.SheetBackSlide mBackHelper;

    private boolean mOpen;
    /** NEWTUBE(haptics): the dragged sheet is past the line where letting go closes it. */
    private boolean mDismissArmed;
    @Nullable
    private ValueAnimator mSheetAnimator;

    // Header drag (one tracked pointer; a second finger taking over is rebased, not a jump)
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean mHeaderDown;
    private boolean mHeaderDragging;
    private float mDownX;
    private float mDownY;
    private float mDragStartOffset;
    @Nullable
    private VelocityTracker mVelocityTracker;

    // List pull (nested scroll): a flick's speed, taken from the list when the sheet was pulled
    private float mNestedFlingDown;

    // Predictive back
    private boolean mBackInProgress;
    /** Material's finishing slide (not ours to cancel) is running; an open waits for it. */
    private boolean mBackFinishing;
    private boolean mOpenAfterBack;

    public CommentsPanelLayout(@NonNull Context context) {
        this(context, null);
    }

    public CommentsPanelLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mDensity = getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mScrim = findViewById(R.id.comments_scrim);
        mSheet = findViewById(R.id.comments_sheet);
        mHeader = findViewById(R.id.comments_header);
        mBackHelper = new com.newtube.mobile.ui.common.SheetBackSlide(mSheet);
    }

    public void setCallback(@Nullable Callback callback) {
        mCallback = callback;
    }

    public boolean isOpen() {
        return mOpen;
    }

    public View getSheet() {
        return mSheet;
    }

    // ---------------------------------------------------------------------------------
    // Open / close
    // ---------------------------------------------------------------------------------

    /** Slide the sheet up over the watch page. */
    public void open() {
        if (mOpen) {
            return;
        }
        if (mBackFinishing) {
            mOpenAfterBack = true;
            return;
        }
        mOpen = true;
        boolean wasShown = getVisibility() == VISIBLE;
        setVisibility(VISIBLE);
        cancelSheetAnimation();
        float from = wasShown ? offset() : sheetRange();
        setOffset(from);
        animateOffset(0f, OPEN_MS, EMPHASIZED_DECELERATE, null);
        if (mCallback != null) {
            mCallback.onPanelOpened();
        }
    }

    /** Slide the sheet away (the close button, back without a gesture, a playlist moving on). */
    public void close() {
        closeFrom(CLOSE_MS);
    }

    /** Gone at once, no motion: minimize, fullscreen, a new video in a hidden window. */
    public void closeImmediately() {
        cancelSheetAnimation();
        cancelBackProgress();
        mOpenAfterBack = false;
        boolean wasOpen = mOpen;
        mOpen = false;
        setVisibility(INVISIBLE);
        setOffset(sheetRange());
        if (wasOpen && mCallback != null) {
            mCallback.onPanelClosed();
        }
    }

    /** Fullscreen / PiP hide the open panel without closing it; {@link #resume()} shows it again. */
    public void suspend() {
        cancelSheetAnimation();
        cancelBackProgress();
        endHeaderDrag();
        setVisibility(INVISIBLE);
    }

    public void resume() {
        if (mOpen) {
            setOffset(0f);
            setVisibility(VISIBLE);
        }
    }

    private void closeFrom(long durationMs) {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        cancelSheetAnimation();
        float scrimFrom = mScrim.getAlpha();
        long scrimMs = Math.max(durationMs, SCRIM_OUT_MS);
        mScrim.animate().cancel();
        mScrim.setAlpha(scrimFrom);
        mScrim.animate().alpha(0f).setStartDelay(0).setDuration(scrimMs).setInterpolator(null).start();
        animateOffset(sheetRange(), durationMs, EMPHASIZED_ACCELERATE, this::onClosedFully);
    }

    private void onClosedFully() {
        mScrim.animate().cancel();
        mScrim.setAlpha(0f);
        setVisibility(INVISIBLE);
        if (mCallback != null) {
            mCallback.onPanelClosed();
        }
    }

    private float sheetRange() {
        int height = getHeight();
        if (height == 0 && getParent() instanceof View) {
            height = ((View) getParent()).getHeight();
        }
        return height > 0 ? height : 2000f * mDensity;
    }

    private float offset() {
        return mSheet.getTranslationY();
    }

    /** Sheet position: 0 = open, {@link #sheetRange()} = off the bottom. The scrim follows. */
    private void setOffset(float y) {
        mSheet.setTranslationY(y);
        float range = sheetRange();
        float shown = range > 0 ? 1f - Math.min(1f, Math.max(0f, y / range)) : 0f;
        mScrim.animate().cancel();
        mScrim.setAlpha(SCRIM_ALPHA * shown);
    }

    private void animateOffset(float to, long durationMs, Interpolator interpolator, @Nullable Runnable end) {
        cancelSheetAnimation();
        float from = offset();
        if (from == to) {
            if (end != null) {
                end.run();
            }
            return;
        }
        boolean closing = !mOpen;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(durationMs);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(a -> {
            float y = (float) a.getAnimatedValue();
            if (closing) {
                // Closing: the scrim runs on its own, longer fade (closeFrom); only move the sheet.
                mSheet.setTranslationY(y);
            } else {
                setOffset(y);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mSheetAnimator == animation) {
                    mSheetAnimator = null;
                }
                if (!mCancelled && end != null) {
                    end.run();
                }
            }
        });
        mSheetAnimator = animator;
        animator.start();
    }

    private void cancelSheetAnimation() {
        if (mSheetAnimator != null) {
            ValueAnimator animator = mSheetAnimator;
            mSheetAnimator = null;
            animator.cancel();
        }
    }

    /**
     * NEWTUBE(haptics): a finger moved the sheet to {@code y}: click as it crosses the line where
     * letting go closes it, either way.
     */
    private void dragTo(float y) {
        setOffset(y);
        // Back above the line by a margin before it disarms, so a finger resting on it never chatters.
        float line = sheetRange() * DISMISS_SHARE;
        boolean armed = mDismissArmed ? y > line - HAPTIC_HYSTERESIS_DP * mDensity : y > line;
        if (armed != mDismissArmed) {
            mDismissArmed = armed;
            Haptics.threshold(this, armed);
        }
    }

    /** A drag let go at {@code y} px moving down at {@code velocity} px/s: leave or settle back. */
    private void release(float y, float velocity) {
        float range = sheetRange();
        boolean flick = velocity > FLICK_DP_PER_S * mDensity && y > FLICK_MIN_DP * mDensity;
        boolean armed = mDismissArmed;
        mDismissArmed = false;
        // Armed = what the finger last felt (the click), dead zone included: that is what it does.
        if (armed || y > range * DISMISS_SHARE || flick) {
            if (!armed) {
                Haptics.threshold(this, true); // flicked away before the line: the click it skipped
            }
            // Carry the finger's speed: the rest of the way at its pace, within 120-200ms.
            long ms = CLOSE_MS;
            if (velocity > 0) {
                ms = Math.max(FLICK_CLOSE_MIN_MS, Math.min(CLOSE_MS, (long) ((range - y) / velocity * 1000f)));
            }
            closeFrom(ms);
        } else if (y > 0f) {
            animateOffset(0f, SETTLE_MS, STANDARD, null);
        }
    }

    // ---------------------------------------------------------------------------------
    // Header drag
    // ---------------------------------------------------------------------------------

    private boolean isInHeader(MotionEvent ev) {
        float top = mSheet.getTop() + mSheet.getTranslationY() + mHeader.getTop();
        return ev.getY() >= top && ev.getY() <= top + mHeader.getHeight();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mOpen) {
            // Sliding away: its rows are not tappable any more (the touch reaches the page below).
            return getVisibility() == VISIBLE;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mHeaderDown = isInHeader(ev) && mSheetAnimator == null && !mBackInProgress;
                mHeaderDragging = false;
                mActivePointerId = ev.getPointerId(0);
                mDownX = ev.getX();
                mDownY = ev.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Two fingers on the header before it moved: not a drag.
                if (!mHeaderDragging) {
                    mHeaderDown = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mHeaderDown && !mHeaderDragging) {
                    int index = ev.findPointerIndex(mActivePointerId);
                    if (index < 0) {
                        break;
                    }
                    float dy = ev.getY(index) - mDownY;
                    float dx = ev.getX(index) - mDownX;
                    if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                        startHeaderDrag(ev, index);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHeaderDown = false;
                break;
        }
        return mHeaderDragging;
    }

    private void startHeaderDrag(MotionEvent ev, int index) {
        mHeaderDragging = true;
        mDownY = ev.getY(index);
        mDragStartOffset = offset();
        mDismissArmed = false;
        mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(ev);
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mHeaderDragging) {
            // Touches that reach the frame itself (the scrim, while the sheet slides) are the
            // panel's: never let them fall through to the watch page underneath.
            return mOpen || super.onTouchEvent(ev);
        }
        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int index = ev.findPointerIndex(mActivePointerId);
                if (index >= 0) {
                    float y = mDragStartOffset + ev.getY(index) - mDownY;
                    dragTo(Math.max(0f, y));
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // The dragging finger lifted while another stays: carry on from where the sheet is.
                int up = ev.getActionIndex();
                if (ev.getPointerId(up) == mActivePointerId) {
                    int next = up == 0 ? 1 : 0;
                    mActivePointerId = ev.getPointerId(next);
                    mDownY = ev.getY(next);
                    mDragStartOffset = offset();
                    if (mVelocityTracker != null) {
                        mVelocityTracker.clear();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float velocity = 0f;
                if (mVelocityTracker != null) {
                    mVelocityTracker.computeCurrentVelocity(1000);
                    velocity = mVelocityTracker.getYVelocity(mActivePointerId);
                }
                endHeaderDrag();
                release(offset(), velocity);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                // Taken away from us (not let go): no decision was made, so the sheet goes back up.
                endHeaderDrag();
                mDismissArmed = false;
                if (offset() > 0f) {
                    animateOffset(0f, SETTLE_MS, STANDARD, null);
                }
                return true;
        }
        return true;
    }

    private void endHeaderDrag() {
        mHeaderDown = false;
        mHeaderDragging = false;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // Pulling a list down from its top (nested scrolling from the RecyclerViews)
    // ---------------------------------------------------------------------------------

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes, int type) {
        return mOpen && type == ViewCompat.TYPE_TOUCH && (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0
                && !mHeaderDragging && !mBackInProgress;
    }

    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes, int type) {
        mParentHelper.onNestedScrollAccepted(child, target, axes, type);
        mNestedFlingDown = 0f;
        mDismissArmed = false;
    }

    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        // Finger moving back up while the sheet is pulled down: raise the sheet before the list scrolls.
        if (type != ViewCompat.TYPE_TOUCH || dy <= 0 || offset() <= 0f || mSheetAnimator != null) {
            return;
        }
        float taken = Math.min(dy, offset());
        dragTo(offset() - taken);
        consumed[1] = Math.round(taken);
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                               int dyUnconsumed, int type, @NonNull int[] consumed) {
        // The list is at its top and the finger keeps pulling down: the sheet follows it.
        if (type != ViewCompat.TYPE_TOUCH || dyUnconsumed >= 0 || mSheetAnimator != null) {
            return;
        }
        dragTo(Math.min(sheetRange(), offset() - dyUnconsumed));
        consumed[1] += dyUnconsumed;
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                               int dyUnconsumed, int type) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, new int[2]);
    }

    @Override
    public boolean onNestedPreFling(@NonNull View target, float velocityX, float velocityY) {
        if (offset() > 0f) {
            // The list would fling from a pulled-down sheet: the flick is the sheet's instead.
            mNestedFlingDown = -velocityY;
            return true;
        }
        return false;
    }

    @Override
    public void onStopNestedScroll(@NonNull View target, int type) {
        mParentHelper.onStopNestedScroll(target, type);
        if (type != ViewCompat.TYPE_TOUCH) {
            return;
        }
        if (offset() > 0f && mSheetAnimator == null && mOpen) {
            release(offset(), mNestedFlingDown);
        }
        mDismissArmed = false;
        mNestedFlingDown = 0f;
    }

    @Override
    public int getNestedScrollAxes() {
        return mParentHelper.getNestedScrollAxes();
    }

    // ---------------------------------------------------------------------------------
    // Predictive back (the list page only; a replies page goes back to the list instead)
    // ---------------------------------------------------------------------------------

    public void startBackProgress(@NonNull BackEventCompat event) {
        if (!mOpen || mBackHelper == null || mSheetAnimator != null) {
            return;
        }
        mBackInProgress = true;
        mBackHelper.start();
    }

    public void updateBackProgress(@NonNull BackEventCompat event) {
        if (mBackInProgress && mBackHelper != null) {
            mBackHelper.progress(event.getProgress());
        }
    }

    public void cancelBackProgress() {
        if (mBackInProgress && mBackHelper != null) {
            mBackInProgress = false;
            mBackHelper.cancel();
        }
    }

    /** Back committed: finish the shrink the gesture started, or close normally without one. */
    public void handleBack(@Nullable BackEventCompat lastEvent) {
        if (!mBackInProgress || mBackHelper == null || lastEvent == null) {
            mBackInProgress = false;
            close();
            return;
        }
        mBackInProgress = false;
        mOpen = false;
        mBackFinishing = true;
        mScrim.animate().cancel();
        mScrim.animate().alpha(0f).setStartDelay(0).setDuration(SCRIM_OUT_MS).setInterpolator(null).start();
        mBackHelper.finish(sheetRange(), new Runnable() {
            @Override
            public void run() {
                mBackFinishing = false;
                mSheet.setTranslationY(sheetRange());
                if (!mOpen) {
                    onClosedFully();
                }
                if (mOpenAfterBack) {
                    // The comments entry was tapped while the sheet was still leaving.
                    mOpenAfterBack = false;
                    open();
                }
            }
        });
    }
}
