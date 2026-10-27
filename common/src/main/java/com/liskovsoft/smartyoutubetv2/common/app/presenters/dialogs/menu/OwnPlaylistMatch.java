package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu;

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;

import java.util.List;

/**
 * NEWTUBE(#36): whether a video card sits inside one of the signed-in user's own playlists
 * (Watch later included), so its menu can offer "Remove from &lt;playlist&gt;".
 *
 * <p>The card's {@code playlistId} is the playlist it was listed in (a playlist page, a pinned
 * playlist section). {@code infos} is the account's own playlists as the add-to-playlist call
 * returns them for this video - only playlists the user can edit, Watch later first, each flagged
 * with whether the video is in it. Signed out that list is empty, so nothing matches. A mix or
 * someone else's playlist is not in the list, so it never matches either.
 */
final class OwnPlaylistMatch {
    private OwnPlaylistMatch() {
    }

    /** The user's playlist that this card was listed in and that holds the video, or null. */
    static PlaylistInfo find(String cardPlaylistId, List<PlaylistInfo> infos) {
        if (cardPlaylistId == null || infos == null) {
            return null;
        }

        for (PlaylistInfo info : infos) {
            if (info != null && cardPlaylistId.equals(info.getPlaylistId())) {
                return info.isSelected() && info.getTitle() != null ? info : null;
            }
        }

        return null;
    }
}
