package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(liquid): the Liquid glass navigation pill's selected tab, ported from BitChord Dial's
 * floating tab bar (ui/components/floatingtabbar: GlassSelectionPill.kt's GlassPillMotion and
 * PillSquashModel, and FloatingTabBar.kt's drag). That motion is itself ported from
 * liquid_glass_easy's LiquidGlassAnimatedNavBar (pub.dev/packages/liquid_glass_easy, Copyright (c)
 * 2025 Ahmed Gamil, MIT License).
 *
 * <p>The selected tab sits on a soft pill. Choosing another tab lifts it: it swells past the bar
 * on two under-damped springs, turns to a lens (refracting the bar and its icons, with a rim of
 * light), travels on its own spring - stretching as it launches and squashing as it brakes - and
 * settles back to the flat pill on landing. Pressing the selected tab lifts it too, and a
 * sideways drag on the bar carries it under the finger; let go and it snaps to the nearest tab,
 * which is then chosen. The lens needs Android 13 (12 draws it clear); before 12 the flat pill
 * moves alone.</p>
 */
public final class LiquidNav {
    /** How much taller than the bar the lifted pill stands. */
    private static final float GROW_HEIGHT_DP = 9f;
    /** The resting pill's inset in its tab's cell. */
    private static final float INSET_X_DP = 6f;
    private static final float INSET_Y_DP = 5f;
    private static final float LENS_HEIGHT_DP = 12f;
    private static final float LENS_AMOUNT_DP = 14f;

    private final BottomNavigationView mNav;
    private final ViewGroup mRoot;
    @Nullable
    private final BackdropBlurDrawable.Layers mLayers;
    private final int mPageColor;
    private final int mFillColor;
    private final float mDensity;
    private final Motion mMotion = new Motion();
    private final FlatPill mFlat = new FlatPill();
    @Nullable
    private final LensPill mLens;

    public LiquidNav(BottomNavigationView nav, ViewGroup root, @Nullable BackdropBlurDrawable.Layers layers,
                     int pageColor) {
        mNav = nav;
        mRoot = root;
        mLayers = layers;
        mPageColor = pageColor;
        mDensity = nav.getResources().getDisplayMetrics().density;
        // BitChord's selected-tab fill: white in both themes, half in the light one, faint in the dark.
        mFillColor = ThemeMode.isLight(nav.getContext()) ? 0x80FFFFFF : 0x24FFFFFF;
        mLens = Build.VERSION.SDK_INT >= 31 ? new LensPill() : null;
        if (mLens != null) {
            root.getOverlay().add(mLens);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> mLens.setBounds(0, 0, r - l, b - t));
            mLens.setBounds(0, 0, root.getWidth(), root.getHeight());
        }
        // The selection is followed, not told: a tap, a swipe between pages or the code all end
        // in the bar's own selected item, which is checked before each frame.
        nav.getViewTreeObserver().addOnPreDrawListener(() -> {
            followSelection();
            return true;
        });
    }

    /** The flat pill, for the navigation's background: between its glass and its rim. */
    public Drawable flatPill() {
        return mFlat;
    }

    // ---------------------------------------------------------------------------------
    // The tabs
    // ---------------------------------------------------------------------------------

    @Nullable
    private ViewGroup menuView() {
        for (int i = 0; i < mNav.getChildCount(); i++) {
            View child = mNav.getChildAt(i);
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    private final List<View> mItems = new ArrayList<>();

    private List<View> items() {
        mItems.clear();
        ViewGroup menu = menuView();
        if (menu != null) {
            for (int i = 0; i < menu.getChildCount(); i++) {
                View item = menu.getChildAt(i);
                if (item.getVisibility() == View.VISIBLE && item.getWidth() > 0) {
                    mItems.add(item);
                }
            }
        }
        return mItems;
    }

    /** A tab's centre in the bar, for a position in tab steps (past the ends, a step further on). */
    private float centerAt(float position) {
        List<View> items = items();
        int count = items.size();
        if (count == 0) {
            return 0f;
        }
        ViewGroup menu = menuView();
        float origin = menu != null ? menu.getLeft() : 0f;
        if (count == 1) {
            return origin + center(items.get(0));
        }
        int low = (int) Math.floor(position);
        low = Math.max(0, Math.min(count - 2, low));
        float a = center(items.get(low));
        float b = center(items.get(low + 1));
        return origin + a + (b - a) * (position - low);
    }

    private static float center(View item) {
        return item.getLeft() + item.getWidth() / 2f;
    }

    private float step() {
        List<View> items = items();
        if (items.size() < 2) {
            return items.isEmpty() ? 0f : items.get(0).getWidth();
        }
        return center(items.get(1)) - center(items.get(0));
    }

    private float cellWidth() {
        List<View> items = items();
        return items.isEmpty() ? 0f : items.get(0).getWidth();
    }

    private int selectedIndex() {
        Menu menu = mNav.getMenu();
        int selected = mNav.getSelectedItemId();
        int index = 0;
        for (int i = 0; i < menu.size(); i++) {
            if (!menu.getItem(i).isVisible()) {
                continue;
            }
            if (menu.getItem(i).getItemId() == selected) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private boolean mPlaced;

    private void followSelection() {
        int index = selectedIndex();
        if (index < 0 || items().isEmpty() || mDragging) {
            return;
        }
        if (!mPlaced) {
            mPlaced = true;
            mMotion.snapTo(index);
            invalidate();
        } else if (index != mMotion.index) {
            mMotion.animateTo(index);
        }
    }

    /** The pill's rectangle now, in the bar's coordinates. */
    private final android.graphics.RectF mPillRect = new android.graphics.RectF();

    private boolean pillRect() {
        float cell = cellWidth();
        int barHeight = mNav.getHeight();
        if (cell <= 0f || barHeight <= 0) {
            return false;
        }
        float restW = cell - 2 * INSET_X_DP * mDensity;
        float restH = barHeight - 2 * INSET_Y_DP * mDensity;
        float liftH = barHeight + GROW_HEIGHT_DP * mDensity;
        float liftW = liftH * restW / restH;
        float w = (restW + (liftW - restW) * mMotion.liftX) * (1f + mMotion.deviation);
        float h = (restH + (liftH - restH) * mMotion.liftY) * (1f - mMotion.deviation);
        float cx = centerAt(mMotion.position);
        float cy = barHeight / 2f;
        mPillRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        return true;
    }

    private void invalidate() {
        mNav.invalidate();
        if (mLens != null) {
            mLens.invalidateSelf();
        }
    }

    // ---------------------------------------------------------------------------------
    // The finger (fed from the activity's dispatchTouchEvent, in window coordinates)
    // ---------------------------------------------------------------------------------

    private boolean mTracking;
    private boolean mDragging;
    private boolean mHeld;
    private float mDownX;
    private float mDownY;
    private float mTotalDrag;
    private int mStartIndex;
    private int mLastTick;
    private final int[] mNavAt = new int[2];

    /** True while the bar's pill has the touch (the bar's tabs had it cancelled). */
    public boolean onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mTracking = false;
                mDragging = false;
                mHeld = false;
                if (mNav.getVisibility() != View.VISIBLE || mNav.getTranslationY() != 0f) {
                    return false;
                }
                mNav.getLocationInWindow(mNavAt);
                float x = event.getX() - mNavAt[0];
                float y = event.getY() - mNavAt[1];
                if (x < 0 || y < 0 || x > mNav.getWidth() || y > mNav.getHeight() || step() <= 0f) {
                    return false;
                }
                int index = selectedIndex();
                if (index < 0) {
                    return false;
                }
                mTracking = true;
                mDownX = event.getX();
                mDownY = event.getY();
                mTotalDrag = 0f;
                mStartIndex = index;
                mLastTick = index;
                // A press on the selected tab picks the pill up where it stands.
                if (pillRect() && x >= mPillRect.left && x <= mPillRect.right) {
                    mHeld = true;
                    mMotion.startDrag();
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mTracking) {
                    return false;
                }
                float dx = event.getX() - mDownX;
                float dy = event.getY() - mDownY;
                if (!mDragging) {
                    int slop = ViewConfiguration.get(mNav.getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        mDragging = true;
                        if (!mHeld) {
                            mMotion.startDrag();
                        }
                        cancelTabs(event);
                    } else {
                        return false;
                    }
                }
                mTotalDrag = dx;
                float stepPx = step();
                int last = items().size() - 1;
                float offset = mTotalDrag;
                if ((offset > 0 && mStartIndex == last) || (offset < 0 && mStartIndex == 0)) {
                    offset *= 0.25f; // resistance past the ends
                }
                float position = mStartIndex + offset / stepPx;
                mMotion.dragTo(position);
                int near = Math.round(Math.max(0f, Math.min(last, position)));
                if (near != mLastTick) {
                    mLastTick = near;
                    mNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean dragged = mDragging;
                boolean wasTracking = mTracking;
                mTracking = false;
                mDragging = false;
                if (!wasTracking) {
                    return false;
                }
                if (!dragged) {
                    if (mHeld) {
                        mHeld = false;
                        mMotion.release(mStartIndex); // set down where it was; the tap goes through
                    }
                    return false;
                }
                mHeld = false;
                float ratio = mTotalDrag / step();
                int shift = 0;
                if (ratio > 0.35f) {
                    shift = Math.max(1, Math.round(ratio));
                } else if (ratio < -0.35f) {
                    shift = Math.min(-1, Math.round(ratio));
                }
                int last = items().size() - 1;
                int next = Math.max(0, Math.min(last, mStartIndex + shift));
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    next = mStartIndex;
                }
                mMotion.release(next);
                if (next != mStartIndex) {
                    selectIndex(next);
                }
                return true;
            }
            default:
                return mDragging;
        }
    }

    private void cancelTabs(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        cancel.offsetLocation(-mNavAt[0], -mNavAt[1]);
        mNav.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private void selectIndex(int index) {
        Menu menu = mNav.getMenu();
        int visible = 0;
        for (int i = 0; i < menu.size(); i++) {
            if (!menu.getItem(i).isVisible()) {
                continue;
            }
            if (visible == index) {
                mNav.setSelectedItemId(menu.getItem(i).getItemId());
                return;
            }
            visible++;
        }
    }

    // ---------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------

    /** The resting pill: a fill and nothing else, under the tabs (the bar's background). */
    private final class FlatPill extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mLens != null && !mMotion.isFlat) {
                return; // the lens draws it while it is lifted
            }
            if (!mPlaced || !pillRect()) {
                return;
            }
            mPaint.setColor(mFillColor);
            float r = Math.min(mPillRect.width(), mPillRect.height()) / 2f;
            canvas.drawRoundRect(mPillRect, r, r, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The lifted pill: a lens over the bar (the root's overlay, so it can stand past the bar's
     * edges), refracting what is under it - the page, the bar's glass, the selection fill fading
     * out as the glass comes on, and the tab row - with a rim of light shed first on landing.
     */
    @RequiresApi(31)
    private final class LensPill extends Drawable {
        private final RenderNode mNode = new RenderNode("newtube-liquid-pill");
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mClip = new Path();
        private final Path mNavClip = new Path();
        private final int[] mAt = new int[2];
        private final int[] mRootAt = new int[2];
        private final List<View> mLayerViews = new ArrayList<>();

        LensPill() {
            mRim.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (mMotion.isFlat || !mPlaced || !canvas.isHardwareAccelerated() || !mNav.isShown() || !pillRect()) {
                return;
            }
            mRoot.getLocationInWindow(mRootAt);
            mNav.getLocationInWindow(mNavAt);
            // The pill in the root's coordinates.
            float navX = mNavAt[0] - mRootAt[0];
            float navY = mNavAt[1] - mRootAt[1];
            float left = navX + mPillRect.left;
            float top = navY + mPillRect.top;
            int width = Math.max(1, Math.round(mPillRect.width()));
            int height = Math.max(1, Math.round(mPillRect.height()));
            float presence = mMotion.glassPresence;
            float lensHeight = LENS_HEIGHT_DP * mDensity * presence;
            int pad = Math.round(LENS_HEIGHT_DP * mDensity);

            mNode.setPosition(0, 0, width + 2 * pad, height + 2 * pad);
            RecordingCanvas recording = mNode.beginRecording(width + 2 * pad, height + 2 * pad);
            try {
                // The recording's origin is the pill's top-left less the padding.
                recording.translate(pad - left, pad - top);
                recording.drawColor(mPageColor);
                // 1) The page.
                if (mLayers != null) {
                    mLayerViews.clear();
                    mLayers.collect(mLayerViews);
                    for (View layer : mLayerViews) {
                        if (layer.getWidth() <= 0 || !layer.isAttachedToWindow() || layer.getAlpha() <= 0f) {
                            continue;
                        }
                        layer.getLocationInWindow(mAt);
                        int save = recording.save();
                        recording.translate(mAt[0] - mRootAt[0], mAt[1] - mRootAt[1]);
                        recording.clipRect(0, 0, layer.getWidth(), layer.getHeight());
                        layer.draw(recording);
                        recording.restoreToCount(save);
                    }
                    mLayerViews.clear();
                }
                // 2) The bar, inside its own pill: its glass, the fading fill, its tabs.
                int save = recording.save();
                recording.translate(navX, navY);
                mNavClip.reset();
                float navR = mNav.getHeight() / 2f;
                mNavClip.addRoundRect(0, 0, mNav.getWidth(), mNav.getHeight(), navR, navR, Path.Direction.CW);
                recording.clipPath(mNavClip);
                Drawable background = mNav.getBackground();
                if (background != null) {
                    background.draw(recording);
                }
                recording.restoreToCount(save);
                int fillAlpha = Math.round(android.graphics.Color.alpha(mFillColor) * (1f - mMotion.material));
                if (fillAlpha > 0) {
                    mFill.setColor(mFillColor);
                    mFill.setAlpha(fillAlpha);
                    recording.drawRect(left - pad, top - pad, left + width + pad, top + height + pad, mFill);
                }
                ViewGroup menu = menuView();
                if (menu != null) {
                    save = recording.save();
                    recording.translate(navX + menu.getLeft(), navY + menu.getTop());
                    menu.draw(recording);
                    recording.restoreToCount(save);
                }
            } finally {
                mNode.endRecording();
            }
            Object lens = LiquidGlass.lensOnly(width, height, pad, lensHeight,
                    LENS_AMOUNT_DP * mDensity * mMotion.material);
            mNode.setRenderEffect((RenderEffect) lens);

            int save = canvas.save();
            float r = Math.min(width, height) / 2f;
            mClip.reset();
            mClip.addRoundRect(left, top, left + width, top + height, r, r, Path.Direction.CW);
            canvas.clipPath(mClip);
            canvas.translate(left - pad, top - pad);
            canvas.drawRenderNode(mNode);
            canvas.restoreToCount(save);
            // The rim (and in BitChord a contact shadow) go first on landing.
            float rim = mMotion.material * Math.max(0f, Math.min(1f, (presence - 0.45f) / 0.55f));
            LiquidGlass.drawRim(canvas, mRim, left, top, left + width, top + height,
                    (float) Math.ceil(LiquidGlass.RIM_DP * mDensity) * 2f, rim);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------------------------------------------------------------------------
    // The motion (GlassPillMotion), frame by frame
    // ---------------------------------------------------------------------------------

    private static final double TRAVEL_STIFFNESS = 280.0;
    private static final double TRAVEL_DAMPING = 31.4;
    /** Damping ratio 0.6 across, 0.7 down, so width overshoots a shade further. */
    private static final double LIFT_STIFFNESS = 250.0;
    private static final double LIFT_DAMPING_X = 19.0;
    private static final double LIFT_DAMPING_Y = 22.1;
    /** Critically damped and four times as stiff: the glass is on long before the size settles. */
    private static final double MATERIAL_STIFFNESS = 1000.0;
    private static final double MATERIAL_DAMPING = 63.3;
    private static final double HANDOVER_START = 0.92;
    private static final double HANDOVER_TAU = 0.09;
    private static final double GLASS_RETURN_TAU = 0.05;
    private static final double FOLLOW_TAU = 0.05;
    private static final double SIGN_TAU = 0.25;

    private final class Motion implements Choreographer.FrameCallback {
        /** Where the pill is drawn, in tab steps. */
        float position;
        float liftX;
        float liftY;
        float material;
        float deviation;
        float glassPresence;
        boolean isFlat = true;
        int index = -1;

        private double travelPos;
        private double travelVel;
        private double travelTarget;
        private double travelFrom;
        private boolean travelActive;
        private boolean lifted;
        private double liftXPos;
        private double liftXVel;
        private double liftYPos;
        private double liftYVel;
        private double liftPos;
        private double liftVel;
        private boolean dragging;
        private double dragTarget;
        private double dragFollow;
        private double travelSign;
        private double travelSignEased;
        private double handover = 1.0;
        private double squash;
        private final SquashModel squashModel = new SquashModel();
        private boolean running;
        private long lastFrame = -1;

        void animateTo(int next) {
            if (next == index) {
                return;
            }
            index = next;
            travelActive = true;
            lifted = true;
            travelFrom = travelPos;
            travelTarget = next;
            travelSign = signOfTravel(travelTarget - travelFrom);
            isFlat = false;
            start();
        }

        void snapTo(int target) {
            index = target;
            travelPos = target;
            travelTarget = target;
            travelFrom = target;
            travelVel = 0;
            travelActive = false;
            dragging = false;
            lifted = false;
            liftXPos = 0;
            liftXVel = 0;
            liftYPos = 0;
            liftYVel = 0;
            liftPos = 0;
            liftVel = 0;
            handover = 1.0;
            squash = 0;
            squashModel.stop();
            travelSign = 0;
            travelSignEased = 0;
            publish();
            isFlat = true;
        }

        void startDrag() {
            dragging = true;
            travelActive = false;
            lifted = true;
            travelSign = 0;
            dragFollow = travelPos;
            dragTarget = travelPos;
            travelVel = 0;
            isFlat = false;
            start();
        }

        void dragTo(float target) {
            if (dragging) {
                dragTarget = target;
            }
        }

        void release(int next) {
            if (!dragging) {
                return;
            }
            double from = dragFollow;
            dragging = false;
            index = next;
            travelActive = true;
            travelPos = from;
            travelVel = 0;
            travelFrom = from;
            travelTarget = next;
            travelSign = signOfTravel(travelTarget - travelFrom);
            start();
        }

        private void start() {
            if (running) {
                return;
            }
            running = true;
            lastFrame = -1;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            double dt = lastFrame < 0 ? 0.0 : (frameTimeNanos - lastFrame) / 1e9;
            lastFrame = frameTimeNanos;
            boolean more = tick(Math.min(dt, 0.1), frameTimeNanos / 1e9);
            invalidate();
            if (more) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                running = false;
            }
        }

        private boolean tick(double dt, double now) {
            // 1) Travel.
            boolean travelSettled = true;
            if (travelActive) {
                double[] r = springStep(travelPos, travelVel, travelTarget, dt, TRAVEL_STIFFNESS, TRAVEL_DAMPING);
                travelPos = r[0];
                travelVel = r[1];
                travelSettled = Math.abs(travelPos - travelTarget) < 0.003 && Math.abs(travelVel) < 0.05;
                if (travelSettled) {
                    travelPos = travelTarget;
                    travelVel = 0;
                }
            }
            // 2) Under a finger, chase it smoothly.
            if (dragging) {
                dragFollow += (dragTarget - dragFollow) * (1 - Math.exp(-dt / FOLLOW_TAU));
            }
            // 3) The lift, held for the journey and released on landing.
            if (!dragging && (travelSettled || travelProgress() >= HANDOVER_START)) {
                lifted = false;
            }
            double liftTarget = lifted ? 1.0 : 0.0;
            double[] x = stepLift(liftXPos, liftXVel, liftTarget, dt, LIFT_STIFFNESS, LIFT_DAMPING_X);
            liftXPos = x[0];
            liftXVel = x[1];
            double[] y = stepLift(liftYPos, liftYVel, liftTarget, dt, LIFT_STIFFNESS, LIFT_DAMPING_Y);
            liftYPos = y[0];
            liftYVel = y[1];
            double[] m = stepLift(liftPos, liftVel, liftTarget, dt, MATERIAL_STIFFNESS, MATERIAL_DAMPING);
            liftPos = m[0];
            liftVel = m[1];
            boolean liftSettled = !lifted && liftXPos == 0.0 && liftYPos == 0.0 && liftPos == 0.0;
            // 4) The deflation outlives the spring that carried the pill there.
            if (travelActive && travelSettled && liftSettled && !dragging) {
                travelActive = false;
            }
            // 5) Sample the pill where it is drawn this frame.
            double frac = dragging ? dragFollow : travelPos;
            float stepDp = step() / mDensity;
            if (stepDp > 0f) {
                if (!squashModel.tracking) {
                    squashModel.start();
                }
                squash = squashModel.track(frac * stepDp, now, dt);
                if (travelSignEased == 0.0) {
                    travelSignEased = travelSign;
                } else if (travelSignEased != travelSign) {
                    travelSignEased += (travelSign - travelSignEased) * (1 - Math.exp(-dt / SIGN_TAU));
                    if (Math.abs(travelSign - travelSignEased) < 0.01) {
                        travelSignEased = travelSign;
                    }
                }
                // Keep the force's magnitude, with the sign of the travel: one way the whole trip.
                double key = travelSignEased;
                if (key != 0.0) {
                    squash = squash * (1 - Math.abs(key)) - key * Math.abs(squash);
                }
            }
            // 6) The hand-off rides the same landing as the deflation.
            double handoverTarget = lifted ? 0.0 : 1.0;
            double tau = handoverTarget > handover ? HANDOVER_TAU : GLASS_RETURN_TAU;
            handover += (handoverTarget - handover) * (1 - Math.exp(-dt / tau));
            if (Math.abs(handoverTarget - handover) < 0.002) {
                handover = handoverTarget;
            }
            boolean settled = !travelActive && !dragging && liftSettled && Math.abs(squash) < 0.0005 && handover >= 1.0;
            if (settled) {
                squashModel.stop();
                squash = 0;
                travelSign = 0;
                travelSignEased = 0;
            }
            publish();
            isFlat = settled;
            return !settled;
        }

        private void publish() {
            position = (float) (dragging ? dragFollow : travelPos);
            liftX = (float) liftXPos;
            liftY = (float) liftYPos;
            material = (float) Math.max(0.0, Math.min(1.0, liftPos));
            deviation = (float) squash;
            glassPresence = (float) Math.max(0.0, Math.min(1.0, 1 - handover));
        }

        private double travelProgress() {
            double span = Math.abs(travelTarget - travelFrom);
            if (span < 1e-6) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, 1 - Math.abs(travelTarget - travelPos) / span));
        }
    }

    private static double signOfTravel(double span) {
        return Math.abs(span) < 1e-6 ? 0.0 : Math.signum(span);
    }

    /** Semi-implicit Euler in 1/240 s substeps, so a long frame cannot blow it up. */
    private static double[] springStep(double x, double vel, double target, double dt, double stiffness, double damping) {
        double t = dt;
        double px = x;
        double pv = vel;
        while (t > 0) {
            double step = t > 1 / 240.0 ? 1 / 240.0 : t;
            double accel = -stiffness * (px - target) - damping * pv;
            pv += accel * step;
            px += pv * step;
            t -= step;
        }
        return new double[] {px, pv};
    }

    /** A spring step snapped to its target once it has nothing left to say. */
    private static double[] stepLift(double x, double vel, double target, double dt, double stiffness, double damping) {
        double[] r = springStep(x, vel, target, dt, stiffness, damping);
        if (Math.abs(r[0] - target) < 0.0008 && Math.abs(r[1]) < 0.01) {
            return new double[] {target, 0.0};
        }
        return r;
    }

    /**
     * Acceleration to deformation (PillSquashModel): the pill's position over a short window,
     * differentiated twice and averaged, scaled and clamped, then eased. Tuned in dp/s^2.
     */
    private static final class SquashModel {
        private static final double SAMPLE_WINDOW = 0.3;
        private static final double SENSITIVITY = 0.00007;
        /** +-12%: the pill travels inside the bar, and more would climb out of it. */
        private static final double MAX_DEFORMATION = 0.12;
        private static final double RESPONSE_TIME = 0.18;

        private final ArrayList<double[]> history = new ArrayList<>();
        boolean tracking;
        private double value;

        void start() {
            history.clear();
            value = 0;
            tracking = true;
        }

        void stop() {
            tracking = false;
            history.clear();
            value = 0;
        }

        double track(double x, double now, double dt) {
            if (!tracking) {
                return value;
            }
            history.add(new double[] {x, now});
            double cutoff = now - SAMPLE_WINDOW;
            while (!history.isEmpty() && history.get(0)[1] < cutoff) {
                history.remove(0);
            }
            double raw = Math.max(-MAX_DEFORMATION, Math.min(MAX_DEFORMATION, averageAcceleration() * SENSITIVITY));
            double ease = Math.max(0.0, Math.min(1.0, dt / RESPONSE_TIME));
            value += (raw - value) * ease;
            return value;
        }

        private double averageAcceleration() {
            if (history.size() < 3) {
                return 0;
            }
            ArrayList<double[]> velocities = new ArrayList<>(history.size());
            for (int i = 1; i < history.size(); i++) {
                double dt = history.get(i)[1] - history.get(i - 1)[1];
                if (dt <= 0) {
                    continue;
                }
                velocities.add(new double[] {(history.get(i)[0] - history.get(i - 1)[0]) / dt,
                        (history.get(i)[1] + history.get(i - 1)[1]) / 2});
            }
            if (velocities.size() < 2) {
                return 0;
            }
            double total = 0;
            int count = 0;
            for (int i = 1; i < velocities.size(); i++) {
                double dt = velocities.get(i)[1] - velocities.get(i - 1)[1];
                if (dt <= 0) {
                    continue;
                }
                total += (velocities.get(i)[0] - velocities.get(i - 1)[0]) / dt;
                count++;
            }
            return count == 0 ? 0 : total / count;
        }
    }
}
