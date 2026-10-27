package com.newtube.mobile.ui.common;

import android.util.Base64;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEWTUBE(shorts): NewTube has no Shorts. Every list the phone shows - the feeds, search results,
 * channel pages and their tabs, playlists, Up next - drops them, always; there is no setting (the
 * shared "Hide content" Shorts rows and the Shorts section are not shown on the phone, and nothing
 * here reads their prefs). A Short opened from a shared youtube.com/shorts/ link still plays in the
 * normal player: links are not lists.
 *
 * <p>A Short is what the item itself says it is ({@link Video#isShorts}: a reel renderer, the Shorts
 * badge or tile style, or a lockup whose tap opens the reel player - see MediaServiceCore's
 * ItemWrapper.isShorts), never a guess from its duration - a guess would also hide ordinary videos.
 */
public final class ShortsFilter {
    private ShortsFilter() {
    }

    public static boolean isShort(Video video) {
        return video != null && (video.isShorts || isShortsTile(video));
    }

    /**
     * A card that is not a video but a way into a Shorts list - the channel's (or Subscriptions')
     * "Shorts" tab served as a tile (#63). Opening it lands on a list whose every item is a Short,
     * so after filtering the user saw a blank "Shorts" screen; the tile goes with them.
     *
     * <p>Two shapes reach the phone. A channel's navigation row (the TV channel page's last,
     * untitled section: Videos, Shorts, Live, Playlists) is a run of channel cards for the channel
     * itself, each carrying the browse params of one of its tabs; the Shorts one is known by those
     * params ({@link #isShortsTabParams}), in every UI language. A tab or guide tile is a nested
     * list (MediaServiceCore's TabMediaItem/GuideMediaItem: {@code hasUploads()}).
     *
     * <p>The title is the fallback for either shape, never enough on its own: a real channel or
     * playlist may be called "Shorts". A channel card without tab params and a playlist card (it
     * has its playlist id) are both kept.
     */
    static boolean isShortsTile(Video video) {
        if (video.videoId != null || video.playlistId != null) {
            return false;
        }
        if (isShortsTabParams(video.playlistParams)) {
            return true;
        }
        boolean tabLink = video.hasNestedItems() || (video.channelId != null && video.playlistParams != null);
        return tabLink && isShortsName(video.getTitle());
    }

    /**
     * Whether channel browse params open the channel's Shorts tab. They are a base64 protobuf
     * (URL-encoded on the way in). YouTube's TV client names the tab inside field 110 / field 2:
     * Shorts {@code 8gYHEgVyAIABAg==} sets field 14, where Videos sets 13 ({@code 8gYHEgVqAIABAg==}),
     * Live 9 and Playlists 12. The web client sends Shorts as field 2 = "shorts" plus 110 / 1 / 19
     * ({@code EgZzaG9ydHPyBgUKA5oBAA==}). Anything unreadable is not Shorts.
     */
    static boolean isShortsTabParams(String params) {
        byte[] proto = decodeParams(params);
        if (proto == null) {
            return false;
        }
        byte[] tab = ProtoReader.field(proto, 110);
        if (tab != null) {
            byte[] tv = ProtoReader.field(tab, 2);
            if (tv != null && ProtoReader.has(tv, 14)) {
                return true;
            }
            byte[] web = ProtoReader.field(tab, 1);
            if (web != null && ProtoReader.has(web, 19)) {
                return true;
            }
        }
        byte[] name = ProtoReader.field(proto, 2);
        return name != null && "shorts".equals(new String(name, StandardCharsets.UTF_8));
    }

    private static byte[] decodeParams(String params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            String base64 = URLDecoder.decode(params, "UTF-8").replace('-', '+').replace('_', '/');
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Exception e) { // not base64 / not URL-encoded: not tab params
            return null;
        }
    }

    /** Just enough protobuf to find a field: its first occurrence. */
    static final class ProtoReader {
        private ProtoReader() {
        }

        /** Field {@code number}'s bytes when it is length-delimited, else null. */
        static byte[] field(byte[] data, int number) {
            return scan(data, number, true);
        }

        /** Whether field {@code number} is present, whatever its wire type. */
        static boolean has(byte[] data, int number) {
            return scan(data, number, false) != null;
        }

        private static byte[] scan(byte[] data, int number, boolean bytesOnly) {
            int[] pos = {0};
            try {
                while (pos[0] < data.length) {
                    long key = varint(data, pos);
                    int field = (int) (key >>> 3);
                    int wire = (int) (key & 7);
                    int start = pos[0];
                    switch (wire) {
                        case 0:
                            varint(data, pos);
                            break;
                        case 1:
                            pos[0] += 8;
                            break;
                        case 2:
                            long len = varint(data, pos);
                            if (len < 0 || len > data.length - pos[0]) { // no overflow on huge lengths
                                return null;
                            }
                            start = pos[0];
                            pos[0] += (int) len;
                            break;
                        case 5:
                            pos[0] += 4;
                            break;
                        default:
                            return null;
                    }
                    if (pos[0] > data.length) {
                        return null;
                    }
                    if (field == number && (wire == 2 || !bytesOnly)) {
                        return Arrays.copyOfRange(data, start, pos[0]);
                    }
                }
            } catch (RuntimeException e) { // truncated varint, or anything else malformed
                return null;
            }
            return null;
        }

        private static long varint(byte[] data, int[] pos) {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                byte b = data[pos[0]++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    private static boolean isShortsName(CharSequence title) {
        if (title == null) {
            return false;
        }
        String name = title.toString().trim();
        return "Shorts".equalsIgnoreCase(name) || "#Shorts".equalsIgnoreCase(name);
    }

    /** {@code videos} without its Shorts, in order (the same list when it has none). */
    public static List<Video> withoutShorts(List<Video> videos) {
        if (videos == null || !containsShort(videos)) {
            return videos;
        }
        List<Video> result = new ArrayList<>(videos.size());
        for (Video video : videos) {
            if (!isShort(video)) {
                result.add(video);
            }
        }
        return result;
    }

    /**
     * Whether a channel section is the channel's Shorts section - the one to leave out entirely:
     * named Shorts (YouTube's label, or this app's {@code localizedLabel}) and holding nothing but
     * Shorts (or nothing at all, when the service already emptied it). Any other section keeps its
     * tab and its next page even when its first page filtered down to zero cards.
     */
    public static boolean isShortsSection(String title, String localizedLabel, List<Video> videos) {
        if (title == null) {
            return false;
        }
        String name = title.trim();
        boolean named = "Shorts".equalsIgnoreCase(name)
                || (localizedLabel != null && localizedLabel.trim().equalsIgnoreCase(name));
        if (!named) {
            return false;
        }
        if (videos != null) {
            for (Video video : videos) {
                if (video != null && !isShort(video)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsShort(List<Video> videos) {
        for (Video video : videos) {
            if (isShort(video)) {
                return true;
            }
        }
        return false;
    }
}
