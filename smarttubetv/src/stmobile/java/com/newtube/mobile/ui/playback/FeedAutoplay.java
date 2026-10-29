package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.newtube.mobile.player.Media3SourceFactory;
import com.newtube.mobile.player.MediaHttpClient;
import com.newtube.mobile.ui.common.UiPrefs;

import java.lang.ref.WeakReference;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * NEWTUBE(autoplay): the feed plays the card nearest the reading line (55% up the screen) in its
 * thumbnail, muted, like YouTube's. A small player of its own, at a low rung: the formats come
 * from the same /player answer downloads use; a refused link (403) just ends the preview. A
 * speaker in the corner unmutes it; a line along the bottom shows how far it is. Tapping the card
 * opens the real player at that point, from the preview's own picture.
 *
 * <p>Only with one column, only while the screen is in front with nothing else playing (no mini
 * player), and only when Settings > Video cards > Autoplay is on.</p>
 */
public final class FeedAutoplay {
    /** What the host decides: whether a preview may play now. */
    public interface Gate {
        boolean canAutoplay();

        /**
         * NEWTUBE(frosted): the preview drew a new frame (video, or its line moved) in {@code frame}.
         * Glass over it must record it again whole: left alone, the GPU refreshed only the changed
         * patch of the blur, which showed as small sharp rectangles in the bar.
         */
        default void onPreviewFrame(@NonNull View frame) {
        }
    }

    /** The cards' parts the preview uses. */
    public interface Card {
        @Nullable
        Video video();

        /** The thumbnail's frame (a FrameLayout): the preview goes over the thumbnail in it. */
        @Nullable
        ViewGroup thumbnailFrame();

        /** The thumbnail image (the still that hands over to the player). */
        @Nullable
        ImageView thumbnail();
    }

    private static final float READING_LINE = 0.45f; // from the top: 55% up the screen
    private static final long SETTLE_MS = 450;
    private static final long TICK_MS = 250;
    private static final int MAX_HEIGHT = 720; // NEWTUBE(autoplay): was 480
    private static boolean sMuted = true;
    private static WeakReference<FeedAutoplay> sCurrent = new WeakReference<>(null);

    private final Context mContext;
    private final RecyclerView mGrid;
    private final Gate mGate;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mActive;

    @Nullable
    private ExoPlayer mPlayer;
    @Nullable
    private Disposable mFetch;
    @Nullable
    private String mPlayingId;
    @Nullable
    private ViewGroup mFrame;
    @Nullable
    private TextureView mTexture;
    @Nullable
    private ImageButton mSpeaker;
    @Nullable
    private PlayerTimeBar mLine;
    private boolean mLineScrubbing;
    private boolean mShown;

    private final Runnable mEvaluate = this::evaluate;
    /** The badge's glass: a small copy of the preview's frame, taken this often. */
    private static final long LIVE_FRAME_MS = 66;
    private static final int LIVE_FRAME_WIDTH = 96;
    private static final int LIVE_FRAME_HEIGHT = 54;
    @Nullable
    private Bitmap mLiveFrame;
    private long mLiveAt;
    /** The duration badge counting down, and the length it showed before. */
    @Nullable
    private android.widget.TextView mBadge;
    @Nullable
    private CharSequence mBadgeText;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateLine();
            updateBadge();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    public FeedAutoplay(Context context, RecyclerView grid, Gate gate) {
        mContext = context;
        mGrid = grid;
        mGate = gate;
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                evaluateSoon();
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                evaluateSoon();
            }
        });
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                evaluateSoon();
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                if (mFrame != null && isAncestor(view, mFrame)) {
                    stop(); // its card left the screen (or was recycled)
                }
            }
        });
    }

    /** The screen came to the front (true) or left it (false: everything stops, the player goes). */
    public void setActive(boolean active) {
        mActive = active;
        if (active) {
            sCurrent = new WeakReference<>(this);
            evaluateSoon();
        } else {
            stop();
            releasePlayer();
        }
    }

    public void release() {
        setActive(false);
        mHandler.removeCallbacksAndMessages(null);
    }

    /** Something the gate depends on changed (a tab, the You panel, a setting): look again. */
    public void evaluateSoon() {
        mHandler.removeCallbacks(mEvaluate);
        mHandler.postDelayed(mEvaluate, SETTLE_MS);
    }

    private void evaluate() {
        if (!mActive || !mGate.canAutoplay() || !UiPrefs.isFeedAutoplayAllowed(mContext)) {
            stop();
            return;
        }
        Card target = pickTarget();
        Video video = target != null ? target.video() : null;
        ViewGroup frame = target != null ? target.thumbnailFrame() : null;
        if (video == null || frame == null || video.videoId == null || video.isLive || video.isUpcoming) {
            stop();
            return;
        }
        if (video.videoId.equals(mPlayingId) && frame == mFrame) {
            return; // already playing there
        }
        start(video, frame);
    }

    /** The card whose thumbnail's middle is nearest the reading line, if mostly on screen. */
    @Nullable
    private Card pickTarget() {
        int[] at = new int[2];
        float line = mGrid.getRootView().getHeight() * READING_LINE;
        Card best = null;
        float bestDistance = Float.MAX_VALUE;
        android.graphics.Rect visible = new android.graphics.Rect();
        for (int i = 0; i < mGrid.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = mGrid.getChildViewHolder(mGrid.getChildAt(i));
            if (!(holder instanceof Card)) {
                continue;
            }
            ViewGroup frame = ((Card) holder).thumbnailFrame();
            if (frame == null || frame.getHeight() <= 0 || !frame.isShown()) {
                continue;
            }
            if (!frame.getGlobalVisibleRect(visible) || visible.height() < frame.getHeight() * 0.6f) {
                continue;
            }
            frame.getLocationInWindow(at);
            float distance = Math.abs(at[1] + frame.getHeight() / 2f - line);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (Card) holder;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------

    private void start(Video video, ViewGroup frame) {
        stop();
        mPlayingId = video.videoId;
        mFrame = frame;
        String videoId = video.videoId;
        mFetch = YouTubeMediaItemService.instance().getFormatInfoObserve(videoId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(info -> {
                    if (videoId.equals(mPlayingId) && mFrame == frame && mActive) {
                        play(info, frame);
                    }
                }, error -> {
                    if (videoId.equals(mPlayingId)) {
                        stop();
                    }
                });
    }

    private void play(@Nullable MediaItemFormatInfo info, ViewGroup frame) {
        if (info == null || info.isUnplayable() || info.isLive()) {
            stop();
            return;
        }
        MediaSource source = buildSource(info);
        if (source == null) {
            stop();
            return;
        }
        ExoPlayer player = ensurePlayer();
        attachViews(frame);
        if (mTexture != null) {
            player.setVideoTextureView(mTexture);
        }
        player.setVolume(sMuted ? 0f : 1f);
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Nullable
    private MediaSource buildSource(MediaItemFormatInfo info) {
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(
                MediaHttpClient.create(OkHttpManager.instance().getClient()))
                .setUserAgent(Media3SourceFactory.MEDIA_USER_AGENT);
        // Bounded Range reads: one open-ended GET of an adaptive stream is paced to playback speed
        // and the preview froze a few seconds in (RangedDataSource).
        RangedDataSource.Factory ranged = new RangedDataSource.Factory(http);
        // NEWTUBE(autoplay): the best picture up to 720p with the sound beside it; the one file with
        // both (360p) only when there is no such picture. The opened player then plays at the
        // quality set in Settings.
        MediaFormat video = pickVideo(info.getAdaptiveFormats());
        if (video == null) {
            MediaFormat muxed = pickMuxed(info.getUrlFormats());
            return muxed == null ? null
                    : new ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(Uri.parse(muxed.getUrl())));
        }
        MediaSource picture = new ProgressiveMediaSource.Factory(ranged)
                .createMediaSource(MediaItem.fromUri(Uri.parse(video.getUrl())));
        MediaFormat audio = pickAudio(info.getAdaptiveFormats());
        if (audio == null) {
            return picture;
        }
        MediaSource sound = new ProgressiveMediaSource.Factory(ranged)
                .createMediaSource(MediaItem.fromUri(Uri.parse(audio.getUrl())));
        return new MergingMediaSource(true, picture, sound);
    }

    @Nullable
    private static MediaFormat pickMuxed(@Nullable List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }
        MediaFormat best = null;
        for (MediaFormat format : formats) {
            if (format.getUrl() == null || format.getMimeType() == null || !format.getMimeType().startsWith("video/mp4")
                    || format.getHeight() <= 0 || format.getHeight() > MAX_HEIGHT) {
                continue;
            }
            if (best == null || format.getHeight() > best.getHeight()) {
                best = format;
            }
        }
        return best;
    }

    @Nullable
    private static MediaFormat pickVideo(@Nullable List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }
        MediaFormat best = null;
        for (MediaFormat format : formats) {
            String mime = format.getMimeType();
            if (format.getUrl() == null || mime == null || !mime.startsWith("video/mp4") || !mime.contains("avc1")
                    || format.getHeight() <= 0 || format.getHeight() > MAX_HEIGHT || format.isOtf()) {
                continue;
            }
            if (best == null || format.getHeight() > best.getHeight()) {
                best = format;
            }
        }
        return best;
    }

    @Nullable
    private static MediaFormat pickAudio(@Nullable List<MediaFormat> formats) {
        if (formats == null) {
            return null;
        }
        MediaFormat best = null;
        for (MediaFormat format : formats) {
            String mime = format.getMimeType();
            if (format.getUrl() == null || mime == null || !mime.startsWith("audio/mp4") || format.isDrc()) {
                continue;
            }
            // The original language first (YouTube's ".4" track, or the only one), not a dub;
            // then the smallest: it is a preview.
            if (best == null || audioRank(format) > audioRank(best)
                    || (audioRank(format) == audioRank(best) && bitrate(format) < bitrate(best))) {
                best = format;
            }
        }
        return best;
    }

    private static int audioRank(MediaFormat format) {
        String id = format.getAudioTrackId();
        return id == null || id.isEmpty() || id.endsWith(".4") ? 2 : 1;
    }

    private static long bitrate(MediaFormat format) {
        try {
            return format.getBitrate() != null ? Long.parseLong(format.getBitrate()) : Long.MAX_VALUE;
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private ExoPlayer ensurePlayer() {
        if (mPlayer == null) {
            mPlayer = new ExoPlayer.Builder(mContext).build();
            mPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
            mPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mPlayer.addListener(mListener);
        }
        return mPlayer;
    }

    private final Player.Listener mListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            showViews();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            stop(); // a refused link (403) or the like: no preview for this one
        }
    };

    /**
     * NEWTUBE(autoplay-handoff): how far ahead of the preview the watch page's player loads. It
     * waits there, paused, for the preview to catch up, and takes over on that frame.
     */
    private static final long HANDOFF_LEAD_MS = 2_500;

    // ---------------------------------------------------------------------------------
    // The views over the thumbnail
    // ---------------------------------------------------------------------------------

    private void attachViews(ViewGroup frame) {
        float density = mContext.getResources().getDisplayMetrics().density;
        mTexture = new TextureView(mContext);
        mTexture.setAlpha(0f);
        frame.addView(mTexture, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The player's own seek bar, small: a line with its dot, and the dot can be dragged.
        mLine = new PlayerTimeBar(mContext);
        // On the thumbnail's bottom edge (the dot may hang over the title below).
        mLine.setTrackAtBottom(true);
        mLine.setShown(true, false);
        mLine.setAlpha(0f);
        mLine.addListener(new androidx.media3.ui.TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull androidx.media3.ui.TimeBar timeBar, long position) {
                mLineScrubbing = true;
            }

            @Override
            public void onScrubMove(@NonNull androidx.media3.ui.TimeBar timeBar, long position) {
            }

            @Override
            public void onScrubStop(@NonNull androidx.media3.ui.TimeBar timeBar, long position, boolean canceled) {
                mLineScrubbing = false;
                if (!canceled && mPlayer != null) {
                    mPlayer.seekTo(position);
                }
            }
        });
        // Beside the thumbnail's frame, not in it: the frame clips to its rounded corners, which
        // would cut the dot in half. Its bottom on the frame's bottom, drawn over the frame.
        ViewGroup card = frame.getParent() instanceof ViewGroup ? (ViewGroup) frame.getParent() : frame;
        // Taller than the bar by the dot's lower half, and moved down by as much: the line stays on
        // the thumbnail's edge and the whole dot is inside the view (nothing clips it).
        int below = Math.round(8 * density);
        int barHeight = Math.round(20 * density) + below;
        mLine.setPadding(0, 0, 0, below);
        mLine.setTranslationY(below);
        // The line keeps to the thumbnail's rounded shape; the dot may go past it.
        final int belowPx = below;
        mLine.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!(v instanceof PlayerTimeBar)) {
                return;
            }
            float radius = frame instanceof com.google.android.material.card.MaterialCardView
                    ? ((com.google.android.material.card.MaterialCardView) frame).getRadius() : 0f;
            float bottom = (b - t) - belowPx;
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(0f, bottom - frame.getHeight(), r - l, bottom, radius, radius,
                    android.graphics.Path.Direction.CW);
            ((PlayerTimeBar) v).setTrackClip(clip);
        });
        if (card instanceof androidx.constraintlayout.widget.ConstraintLayout && frame.getId() != View.NO_ID) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lineParams =
                    new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, barHeight);
            lineParams.startToStart = frame.getId();
            lineParams.endToEnd = frame.getId();
            lineParams.bottomToBottom = frame.getId();
            card.addView(mLine, lineParams);
            mLine.setTranslationZ(frame.getElevation() + frame.getTranslationZ() + 1f);
        } else {
            frame.addView(mLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    barHeight, Gravity.BOTTOM));
        }

        mSpeaker = new ImageButton(mContext);
        mSpeaker.setBackgroundResource(R.drawable.bg_mini_scrim_circle);
        int pad = Math.round(7 * density);
        mSpeaker.setPadding(pad, pad, pad, pad);
        mSpeaker.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mSpeaker.setAlpha(0f);
        updateSpeaker();
        mSpeaker.setOnClickListener(v -> {
            sMuted = !sMuted;
            if (mPlayer != null) {
                mPlayer.setVolume(sMuted ? 0f : 1f);
            }
            updateSpeaker();
        });
        int size = Math.round(36 * density);
        FrameLayout.LayoutParams speakerParams = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        int margin = Math.round(8 * density);
        speakerParams.setMargins(margin, margin, margin, margin);
        frame.addView(mSpeaker, speakerParams);
    }

    private void updateSpeaker() {
        if (mSpeaker != null) {
            mSpeaker.setImageResource(sMuted ? R.drawable.ic_player_volume_off : R.drawable.ic_player_volume_up);
            mSpeaker.setContentDescription(mContext.getString(sMuted
                    ? R.string.mobile_autoplay_unmute : R.string.mobile_autoplay_mute));
        }
    }

    /** NEWTUBE(frosted): each display frame while the preview shows, the host hears of it. */
    private final Runnable mFrameLoop = new Runnable() {
        @Override
        public void run() {
            ViewGroup frame = mFrame;
            if (!mShown || frame == null) {
                return;
            }
            mGate.onPreviewFrame(frame);
            // The duration badge's glass shows the playing picture, not the still thumbnail.
            long now = android.os.SystemClock.uptimeMillis();
            TextureView texture = mTexture;
            if (texture != null && texture.isAvailable() && now - mLiveAt >= LIVE_FRAME_MS) {
                mLiveAt = now;
                if (mLiveFrame == null) {
                    mLiveFrame = Bitmap.createBitmap(LIVE_FRAME_WIDTH, LIVE_FRAME_HEIGHT, Bitmap.Config.ARGB_8888);
                }
                try {
                    texture.getBitmap(mLiveFrame);
                    com.newtube.mobile.ui.common.BadgeGlassDrawable.setLiveIn(frame, mLiveFrame, texture);
                } catch (RuntimeException e) {
                    // the texture went away under us: the badge keeps its last picture
                }
            }
            frame.postOnAnimation(this);
        }
    };

    private void showViews() {
        if (mShown) {
            return;
        }
        mShown = true;
        if (mFrame != null) {
            mFrame.postOnAnimation(mFrameLoop);
        }
        for (View view : new View[] {mTexture, mSpeaker, mLine}) {
            if (view != null) {
                view.animate().alpha(1f).setDuration(260).start();
            }
        }
        View watch = mFrame != null ? mFrame.findViewById(R.id.video_watch_progress) : null;
        if (watch != null) {
            watch.animate().alpha(0f).setDuration(200).start();
        }
    }

    /** NEWTUBE(autoplay): the duration badge counts down the time left while the preview plays. */
    private void updateBadge() {
        ViewGroup frame = mFrame;
        if (mPlayer == null || frame == null || !mShown) {
            return;
        }
        if (mBadge == null) {
            View badge = frame.findViewById(R.id.video_badge);
            if (badge == null) {
                badge = frame.findViewById(R.id.related_badge);
            }
            if (!(badge instanceof android.widget.TextView) || badge.getVisibility() != View.VISIBLE) {
                return;
            }
            CharSequence text = ((android.widget.TextView) badge).getText();
            if (text == null || !text.toString().trim().matches("\\d+(:\\d{2}){1,2}")) {
                return; // LIVE, SHORTS and the like: nothing to count
            }
            mBadge = (android.widget.TextView) badge;
            mBadgeText = text;
        }
        long duration = mPlayer.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) {
            return;
        }
        long left = Math.max(0, (duration - mPlayer.getCurrentPosition() + 999) / 1000);
        String value = left >= 3600
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", left / 3600, left / 60 % 60, left % 60)
                : String.format(java.util.Locale.US, "%d:%02d", left / 60, left % 60);
        if (!android.text.TextUtils.equals(mBadge.getText(), value)) {
            mBadge.setText(value);
        }
    }

    private void restoreBadge() {
        if (mBadge != null && mBadgeText != null) {
            mBadge.setText(mBadgeText);
        }
        mBadge = null;
        mBadgeText = null;
    }

    private void updateLine() {
        if (mPlayer == null || mLine == null || mLineScrubbing) {
            return;
        }
        long duration = mPlayer.getDuration();
        if (duration > 0 && duration != C.TIME_UNSET) {
            mLine.setDuration(duration);
            mLine.setPosition(mPlayer.getCurrentPosition());
            mLine.setBufferedPosition(mPlayer.getBufferedPosition());
        }
    }

    /** Stop the preview and take its views off the card (the player is kept for the next one). */
    public void stop() {
        mHandler.removeCallbacks(mTick);
        if (mFetch != null) {
            mFetch.dispose();
            mFetch = null;
        }
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.clearVideoTextureView(mTexture);
        }
        ViewGroup frame = mFrame;
        if (frame != null) {
            for (View view : new View[] {mTexture, mSpeaker, mLine}) {
                if (view != null && view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
            }
            View watch = frame.findViewById(R.id.video_watch_progress);
            if (watch != null) {
                watch.animate().cancel();
                watch.setAlpha(1f);
            }
            // Back to the still thumbnail under the badge, and its whole length.
            com.newtube.mobile.ui.common.BadgeGlassDrawable.setLiveIn(frame, null, null);
            restoreBadge();
        }
        mTexture = null;
        mSpeaker = null;
        mLine = null;
        mLineScrubbing = false;
        if (mFrame != null) {
            mFrame.removeCallbacks(mFrameLoop);
        }
        mFrame = null;
        mPlayingId = null;
        mShown = false;
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    private static boolean isAncestor(View ancestor, View view) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The preview's seek bar or speaker is under ({@code rawX}, {@code rawY}) (screen px): the
     * screen's own gestures (the tab swipe) leave that touch to them.
     */
    /** How long a card keeps the preview's frame after it was opened (the page's open morph). */
    private static final long STILL_HOLD_MS = 1_200;

    /** Thumbnails showing a preview's frame instead of their picture (to be loaded again). */
    private static final java.util.WeakHashMap<ImageView, Boolean> sStills = new java.util.WeakHashMap<>();

    /** True (once) if {@code thumbnail} shows a preview's frame: its card loads its picture again. */
    public static boolean takeStill(ImageView thumbnail) {
        return sStills.remove(thumbnail) != null;
    }

    /** True if {@code thumbnail} shows a preview's frame (the flag stays). */
    public static boolean isStill(ImageView thumbnail) {
        return sStills.containsKey(thumbnail);
    }

    public static boolean hitsControls(float rawX, float rawY) {
        FeedAutoplay autoplay = sCurrent.get();
        if (autoplay == null || !autoplay.mShown) {
            return false;
        }
        int[] at = new int[2];
        for (View view : new View[] {autoplay.mLine, autoplay.mSpeaker}) {
            if (view == null || !view.isShown()) {
                continue;
            }
            view.getLocationOnScreen(at);
            float slop = view == autoplay.mLine ? 12 * view.getResources().getDisplayMetrics().density : 0f;
            if (rawX >= at[0] && rawX <= at[0] + view.getWidth()
                    && rawY >= at[1] - slop && rawY <= at[1] + view.getHeight() + slop) {
                return true;
            }
        }
        return false;
    }

    /**
     * A card is being opened: if it is the one playing, its thumbnail becomes the preview's
     * current picture (so the player opens from what was on screen), and the player starts where
     * the preview is. The line stays - it travels up with the picture.
     */
    public static void beforeOpen(@Nullable ViewGroup frame, @Nullable ImageView thumbnail, @Nullable Video video) {
        FeedAutoplay autoplay = sCurrent.get();
        if (autoplay == null || frame == null || frame != autoplay.mFrame || autoplay.mPlayer == null
                || video == null || video.videoId == null || !video.videoId.equals(autoplay.mPlayingId)) {
            return;
        }
        ExoPlayer player = autoplay.mPlayer;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        // NEWTUBE(autoplay-handoff): a preview playing well before its end goes on playing, into
        // the watch page (PreviewHandoff), instead of pausing for the page to start over.
        boolean handoff = autoplay.mShown && player.getPlaybackState() == Player.STATE_READY
                && player.getPlayWhenReady() && duration != C.TIME_UNSET
                && position + HANDOFF_LEAD_MS + 3_000 < duration;
        if (handoff) {
            video.pendingPosMs = position + HANDOFF_LEAD_MS;
        } else if (position > 1_000) {
            video.pendingPosMs = position;
        }
        if (autoplay.mShown && autoplay.mTexture != null && autoplay.mTexture.isAvailable() && thumbnail != null) {
            try {
                Bitmap still = autoplay.mTexture.getBitmap();
                if (still != null) {
                    android.graphics.drawable.Drawable picture = thumbnail.getDrawable();
                    thumbnail.setImageBitmap(still);
                    sStills.put(thumbnail, Boolean.TRUE);
                    android.graphics.drawable.Drawable frame = thumbnail.getDrawable();
                    // Once the watch page has grown from the frame, the card shows its own picture
                    // again (it kept the frame until it was bound anew, often never).
                    thumbnail.postDelayed(() -> {
                        if (thumbnail.getDrawable() == frame && picture != null && sStills.remove(thumbnail) != null) {
                            thumbnail.setImageDrawable(picture);
                        }
                    }, STILL_HOLD_MS);
                }
            } catch (RuntimeException ignored) {
                // the thumbnail stays the thumbnail
            }
        }
        if (autoplay.mSpeaker != null) {
            autoplay.mSpeaker.setVisibility(View.INVISIBLE);
        }
        if (autoplay.mTexture != null) {
            autoplay.mTexture.setVisibility(View.INVISIBLE); // the still above is drawn instead
        }
        if (handoff) {
            // The player is the watch page's now: this preview lets go of it (stop() leaves it).
            player.removeListener(autoplay.mListener);
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            autoplay.mPlayer = null;
            PreviewHandoff.offer(player, video.videoId, sMuted);
            return;
        }
        player.setPlayWhenReady(false);
    }
}
