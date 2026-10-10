package com.newtube.mobile.ui.browse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * NEWTUBE(tab-slide): Home's tabs side by side. A tab change slides the page sideways - the one
 * you leave goes out one side while the next comes in from the other, in the bar's order - and a
 * sideways swipe on the page drags it, one tab at a time, like a pager.
 *
 * <p>There is one feed (Home shows a section at a time), so the page coming in is a picture of
 * it: the last time it was on screen (copied from the window as it was left, PixelCopy), or for a
 * tab not shown yet the loading placeholders. The real page replaces the picture as the slide
 * lands - the section it then shows comes back at the scroll it was left at (Home keeps that), so
 * the two match.</p>
 */
final class TabSlider {
    interface Host {
        /** The page's visible views, which move together (the feed, its placeholders, You). */
        List<View> pageViews();

        /** The tabs' ids, in the bar's order. */
        int[] tabIds();

        int selectedTabId();

        /** Change to {@code tabId} at once (the bar and the page). */
        void switchTo(int tabId);

        /**
         * The picture's area in the root: the whole page, including what is under the glass bars
         * (the pictures are of the page alone, without the bars; the live glass blurs them).
         */
        Rect pageRect();

        /** The loading placeholders' view (drawn for a tab that has no picture yet), if any. */
        @Nullable
        View placeholders();

        /** The page colour, under a picture's empty parts. */
        int pageColor();

        /**
         * The page moved (a slide frame): what is behind the glass bars changed. {@code incoming}:
         * the picture sliding in beside the page, or null once there is none.
         */
        void onSlideFrame(@Nullable View incoming);

    }

    private static final long SLIDE_MS = 280;
    private static final float COMMIT_SHARE = 0.3f;
    private static final float FLING_DP = 600f;

    private final Activity mActivity;
    private final ViewGroup mRoot;
    private final Host mHost;
    private final SparseArray<Bitmap> mPictures = new SparseArray<>();
    private final Interpolator mEase = new DecelerateInterpolator(1.6f);
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private ImageView mGhost;
    /** The picture of the tab that just landed, fading out over the real page. */
    @Nullable
    private ImageView mFading;
    /** The tab coming in, and from which side: 1 = from the right (a later tab), -1 = the left. */
    private int mTarget;
    private int mDirection;
    private float mProgress;
    @Nullable
    private ValueAnimator mAnimator;

    TabSlider(Activity activity, ViewGroup root, Host host) {
        mActivity = activity;
        mRoot = root;
        mHost = host;
    }

    boolean isBusy() {
        return mGhost != null;
    }

    /** A tab was tapped: slide to it. */
    void slideTo(int tabId) {
        finishNow();
        int direction = directionTo(tabId);
        if (direction == 0 || !begin(tabId, direction)) {
            mHost.switchTo(tabId);
            return;
        }
        animate(1f, SLIDE_MS);
    }

    // ---------------------------------------------------------------------------------
    // Dragging
    // ---------------------------------------------------------------------------------

    /** A sideways drag of {@code dx} px (positive: the finger went right, toward an earlier tab). */
    void drag(float dx) {
        if (mAnimator != null) {
            return;
        }
        int direction = dx < 0 ? 1 : -1;
        if (mGhost == null || direction != mDirection) {
            int neighbour = neighbour(direction);
            if (mGhost != null) {
                removeGhost();
                resetPage(null);
            }
            if (neighbour == 0 || !begin(neighbour, direction)) {
                // No tab that way: the page gives a little, then stops.
                for (View view : mHost.pageViews()) {
                    view.setTranslationX(dx * 0.15f);
                }
                mHost.onSlideFrame(null);
                return;
            }
        }
        apply(Math.min(1f, Math.abs(dx) / Math.max(1, mRoot.getWidth())));
    }

    /** The finger lifted, at {@code velocityX} px/s. */
    void release(float velocityX) {
        if (mGhost == null) {
            for (View view : mHost.pageViews()) {
                view.animate().translationX(0f).setDuration(200).setInterpolator(mEase)
                        .setUpdateListener(a -> mHost.onSlideFrame(null)).start();
            }
            return;
        }
        float fling = FLING_DP * mActivity.getResources().getDisplayMetrics().density;
        boolean towards = mDirection > 0 ? velocityX < -fling : velocityX > fling;
        boolean away = mDirection > 0 ? velocityX > fling : velocityX < -fling;
        boolean commit = !away && (towards || mProgress > COMMIT_SHARE);
        animate(commit ? 1f : 0f, Math.round(SLIDE_MS * (commit ? 1f - mProgress : mProgress)) + 80);
    }

    // ---------------------------------------------------------------------------------

    private int index(int tabId) {
        int[] ids = mHost.tabIds();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == tabId) {
                return i;
            }
        }
        return -1;
    }

    private int directionTo(int tabId) {
        int from = index(mHost.selectedTabId());
        int to = index(tabId);
        if (from < 0 || to < 0 || from == to) {
            return 0;
        }
        return to > from ? 1 : -1;
    }

    private int neighbour(int direction) {
        int[] ids = mHost.tabIds();
        int at = index(mHost.selectedTabId()) + direction;
        return at >= 0 && at < ids.length && index(mHost.selectedTabId()) >= 0 ? ids[at] : 0;
    }

    /** Copy the page as it is now (its tab's picture), then put the incoming one beside it. */
    private boolean begin(int target, int direction) {
        Rect area = mHost.pageRect();
        if (area.width() <= 0 || area.height() <= 0) {
            return false;
        }
        if (mFading != null) {
            // A tab was tapped again while the last picture is still fading: it is gone now.
            mFading.animate().cancel();
            mRoot.removeView(mFading);
            mFading = null;
        } else {
            copyPage(mHost.selectedTabId(), area);
        }
        mTarget = target;
        mDirection = direction;
        mProgress = 0f;
        ImageView ghost = new ImageView(mActivity);
        ghost.setScaleType(ImageView.ScaleType.FIT_XY);
        ghost.setBackgroundColor(mHost.pageColor());
        Bitmap picture = mPictures.get(target);
        if (picture == null) {
            picture = drawPlaceholders(area);
        }
        if (picture != null) {
            ghost.setImageBitmap(picture);
        }
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(area.width(), area.height());
        // Over the page's views, under the glass bars (which come after them in the root and blur
        // it as they do the page).
        int at = 0;
        for (View view : mHost.pageViews()) {
            at = Math.max(at, mRoot.indexOfChild(view) + 1);
        }

        mRoot.addView(ghost, Math.min(at, mRoot.getChildCount()), params);
        ghost.setX(area.left);
        ghost.setY(area.top);
        mGhost = ghost;
        apply(0f);
        return true;
    }

    private void apply(float progress) {
        mProgress = progress;
        float width = mRoot.getWidth();
        for (View view : mHost.pageViews()) {
            view.setTranslationX(-mDirection * progress * width);
        }
        if (mGhost != null) {
            Rect area = mHost.pageRect();
            mGhost.setX(area.left + mDirection * (1f - progress) * width);
        }
        mHost.onSlideFrame(mGhost);
    }

    private void animate(float to, long durationMs) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mProgress, to);
        animator.setDuration(Math.max(80, durationMs));
        animator.setInterpolator(mEase);
        animator.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAnimator == animation) {
                    mAnimator = null;
                }
                if (!mCancelled) {
                    land(to >= 1f);
                }
            }
        });
        mAnimator = animator;
        animator.start();
    }

    /** The slide ended: on the new tab (the real page takes over from the picture), or back. */
    private void land(boolean arrived) {
        ImageView ghost = mGhost;
        mGhost = null;
        // The glass keeps showing the picture until it has faded: the real page may not be there yet.
        resetPage(arrived ? ghost : null);
        if (!arrived) {
            if (ghost != null) {
                mRoot.removeView(ghost);
            }
            return;
        }
        mHost.switchTo(mTarget);
        if (ghost != null) {
            // The picture stays a moment over the page that replaced it, and fades.
            ghost.setX(mHost.pageRect().left);
            mFading = ghost;
            ghost.animate().alpha(0f).setStartDelay(60).setDuration(160)
                    .setUpdateListener(a -> mHost.onSlideFrame(ghost))
                    .withEndAction(() -> {
                        mRoot.removeView(ghost);
                        if (mFading == ghost) {
                            mFading = null;
                            if (mGhost == null) {
                                mHost.onSlideFrame(null);
                            }
                        }
                    }).start();
        }
    }

    /** A new slide, while one is still going: the old one ends where it was heading. */
    private void finishNow() {
        if (mAnimator != null) {
            ValueAnimator animator = mAnimator;
            mAnimator = null;
            animator.cancel();
            land(true);
        } else if (mGhost != null) {
            removeGhost();
            resetPage(null);
        }
    }

    private void removeGhost() {
        if (mGhost != null) {
            mRoot.removeView(mGhost);
            mGhost = null;
        }
    }

    private void resetPage(@Nullable View incoming) {
        for (View view : mHost.pageViews()) {
            view.animate().cancel();
            view.setTranslationX(0f);
        }
        mHost.onSlideFrame(incoming);
    }

    // ---------------------------------------------------------------------------------
    // Pictures
    // ---------------------------------------------------------------------------------

    /**
     * The page as it is now (without the bars: its views drawn on their own, so nothing of the
     * glass is in it), kept at half size as {@code tabId}'s picture.
     */
    private void copyPage(int tabId, Rect area) {
        if (Build.VERSION.SDK_INT < 29 || tabId == 0) {
            return;
        }
        int width = Math.max(1, area.width() / 2);
        int height = Math.max(1, area.height() / 2);
        try {
            android.graphics.RenderNode node = new android.graphics.RenderNode("tab-picture");
            node.setPosition(0, 0, width, height);
            android.graphics.RecordingCanvas canvas = node.beginRecording(width, height);
            try {
                canvas.scale(0.5f, 0.5f);
                canvas.drawColor(mHost.pageColor());
                for (View view : mHost.pageViews()) {
                    canvas.save();
                    canvas.translate(view.getLeft() - area.left, view.getTop() - area.top);
                    canvas.clipRect(0, 0, view.getWidth(), view.getHeight());
                    view.draw(canvas);
                    canvas.restore();
                }
            } finally {
                node.endRecording();
            }
            if (mPictureRenderer == null) {
                mPictureRenderer = new PictureRenderer();
            }
            Bitmap bitmap = mPictureRenderer.render(node, width, height);
            if (bitmap == null) {
                return;
            }
            Bitmap old = mPictures.get(tabId);
            mPictures.put(tabId, bitmap);
            if (old != null && old != bitmap && (mGhost == null || !isShown(old))) {
                old.recycle();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // No picture this time (the tab comes in as its placeholders).
        }
    }

    @Nullable
    private PictureRenderer mPictureRenderer;

    /** Give back the renderer (the screen is going). */
    void release() {
        if (mPictureRenderer != null) {
            mPictureRenderer.release();
            mPictureRenderer = null;
        }
    }

    /** Draws a RenderNode into a bitmap on the GPU; keeps its renderer between pictures (it is slow to build). */
    @androidx.annotation.RequiresApi(29)
    private static final class PictureRenderer {
        @Nullable
        private android.media.ImageReader mReader;
        @Nullable
        private android.graphics.HardwareRenderer mRenderer;
        private int mWidth;
        private int mHeight;

        @Nullable
        Bitmap render(android.graphics.RenderNode node, int width, int height) {
            if (mReader == null || mWidth != width || mHeight != height) {
                release();
                mReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1,
                        android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                                | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                mRenderer = new android.graphics.HardwareRenderer();
                mRenderer.setSurface(mReader.getSurface());
                mWidth = width;
                mHeight = height;
            }
            mRenderer.setContentRoot(node);
            mRenderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            android.media.Image image = mReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            try {
                Bitmap hardware = Bitmap.wrapHardwareBuffer(image.getHardwareBuffer(),
                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                if (hardware == null) {
                    return null;
                }
                Bitmap copy = hardware.copy(Bitmap.Config.RGB_565, false);
                hardware.recycle();
                return copy;
            } finally {
                image.close();
            }
        }

        void release() {
            if (mRenderer != null) {
                mRenderer.destroy();
                mRenderer = null;
            }
            if (mReader != null) {
                mReader.close();
                mReader = null;
            }
        }
    }

    private boolean isShown(Bitmap bitmap) {
        return mGhost != null && mGhost.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) mGhost.getDrawable()).getBitmap() == bitmap;
    }

    /** The loading placeholders as a picture, for a tab not shown yet. */
    @Nullable
    private Bitmap drawPlaceholders(Rect area) {
        View placeholders = mHost.placeholders();
        if (placeholders == null || placeholders.getWidth() <= 0 || placeholders.getHeight() <= 0) {
            return null;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, area.width() / 2), Math.max(1, area.height() / 2),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(0.5f, 0.5f);
            canvas.drawColor(mHost.pageColor());
            canvas.translate(placeholders.getLeft() - area.left, placeholders.getTop() - area.top);
            placeholders.draw(canvas);
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A tab's picture is out of date (its feed was refreshed or reloaded). */
    void forget(int tabId) {
        mPictures.remove(tabId);
    }
}
