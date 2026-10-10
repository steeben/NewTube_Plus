package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.google.android.material.card.MaterialCardView;
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
    /** NEWTUBE(mini-style): the frosted title's picture of the video: size and refresh. */
    private static final int FROST_WIDTH = 64;
    private static final int FROST_HEIGHT = 36;
    private static final int FROST_BLUR_RADIUS = 2;
    private static final long FROST_INTERVAL_MS = 80;

    private final Activity mActivity;
    private final View mBar;
    /** NEWTUBE(mini-style): every drag on the mini player (close, expand, change corner). */
    private final MiniPlayerGestures mGestures;
    private final FrameLayout mFrame;
    private final ImageView mFreeze;
    private final ImageButton mPlayPause;
    private final ImageButton mClose;
    private final ProgressBar mProgress;
    /** NEWTUBE(mini-style): the other styles' parts (GONE where a style has no use for them). */
    private final View mBackdrop;
    private final View mDim;
    private final LinearLayout mText;
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
    /** NEWTUBE(mini-style): the bar is drawn for a minimize that is still under the finger. */
    private boolean mPreviewing;
    private final MiniPlayerBridge.CardFold mCardFold = this::foldForPlayer;

    /** NEWTUBE(mini-style): the volume when a closing drag began; -1 when none is fading it. */
    private float mGestureVolume = -1f;
    @Nullable
    private ValueAnimator mVolumeFade;

    /** NEWTUBE(mini-style): the frosted glass behind the video-background bar's title. */
    @Nullable
    private FrostedScrimDrawable mFrost;
    @Nullable
    private Bitmap mFrostSample;
    @Nullable
    private Bitmap mFrostBlur;
    @Nullable
    private int[] mFrostPixels;
    @Nullable
    private int[] mFrostScratch;
    private long mFrostAt;
    private final Rect mFrostSource = new Rect();

    /** NEWTUBE(mini-style): the gesture card's TalkBack actions (no buttons to reach). */
    private int mExpandActionId = View.NO_ID;
    private int mCloseActionId = View.NO_ID;

    public MobileMiniPlayerController(Activity activity) {
        mActivity = activity;
        mBar = activity.findViewById(R.id.mobile_mini_player);
        mFrame = activity.findViewById(R.id.mobile_mini_player_frame);
        mFreeze = activity.findViewById(R.id.mobile_mini_freeze);
        mPlayPause = activity.findViewById(R.id.mobile_mini_play_pause);
        mClose = activity.findViewById(R.id.mobile_mini_close);
        mProgress = activity.findViewById(R.id.mobile_mini_progress);
        mBackdrop = activity.findViewById(R.id.mobile_mini_backdrop);
        mDim = activity.findViewById(R.id.mobile_mini_dim);
        mText = activity.findViewById(R.id.mobile_mini_text);
        mTitle = activity.findViewById(R.id.mobile_mini_title);
        mChannel = activity.findViewById(R.id.mobile_mini_channel);
        mFlash = activity.findViewById(R.id.mobile_mini_flash);
        applyStyle(MiniPlayerStyle.get(activity));

        // A tap expands, or - on the gesture card - plays or pauses.
        View.OnClickListener tap = v -> {
            if (mClosing || mPreviewing || !MiniPlayerBridge.isActive()) {
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

        mPlayPause.setOnClickListener(v -> togglePlayback(false));
        mClose.setOnClickListener(v -> close());

        mGestures = MiniPlayerGestures.attach(mBar, new MiniPlayerGestures.Callback() {
            @Override
            public boolean canDrag() {
                return !mClosing && !mEntryAnimating && !mPreviewing && MiniPlayerBridge.isActive();
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
        }, mFrame, mBar);
    }

    private void expand() {
        if (mClosing || mPreviewing || !MiniPlayerBridge.isActive()) {
            return;
        }
        restoreGestureVolume();
        publishBounds(); // from where it is, swiped up part of the way included
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
        } else if (!mPreviewing) {
            hide();
        }
    }

    /** The host is covered: a card still waiting to be folded can go now. */
    public void onHostStop() {
        hide();
    }

    /**
     * NEWTUBE(mini-style): a bar minimize is under the finger on the player above this host: draw
     * the bar now, with the title and buttons but no video, so the video lands in a bar that is
     * already there instead of one fading in around it (see MiniPlayerBridge.previewHost).
     */
    public void preview(@Nullable Video video, int progress, boolean playing) {
        if (MiniPlayerBridge.isActive() || mActivity.isFinishing() || mActivity.isDestroyed()) {
            return;
        }
        int style = MiniPlayerStyle.get(mActivity);
        if (style != mStyle || MiniPlayerStyle.isCardLeft(mActivity) != mCardLeft) {
            applyStyle(style);
        }
        if (!MiniPlayerStyle.isBar(mStyle)) {
            return;
        }
        finishClose();
        mGestures.stop();
        mBar.animate().cancel();
        mPreviewing = true;
        resetTransform();
        mBar.setAlpha(1f);
        mFreeze.setVisibility(View.GONE);
        setTextIfChanged(mTitle, video != null ? video.getTitle() : null);
        setTextIfChanged(mChannel, video != null ? video.getAuthor() : null);
        mProgress.setProgress(progress);
        mPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        mBar.setVisibility(View.VISIBLE);
    }

    /** NEWTUBE(mini-style): the minimize was cancelled - the previewed bar goes. */
    public void endPreview() {
        if (!mPreviewing) {
            return;
        }
        mPreviewing = false;
        if (!MiniPlayerBridge.isActive()) {
            fold();
        }
    }

    /** Show the live mini session. Optionally animate it down from the watch-page video box. */
    public void sync(boolean animateFromPlayer) {
        finishClose(); // a card closing when this runs is closed, not brought back
        MiniPlayerBridge.clearPendingCardFold(mCardFold);
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        if (player == null) {
            mPreviewing = false;
            hide();
            return;
        }
        mPreviewing = false;
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
        }
        updateText();

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
        mPreviewing = false;
        resetTransform();
        mBar.setAlpha(1f);
        mBar.setVisibility(View.GONE);
    }

    private void fadeInControls() {
        for (View view : chromeViews()) {
            view.animate().alpha(1f).setDuration(Motion.FADE_IN_MS).setInterpolator(Motion.STANDARD).start();
        }
    }

    /**
     * Everything drawn over or around the video: hidden while the minimized video lands on the
     * card, then faded in. NEWTUBE(mini-style): the bars' parts too (they are drawn whole under a
     * bar minimize, so for them this only matters to the card).
     */
    private View[] chromeViews() {
        if (mStyle == MiniPlayerStyle.CARD_GESTURE) {
            return new View[] {mProgress}; // its dim shows the paused state (updatePlayPause)
        }
        return new View[] {mPlayPause, mClose, mProgress, mBackdrop, mDim, mText};
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
                    for (View view : chromeViews()) {
                        view.animate().alpha(1f).setDuration(90).start();
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
                location[0] + width, location[1] + mBar.getHeight()), mStyle == MiniPlayerStyle.BAR_VIDEO);
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
        MiniPlayerBridge.fitToVideo(mFrame); // an autoplayed next video may have another shape
        Utils.postDelayed(mTick, TICK_MS);
    }

    private void updatePlayPause(ExoPlayer player) {
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        if (mStyle == MiniPlayerStyle.CARD_GESTURE) {
            // NEWTUBE(mini-style): no button to show it, so a paused gesture card stays dimmed.
            float dim = playing ? 0f : 1f;
            if (mDim.getAlpha() != dim) {
                mDim.animate().cancel();
                mDim.animate().alpha(dim).setDuration(Motion.FADE_IN_MS).start();
            }
        }
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

    /** Lay the card and its children out for {@code style} (see MiniPlayerStyle). */
    private void applyStyle(int style) {
        mStyle = style;
        mCardLeft = MiniPlayerStyle.isCardLeft(mActivity);
        float density = mActivity.getResources().getDisplayMetrics().density;
        boolean bar = MiniPlayerStyle.isBar(style);
        int barHeight = px(MiniPlayerStyle.BAR_HEIGHT_DP, density);
        int thumbWidth = Math.round(MiniPlayerStyle.barThumbWidthPx(density));
        int buttonSize = px(48, density);

        // The card: in a bottom corner, or a square-cornered bar across the screen.
        ViewGroup.LayoutParams params = mBar.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int margin = bar ? 0 : px(MiniPlayerStyle.CARD_MARGIN_DP, density);
            int startMargin = !bar && mCardLeft ? margin : 0;
            int endMargin = !bar && !mCardLeft ? margin : 0;
            margins.height = bar ? barHeight : px(MiniPlayerStyle.CARD_HEIGHT_DP, density);
            margins.width = bar ? ViewGroup.LayoutParams.MATCH_PARENT : px(MiniPlayerStyle.CARD_WIDTH_DP, density);
            margins.leftMargin = startMargin;
            margins.rightMargin = endMargin;
            margins.setMarginStart(startMargin);
            margins.setMarginEnd(endMargin);
            margins.bottomMargin = margin;
            if (params instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams constraints = (ConstraintLayout.LayoutParams) params;
                if (bar) {
                    constraints.width = 0; // MATCH_CONSTRAINT between the two edges
                }
                constraints.startToStart = bar || mCardLeft
                        ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                constraints.endToEnd = bar || !mCardLeft
                        ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                constraints.validate();
            } else if (params instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) params).gravity = bar ? Gravity.BOTTOM
                        : Gravity.BOTTOM | (mCardLeft ? Gravity.START : Gravity.END);
            }
            mBar.setLayoutParams(params);
        }
        if (mBar instanceof MaterialCardView) {
            MaterialCardView card = (MaterialCardView) mBar;
            card.setRadius(bar ? 0f : MiniPlayerStyle.CARD_CORNER_DP * density);
            card.setCardElevation(bar ? 0f : 8f * density);
            card.setCardBackgroundColor(style == MiniPlayerStyle.BAR ? Color.TRANSPARENT : Color.BLACK);
        }

        mBackdrop.setVisibility(style == MiniPlayerStyle.BAR ? View.VISIBLE : View.GONE);
        boolean dim = style == MiniPlayerStyle.BAR_VIDEO || style == MiniPlayerStyle.CARD_GESTURE;
        mDim.setVisibility(dim ? View.VISIBLE : View.GONE);
        mDim.animate().cancel();
        mDim.setAlpha(style == MiniPlayerStyle.CARD_GESTURE ? 0f : 1f);
        mFlash.animate().cancel();
        mFlash.setVisibility(View.GONE);

        // The video: the whole card, the bar's 16:9 thumbnail slot, or the whole bar cropped.
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) mFrame.getLayoutParams();
        if (style == MiniPlayerStyle.BAR) {
            frame.width = thumbWidth;
            frame.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        } else {
            frame.width = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frame.gravity = Gravity.CENTER;
        }
        mFrame.setLayoutParams(frame);
        if (mFrame instanceof AspectRatioFrameLayout) {
            ((AspectRatioFrameLayout) mFrame).setResizeMode(style == MiniPlayerStyle.BAR_VIDEO
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        mFrame.setBackgroundColor(style == MiniPlayerStyle.BAR ? Color.BLACK : Color.TRANSPARENT);

        // The title (and channel): beside the thumbnail, or on frosted glass over the video.
        FrameLayout.LayoutParams text = (FrameLayout.LayoutParams) mText.getLayoutParams();
        text.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        int onSurface = ContextCompat.getColor(mActivity, R.color.mobile_color_on_surface);
        int onVideo = ContextCompat.getColor(mActivity, R.color.mobile_player_on_video);
        if (style == MiniPlayerStyle.BAR_VIDEO) {
            text.setMarginStart(px(12, density));
            text.setMarginEnd(2 * buttonSize + px(8, density));
            if (mFrost == null) {
                mFrost = new FrostedScrimDrawable(14f * density,
                        ContextCompat.getColor(mActivity, R.color.mobile_mini_frost_tint),
                        ContextCompat.getColor(mActivity, R.color.mobile_mini_frost_edge), density);
            }
            mText.setBackground(mFrost);
            mText.setPadding(px(12, density), px(6, density), px(12, density), px(6, density));
            mTitle.setTextColor(onVideo);
            mChannel.setVisibility(View.GONE);
        } else {
            text.setMarginStart(thumbWidth + px(12, density));
            text.setMarginEnd(2 * buttonSize + px(4, density));
            mText.setBackground(null);
            mText.setPadding(0, 0, 0, 0);
            mTitle.setTextColor(onSurface);
            mChannel.setVisibility(View.VISIBLE);
        }
        mText.setLayoutParams(text);
        mText.setVisibility(bar ? View.VISIBLE : View.GONE);

        // Play/pause and close: over the card's video, side by side at the bar's end, or - on the
        // gesture card - none at all.
        FrameLayout.LayoutParams play = (FrameLayout.LayoutParams) mPlayPause.getLayoutParams();
        FrameLayout.LayoutParams close = (FrameLayout.LayoutParams) mClose.getLayoutParams();
        if (bar) {
            play.width = buttonSize;
            play.height = buttonSize;
            play.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            play.setMarginEnd(buttonSize);
            close.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        } else {
            play.width = px(40, density);
            play.height = px(40, density);
            play.gravity = Gravity.CENTER;
            play.setMarginEnd(0);
            close.gravity = Gravity.TOP | Gravity.END;
        }
        close.width = buttonSize;
        close.height = buttonSize;
        mPlayPause.setLayoutParams(play);
        mClose.setLayoutParams(close);
        if (bar) {
            // Plain icons: on the page colour (the bar), or white straight on the dimmed video.
            int iconColor = style == MiniPlayerStyle.BAR ? onSurface : onVideo;
            mPlayPause.setBackground(borderlessRipple());
            mClose.setBackground(borderlessRipple());
            mPlayPause.setPadding(px(12, density), px(12, density), px(12, density), px(12, density));
            mClose.setPadding(px(14, density), px(14, density), px(14, density), px(14, density));
            mPlayPause.setImageTintList(ColorStateList.valueOf(iconColor));
            mClose.setImageTintList(ColorStateList.valueOf(iconColor));
        } else {
            // White icons on dark circles over the card's video.
            mPlayPause.setBackgroundResource(R.drawable.bg_mini_scrim_circle);
            mClose.setBackgroundResource(R.drawable.bg_mini_scrim_circle_touch);
            mPlayPause.setPadding(px(9, density), px(9, density), px(9, density), px(9, density));
            mClose.setPadding(px(15, density), px(15, density), px(15, density), px(15, density));
            mPlayPause.setImageTintList(null);
            mClose.setImageTintList(ColorStateList.valueOf(onVideo));
        }
        int buttons = style == MiniPlayerStyle.CARD_GESTURE ? View.GONE : View.VISIBLE;
        mPlayPause.setVisibility(buttons);
        mClose.setVisibility(buttons);

        mProgress.setProgressBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(mActivity,
                style == MiniPlayerStyle.BAR ? R.color.mobile_color_track : R.color.mobile_color_progress_track)));

        updateAccessibility();
        mBar.requestLayout();
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

    @Nullable
    private Drawable borderlessRipple() {
        TypedValue value = new TypedValue();
        if (!mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
                || value.resourceId == 0) {
            return null;
        }
        return ContextCompat.getDrawable(mActivity, value.resourceId);
    }

    private static int px(float dp, float density) {
        return Math.round(dp * density);
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
     * NEWTUBE(mini-style): refresh the frosted glass behind the video-background bar's title - a
     * tiny copy of the current frame, blurred, of which the scrim draws the part under the title.
     * At most every {@link #FROST_INTERVAL_MS}, on the texture's own frame callback.
     */
    private void updateFrost() {
        if (mStyle != MiniPlayerStyle.BAR_VIDEO || mFrost == null || mTexture == null
                || !mTexture.isAvailable() || mText.getVisibility() != View.VISIBLE) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mFrostAt < FROST_INTERVAL_MS) {
            return;
        }
        mFrostAt = now;
        int frameWidth = mFrame.getWidth();
        int frameHeight = mFrame.getHeight();
        if (frameWidth <= 0 || frameHeight <= 0 || mText.getWidth() <= 0) {
            return;
        }
        try {
            if (mFrostSample == null) {
                mFrostSample = Bitmap.createBitmap(FROST_WIDTH, FROST_HEIGHT, Bitmap.Config.ARGB_8888);
                mFrostBlur = Bitmap.createBitmap(FROST_WIDTH, FROST_HEIGHT, Bitmap.Config.ARGB_8888);
                mFrostPixels = new int[FROST_WIDTH * FROST_HEIGHT];
                mFrostScratch = new int[FROST_WIDTH * FROST_HEIGHT];
            }
            mTexture.getBitmap(mFrostSample);
        } catch (RuntimeException e) {
            return; // the texture went away under us; the scrim keeps its last picture
        }
        mFrostSample.getPixels(mFrostPixels, 0, FROST_WIDTH, 0, 0, FROST_WIDTH, FROST_HEIGHT);
        boxBlur(mFrostPixels, mFrostScratch, FROST_WIDTH, FROST_HEIGHT, FROST_BLUR_RADIUS);
        boxBlur(mFrostPixels, mFrostScratch, FROST_WIDTH, FROST_HEIGHT, FROST_BLUR_RADIUS);
        mFrostBlur.setPixels(mFrostPixels, 0, FROST_WIDTH, 0, 0, FROST_WIDTH, FROST_HEIGHT);

        // The title's box in the frame's pixels, then in the small copy's.
        float left = mText.getLeft() - mFrame.getLeft();
        float top = mText.getTop() - mFrame.getTop();
        float sx = (float) FROST_WIDTH / frameWidth;
        float sy = (float) FROST_HEIGHT / frameHeight;
        mFrostSource.set(Math.round(left * sx), Math.round(top * sy),
                Math.round((left + mText.getWidth()) * sx), Math.round((top + mText.getHeight()) * sy));
        if (mFrostSource.width() <= 0) {
            mFrostSource.right = mFrostSource.left + 1;
        }
        if (mFrostSource.height() <= 0) {
            mFrostSource.bottom = mFrostSource.top + 1;
        }
        mFrost.setFrame(mFrostBlur, mFrostSource);
    }

    /** One horizontal and one vertical box-blur pass over {@code pixels}, in place. */
    private static void boxBlur(int[] pixels, int[] scratch, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = pixels[row + Math.max(0, Math.min(width - 1, x + k))];
                    a += c >>> 24;
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                scratch[row + x] = ((a / window) << 24) | ((r / window) << 16) | ((g / window) << 8) | (b / window);
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = scratch[Math.max(0, Math.min(height - 1, y + k)) * width + x];
                    a += c >>> 24;
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                pixels[y * width + x] = ((a / window) << 24) | ((r / window) << 16) | ((g / window) << 8) | (b / window);
            }
        }
    }
}
