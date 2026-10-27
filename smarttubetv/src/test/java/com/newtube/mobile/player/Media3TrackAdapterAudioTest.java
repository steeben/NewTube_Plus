package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.google.common.collect.ImmutableList;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEWTUBE(audio-track): which audio track plays on a dubbed video. The generated MPD gives every
 * language variant its own adaptation sets, with the variant tag in the label ("en (original)") and
 * Role=main on the original only; every variant repeats the same itags (140, 251).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3TrackAdapterAudioTest {
    private DefaultTrackSelector selector;
    private Media3TrackAdapter adapter;

    @Before
    public void setUp() {
        selector = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        adapter = new Media3TrackAdapter(selector);
        adapter.setPreferOriginalAudio(true); // what Media3PlayerController does
    }

    private static Format audio(String id, String codecs, int bitrate, String tag, int role) {
        String language = tag == null ? null : tag.substring(0, tag.indexOf(' '));
        return new Format.Builder()
                .setId(id)
                .setSampleMimeType(codecs.equals("opus") ? MimeTypes.AUDIO_OPUS : MimeTypes.AUDIO_AAC)
                .setCodecs(codecs)
                .setAverageBitrate(bitrate)
                .setChannelCount(2)
                .setSampleRate(48_000)
                .setLanguage(language)
                .setLabel(tag)
                .setRoleFlags(role)
                .build();
    }

    /** Two adaptation sets per variant, like the MPD: AAC (139, 140) and Opus (249, 251). */
    private static List<Tracks.Group> variant(String tag, int role) {
        TrackGroup aac = new TrackGroup(tag + "-mp4",
                audio("140", "mp4a.40.2", 129_000, tag, role), audio("139", "mp4a.40.5", 48_000, tag, role));
        TrackGroup opus = new TrackGroup(tag + "-webm",
                audio("251", "opus", 158_000, tag, role), audio("249", "opus", 50_000, tag, role));
        return Arrays.asList(group(aac), group(opus));
    }

    private static Tracks.Group group(TrackGroup trackGroup) {
        int[] support = new int[trackGroup.length];
        Arrays.fill(support, C.FORMAT_HANDLED);
        return new Tracks.Group(trackGroup, true, support, new boolean[trackGroup.length]);
    }

    private static Tracks tracks(List<List<Tracks.Group>> variants) {
        List<Tracks.Group> groups = new ArrayList<>();
        for (List<Tracks.Group> variant : variants) {
            groups.addAll(variant);
        }
        return new Tracks(ImmutableList.copyOf(groups));
    }

    /** The dubbed video: a dub first (YouTube's manifests often list one first), then the original. */
    private static Tracks dubbedVideo() {
        return tracks(Arrays.asList(
                variant("es (dubbed)", C.ROLE_FLAG_DUB),
                variant("en (original)", C.ROLE_FLAG_MAIN),
                variant("fr (dubbed-auto)", C.ROLE_FLAG_DUB)));
    }

    /** The one audio override in force: [label, id]. */
    private String[] audioOverride() {
        TrackSelectionOverride found = null;
        for (TrackSelectionOverride override : selector.getParameters().overrides.values()) {
            if (override.getType() == C.TRACK_TYPE_AUDIO) {
                assertTrue("one audio override", found == null);
                found = override;
            }
        }
        assertNotNull("an audio override", found);
        assertEquals(1, found.trackIndices.size());
        Format format = found.mediaTrackGroup.getFormat(found.trackIndices.get(0));
        return new String[]{format.label, format.id};
    }

    private FormatItem offered(String tag, String id) {
        for (FormatItem item : adapter.getFormats(TrackSelectorManager.RENDERER_INDEX_AUDIO)) {
            if (tag.equals(item.getLanguage()) && id.equals(item.getFormatId())) {
                return item;
            }
        }
        throw new AssertionError("not offered: " + tag + " " + id);
    }

    @Test
    public void aFreshInstallPlaysTheOriginalNotTheDubListedFirst() {
        // Fresh install: restoreAudioFormat applies the default preset before tracks are known.
        adapter.selectFormat(FormatItem.AUDIO_51_AC3);
        adapter.onTracksChanged(dubbedVideo());

        String[] playing = audioOverride();
        assertEquals("en (original)", playing[0]);
        assertEquals("251", playing[1]); // the best original track, not the first one listed
    }

    @Test
    public void noAudioTargetAtAllAlsoPlaysTheOriginal() {
        adapter.onTracksChanged(dubbedVideo());

        assertEquals("en (original)", audioOverride()[0]);
    }

    @Test
    public void aSingleLanguageVideoIsLeftToMedia3() {
        adapter.selectFormat(FormatItem.AUDIO_51_AC3);
        adapter.onTracksChanged(tracks(Arrays.asList(variant(null, C.ROLE_FLAG_MAIN))));

        for (TrackSelectionOverride override : selector.getParameters().overrides.values()) {
            assertTrue("no audio override", override.getType() != C.TRACK_TYPE_AUDIO);
        }
    }

    @Test
    public void pickingADubPlaysThatLanguageAlthoughEveryVariantHas251() {
        adapter.onTracksChanged(dubbedVideo());

        adapter.selectFormat(offered("fr (dubbed-auto)", "251"));

        String[] playing = audioOverride();
        assertEquals("fr (dubbed-auto)", playing[0]);
        assertEquals("251", playing[1]);
    }

    @Test
    public void aDubPickedOnOneVideoDoesNotCarryToTheNext() {
        // Issues #52/#53: the pick is per video; the next video's restore applies only the
        // stored codec preference (PlayerData.getStoredAudioPreference), never the pick.
        adapter.onTracksChanged(dubbedVideo());
        adapter.selectFormat(offered("es (dubbed)", "251"));
        assertEquals("es (dubbed)", audioOverride()[0]);

        adapter.onSourceChanged();
        adapter.selectFormat(FormatItem.AUDIO_51_AC3);
        adapter.onTracksChanged(tracks(Arrays.asList(
                variant("es (dubbed)", C.ROLE_FLAG_DUB),
                variant("en (original)", C.ROLE_FLAG_MAIN))));

        assertEquals("en (original)", audioOverride()[0]);
    }

    @Test
    public void aPickSurvivesAReloadOfTheSameVideo() {
        adapter.onTracksChanged(dubbedVideo());
        FormatItem french = offered("fr (dubbed-auto)", "251");
        adapter.selectFormat(french);

        adapter.onSourceChanged();
        adapter.selectFormat(french); // restoreAudioFormat: this video's pick
        adapter.onTracksChanged(dubbedVideo());

        String[] playing = audioOverride();
        assertEquals("fr (dubbed-auto)", playing[0]);
        assertEquals("251", playing[1]);
    }

    @Test
    public void theExactVariantBeatsAnotherVariantOfTheSameLanguage() {
        adapter.onTracksChanged(tracks(Arrays.asList(
                variant("es (dubbed-auto)", C.ROLE_FLAG_DUB),
                variant("es (dubbed)", C.ROLE_FLAG_DUB),
                variant("en (original)", C.ROLE_FLAG_MAIN))));

        adapter.selectFormat(offered("es (dubbed)", "140"));

        String[] playing = audioOverride();
        assertEquals("es (dubbed)", playing[0]);
        assertEquals("140", playing[1]);
    }

    @Test
    public void aLanguageIsNotASubstringMatch() {
        // "en" used to match "ben (dubbed)" (and "de" any "(dubbed)" label).
        FormatItem english = stored(audio("251", "opus", 158_000, "en (dubbed)", C.ROLE_FLAG_DUB));
        adapter.selectFormat(english);
        adapter.onTracksChanged(tracks(Arrays.asList(
                variant("ben (dubbed)", C.ROLE_FLAG_DUB),
                variant("es (original)", C.ROLE_FLAG_MAIN))));

        assertEquals("es (original)", audioOverride()[0]);
    }

    @Test
    public void aRegionalTagIsTheSameLanguage() {
        FormatItem english = stored(audio("251", "opus", 158_000, "en-US (dubbed)", C.ROLE_FLAG_DUB));
        adapter.selectFormat(english);
        adapter.onTracksChanged(tracks(Arrays.asList(
                variant("es (original)", C.ROLE_FLAG_MAIN),
                variant("en (dubbed)", C.ROLE_FLAG_DUB))));

        assertEquals("en (dubbed)", audioOverride()[0]);
    }

    @Test
    public void primaryLanguageSubtagComparison() {
        assertTrue(Media3TrackAdapter.audioLanguageEquals("en", "en-US"));
        assertTrue(Media3TrackAdapter.audioLanguageEquals("en-GB (dubbed)", "EN (original)"));
        assertTrue(Media3TrackAdapter.audioLanguageEquals("es", "es-419"));
        assertTrue(Media3TrackAdapter.audioLanguageEquals(null, null));
        assertFalse(Media3TrackAdapter.audioLanguageEquals("en", "ben"));
        assertFalse(Media3TrackAdapter.audioLanguageEquals("de (dubbed)", "fr (dubbed)"));
        assertFalse(Media3TrackAdapter.audioLanguageEquals("en", null));
        assertFalse(Media3TrackAdapter.audioLanguageEquals("", ""));
    }

    /** The FormatItem a track of another video would have been stored as. */
    private static FormatItem stored(Format format) {
        return ExoFormatItem.from(Media3FormatConverter.toMediaTrack(
                TrackSelectorManager.RENDERER_INDEX_AUDIO, format));
    }

    @Test
    public void aStoredUntaggedTrackPlaysTheOriginalOnADubbedVideoNotTheFirst251() {
        // Stored on a single-language video: 251 without a language.
        FormatItem untagged = stored(audio("251", "opus", 158_000, null, C.ROLE_FLAG_MAIN));

        adapter.selectFormat(untagged);
        adapter.onTracksChanged(dubbedVideo()); // Spanish dub listed first

        assertEquals("en (original)", audioOverride()[0]);
    }

    @Test
    public void theDefaultRowOfAnUntaggedOriginalBesideADubPlaysTheOriginal() {
        Tracks tracks = tracks(Arrays.asList(
                variant("es (dubbed)", C.ROLE_FLAG_DUB),
                variant(null, C.ROLE_FLAG_MAIN)));
        adapter.onTracksChanged(tracks);

        FormatItem defaultRow = null;
        for (FormatItem item : adapter.getFormats(TrackSelectorManager.RENDERER_INDEX_AUDIO)) {
            if (item.getLanguage() == null && "251".equals(item.getFormatId())) {
                defaultRow = item;
            }
        }
        adapter.selectFormat(defaultRow);

        String[] playing = audioOverride();
        assertEquals(null, playing[0]);
        assertEquals("251", playing[1]);
    }

    @Test
    public void aStoredLanguageBeatsTheSameItagInAnotherLanguage() {
        // Stored: Spanish stable-volume Opus. The next video has DRC only in English.
        FormatItem spanishDrc = stored(audio("251-drc", "opus", 160_000, "es (dubbed)", C.ROLE_FLAG_DUB));
        TrackGroup english = new TrackGroup("en-webm",
                audio("251-drc", "opus", 160_000, "en (original)", C.ROLE_FLAG_MAIN),
                audio("251", "opus", 158_000, "en (original)", C.ROLE_FLAG_MAIN));
        TrackGroup spanish = new TrackGroup("es-webm",
                audio("251", "opus", 158_000, "es (dubbed)", C.ROLE_FLAG_DUB),
                audio("249", "opus", 50_000, "es (dubbed)", C.ROLE_FLAG_DUB));

        adapter.selectFormat(spanishDrc);
        adapter.onTracksChanged(new Tracks(ImmutableList.of(group(english), group(spanish))));

        String[] playing = audioOverride();
        assertEquals("es (dubbed)", playing[0]);
        assertEquals("251", playing[1]);
    }

    @Test
    public void aStoredLanguageInAnotherCodecBeatsTheSameItagInAnotherLanguage() {
        // Stored: Spanish Opus 251. The next video has Spanish only in AAC, English in Opus 251.
        FormatItem spanishOpus = stored(audio("251", "opus", 158_000, "es (dubbed)", C.ROLE_FLAG_DUB));
        TrackGroup englishOpus = new TrackGroup("en-webm",
                audio("251", "opus", 158_000, "en (original)", C.ROLE_FLAG_MAIN));
        TrackGroup spanishAac = new TrackGroup("es-mp4",
                audio("140", "mp4a.40.2", 129_000, "es (dubbed)", C.ROLE_FLAG_DUB),
                audio("139", "mp4a.40.5", 48_000, "es (dubbed)", C.ROLE_FLAG_DUB));

        adapter.selectFormat(spanishOpus);
        adapter.onTracksChanged(new Tracks(ImmutableList.of(group(englishOpus), group(spanishAac))));

        String[] playing = audioOverride();
        assertEquals("es (dubbed)", playing[0]);
        assertEquals("140", playing[1]); // the best Spanish rendition
    }

    @Test
    public void aStoredUntaggedTrackPlaysAnAacOnlyOriginalNotADubsSameItag() {
        // Stored on a single-language video: untagged Opus 251. Here only the dub has 251.
        FormatItem untagged = stored(audio("251", "opus", 158_000, null, C.ROLE_FLAG_MAIN));
        TrackGroup dubOpus = new TrackGroup("es-webm",
                audio("251", "opus", 158_000, "es (dubbed)", C.ROLE_FLAG_DUB));
        TrackGroup originalAac = new TrackGroup("en-mp4",
                audio("140", "mp4a.40.2", 129_000, "en (original)", C.ROLE_FLAG_MAIN),
                audio("139", "mp4a.40.5", 48_000, "en (original)", C.ROLE_FLAG_MAIN));

        adapter.selectFormat(untagged);
        adapter.onTracksChanged(new Tracks(ImmutableList.of(group(dubOpus), group(originalAac))));

        String[] playing = audioOverride();
        assertEquals("en (original)", playing[0]);
        assertEquals("140", playing[1]);
    }

    @Test
    public void aStoredDubTheNextVideoLacksFallsBackToItsOriginal() {
        adapter.onTracksChanged(dubbedVideo());
        FormatItem french = offered("fr (dubbed-auto)", "251");
        adapter.selectFormat(french);

        adapter.onSourceChanged();
        adapter.selectFormat(french);
        adapter.onTracksChanged(tracks(Arrays.asList(
                variant("de (dubbed)", C.ROLE_FLAG_DUB),
                variant("es (original)", C.ROLE_FLAG_MAIN))));

        String[] playing = audioOverride();
        assertEquals("es (original)", playing[0]);
        assertEquals("251", playing[1]);
    }
}
