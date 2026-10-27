package com.newtube.mobile.ui.channel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;

/** NEWTUBE(#63): the channel's untitled shortcut row is labelled "More", not "Videos". */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChannelTabShortcutsTest {
    private static final String CHANNEL = "UC4-79UOlP48-QNGgCko5p2g";

    @Test
    public void theShortcutRowAsServedForMrBeast2() {
        assertTrue(ChannelTabShortcuts.isShortcutRow(Arrays.asList(
                shortcut("Videos", "8gYHEgVqAIABAg%3D%3D"),
                shortcut("Live", "8gYHEgVKAIABAg%3D%3D"),
                shortcut("Playlists", "8gYLEgliBAoCEGiAAQI%3D"))));
    }

    @Test
    public void otherUntitledSectionsKeepTheVideosFallback() {
        Video video = new Video();
        video.videoId = "_ljhBopSEVI";
        video.playlistParams = "wAEB"; // videos carry params too
        assertFalse("videos", ChannelTabShortcuts.isShortcutRow(Arrays.asList(video)));

        Video channel = new Video();
        channel.title = "MrBeast";
        channel.channelId = "UCX6OQ3DkcsbYNE6H8uQQuVA";
        assertFalse("a shelf of real channels", ChannelTabShortcuts.isShortcutRow(Arrays.asList(channel)));

        Video playlist = shortcut("New Uploads", "kgYPKgtpZklfZndnNTVrOEAB");
        playlist.playlistId = "PLj-VLkYRjRxn9gKg2XKYNP_TtJDTKN2-f";
        assertFalse("playlists", ChannelTabShortcuts.isShortcutRow(Arrays.asList(playlist)));

        assertFalse("mixed", ChannelTabShortcuts.isShortcutRow(
                Arrays.asList(shortcut("Videos", "8gYHEgVqAIABAg%3D%3D"), video)));
        assertFalse("empty", ChannelTabShortcuts.isShortcutRow(new ArrayList<>()));
        assertFalse("null", ChannelTabShortcuts.isShortcutRow(null));
    }

    private static Video shortcut(String title, String params) {
        Video video = new Video();
        video.title = title;
        video.channelId = CHANNEL;
        video.playlistParams = params;
        return video;
    }
}
