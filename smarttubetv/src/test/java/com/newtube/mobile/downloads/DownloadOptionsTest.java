package com.newtube.mobile.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DownloadOptionsTest {
    private static final FakeMediaFormat AAC = FakeMediaFormat.audio("140", 129_000, 5_000_000);

    @Test
    public void offersOneAvcRungPerHeightTallestFirstPlusAudio() {
        List<MediaFormat> adaptive = Arrays.asList(
                FakeMediaFormat.video("134", 360, 400_000, 20_000_000),
                FakeMediaFormat.video("137", 1080, 2_500_000, 120_000_000),
                FakeMediaFormat.video("136", 720, 1_200_000, 60_000_000),
                FakeMediaFormat.vp9("248", 1080, 2_000_000, 100_000_000),
                FakeMediaFormat.vp9("271", 1440, 5_000_000, 300_000_000),
                FakeMediaFormat.opus("251", 140_000, 6_000_000),
                AAC);

        List<DownloadOption> options = DownloadOptions.build(adaptive, null);

        assertEquals(4, options.size());
        assertEquals("1080p", options.get(0).qualityLabel);
        assertEquals("720p", options.get(1).qualityLabel);
        assertEquals("360p", options.get(2).qualityLabel);
        assertTrue(options.get(3).isAudioOnly());
        assertEquals(125_000_000L, options.get(0).totalBytes);
        assertEquals(5_000_000L, options.get(3).totalBytes);
        assertSame(AAC, options.get(0).audio);
        assertFalse(options.get(0).isProgressive());
    }

    @Test
    public void sameHeightKeepsTheHigherBitrateVariant() {
        FakeMediaFormat p60 = FakeMediaFormat.video("299", 1080, 4_000_000, 200_000_000).label("1080p60");
        List<MediaFormat> adaptive = Arrays.asList(
                FakeMediaFormat.video("137", 1080, 2_500_000, 120_000_000), p60, AAC);

        List<DownloadOption> options = DownloadOptions.build(adaptive, null);

        assertSame(p60, options.get(0).video);
        assertEquals("1080p60", options.get(0).qualityLabel);
    }

    @Test
    public void prefersTheOriginalAudioTrackOverDubsAndDrc() {
        FakeMediaFormat dubbed = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("es-ES.10");
        FakeMediaFormat original = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("en.4");
        FakeMediaFormat drc = FakeMediaFormat.audio("140", 200_000, 5_000_000).track("en.4").drc();

        assertSame(original, DownloadOptions.pickAudio(Arrays.asList(dubbed, drc, original)));
        assertSame(dubbed, DownloadOptions.pickAudio(Collections.singletonList(dubbed)));
        assertNull(DownloadOptions.pickAudio(Collections.singletonList(FakeMediaFormat.opus("251", 1, 1))));
    }

    /** Issue #29: every variant is itag 140; the URL xtags (acont=) name the original. */
    @Test
    public void picksTheXtagsOriginalAmongSameItagDubs() {
        FakeMediaFormat autoDub = FakeMediaFormat.audio("140", 130_000, 5_000_000).track("de-DE.10").lang("de (dubbed-auto)");
        FakeMediaFormat dub = FakeMediaFormat.audio("140", 131_000, 5_000_000).track("es-US.3").lang("es‐us (dubbed)");
        FakeMediaFormat original = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("en.4").lang("en (original)");
        FakeMediaFormat originalDrc = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("en.4").lang("en (original)").drc();
        List<MediaFormat> formats = Arrays.asList(autoDub, dub, originalDrc, original);

        assertSame(original, DownloadOptions.pickAudio(formats));
        assertSame(original, DownloadOptions.pickAudio(formats, "140", null));
        assertSame(original, DownloadOptions.build(formats, null).get(0).audio);
        assertEquals("en (original)|en.4", DownloadOptions.audioTrackKey(original));
    }

    /** A dub's id may carry .4 too: an explicit acont tag outranks the id heuristic. */
    @Test
    public void xtagsVariantOutranksTheTrackIdHeuristic() {
        FakeMediaFormat dubWithFour = FakeMediaFormat.audio("140", 140_000, 5_000_000).track("fr.4").lang("fr (dubbed-auto)");
        FakeMediaFormat original = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("en.3").lang("en (original)");

        assertSame(original, DownloadOptions.pickAudio(Arrays.asList(dubWithFour, original)));
    }

    /** The re-resolve after a refused link keeps the item's language, not the first itag hit. */
    @Test
    public void reResolveKeepsTheChosenTrackAndFallsBackToTheOriginal() {
        FakeMediaFormat dub = FakeMediaFormat.audio("140", 131_000, 5_000_000).track("es-US.3").lang("es‐us (dubbed)");
        FakeMediaFormat original = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("en.4").lang("en (original)");
        FakeMediaFormat opus = FakeMediaFormat.opus("251", 160_000, 5_000_000).track("en.4").lang("en (original)");
        List<MediaFormat> formats = Arrays.asList(dub, opus, original);

        assertSame(dub, DownloadOptions.pickAudio(formats, "140", DownloadOptions.audioTrackKey(dub)));
        assertSame(original, DownloadOptions.pickAudio(formats, "140", DownloadOptions.audioTrackKey(original)));
        // Another client renumbered the track id: the language tag still matches.
        assertSame(dub, DownloadOptions.pickAudio(formats, "140", "es‐us (dubbed)|es-US.9"));
        // The wanted track is gone (or the item predates the field): the original, never a dub.
        assertSame(original, DownloadOptions.pickAudio(formats, "140", "pt (dubbed)|pt.3"));
        assertSame(original, DownloadOptions.pickAudio(formats, "140", null));
        assertNull(DownloadOptions.pickAudio(formats, "139", null));
    }

    @Test
    public void singleUntaggedAudioStillResolves() {
        assertEquals(DownloadOptions.UNTAGGED_TRACK, DownloadOptions.audioTrackKey(AAC));
        assertSame(AAC, DownloadOptions.pickAudio(Collections.singletonList(AAC), "140", null));
        assertSame(AAC, DownloadOptions.pickAudio(Collections.singletonList(AAC), "140", DownloadOptions.UNTAGGED_TRACK));
    }

    /** An item saved before the track was recorded may hold a dub's bytes: never resume it. */
    @Test
    public void oldItemWithAPartFileRestartsTheAudioPart() {
        String original = "en (original)|en.4";
        assertTrue(DownloadOptions.mustRestartAudioPart(null, original, 3_000_000, 5_000_000));
        // Sol's repro: a 6 MB dub prefix against a 5 MB original would skip the fetch entirely.
        assertTrue(DownloadOptions.mustRestartAudioPart(null, original, 6_000_000, 5_000_000));
        // Nothing kept, nothing to reset.
        assertFalse(DownloadOptions.mustRestartAudioPart(null, original, 0, 5_000_000));
    }

    @Test
    public void sameVariantKeepsTheResume() {
        String original = "en (original)|en.4";
        assertFalse(DownloadOptions.mustRestartAudioPart(original, original, 3_000_000, 5_000_000));
        assertFalse(DownloadOptions.mustRestartAudioPart(original, original, 5_000_000, 5_000_000));
        assertFalse(DownloadOptions.mustRestartAudioPart(DownloadOptions.UNTAGGED_TRACK,
                DownloadOptions.UNTAGGED_TRACK, 3_000_000, -1));
        // Same track, but the part outgrew the stream: it cannot be a prefix of it.
        assertTrue(DownloadOptions.mustRestartAudioPart(original, original, 6_000_000, 5_000_000));
    }

    @Test
    public void changedVariantRestartsTheAudioPart() {
        assertTrue(DownloadOptions.mustRestartAudioPart("pt (dubbed)|pt.3", "en (original)|en.4", 3_000_000, 5_000_000));
        assertTrue(DownloadOptions.mustRestartAudioPart("en (original)|en.4", "en (original)|en.9", 3_000_000, 5_000_000));
    }

    @Rule
    public TemporaryFolder mTmp = new TemporaryFolder();

    private File audioPart(int bytes) throws IOException {
        File part = new File(mTmp.getRoot(), "item.audio");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(new byte[bytes]);
        }
        return part;
    }

    /** The identity beside the part, not the async registry, decides the resume. */
    @Test
    public void sidecarMatchingTheResolvedTrackResumes() throws IOException {
        String original = "en (original)|en.4";
        File part = audioPart(300);
        DownloadOptions.writeAudioPartTrack(part, original);
        assertEquals(original, DownloadOptions.readAudioPartTrack(part));
        assertFalse(DownloadOptions.mustRestartAudioPart(part, original, 500));
    }

    /** Stored A fell back to B, B's bytes landed, the registry still says A: the sidecar says B. */
    @Test
    public void sidecarNamingAnotherTrackResets() throws IOException {
        File part = audioPart(300);
        DownloadOptions.writeAudioPartTrack(part, "en (original)|en.4");
        assertTrue(DownloadOptions.mustRestartAudioPart(part, "pt (dubbed)|pt.3", 500));
    }

    /** A 1.15.0 part has no sidecar: unknown bytes, never resumed. */
    @Test
    public void missingSidecarResets() throws IOException {
        File part = audioPart(300);
        assertNull(DownloadOptions.readAudioPartTrack(part));
        assertTrue(DownloadOptions.mustRestartAudioPart(part, "en (original)|en.4", 500));
        // No part at all: nothing to reset.
        assertFalse(DownloadOptions.mustRestartAudioPart(new File(mTmp.getRoot(), "none.audio"),
                "en (original)|en.4", 500));
    }

    @Test
    public void sidecarMatchingButPartLongerThanTheStreamResets() throws IOException {
        String original = "en (original)|en.4";
        File part = audioPart(600);
        DownloadOptions.writeAudioPartTrack(part, original);
        assertTrue(DownloadOptions.mustRestartAudioPart(part, original, 500));
    }

    @Test
    public void writingNullClearsTheSidecar() throws IOException {
        File part = audioPart(300);
        DownloadOptions.writeAudioPartTrack(part, "en (original)|en.4");
        DownloadOptions.writeAudioPartTrack(part, null);
        assertFalse(DownloadOptions.audioIdFile(part).exists());
    }

    @Test
    public void videoPartRestartsOnlyWhenLongerThanTheStream() {
        assertFalse(DownloadOptions.mustRestartPart(0, 100));
        assertFalse(DownloadOptions.mustRestartPart(50, 100));
        assertFalse(DownloadOptions.mustRestartPart(50, -1));
        assertTrue(DownloadOptions.mustRestartPart(150, 100));
    }

    @Test
    public void fallsBackToProgressiveStreamsWhenNoSeparateAvcTrack() {
        List<MediaFormat> adaptive = Arrays.asList(FakeMediaFormat.vp9("248", 1080, 2_000_000, 1), AAC);
        List<MediaFormat> progressive = Arrays.asList(
                FakeMediaFormat.video("18", 360, 500_000, 30_000_000),
                FakeMediaFormat.video("22", 720, 1_500_000, 90_000_000));

        List<DownloadOption> options = DownloadOptions.build(adaptive, progressive);

        assertEquals(3, options.size());
        assertTrue(options.get(0).isProgressive());
        assertEquals(720, options.get(0).height);
        assertEquals(90_000_000L, options.get(0).totalBytes);
        assertTrue(options.get(2).isAudioOnly());
    }

    @Test
    public void unknownLengthMakesTheTotalUnknown() {
        List<MediaFormat> adaptive = Arrays.asList(
                FakeMediaFormat.video("137", 1080, 2_500_000, -1), AAC);

        assertEquals(-1, DownloadOptions.build(adaptive, null).get(0).totalBytes);
    }

    @Test
    public void nothingUsableGivesNoOptions() {
        assertTrue(DownloadOptions.build(null, null).isEmpty());
        assertTrue(DownloadOptions.build(Collections.singletonList(FakeMediaFormat.vp9("248", 1080, 1, 1)), null).isEmpty());
    }

    @Test
    public void formatsBytesLikeTheFileManager() {
        assertEquals("", DownloadOptions.formatBytes(-1));
        assertEquals("512 B", DownloadOptions.formatBytes(512));
        assertEquals("4.2 MB", DownloadOptions.formatBytes(4_200_000));
        assertEquals("245 MB", DownloadOptions.formatBytes(245_000_000));
        assertEquals("1.3 GB", DownloadOptions.formatBytes(1_300_000_000));
    }

    @Test
    public void formatsDurationLikeTheCardBadge() {
        assertEquals("0:07", DownloadOptions.formatDuration(7_400));
        assertEquals("12:34", DownloadOptions.formatDuration(754_000));
        assertEquals("1:02:03", DownloadOptions.formatDuration(3_723_000));
    }

    @Test
    public void fileNamesAreSafeAndBounded() {
        assertEquals("Why not A talk about C C++ (part 1).mp4",
                DownloadOptions.fileNameFor("Why not? A talk about C/C++ (part 1)", "abc", "mp4"));
        assertEquals("abc.m4a", DownloadOptions.fileNameFor("   ", "abc", "m4a"));
        assertEquals("abc.mp4", DownloadOptions.fileNameFor(null, "abc", "mp4"));
        String longTitle = new String(new char[300]).replace('\0', 'x');
        assertEquals(120 + ".mp4".length(), DownloadOptions.fileNameFor(longTitle, "abc", "mp4").length());
        assertFalse(DownloadOptions.fileNameFor("a\tb\nc:d|e", "abc", "mp4").matches(".*[\\t\\n:|].*"));
    }
}
