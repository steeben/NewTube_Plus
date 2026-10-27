package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** NEWTUBE(shorts): NewTube shows no Shorts, whatever the stored Hide content prefs say. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsFilterTest {
    @Test
    public void everyShortIsDroppedAndTheRestKeepsItsOrder() {
        Video a = video("a", false);
        Video s1 = video("s1", true);
        Video b = video("b", false);
        Video s2 = video("s2", true);

        assertEquals(Arrays.asList(a, b), ShortsFilter.withoutShorts(Arrays.asList(s1, a, s2, b)));
    }

    @Test
    public void aListWithoutShortsIsReturnedAsIs() {
        List<Video> feed = Arrays.asList(video("a", false), video("b", false));
        assertSame(feed, ShortsFilter.withoutShorts(feed));
        assertNull(ShortsFilter.withoutShorts(null));
    }

    @Test
    public void aShelfOfShortsLeavesNothing() {
        assertEquals(new ArrayList<Video>(),
                ShortsFilter.withoutShorts(Arrays.asList(video("s1", true), video("s2", true))));
    }

    @Test
    public void nullSlotsAreNotShorts() {
        Video a = video("a", false);
        assertEquals(Arrays.asList(null, a),
                ShortsFilter.withoutShorts(Arrays.asList(null, video("s", true), a)));
        assertFalse(ShortsFilter.isShort(null));
    }

    @Test
    public void theChannelsShortsSectionIsNamedShortsAndHoldsOnlyShorts() {
        List<Video> shorts = Arrays.asList(video("s1", true), video("s2", true));
        assertTrue(ShortsFilter.isShortsSection("Shorts", "Shorts", shorts));
        assertTrue(ShortsFilter.isShortsSection(" SHORTS ", null, shorts));
        assertTrue("localized label", ShortsFilter.isShortsSection("Cortos", "Cortos", shorts));
        assertTrue("already emptied by the service", ShortsFilter.isShortsSection("Shorts", null, new ArrayList<>()));
    }

    @Test
    public void aNormalSectionWhoseFirstPageIsAllShortsIsNotTheShortsSection() {
        List<Video> shorts = Arrays.asList(video("s1", true), video("s2", true));
        assertFalse(ShortsFilter.isShortsSection("Videos", "Shorts", shorts));
        assertFalse(ShortsFilter.isShortsSection(null, "Shorts", shorts));
        assertFalse("a Shorts-named row with real videos is kept",
                ShortsFilter.isShortsSection("Shorts", "Shorts", Arrays.asList(video("s1", true), video("a", false))));
    }

    @Test
    public void aShortsTabServedAsATileIsAShort() {
        // #63: the channel's / Subscriptions' "Shorts" tab arrives as a nested-list card with no
        // video; it opened a list of nothing but Shorts, i.e. a blank screen.
        Video tab = tile(" Shorts ", null, null);
        assertTrue(ShortsFilter.isShort(tab));
        assertTrue("guide-style tab with a browse id", ShortsFilter.isShort(tile("#Shorts", "UCabc", null)));

        Video a = video("a", false);
        assertEquals(Arrays.asList(a), ShortsFilter.withoutShorts(Arrays.asList(tab, a)));
    }

    @Test
    public void aChannelOrPlaylistNamedShortsIsKept() {
        Video channel = new Video();
        channel.title = "Shorts";
        channel.channelId = "UCshorts";
        assertFalse("a real channel named Shorts", ShortsFilter.isShort(channel));

        Video playlist = new Video();
        playlist.title = "#Shorts";
        playlist.playlistId = "PLabc";
        assertFalse("a playlist named #Shorts", ShortsFilter.isShort(playlist));

        assertFalse("a nested playlist named #Shorts", ShortsFilter.isShort(tile("#Shorts", "UCabc", "PLabc")));
    }

    @Test
    public void theChannelNavigationRowLosesOnlyItsShortsTile() {
        // #63, as served for MrBeast 2 (1.15.1): the channel page's untitled last section is a row of
        // channel cards for the channel itself, one per tab, told apart only by their browse params.
        Video videos = tabLink("Videos", "8gYHEgVqAIABAg%3D%3D");
        Video shorts = tabLink("Shorts", "8gYHEgVyAIABAg%3D%3D");
        Video live = tabLink("Live", "8gYHEgVKAIABAg%3D%3D");
        Video playlists = tabLink("Playlists", "8gYLEgliBAoCEGiAAQI%3D");

        assertEquals(Arrays.asList(videos, live, playlists),
                ShortsFilter.withoutShorts(Arrays.asList(videos, shorts, live, playlists)));
    }

    @Test
    public void theShortsTabIsKnownByItsParamsInAnyLanguage() {
        assertTrue("TV client, any title", ShortsFilter.isShort(tabLink("Cortos", "8gYHEgVyAIABAg%3D%3D")));
        assertTrue("not URL-encoded", ShortsFilter.isShort(tabLink("Kurzvideos", "8gYHEgVyAIABAg==")));
        assertTrue("web client", ShortsFilter.isShortsTabParams("EgZzaG9ydHPyBgUKA5oBAA%3D%3D"));

        assertFalse("web Videos", ShortsFilter.isShortsTabParams("EgZ2aWRlb3PyBgQKAjoA"));
        assertFalse("web Live", ShortsFilter.isShortsTabParams("EgdzdHJlYW1z8gYECgJ6AA%3D%3D"));
        assertFalse("TV Videos", ShortsFilter.isShortsTabParams("8gYHEgVqAIABAg%3D%3D"));
        assertFalse("TV Playlists", ShortsFilter.isShortsTabParams("8gYLEgliBAoCEGiAAQI%3D"));
        assertFalse(ShortsFilter.isShortsTabParams(null));
        assertFalse(ShortsFilter.isShortsTabParams(""));
        assertFalse("garbage", ShortsFilter.isShortsTabParams("%%%not base64"));
        assertFalse("truncated", ShortsFilter.isShortsTabParams("8gYH"));
    }

    @Test
    public void aShortsTitleOnATabLinkIsTheFallback() {
        // Unknown params (a future encoding) still go when the tile is a tab link titled Shorts.
        assertTrue(ShortsFilter.isShort(tabLink("Shorts", "CgJ6eg%3D%3D")));
        assertFalse(ShortsFilter.isShort(tabLink("Videos", "CgJ6eg%3D%3D")));
    }

    @Test
    public void aVideoTitledShortsIsNotATile() {
        Video named = video("v1", false);
        named.title = "Shorts";
        assertFalse(ShortsFilter.isShort(named));

        assertFalse(ShortsFilter.isShort(tile("Shorts and more", null, null)));
    }

    /** A channel card that opens one of the channel's own tabs (channel id + browse params). */
    private static Video tabLink(String title, String params) {
        Video video = new Video();
        video.title = title;
        video.channelId = "UC4-79UOlP48-QNGgCko5p2g";
        video.playlistParams = params;
        video.itemType = MediaItem.TYPE_CHANNEL;
        return video;
    }

    /** A card backed by a MediaItem with nested uploads (a tab / guide tile). */
    private static Video tile(String title, String channelId, String playlistId) {
        Video video = new Video();
        video.title = title;
        video.channelId = channelId;
        video.playlistId = playlistId;
        video.mediaItem = (MediaItem) Proxy.newProxyInstance(MediaItem.class.getClassLoader(),
                new Class<?>[] {MediaItem.class}, (proxy, method, args) -> {
                    if ("hasUploads".equals(method.getName())) {
                        return true;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    return null;
                });
        return video;
    }

    private static Video video(String id, boolean isShorts) {
        Video video = new Video();
        video.videoId = id;
        video.title = id;
        video.isShorts = isShorts;
        return video;
    }
    @Test(timeout = 2000)
    public void malformedParamsNeitherHangNorThrow() {
        // Oversized lengths that used to overflow the bounds check (gpt-6.1-sol review).
        for (String b64 : new String[] {"Cvb/////////fw==", "8gb//////////38=", "8gY=", ""}) {
            byte[] data = java.util.Base64.getDecoder().decode(b64);
            assertNull(ShortsFilter.ProtoReader.field(data, 110));
        }
    }
}
