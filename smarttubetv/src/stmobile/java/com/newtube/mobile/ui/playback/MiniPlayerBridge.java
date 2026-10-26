package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;

import java.lang.ref.WeakReference;

/**
 * Hand-off state for the YouTube-style in-app mini-player.
 *
 * <p>The playback Activity OWNS the {@link ExoPlayer} (see {@link MobilePlaybackService} -
 * the service only wires the media session/notification, background audio already survives the
 * Activity being covered). Minimizing therefore does NOT move the player anywhere:
 * {@link MobilePlaybackActivity} stays alive behind {@code MobileBrowseActivity}, and the card
 * simply re-parents the player's session-long {@link SurfaceTexture} into its own TextureView
 * (see {@link #getSessionTexture()}) - the codec never notices the hand-off. This class is only
 * the bridge between those two activities: "is a mini session active?" plus lazy access to the
 * live player/video/texture (lazy so an engine restart inside the playback activity never
 * leaves the card holding released objects).</p>
 *
 * <p>All access is main-thread (activity lifecycle callbacks + view clicks), so plain statics
 * are safe. The activity is held weakly: if the system destroys the backgrounded player
 * activity, {@link #isActive()} turns false on its own and the bar simply hides.</p>
 */
public final class MiniPlayerBridge {
    /**
     * A screen that can dock the live mini card: Home, Search, and Channel all qualify. The
     * player minimizes onto whichever host was RESUMED most recently - that is exactly the
     * screen sitting underneath the translucent playback window, so a video opened from Search
     * minimizes back onto the search results instead of yanking Home to the front.
     */
    public interface MiniHost {
        /** @return true when the mini card was made visible and scheduled for drawing. */
        boolean prepareMiniPlayerForHandoff(Runnable onDrawn);

        /**
         * NEWTUBE(pills): show (or take away) the docked pill bar without its video - its glass,
         * title and buttons - under the player's video while a minimize or expand covers it.
         */
        default void showMiniShell(boolean show) {
        }

        /** The ViewManager view class that reorders this host's Activity to the front. */
        Class<?> getMiniHostViewClass();

        /**
         * Distance in px between the host's card bottom edge and the content bottom (Home's
         * 56dp bottom-nav row; 0 for overlay hosts). Lets the minimize morph land exactly on
         * THIS host's card instead of always assuming Home's geometry.
         */
        int getMiniCardBottomOffsetPx();

        /**
         * NEWTUBE(mini-style): the bottom navigation's share in sight (0 hidden below the screen,
         * 1 in place) while a bar minimize or expand moves the watch page over it. Hosts without
         * one ignore it.
         */
        default void setBottomBarReveal(float reveal) {
        }

        /** NEWTUBE(mini-style): the bottom navigation's height in px, 0 for hosts without one. */
        default int getBottomBarHeightPx() {
            return 0;
        }

        /**
         * NEWTUBE(mini-underlap): how far the video-background bar's video reaches down behind this
         * host's (glass) bottom navigation, px; 0 to stop at its top.
         */
        default int getMiniUnderlapPx() {
            return 0;
        }

        /** NEWTUBE(mini-underlap): the mini player drew a new video frame (glass over it redraws). */
        default void onMiniVideoFrame() {
        }
    }

    /**
     * NEWTUBE(mini-style): the player's side of a bar pulled up from a host - the finger stays on the
     * host's window while the player, brought to the front, follows it.
     */
    public interface ExpandDrag {
        /** The finger is {@code liftPx} above where the pull began. */
        void onExpandDrag(float liftPx);

        /** It let go; {@code cancelled}: the touch was taken away instead. */
        void onExpandDragEnd(float velocityY, boolean cancelled);
    }

    /**
     * NEWTUBE(motion): a host card left on screen, frozen on its last frame, while the player comes
     * back over it (see {@link #setPendingCardFold}).
     */
    public interface CardFold {
        /** Hide the card now; run {@code onFolded} once a frame without it is on screen. */
        void fold(@Nullable Runnable onFolded);
    }

    private static final long NAVIGATION_PENDING_MS = 30_000;
    @Nullable
    private static CardFold sPendingFold;

    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static WeakReference<MobilePlaybackActivity> sPendingNavigation = new WeakReference<>(null);
    private static WeakReference<MiniHost> sMiniHost = new WeakReference<>(null);
    private static long sPendingNavigationAtMs;
    private static boolean sActive;
    private static Bitmap sHandoffStill;
    private static Bitmap sMiniEntryStill;
    private static Rect sMiniBounds;
    /** NEWTUBE(wave): the player's wave, for the video-background mini bar to show the same. */
    private static boolean sWaveOn;
    @Nullable
    private static int[] sWaveColors;

    public static void setWave(boolean on, @Nullable int[] colors) {
        sWaveOn = on;
        sWaveColors = colors;
    }

    public static boolean isWaveOn() {
        return sWaveOn;
    }

    @Nullable
    public static int[] getWaveColors() {
        return sWaveColors;
    }
    private static boolean sMiniBoundsCrop;

    private MiniPlayerBridge() {
    }

    /**
     * Track the host screen currently underneath (or about to be underneath) the player. Called
     * from each host's onResume - the last resumed host is the minimize destination. Hosts stay
     * registered while merely paused (the player on top of them must still find them).
     */
    public static void registerMiniHost(MiniHost host) {
        sMiniHost = new WeakReference<>(host);
    }

    public static void unregisterMiniHost(MiniHost host) {
        if (sMiniHost.get() == host) {
            sMiniHost = new WeakReference<>(null);
        }
    }

    /** The registered destination host, or null (deep-linked player with no screen beneath). */
    @Nullable
    static MiniHost getMiniHost() {
        return sMiniHost.get();
    }

    /**
     * Pre-render the destination card while the playback window still covers it. This closes
     * the one-frame gap between hiding the morphed playback Activity and the host's onResume
     * callback. Kept separate from {@link #activate(MobilePlaybackActivity)} because channel
     * navigation has a different mini-player host and must not let this host claim the shared
     * SurfaceTexture.
     */
    /** NEWTUBE(pills): see {@link MiniHost#showMiniShell}. */
    static void showHostShell(MobilePlaybackActivity source, boolean show) {
        sShellSource = new WeakReference<>(show ? source : null);
        MiniHost host = sMiniHost.get();
        if (host != null) {
            host.showMiniShell(show);
        }
    }

    /**
     * NEWTUBE(pills): the watch page whose video is sliding over the host's pill shell. A minimize
     * shows the shell long before this bridge is activated (that is the hand-over, at the end), so
     * the shell reads its title, position and buttons from the sliding page.
     */
    private static WeakReference<MobilePlaybackActivity> sShellSource = new WeakReference<>(null);

    @Nullable
    private static MobilePlaybackActivity shellSource() {
        return isActive() ? sActivity.get() : sShellSource.get();
    }

    @Nullable
    static ExoPlayer getShellPlayer() {
        MobilePlaybackActivity source = shellSource();
        return source != null ? source.getSharedPlayer() : null;
    }

    @Nullable
    static Video getShellVideo() {
        MobilePlaybackActivity source = shellSource();
        return source != null ? source.getVideo() : null;
    }

    static boolean shellHasNext() {
        MobilePlaybackActivity source = shellSource();
        return source != null && source.hasQueueNext();
    }

    static boolean prepareMiniHostForHandoff(Runnable onDrawn) {
        MiniHost host = sMiniHost.get();
        return host != null && host.prepareMiniPlayerForHandoff(onDrawn);
    }

    /**
     * NEWTUBE(motion): a host pausing with a mini session docked detaches its texture (the player
     * may be about to take it back) but keeps the card showing that last frame instead of hiding
     * it. The expanding player's window draws ~50 ms after the host pauses (Pixel 9), and a card
     * hidden at pause read as a blink: 4 frames of no video before the morph. The player folds the
     * card once its own frame over the card is on screen ({@link #foldHostCard}); a host that
     * resumes or stops first clears or folds it itself. Deliberately not part of the session state
     * that {@link #deactivate()} clears: the player deactivates before it folds.
     */
    public static void setPendingCardFold(@Nullable CardFold fold) {
        sPendingFold = fold;
    }

    public static void clearPendingCardFold(CardFold fold) {
        if (sPendingFold == fold) {
            sPendingFold = null;
        }
    }

    /**
     * NEWTUBE(motion): a host's X is closing the session once its card has animated away; the
     * runnable aborts that (and puts the sound back). A new video delivered to this same live player
     * meanwhile (PlaybackPresenter re-uses it) must not be closed with it: the player calls
     * {@link #cancelClosing()} when it is handed a different video.
     */
    @Nullable
    private static Runnable sClosingAbort;

    public static void setClosing(@Nullable Runnable abort) {
        sClosingAbort = abort;
    }

    static void cancelClosing() {
        Runnable abort = sClosingAbort;
        sClosingAbort = null;
        if (abort != null) {
            abort.run();
        }
    }

    /** The player now covers the card: fold it, then run {@code onFolded} (at once if none waits). */
    static void foldHostCard(@Nullable Runnable onFolded) {
        CardFold fold = sPendingFold;
        sPendingFold = null;
        if (fold != null) {
            fold.fold(onFolded);
        } else if (onFolded != null) {
            onFolded.run();
        }
    }

    private static boolean sExpandDragging;
    private static float sExpandLift;
    private static boolean sExpandEnded;
    private static float sExpandEndVelocity;
    private static boolean sExpandEndCancelled;
    @Nullable
    private static ExpandDrag sExpandDrag;

    /** NEWTUBE(mini-style): a host bar's pull begins; the expand it starts follows it. */
    public static void beginExpandDrag() {
        sExpandDragging = true;
        sExpandLift = 0f;
        sExpandEnded = false;
    }

    public static void expandDrag(float liftPx) {
        if (!sExpandDragging) {
            return;
        }
        sExpandLift = liftPx;
        if (sExpandDrag != null) {
            sExpandDrag.onExpandDrag(liftPx);
        }
    }

    public static void endExpandDrag(float velocityY, boolean cancelled) {
        if (!sExpandDragging) {
            return;
        }
        sExpandDragging = false;
        if (sExpandDrag != null) {
            ExpandDrag drag = sExpandDrag;
            sExpandDrag = null;
            drag.onExpandDragEnd(velocityY, cancelled);
        } else {
            sExpandEnded = true; // the player is not in front yet: it reads this when it is
            sExpandEndVelocity = velocityY;
            sExpandEndCancelled = cancelled;
        }
    }

    /** True while a pull started by a host bar has not been handed to its player yet. */
    static boolean hasPendingExpandDrag() {
        return sExpandDragging || sExpandEnded;
    }

    /**
     * The player is in front and follows the pull: it gets where the finger is now, then every
     * move, then the release (at once, if the finger already let go).
     */
    static void attachExpandDrag(ExpandDrag drag) {
        if (sExpandEnded) {
            sExpandEnded = false;
            drag.onExpandDrag(sExpandLift);
            drag.onExpandDragEnd(sExpandEndVelocity, sExpandEndCancelled);
            return;
        }
        if (!sExpandDragging) {
            return;
        }
        sExpandDrag = drag;
        drag.onExpandDrag(sExpandLift);
    }

    static void detachExpandDrag(ExpandDrag drag) {
        if (sExpandDrag == drag) {
            sExpandDrag = null;
        }
    }

    /** Called by the playback activity right before it backgrounds itself into mini mode. */
    static void activate(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        sActive = true;
    }

    /**
     * Mark a channel route that should become an in-app mini session only if its destination
     * Activity is actually created. Channel-id resolution can be asynchronous, so activating here
     * would strand a detached/shrunk player when lookup fails.
     */
    static void prepareNavigation(MobilePlaybackActivity activity) {
        sPendingNavigation = new WeakReference<>(activity);
        sPendingNavigationAtMs = SystemClock.uptimeMillis();
    }

    /** Called by the channel Activity once the pending route has succeeded. */
    public static boolean completePendingNavigation() {
        MobilePlaybackActivity activity = sPendingNavigation.get();
        boolean fresh = activity != null
                && SystemClock.uptimeMillis() - sPendingNavigationAtMs <= NAVIGATION_PENDING_MS;
        sPendingNavigation = new WeakReference<>(null);
        sPendingNavigationAtMs = 0;

        return fresh && !activity.isFinishing() && !activity.isDestroyed()
                && activity.minimizeForNavigation();
    }

    /** Called when the playback activity takes its surface back (expand / new video / destroy). */
    public static void deactivate() {
        sActive = false;
        sActivity = new WeakReference<>(null);
        sPendingNavigation = new WeakReference<>(null);
        sPendingNavigationAtMs = 0;
        sHandoffStill = null;
        sMiniEntryStill = null;
        sMiniBounds = null;
        sMiniBoundsCrop = false;
    }

    /**
     * The session-long video {@link SurfaceTexture} (see MobilePlaybackActivity's persistent
     * surface docs). The Browse card re-parents this into its own TextureView while the mini
     * session is active - the codec's output surface never changes, so playback never stalls.
     */
    @Nullable
    public static SurfaceTexture getSessionTexture() {
        return isActive() ? sActivity.get().getSessionTexture() : null;
    }

    /** Last card frame, captured by Browse when it detaches; shown by the expanding player. */
    public static void setHandoffStill(@Nullable Bitmap frame) {
        sHandoffStill = frame;
    }

    /** Initial full-size frame shown while a newly-created mini TextureView adopts the session. */
    static void setMiniEntryStill(@Nullable Bitmap frame) {
        sMiniEntryStill = frame;
    }

    /** Consume the initial mini frame (single use by the destination screen). */
    @Nullable
    public static Bitmap takeMiniEntryStill() {
        Bitmap frame = sMiniEntryStill;
        sMiniEntryStill = null;
        return frame;
    }

    /** Exact on-screen mini rectangle, used to reverse the morph without a geometry jump. */
    public static void setMiniBounds(@Nullable Rect bounds) {
        setMiniBounds(bounds, false);
    }

    /**
     * NEWTUBE(mini-style): {@code crop} - the video fills these bounds' width and is cut to their
     * height (the video-background bar, see MiniPlayerStyle.Target#crop) rather than fitted.
     */
    public static void setMiniBounds(@Nullable Rect bounds, boolean crop) {
        sMiniBounds = bounds != null ? new Rect(bounds) : null;
        sMiniBoundsCrop = bounds != null && crop;
    }

    /** NEWTUBE(mini-style): see {@link #setMiniBounds(Rect, boolean)}; read before the take. */
    static boolean isMiniBoundsCropped() {
        return sMiniBounds != null && sMiniBoundsCrop;
    }

    @Nullable
    static Rect takeMiniBounds() {
        Rect bounds = sMiniBounds;
        sMiniBounds = null;
        sMiniBoundsCrop = false;
        return bounds;
    }

    /** Consume the captured card frame (single use). */
    @Nullable
    static Bitmap takeHandoffStill() {
        Bitmap frame = sHandoffStill;
        sHandoffStill = null;
        return frame;
    }

    /** True while a live, still-alive player session is docked in the mini bar. */
    public static boolean isActive() {
        if (!sActive) {
            return false;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getSharedPlayer() == null) {
            deactivate(); // the session died behind our back - self-heal
            return false;
        }
        return true;
    }

    /** The live player to render in the bar, or null when no mini session is active. */
    @Nullable
    public static ExoPlayer getPlayer() {
        return isActive() ? sActivity.get().getSharedPlayer() : null;
    }

    /**
     * NEWTUBE(issue #9): give a host card's video frame (an {@link AspectRatioFrameLayout}, see
     * {@code mobile_mini_player_frame}) the playing video's shape. The card is a fixed 16:9 box and
     * a TextureView scales its buffer to its own bounds, so a 2.35:1 film, a 4:3 clip or a vertical
     * video filled the card stretched. Now it is letterboxed on the card's black background, like
     * the watch page's video box the minimize morph shrinks into it. Unknown size: unchanged.
     * Cheap and idempotent (setAspectRatio only relayouts on a change), so hosts call it from their
     * sync and their progress tick - the tick picks up an autoplayed video of another shape.
     */
    public static void fitToVideo(@Nullable View frame) {
        if (!(frame instanceof AspectRatioFrameLayout)) {
            return;
        }
        // NEWTUBE(black-bars): the frame's shape and its picture's crop are checked together right
        // before every frame is drawn - set apart (a tick, a layout), one could be a frame behind
        // the other, a picture stretched for a frame.
        if (!sCropFrames.containsKey(frame)) {
            sCropFrames.put(frame, Boolean.TRUE);
            frame.getViewTreeObserver().addOnPreDrawListener(() -> {
                boolean reshaped = updateFrameAspect((AspectRatioFrameLayout) frame);
                applyCropTransform(frame);
                return !reshaped; // a new shape: lay out first, then draw
            });
        }
        if (updateFrameAspect((AspectRatioFrameLayout) frame)) {
            frame.requestLayout();
        }
        applyCropTransform(frame);
    }

    /** The frame's shape: the video's, cropped. True if it changed. */
    private static boolean updateFrameAspect(AspectRatioFrameLayout frame) {
        ExoPlayer player = getPlayer();
        if (player == null) {
            return false;
        }
        VideoSize size = player.getVideoSize();
        if (size.width <= 0 || size.height <= 0) {
            return false;
        }
        float aspect = croppedAspect(size.width * size.pixelWidthHeightRatio / size.height);
        Float shown = sFrameAspects.get(frame);
        if (shown != null && Math.abs(shown - aspect) < 0.0001f) {
            return false;
        }
        sFrameAspects.put(frame, aspect);
        frame.setAspectRatio(aspect);
        return true;
    }

    private static final java.util.WeakHashMap<View, Float> sFrameAspects = new java.util.WeakHashMap<>();

    private static void applyCropTransform(View frame) {
        android.view.ViewGroup group = (android.view.ViewGroup) frame;
        float[] crop = sCrop;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            // A child laid out later (a new texture) gets its crop then, too.
            if (!sCropFrames.containsKey(child)) {
                sCropFrames.put(child, Boolean.TRUE);
                child.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    if ((r - l != or - ol || b - t != ob - ot) && v.getParent() instanceof View) {
                        applyCropTransform((View) v.getParent());
                    }
                });
            }
            // NEWTUBE(pills): a frame of a fixed shape (the plain pill bar's thumbnail, FILL) is
            // filled whatever the video's shape: a square or vertical video is cropped to it, not
            // shown as a narrower thumbnail.
            float fillX = 1f;
            float fillY = 1f;
            Float aspect = sFrameAspects.get(frame);
            if (frame instanceof AspectRatioFrameLayout
                    && ((AspectRatioFrameLayout) frame).getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FILL
                    && aspect != null && aspect > 0f && frame.getWidth() > 0 && frame.getHeight() > 0) {
                float shape = frame.getWidth() / (float) frame.getHeight();
                if (aspect < shape) {
                    fillY = shape / aspect;
                } else {
                    fillX = aspect / shape;
                }
            }
            if (crop == null && fillX == 1f && fillY == 1f) {
                child.setScaleX(1f);
                child.setScaleY(1f);
                child.setTranslationX(0f);
                child.setTranslationY(0f);
                continue;
            }
            if (child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue; // laid out later: its layout listener crops it then
            }
            if (crop == null) {
                crop = new float[4];
            }
            float sx = fillX / Math.max(0.01f, 1f - crop[2] - crop[3]);
            float sy = fillY / Math.max(0.01f, 1f - crop[0] - crop[1]);
            child.setPivotX(child.getWidth() / 2f);
            child.setPivotY(child.getHeight() / 2f);
            child.setScaleX(sx);
            child.setScaleY(sy);
            child.setTranslationX(-child.getWidth() * (crop[2] - crop[3]) / 2f * sx);
            child.setTranslationY(-child.getHeight() * (crop[0] - crop[1]) / 2f * sy);
        }
    }

    /** NEWTUBE(black-bars): the playing video's crop (top, bottom, left, right shares), or null. */
    @Nullable
    private static float[] sCrop;

    public static void setCrop(@Nullable float[] crop) {
        sCrop = crop;
    }

    @Nullable
    public static float[] getCrop() {
        return sCrop;
    }

    /** The cropped picture's shape (width / height), for a picture of {@code aspect}. */
    public static float croppedAspect(float aspect) {
        float[] crop = sCrop;
        return crop == null ? aspect : aspect * (1f - crop[2] - crop[3]) / Math.max(0.01f, 1f - crop[0] - crop[1]);
    }

    /** NEWTUBE(black-bars): the player box's shape (16:9, or the cropped picture's) - the card's. */
    private static float sBoxAspect = 16f / 9f;

    public static void setBoxAspect(float aspect) {
        sBoxAspect = aspect > 0f ? aspect : 16f / 9f;
    }

    public static float getBoxAspect() {
        return sBoxAspect;
    }

    /** Frames that redo their crop when they are laid out again (their children's size changed). */
    private static final java.util.WeakHashMap<View, Boolean> sCropFrames = new java.util.WeakHashMap<>();

    /**
     * NEWTUBE(loudness): the session's volume as its player controller keeps it, apart from loudness
     * normalization (a direct ExoPlayer write would be undone by the next normalization update);
     * -1 when no mini session is active.
     */
    public static float getVolume() {
        return isActive() ? sActivity.get().getVolume() : -1;
    }

    /** NEWTUBE(loudness): see {@link #getVolume()}. No-op when no mini session is active. */
    public static void setVolume(float volume) {
        if (isActive()) {
            sActivity.get().setVolume(volume);
        }
    }

    /** NEWTUBE(mini-next): the playing video is in a playlist with a video after it. */
    public static boolean hasNext() {
        return isActive() && sActivity.get().hasQueueNext();
    }

    /** NEWTUBE(mini-next): play the playlist's next video (the player's own Next). */
    public static void playNext() {
        if (isActive()) {
            sActivity.get().playNextFromMini();
        }
    }

    /** Metadata of the playing video (title/author for the bar), or null. */
    @Nullable
    public static Video getVideo() {
        return isActive() ? sActivity.get().getVideo() : null;
    }

    /**
     * Expand: bring the (still alive) playback activity back to the front. Its onResume
     * re-claims the video surface and deactivates this bridge. The caller must detach the
     * mini bar's PlayerView first so the surface is free.
     */
    public static void expand(Context context) {
        ViewManager.instance(context).startView(PlaybackView.class);
    }

    /** Close from the bar's X: stop playback and finish the hidden playback activity. */
    public static void close() {
        MobilePlaybackActivity activity = sActivity.get();
        deactivate();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.closeFromMiniPlayer();
        }
    }
}
