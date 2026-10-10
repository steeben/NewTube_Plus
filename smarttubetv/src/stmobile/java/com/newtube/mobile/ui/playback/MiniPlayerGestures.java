package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MagneticDrag;

/**
 * NEWTUBE(mini-style): every drag on a docked mini player (the card and both bars). It replaces
 * MiniCardSwipe, whose sideways swipe-to-close it keeps (the same magnetic stick-then-click), and
 * adds the rest of the owner's gesture map:
 *
 * <ul>
 *   <li>Up - expand the player. The mini player follows the finger part of the way; let go past
 *   {@link #EXPAND_DP} (or flick up) and it expands from where it is.</li>
 *   <li>Down - close it, the same way as sideways.</li>
 *   <li>Sideways - close it, toward either edge on a bar; on the card only toward the edge it is
 *   docked at. Toward the middle the card follows the finger 1:1 and, past half the way (or
 *   flicked), docks in the other bottom corner.</li>
 * </ul>
 *
 * <p>A drag locks to the axis it starts on. While a closing drag is under the finger the host gets
 * {@link Callback#onCloseProgress} (0 at rest, 1 off-screen), which fades the sound with it, and
 * it comes back if the mini player springs back. Taps are untouched: a drag only claims the touch
 * once it is clearly a drag, and then cancels the pending click.</p>
 */
final class MiniPlayerGestures implements View.OnTouchListener {
    interface Callback {
        /** False while the mini player must not move (closing, entering, no session). */
        boolean canDrag();

        /** The floating card (docks in a corner) rather than a full-width bar. */
        boolean isCard();

        /** The card is docked in the bottom-left corner. */
        boolean isDockedLeft();

        /** Room the card has to travel to the other corner, in px (> 0). */
        float cornerTravelPx();

        /** A closing drag is at {@code fraction} of the way out (0..1): fade the sound with it. */
        void onCloseProgress(float fraction);

        /** Committed: fly to ({@code toX}, {@code toY}) translation over {@code durationMs}, then close. */
        void onClose(float toX, float toY, long durationMs);

        /** Swiped up: expand from where the mini player is now. */
        void onExpand();

        /** The mini player's lift changed ({@code translationY} < 0 while it is pulled up). */
        void onLift(float translationY);

        /** The card reached the other corner (its translation is the whole travel): dock it there. */
        void onDockedOtherSide();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_CLOSE_X = 1;
    private static final int MODE_CLOSE_DOWN = 2;
    private static final int MODE_EXPAND = 3;
    private static final int MODE_MOVE = 4;

    private static final float FLING_VELOCITY_DP = 450f;
    private static final float EXPAND_DP = 48f;
    /** How far the mini player follows the finger up before it expands. */
    private static final float EXPAND_PULL = 0.5f;
    private static final float SNAP_BACK_STIFFNESS = 550f;
    private static final float SNAP_BACK_DAMPING = 0.6f;
    private static final float DOCK_STIFFNESS = 700f;
    private static final float DOCK_DAMPING = 0.85f;
    private static final long FLY_MIN_MS = 110;
    private static final long FLY_MAX_MS = 240;

    private final View mView;
    private final Callback mCallback;
    private final int mTouchSlop;
    private final float mFlingVelocity;
    private final float mExpandPx;
    private final MagneticDrag mMagnetX;
    private final MagneticDrag mMagnetY;
    @Nullable
    private SpringAnimation mSpringX;
    @Nullable
    private SpringAnimation mSpringY;
    private float mDownRawX;
    private float mDownRawY;
    private int mMode = MODE_NONE;
    /** The touch stopped being a drag (a second finger): consume it to the end. */
    private boolean mIgnoring;
    @Nullable
    private VelocityTracker mVelocity;

    /** Returns the gestures, so the host can {@link #stop} them before moving the view itself. */
    static MiniPlayerGestures attach(View view, Callback callback, View... touchTargets) {
        MiniPlayerGestures gestures = new MiniPlayerGestures(view, callback);
        for (View target : touchTargets) {
            if (target != null) {
                target.setOnTouchListener(gestures);
            }
        }
        return gestures;
    }

    private MiniPlayerGestures(View view, Callback callback) {
        mView = view;
        mCallback = callback;
        mTouchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        float density = view.getResources().getDisplayMetrics().density;
        mFlingVelocity = FLING_VELOCITY_DP * density;
        mExpandPx = EXPAND_DP * density;
        mMagnetX = new MagneticDrag(view, position -> {
            mView.setTranslationX(position);
            applyCloseProgress(closeFractionX(position));
        });
        mMagnetY = new MagneticDrag(view, position -> {
            float y = Math.max(0f, position);
            mView.setTranslationY(y);
            applyCloseProgress(closeFractionY(y));
        });
    }

    /** The host is about to move the view itself: stop the springs and ignore the rest of a touch. */
    void stop() {
        if (mMode != MODE_NONE) {
            mMode = MODE_NONE;
            mIgnoring = true;
            recycleVelocity();
        }
        mMagnetX.finish();
        mMagnetY.finish();
        cancelSprings();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mMode = MODE_NONE;
                mIgnoring = false;
                recycleVelocity();
                mVelocity = VelocityTracker.obtain();
                trackVelocity(event);
                return false; // the view still sees the press (ripple, click)
            case MotionEvent.ACTION_MOVE: {
                if (mIgnoring) {
                    return true;
                }
                trackVelocity(event);
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (mMode == MODE_NONE) {
                    if (Math.hypot(dx, dy) <= mTouchSlop || !mCallback.canDrag()) {
                        return false;
                    }
                    mMode = pickMode(dx, dy);
                    claim(view, event);
                }
                drag(dx, dy);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mIgnoring) {
                    mIgnoring = false;
                    return true;
                }
                if (mMode == MODE_NONE) {
                    recycleVelocity();
                    return false;
                }
                trackVelocity(event);
                float velocityX = 0f;
                float velocityY = 0f;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    velocityX = mVelocity.getXVelocity();
                    velocityY = mVelocity.getYVelocity();
                }
                recycleVelocity();
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                drag(dx, dy); // where the finger lifted, which a last MOVE may not have said
                int mode = mMode;
                mMode = MODE_NONE;
                release(mode, dx, dy, velocityX, velocityY);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: not a drag any more. Back to rest; the rest of the touch is ignored.
                if (mMode != MODE_NONE) {
                    mMode = MODE_NONE;
                    mIgnoring = true;
                    recycleVelocity();
                    restAll();
                }
                return mIgnoring;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                mIgnoring = false;
                if (mMode != MODE_NONE) {
                    mMode = MODE_NONE;
                    restAll();
                    return true;
                }
                return false;
            default:
                return mMode != MODE_NONE;
        }
    }

    private int pickMode(float dx, float dy) {
        if (Math.abs(dy) > Math.abs(dx)) {
            return dy > 0f ? MODE_CLOSE_DOWN : MODE_EXPAND;
        }
        if (!mCallback.isCard()) {
            return MODE_CLOSE_X;
        }
        // The card: toward the edge it is docked at closes it, toward the middle moves it.
        boolean outward = mCallback.isDockedLeft() ? dx < 0f : dx > 0f;
        return outward ? MODE_CLOSE_X : MODE_MOVE;
    }

    /** Drop the press and its pending click: this touch is a drag now. */
    private void claim(View view, MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        view.onTouchEvent(cancel);
        cancel.recycle();
        ViewParent parent = mView.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        mView.animate().cancel();
        cancelSprings();
        if (mMode == MODE_CLOSE_X) {
            mMagnetX.start();
        } else if (mMode == MODE_CLOSE_DOWN) {
            mMagnetY.start();
        }
    }

    private void drag(float dx, float dy) {
        switch (mMode) {
            case MODE_CLOSE_X:
                mMagnetX.move(dx);
                break;
            case MODE_CLOSE_DOWN:
                mMagnetY.move(Math.max(0f, dy));
                break;
            case MODE_EXPAND: {
                // A bar follows the finger (the page fills in under it); the card only leans.
                float lift = Math.min(0f, dy) * (mCallback.isCard() ? EXPAND_PULL : 1f);
                mView.setTranslationY(lift);
                mCallback.onLift(lift);
                break;
            }
            case MODE_MOVE: {
                float travel = mCallback.cornerTravelPx();
                float sign = mCallback.isDockedLeft() ? 1f : -1f;
                float along = Math.max(0f, Math.min(travel, dx * sign));
                mView.setTranslationX(along * sign);
                break;
            }
            default:
                break;
        }
    }

    private void release(int mode, float dx, float dy, float velocityX, float velocityY) {
        switch (mode) {
            case MODE_CLOSE_X:
                mMagnetX.finish();
                releaseClose(true, mMagnetX.isDetached(), dx, velocityX);
                break;
            case MODE_CLOSE_DOWN:
                mMagnetY.finish();
                releaseClose(false, mMagnetY.isDetached(), Math.max(0f, dy), velocityY);
                break;
            case MODE_EXPAND: {
                boolean go = (-dy >= mExpandPx && velocityY <= mFlingVelocity) || velocityY < -mFlingVelocity;
                if (go && mCallback.canDrag()) {
                    Haptics.threshold(mView, true);
                    mCallback.onExpand();
                } else {
                    springY(0f, velocityY);
                }
                break;
            }
            case MODE_MOVE: {
                float travel = mCallback.cornerTravelPx();
                float sign = mCallback.isDockedLeft() ? 1f : -1f;
                float along = mView.getTranslationX() * sign;
                float speed = velocityX * sign;
                boolean go = speed > mFlingVelocity || (along > travel / 2f && speed > -mFlingVelocity);
                if (go && mCallback.canDrag()) {
                    dockOtherSide(travel * sign, velocityX);
                } else {
                    springX(0f, velocityX);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Decided on the finger, animated from the view: right after the click the view still trails
     * the finger (its gap springs shut), and a fast swipe let go within a frame of it clicked but
     * read the view's 0 and sprang back.
     */
    private void releaseClose(boolean sideways, boolean detached, float finger, float velocity) {
        boolean flung = Math.abs(velocity) > mFlingVelocity && Math.signum(velocity) == Math.signum(finger);
        boolean flungBack = Math.abs(velocity) > mFlingVelocity && Math.signum(velocity) == -Math.signum(finger);
        if (finger == 0f || flungBack || (!detached && !flung) || !mCallback.canDrag()) {
            if (sideways) {
                springX(0f, velocity);
            } else {
                springY(0f, velocity);
            }
            return;
        }
        if (!detached) {
            Haptics.threshold(mView, true); // flung before it let go: the click it skipped
        }
        float now = sideways ? mView.getTranslationX() : mView.getTranslationY();
        float target;
        if (sideways) {
            target = Math.signum(finger) * mView.getResources().getDisplayMetrics().widthPixels;
        } else {
            target = Math.max(mView.getHeight() * 1.5f, offBottomPx());
        }
        float remaining = Math.abs(target - now);
        float speed = Math.max(Math.abs(velocity), mFlingVelocity * 2f);
        long durationMs = Math.round(1000f * remaining / speed);
        durationMs = Math.max(FLY_MIN_MS, Math.min(FLY_MAX_MS, durationMs));
        mCallback.onClose(sideways ? target : mView.getTranslationX(),
                sideways ? mView.getTranslationY() : target, durationMs);
    }

    private void dockOtherSide(float target, float velocityX) {
        cancelSprings();
        SpringAnimation spring = new SpringAnimation(mView, DynamicAnimation.TRANSLATION_X, target);
        spring.getSpring().setStiffness(DOCK_STIFFNESS).setDampingRatio(DOCK_DAMPING);
        spring.setStartVelocity(velocityX);
        spring.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled) {
                mCallback.onDockedOtherSide();
            }
        });
        mSpringX = spring;
        spring.start();
    }

    private void springX(float to, float velocity) {
        cancelSprings();
        SpringAnimation spring = new SpringAnimation(mView, DynamicAnimation.TRANSLATION_X, to);
        spring.getSpring().setStiffness(SNAP_BACK_STIFFNESS).setDampingRatio(SNAP_BACK_DAMPING);
        spring.setStartVelocity(velocity);
        spring.addUpdateListener((animation, value, v) -> applyCloseProgress(closeFractionX(value)));
        spring.addEndListener((animation, canceled, value, v) -> {
            if (!canceled) {
                applyCloseProgress(0f);
            }
        });
        mSpringX = spring;
        spring.start();
    }

    private void springY(float to, float velocity) {
        cancelSprings();
        SpringAnimation spring = new SpringAnimation(mView, DynamicAnimation.TRANSLATION_Y, to);
        spring.getSpring().setStiffness(SNAP_BACK_STIFFNESS).setDampingRatio(SNAP_BACK_DAMPING);
        spring.setStartVelocity(velocity);
        spring.addUpdateListener((animation, value, v) -> {
            applyCloseProgress(closeFractionY(Math.max(0f, value)));
            mCallback.onLift(value);
        });
        spring.addEndListener((animation, canceled, value, v) -> {
            if (!canceled) {
                applyCloseProgress(0f);
            }
        });
        mSpringY = spring;
        spring.start();
    }

    /** Everything back to rest (a cancelled or broken-off touch). */
    private void restAll() {
        mMagnetX.finish();
        mMagnetY.finish();
        if (mView.getTranslationY() != 0f) {
            springY(0f, 0f);
        }
        if (mView.getTranslationX() != 0f) {
            springX(0f, 0f);
        }
        if (mView.getTranslationX() == 0f && mView.getTranslationY() == 0f) {
            applyCloseProgress(0f);
        }
    }

    private void cancelSprings() {
        if (mSpringX != null) {
            mSpringX.cancel();
            mSpringX = null;
        }
        if (mSpringY != null) {
            mSpringY.cancel();
            mSpringY = null;
        }
    }

    /** The view fades as it goes, and the host fades the sound the same way. */
    private void applyCloseProgress(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        mView.setAlpha(1f - 0.85f * clamped);
        mCallback.onCloseProgress(clamped);
    }

    private float closeFractionX(float x) {
        return Math.abs(x) / (Math.max(1, mView.getWidth()) * 1.2f);
    }

    private float closeFractionY(float y) {
        return y / Math.max(1f, offBottomPx());
    }

    /** How far down the view has to go to be out of sight. */
    private float offBottomPx() {
        View parent = (View) mView.getParent();
        float below = parent != null ? parent.getHeight() - mView.getTop() : mView.getHeight();
        return Math.max(mView.getHeight(), below);
    }

    private void trackVelocity(MotionEvent event) {
        if (mVelocity == null) {
            return;
        }
        // Raw coordinates: the view moves under the finger, so view-local ones would lag.
        MotionEvent raw = MotionEvent.obtain(event);
        raw.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(raw);
        raw.recycle();
    }

    private void recycleVelocity() {
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }
}
