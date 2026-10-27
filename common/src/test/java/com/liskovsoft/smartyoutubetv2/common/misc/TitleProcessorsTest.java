package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Issue #45: "Original titles" did nothing because DeArrow and the original-title step shared one
 * processed flag (DeArrow, first in line, claimed every video) and one title field.
 */
public class TitleProcessorsTest {
    private static Video video(String id, String title) {
        Video video = new Video();
        video.videoId = id;
        video.title = title;
        return video;
    }

    @Test
    public void deArrowClaimDoesNotHideVideosFromOriginalTitles() {
        List<Video> videos = Arrays.asList(video("a", "Título"), video("b", "Otro"));

        assertEquals(Arrays.asList("a", "b"), DeArrowProcessor.claimVideoIds(videos));
        assertEquals(Arrays.asList("a", "b"), UnlocalizedTitleProcessor.claimVideoIds(videos));
    }

    @Test
    public void eachStepClaimsAVideoOnlyOnce() {
        List<Video> videos = Arrays.asList(video("a", "Título"));

        UnlocalizedTitleProcessor.claimVideoIds(videos);
        DeArrowProcessor.claimVideoIds(videos);

        assertTrue(UnlocalizedTitleProcessor.claimVideoIds(videos).isEmpty());
        assertTrue(DeArrowProcessor.claimVideoIds(videos).isEmpty());
    }

    @Test
    public void cardsWithoutVideoIdAreSkipped() {
        // A search starting with a channel card: a null id in Observable.fromIterable() ended the
        // whole original-titles stream.
        List<Video> videos = Arrays.asList(video(null, "Hangtime"), video("a", "Título"));

        assertEquals(Arrays.asList("a"), UnlocalizedTitleProcessor.claimVideoIds(videos));
        assertEquals(Arrays.asList("a"), DeArrowProcessor.claimVideoIds(videos));
    }

    @Test
    public void deArrowTitleWinsThenOriginalThenYouTubes() {
        Video video = video("a", "Título traducido");
        assertEquals("Título traducido", video.getTitle());

        video.unlocalizedTitle = "Original title";
        assertEquals("Original title", video.getTitle());

        video.deArrowTitle = "Crowd title";
        assertEquals("Crowd title", video.getTitle());
        assertEquals("Crowd title", video.getTitleFull());

        video.deArrowTitle = null; // DeArrow has no title for this video
        assertEquals("Original title", video.getTitle());
        assertEquals("Original title", video.getTitleFull());
    }

    @Test
    public void originalTitleArrivingAfterDeArrowDoesNotOverrideIt() {
        Video video = video("a", "Título traducido");
        video.deArrowTitle = "Crowd title";
        video.unlocalizedTitle = "Original title";

        assertEquals("Crowd title", video.getTitle());
    }
}
