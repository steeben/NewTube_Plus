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
    private static final int MAX_HEIGHT = 480;
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
    private View mLine;
    @Nullable
    private MiniProgressDrawable mLineDrawable;
    private boolean mShown;

    private final Runnable mEvaluate = this::evaluate;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateLine();
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
        if (!mActive || !mGate.canAutoplay() || !UiPrefs.isFeedAutoplay(mContext)) {
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
        // One file with sound and picture (360p) when there is one; else a low picture + the sound.
        MediaFormat muxed = pickMuxed(info.getUrlFormats());
        if (muxed != null) {
            return new ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(Uri.parse(muxed.getUrl())));
        }
        MediaFormat video = pickVideo(info.getAdaptiveFormats());
        if (video == null) {
            return null;
        }
        MediaSource picture = new ProgressiveMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(Uri.parse(video.getUrl())));
        MediaFormat audio = pickAudio(info.getAdaptiveFormats());
        if (audio == null) {
            return picture;
        }
        MediaSource sound = new ProgressiveMediaSource.Factory(http)
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
            if (best == null || bitrate(format) < bitrate(best)) {
                best = format; // the smallest: it is a preview
            }
        }
        return best;
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
            mPlayer.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    showViews();
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    stop(); // a refused link (403) or the like: no preview for this one
                }
            });
        }
        return mPlayer;
    }

    // ---------------------------------------------------------------------------------
    // The views over the thumbnail
    // ---------------------------------------------------------------------------------

    private void attachViews(ViewGroup frame) {
        float density = mContext.getResources().getDisplayMetrics().density;
        mTexture = new TextureView(mContext);
        mTexture.setAlpha(0f);
        frame.addView(mTexture, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mLineDrawable = new MiniProgressDrawable(density);
        mLineDrawable.setColors(MiniPlayerStyle.accent(mContext), 0x33FFFFFF, false);
        mLine = new View(mContext);
        mLine.setBackground(mLineDrawable);
        mLine.setAlpha(0f);
        mLine.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(mLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(10 * density), Gravity.BOTTOM));

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

    private void showViews() {
        if (mShown) {
            return;
        }
        mShown = true;
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

    private void updateLine() {
        if (mPlayer == null || mLineDrawable == null) {
            return;
        }
        long duration = mPlayer.getDuration();
        if (duration > 0 && duration != C.TIME_UNSET) {
            mLineDrawable.setLevel((int) Math.min(10000, mPlayer.getCurrentPosition() * 10000L / duration));
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
                if (view != null && view.getParent() == frame) {
                    frame.removeView(view);
                }
            }
            View watch = frame.findViewById(R.id.video_watch_progress);
            if (watch != null) {
                watch.animate().cancel();
                watch.setAlpha(1f);
            }
        }
        mTexture = null;
        mSpeaker = null;
        mLine = null;
        mLineDrawable = null;
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
        if (position > 1_000) {
            video.pendingPosMs = position;
        }
        if (autoplay.mShown && autoplay.mTexture != null && autoplay.mTexture.isAvailable() && thumbnail != null) {
            try {
                Bitmap still = autoplay.mTexture.getBitmap();
                if (still != null) {
                    thumbnail.setImageBitmap(still);
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
        player.setPlayWhenReady(false);
    }
}
