package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OwnPlaylistMatchTest {
    private static final PlaylistInfo WATCH_LATER = info("WL", "Watch later", true);
    private static final PlaylistInfo MUSIC = info("PLmusic", "Music", true);
    private static final PlaylistInfo LATER_EMPTY = info("PLlater", "Later", false);
    private static final List<PlaylistInfo> MINE = Arrays.asList(WATCH_LATER, MUSIC, LATER_EMPTY);

    @Test
    public void cardInWatchLaterMatchesWatchLater() {
        assertSame(WATCH_LATER, OwnPlaylistMatch.find("WL", MINE));
    }

    @Test
    public void cardInOwnPlaylistMatchesIt() {
        assertSame(MUSIC, OwnPlaylistMatch.find("PLmusic", MINE));
    }

    @Test
    public void someoneElsesPlaylistOrMixDoesNotMatch() {
        assertNull(OwnPlaylistMatch.find("PLsomeoneElse", MINE));
        assertNull(OwnPlaylistMatch.find("RDmix", MINE));
    }

    @Test
    public void ownPlaylistWithoutTheVideoDoesNotMatch() {
        assertNull(OwnPlaylistMatch.find("PLlater", MINE));
    }

    @Test
    public void signedOutOrNoPlaylistDoesNotMatch() {
        assertNull(OwnPlaylistMatch.find("WL", Collections.emptyList()));
        assertNull(OwnPlaylistMatch.find("WL", null));
        assertNull(OwnPlaylistMatch.find(null, MINE));
    }

    private static PlaylistInfo info(String id, String title, boolean selected) {
        return new PlaylistInfo() {
            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public String getPlaylistId() {
                return id;
            }

            @Override
            public boolean isSelected() {
                return selected;
            }

            @Override
            public int getSize() {
                return 0;
            }

            @Override
            public int getCurrentIndex() {
                return 0;
            }
        };
    }
}
