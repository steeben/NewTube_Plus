package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(song): videos the user marked (player gear > Song) as a song, or as not one. A mark
 * overrides the app's own guess (MobilePlaybackActivity.isMusic), and lasts.
 */
public final class MusicMarks {
    private static final String PREFS = "newtube_song_marks";

    private MusicMarks() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True: marked a song; false: marked not one; null: no mark (the guess decides). */
    @Nullable
    public static Boolean get(Context context, @Nullable String videoId) {
        if (videoId == null || !prefs(context).contains(videoId)) {
            return null;
        }
        return prefs(context).getBoolean(videoId, false);
    }

    public static void set(Context context, String videoId, boolean song) {
        prefs(context).edit().putBoolean(videoId, song).apply();
    }
}
