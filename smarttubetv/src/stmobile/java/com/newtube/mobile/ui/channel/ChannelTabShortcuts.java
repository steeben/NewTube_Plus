package com.newtube.mobile.ui.channel;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.List;

/**
 * NEWTUBE(#63): the TV channel page ends with an untitled section of shortcut cards - channel cards
 * for the channel itself, one per tab (Videos, Live, Releases, Playlists), each carrying that tab's
 * browse params and nothing else. Labelled with the generic untitled fallback it read "Videos", the
 * same as the first tab; it is labelled "More" instead.
 */
final class ChannelTabShortcuts {
    private ChannelTabShortcuts() {
    }

    /** Whether every card is a channel-tab shortcut (and there is at least one). */
    static boolean isShortcutRow(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return false;
        }
        for (Video video : videos) {
            if (!isShortcut(video)) {
                return false;
            }
        }
        return true;
    }

    /** A channel card that opens one of a channel's tabs: a channel id plus tab params, no video or playlist. */
    static boolean isShortcut(Video video) {
        return video != null && video.videoId == null && video.playlistId == null
                && video.channelId != null && video.playlistParams != null && !video.playlistParams.isEmpty();
    }
}
