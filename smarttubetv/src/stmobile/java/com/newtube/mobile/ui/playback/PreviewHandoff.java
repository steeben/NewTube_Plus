package com.newtube.mobile.ui.playback;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * NEWTUBE(autoplay-handoff): a feed preview that was playing when its card was tapped, still
 * playing, on its way to the watch page. The page shows it (and lets its sound in) while its own
 * player loads the video at the quality set in Settings, then cross-fades to that player at the
 * same moment of the video. Before this the preview paused on the tap and the page started over:
 * the video stopped for a second, then jumped.
 *
 * <p>One at a time. Not taken within {@link #EXPIRE_MS} (the page never opened, or opened another
 * video), it is released.
 */
final class PreviewHandoff {
    private static final long EXPIRE_MS = 2_000;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private static ExoPlayer sPlayer;
    @Nullable
    private static String sVideoId;
    /** The preview's sound was off (it fades in on the page). */
    private static boolean sMuted;

    private static final Runnable sExpire = PreviewHandoff::clear;

    private PreviewHandoff() {
    }

    static void offer(ExoPlayer player, String videoId, boolean muted) {
        clear();
        sPlayer = player;
        sVideoId = videoId;
        sMuted = muted;
        sHandler.postDelayed(sExpire, EXPIRE_MS);
    }

    /** The preview of {@code videoId}, now the caller's to release; null if there is none. */
    @Nullable
    static ExoPlayer take(@Nullable String videoId) {
        if (sPlayer == null || videoId == null || !videoId.equals(sVideoId)) {
            return null;
        }
        ExoPlayer player = sPlayer;
        sPlayer = null;
        sVideoId = null;
        sHandler.removeCallbacks(sExpire);
        return player;
    }

    static boolean wasMuted() {
        return sMuted;
    }

    static void clear() {
        sHandler.removeCallbacks(sExpire);
        if (sPlayer != null) {
            sPlayer.release();
            sPlayer = null;
        }
        sVideoId = null;
    }
}
