package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.FrameGate;
import com.newtube.mobile.ui.common.Motion;

/**
 * Renders an active {@link MiniPlayerBridge} session inside a screen's mini player: the floating
 * card or, NEWTUBE(mini-style), the gesture-only card or one of the full-width bars (see
 * {@link MiniPlayerStyle}). Home, Search and both channel screens all use this one controller over
 * mobile_mini_player_overlay.xml.
 *
 * <p>The playback Activity still owns both ExoPlayer and its session-long SurfaceTexture. This
 * controller only adopts that texture into a temporary TextureView while its host Activity is in
 * front, then returns it before the host pauses, so the surface ownership rules are the same on
 * every screen.</p>
 */
public final class MobileMiniPlayerController {
    private static final long TICK_MS = 500;
    private static final long ENTRY_DURATION_MS = 280;
    /** NEWTUBE(motion): the longest the expanding player waits for this card to fold. */
    private static final long FOLD_WAIT_MS = 50;
    /** NEWTUBE(mini-style): the sound's fade when the mini player is closed (X, or past a swipe). */
    private static final long VOLUME_FADE_MIN_MS = 160;
    /** NEWTUBE(mini-style): how often the frosted glass takes a new picture of the video. */
    private static final long FROST_INTERVAL_MS = 80;

    private final Activity mActivity;
    /** NEWTUBE(mini-style): the mini player's views and how each style lays them out. */
    private final MiniPlayerViews mViews;
    private final View mBar;
    /** NEWTUBE(mini-style): every drag on the mini player (close, expand, change corner). */
    private final MiniPlayerGestures mGestures;
    private final FrameLayout mFrame;
    private final ImageView mFreeze;
    private final ImageButton mPlayPause;
    private final ImageButton mClose;
    private final ProgressBar mProgress;
    private final View mDim;
    private final TextView mTitle;
    private final TextView mChannel;
    private final ImageView mFlash;
    /** NEWTUBE(mini-style): the style the views are laid out for; -1 before the first. */
    private int mStyle = -1;
    /** NEWTUBE(mini-style): the card is laid out in the bottom-left corner. */
    private boolean mCardLeft;
    private final Runnable mTick = this::onTick;

    private TextureView mTexture;
    private boolean mEntryAnimating;
    private boolean mTextureHasFrame;
    /** NEWTUBE(motion): pre-drawn for a minimize; the buttons fade in once the host is in front. */
    private boolean mButtonsFadePending;
    private final MiniPlayerBridge.CardFold mCardFold = this::foldForPlayer;

    /** NEWTUBE(mini-style): the volume when a closing drag began; -1 when none is fading it. */
    private float mGestureVolume = -1f;
    @Nullable
    private ValueAnimator mVolumeFade;

    private long mFrostAt;

    /** NEWTUBE(mini-style): the gesture card's TalkBack actions (no buttons to reach). */
    private int mExpandActionId = View.NO_ID;
    private int mCloseActionId = View.NO_ID;

    public MobileMiniPlayerController(Activity activity) {
        mActivity = activity;
        mViews = new MiniPlayerViews(activity.findViewById(R.id.mobile_mini_player));
        mBar = mViews.card;
        mFrame = mViews.frame;
        mFreeze = mViews.freeze;
        mPlayPause = mViews.playPause;
        mClose = mViews.close;
        mProgress = mViews.progress;
        mDim = mViews.dim;
        mTitle = mViews.title;
        mChannel = mViews.channel;
        mFlash = mViews.flash;
        applyStyle(MiniPlayerStyle.get(activity));

        // A tap expands, or - on the gesture card - plays or pauses.
        View.OnClickListener tap = v -> {
            if (mClosing || !MiniPlayerBridge.isActive()) {
                return; // a closed card leaving
            }
            if (mStyle == MiniPlayerStyle.CARD_GESTURE) {
                togglePlayback(true);
            } else {
                expand();
            }
        };
        mBar.setOnClickListener(tap);
        mFrame.setOnClickListener(tap);

        mPlayPause.setOnClickListener(v -> {
            togglePlayback(false);
            if (mStyle == MiniPlayerStyle.BAR_VIDEO) {
                pulsePlayButton();
            }
        });
        mClose.setOnClickListener(v -> close());
        // NEWTUBE(mini-next): the playlist's next video, without leaving the mini player.
        mViews.next.setOnClickListener(v -> {
            if (mClosing || !MiniPlayerBridge.isActive()) {
                return;
            }
            MiniPlayerBridge.playNext();
            mViews.next.animate().cancel();
            mViews.next.setScaleX(0.85f);
            mViews.next.setScaleY(0.85f);
            mViews.next.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
        });

        mGestures = MiniPlayerGestures.attach(mBar, new MiniPlayerGestures.Callback() {
            @Override
            public boolean canDrag() {
                return !mClosing && !mEntryAnimating && MiniPlayerBridge.isActive();
            }

            @Override
            public boolean isCard() {
                return !MiniPlayerStyle.isBar(mStyle);
            }

            @Override
            public boolean isDockedLeft() {
                return mCardLeft;
            }

            @Override
            public float cornerTravelPx() {
                View parent = (View) mBar.getParent();
                float margin = MiniPlayerStyle.CARD_MARGIN_DP * mActivity.getResources().getDisplayMetrics().density;
                float width = parent != null ? parent.getWidth() : mActivity.getResources().getDisplayMetrics().widthPixels;
                return Math.max(1f, width - mBar.getWidth() - 2f * margin);
            }

            @Override
            public void onCloseProgress(float fraction) {
                fadeVolumeWithDrag(fraction);
            }

            @Override
            public void onClose(float toX, float toY, long durationMs) {
                close(toX, toY, durationMs);
            }

            @Override
            public void onExpand() {
                expand();
            }

            @Override
            public void onDockedOtherSide() {
                dockOtherSide();
            }

            @Override
            public void onExpandDragStart() {
                expandByDrag();
            }

            @Override
            public void onExpandDrag(float liftPx) {
                MiniPlayerBridge.expandDrag(liftPx);
            }

            @Override
            public void onExpandDragEnd(float velocityY, boolean cancelled) {
                MiniPlayerBridge.endExpandDrag(velocityY, cancelled);
            }
        }, mFrame, mBar);
    }

    private void expand() {
        if (mClosing || !MiniPlayerBridge.isActive()) {
            return;
        }
        restoreGestureVolume();
        publishBounds(); // from where it is, swiped up part of the way included
        MiniPlayerBridge.expand(mActivity);
    }

    /**
     * NEWTUBE(mini-style): a bar pulled up: the player comes to the front at once, sitting on this
     * bar, and follows the finger from there (MiniPlayerBridge.expandDrag) - so the real watch
     * page rises with it, the minimize run backwards.
     */
    private void expandByDrag() {
        if (mClosing || !MiniPlayerBridge.isActive()) {
            return;
        }
        restoreGestureVolume();
        publishBounds();
        MiniPlayerBridge.beginExpandDrag();
        MiniPlayerBridge.expand(mActivity);
    }

    private void togglePlayback(boolean flash) {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            return;
        }
        player.setPlayWhenReady(!player.getPlayWhenReady());
        updatePlayPause(player);
        if (flash) {
            flashPlayback(player.getPlayWhenReady());
        }
    }

    /**
     * NEWTUBE(motion): X - the sound fades and the card, frozen on its last frame, shrinks and fades
     * away instead of vanishing in one frame. The session closes once the card is gone (finishing
     * the hidden player blocks the main thread for ~60 ms, which ate the animation when it ran first).
     */
    private boolean mClosing;
    private float mVolumeBeforeClose = 1f;

    private void close() {
        close(null, null, 0);
    }

    /** NEWTUBE(mini-style): swiped away - it keeps going to ({@code flyToX}, {@code flyToY}). */
    private void close(@Nullable Float flyToX, @Nullable Float flyToY, long flyMs) {
        if (mClosing) {
            return;
        }
        mClosing = true;
        if (MiniPlayerBridge.isActive()) {
            // The sound when the drag began, not the faded one: an aborted close puts it back.
            mVolumeBeforeClose = mGestureVolume >= 0f ? mGestureVolume : MiniPlayerBridge.getVolume();
            fadeVolumeOut(Math.max(VOLUME_FADE_MIN_MS, flyMs));
        }
        mGestureVolume = -1f;
        MiniPlayerBridge.setClosing(this::abortClose);
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        detachTexture();
        mGestures.stop();
        mBar.animate().cancel();
        android.view.ViewPropertyAnimator exit;
        if (flyToX != null && flyToY != null) {
            exit = mBar.animate().translationX(flyToX).translationY(flyToY).alpha(0f).setDuration(flyMs)
                    .setInterpolator(new LinearInterpolator());
        } else if (MiniPlayerStyle.isBar(mStyle)) {
            // NEWTUBE(mini-style): a full-width bar sinks as it fades; shrinking it read as a glitch.
            resetTransform();
            exit = mBar.animate().alpha(0f).translationY(mBar.getHeight() / 2f)
                    .setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        } else {
            resetTransform();
            exit = mBar.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(Motion.EXIT_MS).setInterpolator(Motion.EMPHASIZED_ACCELERATE);
        }
        exit.withLayer().withEndAction(() -> {
                    if (mClosing) {
                        finishClose();
                    } else {
                        fold(); // aborted: the player took the session back; only the card goes
                    }
                }).start();
    }

    /** The end of close(); also run at once by anything that would re-show the card. */
    private void finishClose() {
        if (!mClosing) {
            return;
        }
        mClosing = false;
        cancelVolumeFade();
        MiniPlayerBridge.setClosing(null);
        fold();
        MiniPlayerBridge.close();
    }

    /** The session was handed a new video while the card left: it lives on; the card still goes. */
    private void abortClose() {
        if (!mClosing) {
            return;
        }
        mClosing = false;
        cancelVolumeFade();
        MiniPlayerBridge.setVolume(mVolumeBeforeClose);
    }

    // ---------------------------------------------------------------------------------
    // NEWTUBE(mini-style): the sound follows a closing swipe
    // ---------------------------------------------------------------------------------

    /** A closing drag is {@code fraction} of the way out: the sound goes down with it, and back. */
    private void fadeVolumeWithDrag(float fraction) {
        if (mClosing || !MiniPlayerBridge.isActive()) {
            return;
        }
        if (mGestureVolume < 0f) {
            if (fraction <= 0f) {
                return;
            }
            mGestureVolume = MiniPlayerBridge.getVolume();
            if (mGestureVolume < 0f) {
                return;
            }
        }
        if (fraction <= 0f) {
            restoreGestureVolume();
            return;
        }
        float left = 1f - Math.min(1f, fraction);
        // Squared: the ear hears a straight line as staying loud and then dropping late.
        MiniPlayerBridge.setVolume(mGestureVolume * left * left);
    }

    private void restoreGestureVolume() {
        if (mGestureVolume >= 0f) {
            if (MiniPlayerBridge.isActive()) {
                MiniPlayerBridge.setVolume(mGestureVolume);
            }
            mGestureVolume = -1f;
        }
    }

    private void fadeVolumeOut(long durationMs) {
        cancelVolumeFade();
        float from = MiniPlayerBridge.getVolume();
        if (from <= 0f) {
            MiniPlayerBridge.setVolume(0f);
            return;
        }
        ValueAnimator fade = ValueAnimator.ofFloat(from, 0f);
        fade.setDuration(durationMs);
        fade.setInterpolator(new DecelerateInterpolator());
        fade.addUpdateListener(animation -> {
            if (mClosing) {
                MiniPlayerBridge.setVolume((float) animation.getAnimatedValue());
            }
        });
        mVolumeFade = fade;
        fade.start();
    }

    private void cancelVolumeFade() {
        if (mVolumeFade != null) {
            mVolumeFade.cancel();
            mVolumeFade = null;
        }
    }

    // ---------------------------------------------------------------------------------

    /** NEWTUBE(mini-style): the card was dragged into the other bottom corner - it lives there now. */
    private void dockOtherSide() {
        boolean left = !mCardLeft;
        MiniPlayerStyle.setCardLeft(mActivity, left);
        applyStyle(mStyle); // re-docks the card on that side; its translation goes in the same frame
        mBar.setTranslationX(0f);
        mBar.post(this::publishBounds);
    }

    /**
     * NEWTUBE(motion): the host is pausing. Give the texture back (the player may be taking it) but
     * keep a docked card up, frozen on its last frame, until the player covers it - see
     * MiniPlayerBridge.setPendingCardFold. Hiding it here was a blink before every expand.
     */
    public void onHostPause() {
        finishClose();
        restoreGestureVolume();
        detachTexture();
        if (mBar.getVisibility() == View.VISIBLE && MiniPlayerBridge.isActive()) {
            MiniPlayerBridge.setPendingCardFold(mCardFold);
        } else {
            hide();
        }
    }

    /** The host is covered: a card still waiting to be folded can go now. */
    public void onHostStop() {
        hide();
    }

    /** Show the live mini session. Optionally animate it down from the watch-page video box. */
    public void sync(boolean animateFromPlayer) {
        finishClose(); // a card closing when this runs is closed, not brought back
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hide();
            return;
        }
        restoreGestureVolume();
        mGestures.stop();
        mBar.animate().cancel();
        mBar.setAlpha(1f);
        mBar.setScaleX(1f);
        mBar.setScaleY(1f);
        mBar.setTranslationX(0f); // swiped away or moved toward the other corner
        mBar.setTranslationY(0f); // swiped up or down, or a bar closed with X (it sinks as it goes)
        // NEWTUBE(mini-style): changed in Settings (or the corner on another screen) meanwhile?
        int style = MiniPlayerStyle.get(mActivity);
        if (style != mStyle || MiniPlayerStyle.isCardLeft(mActivity) != mCardLeft) {
            applyStyle(style);
        } else {
            mViews.refreshBarCorners(); // Rounded thumbnail corners may have been switched
        }
        updateUnderlap();
        updateText();
        mViews.setHasNext(MiniPlayerBridge.hasNext());
        // NEWTUBE(accent): the progress line in the accent, which may have changed since.
        mViews.updateProgressColors();

        mEntryAnimating = animateFromPlayer;
        mTextureHasFrame = false;

        Bitmap entryStill = MiniPlayerBridge.takeMiniEntryStill();
        if (entryStill != null) {
            mFreeze.setImageBitmap(entryStill);
            mFreeze.setVisibility(View.VISIBLE);
        }

        MiniPlayerBridge.fitToVideo(mFrame); // NEWTUBE(issue #9): letterbox, never stretch
        attachTexture();
        mBar.setVisibility(View.VISIBLE);
        updatePlayPause(player);

        Utils.removeCallbacks(mTick);
        Utils.postDelayed(mTick, TICK_MS);

        if (mButtonsFadePending && !animateFromPlayer) {
            mButtonsFadePending = false;
            fadeInControls();
        }

        mBar.post(() -> {
            publishBounds();
            if (animateFromPlayer && mBar.getVisibility() == View.VISIBLE) {
                animateFromWatchPage();
            }
        });
    }

    /**
     * The translucent playback Activity is still on top when this runs. Make the endpoint card
     * visible now so this host can draw it underneath before Android reorders it to the front.
     */
    public boolean prepareForHandoff(Runnable onDrawn) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) {
            return false;
        }
        sync(false);
        if (mBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (!MiniPlayerStyle.isBar(mStyle)) {
            // NEWTUBE(motion): the landing video is a bare picture; the buttons fade in once this
            // host is in front (its onResume sync) rather than appearing with the card in one frame.
            // NEWTUBE(mini-style): the bars were drawn whole under the minimize: nothing fades in.
            setControlsAlpha(0f);
            mProgress.setAlpha(1f); // NEWTUBE(mini-progress): the line that rode the video lands on this one
            mButtonsFadePending = true;
        }

        // A pair of frame callbacks only proves that time passed; it does not prove this paused
        // window submitted a buffer. Gate the reorder on an actual draw containing the card, then
        // wait one compositor frame before removing the playback window above it. The fallback
        // avoids stranding the player at the endpoint if an OEM suppresses draws on paused windows.
        final ViewTreeObserver observer = mBar.getViewTreeObserver();
        class DrawGate implements ViewTreeObserver.OnDrawListener, Runnable {
            private boolean mDelivered;

            @Override
            public void onDraw() {
                mBar.post(this);
            }

            @Override
            public void run() {
                if (mDelivered) {
                    return;
                }
                mDelivered = true;
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(this);
                }
                mBar.postOnAnimation(onDrawn);
            }
        }
        DrawGate gate = new DrawGate();
        observer.addOnDrawListener(gate);
        mBar.postDelayed(gate, 100);
        mBar.invalidate();
        return true;
    }

    /**
     * Freeze the last mini frame, release this screen's TextureView, and hide the card. The player
     * Activity can then reclaim the same session texture during Back/expand without a black beat.
     */
    public void hide() {
        finishClose();
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        detachTexture();
        fold();
    }

    /** Freeze the last frame and give the session texture back; the card itself stays as it is. */
    private void detachTexture() {
        Utils.removeCallbacks(mTick);
        mTextureHasFrame = false;
        publishBounds();

        if (mTexture != null && mTexture.getParent() != null) {
            if (mTexture.isAvailable() && MiniPlayerBridge.isActive()) {
                Bitmap still = mTexture.getBitmap();
                if (still != null) {
                    mFreeze.setImageBitmap(still);
                    mFreeze.setVisibility(View.VISIBLE);
                    MiniPlayerBridge.setHandoffStill(still);
                }
            }
            // No throwaway-texture swap here: TextureView#setSurfaceTexture releases the
            // texture the view currently holds, which would destroy the session texture
            // (see MobilePlaybackActivity#detachVideoTexture).
            mFrame.removeView(mTexture);
            mTexture = null;
        }
    }

    private void foldForPlayer(@Nullable Runnable onFolded) {
        fold();
        if (onFolded != null) {
            FrameGate.afterNextFrame(mBar.getRootView(), FOLD_WAIT_MS, onFolded);
        }
    }

    private void fold() {
        mGestures.stop();
        mBar.animate().cancel();
        mEntryAnimating = false;
        mButtonsFadePending = false;
        resetTransform();
        mBar.setAlpha(1f);
        mBar.setVisibility(View.GONE);
    }

    private void fadeInControls() {
        for (View view : chromeViews()) {
            view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
        }
    }

    /** Everything drawn over or around the video: hidden while a minimized video lands on the card. */
    private View[] chromeViews() {
        return mViews.chromeViews();
    }

    private void attachTexture() {
        if (mTexture == null) {
            final TextureView texture = new TextureView(mActivity);
            texture.setOpaque(false); // NEWTUBE(texture-opaque): see MobilePlaybackActivity
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture created, int width, int height) {
                    SurfaceTexture session = MiniPlayerBridge.getSessionTexture();
                    if (session != null && created != session) {
                        texture.setSurfaceTexture(session);
                        created.release();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    // The playback Activity owns the session texture. Release only a temporary
                    // TextureView-created surface that was never replaced by that session.
                    return texture != MiniPlayerBridge.getSessionTexture();
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    mTextureHasFrame = true;
                    hideFreezeWhenReady();
                    updateFrost();
                    if (mActivity instanceof MiniPlayerBridge.MiniHost) {
                        ((MiniPlayerBridge.MiniHost) mActivity).onMiniVideoFrame();
                    }
                }
            });
            mTexture = texture;
        }

        if (mTexture.getParent() == null) {
            mFrame.addView(mTexture, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** Start as the full-width 16:9 watch video, then land on the channel's floating card. */
    private void animateFromWatchPage() {
        int width = mBar.getWidth();
        int height = mBar.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        mGestures.stop();

        if (MiniPlayerStyle.isBar(mStyle)) {
            // NEWTUBE(mini-style): stretching a whole bar out of the 16:9 video box would distort
            // its text and buttons; it rises from the bottom edge instead.
            mBar.setTranslationY(height);
            mBar.animate()
                    .translationY(0f)
                    .setDuration(ENTRY_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        resetTransform();
                        mEntryAnimating = false;
                        hideFreezeWhenReady();
                        publishBounds();
                    })
                    .start();
            return;
        }

        int[] cardLocation = new int[2];
        int[] contentLocation = new int[2];
        mBar.getLocationOnScreen(cardLocation);
        View content = mActivity.findViewById(android.R.id.content);
        content.getLocationOnScreen(contentLocation);

        float sourceWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        float sourceHeight = sourceWidth * 9f / 16f;

        mBar.setPivotX(0f);
        mBar.setPivotY(0f);
        mBar.setScaleX(sourceWidth / width);
        mBar.setScaleY(sourceHeight / height);
        mBar.setTranslationX(contentLocation[0] - cardLocation[0]);
        mBar.setTranslationY(contentLocation[1] - cardLocation[1]);

        setControlsAlpha(0f);
        mBar.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(ENTRY_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    resetTransform();
                    mEntryAnimating = false;
                    hideFreezeWhenReady();
                    // A gentle fade, not a pop, once the card has landed.
                    for (View view : chromeViews()) {
                        view.animate().alpha(1f).setDuration(260)
                                .setInterpolator(Motion.STANDARD).start();
                    }
                    publishBounds();
                })
                .start();
    }

    private void hideFreezeWhenReady() {
        if (!mEntryAnimating && mTextureHasFrame && mFreeze.getVisibility() == View.VISIBLE) {
            mFreeze.setVisibility(View.GONE);
        }
    }

    private void resetTransform() {
        mBar.setPivotX(mBar.getWidth() / 2f);
        mBar.setPivotY(mBar.getHeight() / 2f);
        mBar.setScaleX(1f);
        mBar.setScaleY(1f);
        mBar.setTranslationX(0f);
        mBar.setTranslationY(0f);
        setControlsAlpha(1f);
    }

    private void setControlsAlpha(float alpha) {
        for (View view : chromeViews()) {
            view.animate().cancel();
            view.setAlpha(alpha);
        }
    }

    /**
     * Tell the player where the video sits, for the expand morph. NEWTUBE(mini-style): the bar's
     * thumbnail slot, or the whole video-background bar (cropped), rather than the whole view.
     */
    private void publishBounds() {
        if (mBar.getVisibility() != View.VISIBLE || mBar.getWidth() <= 0 || mBar.getHeight() <= 0) {
            return;
        }
        int[] location = new int[2];
        mBar.getLocationOnScreen(location);
        int width = mBar.getWidth();
        if (mStyle == MiniPlayerStyle.BAR) {
            float density = mActivity.getResources().getDisplayMetrics().density;
            width = Math.min(width, Math.round(MiniPlayerStyle.barThumbWidthPx(density)));
        }
        MiniPlayerBridge.setMiniBounds(new Rect(location[0], location[1],
                location[0] + width, location[1] + mBar.getHeight() - mViews.underlapPx()),
                mStyle == MiniPlayerStyle.BAR_VIDEO);
    }

    private void onTick() {
        if (mBar.getVisibility() != View.VISIBLE) {
            return;
        }
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            hide();
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            mProgress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
        }
        updatePlayPause(player);
        updateText(); // NEWTUBE(mini-style): an autoplayed next video
        mViews.setHasNext(MiniPlayerBridge.hasNext()); // NEWTUBE(mini-next): and its place in the list
        MiniPlayerBridge.fitToVideo(mFrame); // an autoplayed next video may have another shape
        Utils.postDelayed(mTick, TICK_MS);
    }

    private void updatePlayPause(ExoPlayer player) {
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mViews.setPlaying(playing);
        if (mStyle == MiniPlayerStyle.CARD_GESTURE) {
            // NEWTUBE(mini-style): no button to show it, so a paused gesture card stays dimmed.
            float dim = playing ? 0f : 1f;
            if (mDim.getAlpha() != dim) {
                mDim.animate().cancel();
                mDim.animate().alpha(dim).setDuration(Motion.FADE_IN_MS).start();
            }
        }
    }

    /** NEWTUBE(mini-style): the video bar's play/pause answers a tap with a soft flash; its icon stays. */
    private void pulsePlayButton() {
        mFlash.animate().cancel();
        mFlash.setVisibility(View.VISIBLE);
        mFlash.setAlpha(0.9f);
        mFlash.setScaleX(0.6f);
        mFlash.setScaleY(0.6f);
        mFlash.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setStartDelay(0).setDuration(360)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> mFlash.setVisibility(View.GONE))
                .start();
        mPlayPause.animate().cancel();
        mPlayPause.setScaleX(0.88f);
        mPlayPause.setScaleY(0.88f);
        mPlayPause.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
    }

    /** NEWTUBE(mini-style): the gesture card's answer to a tap - the new state, briefly. */
    private void flashPlayback(boolean playing) {
        mFlash.setImageResource(playing ? R.drawable.ic_player_play : R.drawable.ic_player_pause);
        mFlash.animate().cancel();
        mFlash.setVisibility(View.VISIBLE);
        mFlash.setAlpha(1f);
        mFlash.setScaleX(0.85f);
        mFlash.setScaleY(0.85f);
        mFlash.animate().scaleX(1.15f).scaleY(1.15f).alpha(0f).setStartDelay(120).setDuration(380)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> mFlash.setVisibility(View.GONE))
                .start();
    }

    // ---------------------------------------------------------------------------------
    // NEWTUBE(mini-style)
    // ---------------------------------------------------------------------------------

    /** NEWTUBE(mini-underlap): the video bar reaches behind the host's glass navigation. */
    private void updateUnderlap() {
        int under = mActivity instanceof MiniPlayerBridge.MiniHost
                ? ((MiniPlayerBridge.MiniHost) mActivity).getMiniUnderlapPx() : 0;
        mViews.setUnderlapPx(under);
    }

    /** Lay the card and its children out for {@code style} (see MiniPlayerStyle). */
    private void applyStyle(int style) {
        mStyle = style;
        mCardLeft = MiniPlayerStyle.isCardLeft(mActivity);
        mViews.applyStyle(style, mCardLeft);
        updateUnderlap();
        updateAccessibility();
    }

    /**
     * NEWTUBE(mini-style): the gesture card has no buttons and a tap plays or pauses, so TalkBack
     * gets Expand and Close as actions on the card (a swipe is no way to reach them).
     */
    private void updateAccessibility() {
        if (mExpandActionId != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(mBar, mExpandActionId);
            mExpandActionId = View.NO_ID;
        }
        if (mCloseActionId != View.NO_ID) {
            ViewCompat.removeAccessibilityAction(mBar, mCloseActionId);
            mCloseActionId = View.NO_ID;
        }
        if (mStyle == MiniPlayerStyle.CARD_GESTURE) {
            mBar.setContentDescription(mActivity.getString(R.string.mobile_mini_play_pause));
            mExpandActionId = ViewCompat.addAccessibilityAction(mBar,
                    mActivity.getString(R.string.mobile_mini_expand), (view, arguments) -> {
                        expand();
                        return true;
                    });
            mCloseActionId = ViewCompat.addAccessibilityAction(mBar,
                    mActivity.getString(R.string.mobile_mini_close), (view, arguments) -> {
                        if (!mClosing && MiniPlayerBridge.isActive()) {
                            close();
                        }
                        return true;
                    });
        } else {
            mBar.setContentDescription(mActivity.getString(R.string.mobile_mini_expand));
        }
    }

    /** The bars' title and channel, from the playing video (an autoplayed next one included). */
    private void updateText() {
        if (!MiniPlayerStyle.isBar(mStyle)) {
            return;
        }
        Video video = MiniPlayerBridge.getVideo();
        setTextIfChanged(mTitle, video != null ? video.getTitle() : null);
        setTextIfChanged(mChannel, video != null ? video.getAuthor() : null);
    }

    private static void setTextIfChanged(TextView view, @Nullable CharSequence text) {
        CharSequence value = text != null ? text : "";
        if (!TextUtils.equals(view.getText(), value)) {
            view.setText(value);
        }
    }

    /**
     * NEWTUBE(mini-style): refresh the video-background bar's frosted glass from the live video, at
     * most every {@link #FROST_INTERVAL_MS}, on the texture's own frame callback.
     */
    private void updateFrost() {
        if (mStyle != MiniPlayerStyle.BAR_VIDEO || mTexture == null || !mTexture.isAvailable()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mFrostAt < FROST_INTERVAL_MS) {
            return;
        }
        mFrostAt = now;
        mViews.updateFrost(mTexture);
    }
}
