package com.newtube.mobile.downloads;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Picks what the downloader can turn into a plain playable file, from the format lists the
 * player already resolved. Pure Java (no Android types) so the selection rules are unit-tested.
 *
 * <p>Only H.264 video and AAC audio are offered: {@code MediaMuxer} writes MP4, every phone
 * decodes them, and YouTube serves both for every video up to 1080p. VP9/AV1 rungs (1440p+)
 * would need a WebM muxer and are left out. When a video has no separate H.264 track at all
 * (rare), the progressive muxed streams (itag 18/22) are offered instead - those need no muxing.
 */
public final class DownloadOptions {
    private DownloadOptions() {
    }

    public static List<DownloadOption> build(@Nullable List<MediaFormat> adaptiveFormats,
                                             @Nullable List<MediaFormat> urlFormats) {
        List<DownloadOption> result = new ArrayList<>();

        MediaFormat audio = pickAudio(adaptiveFormats);
        Map<Integer, MediaFormat> videoByHeight = pickVideoRungs(adaptiveFormats);

        if (audio != null && !videoByHeight.isEmpty()) {
            for (Map.Entry<Integer, MediaFormat> entry : videoByHeight.entrySet()) {
                MediaFormat video = entry.getValue();
                result.add(new DownloadOption(DownloadOption.KIND_VIDEO, labelOf(video, entry.getKey()),
                        entry.getKey(), video, audio, sumLengths(video, audio)));
            }
        } else {
            for (MediaFormat progressive : pickProgressive(urlFormats)) {
                int height = progressive.getHeight();
                result.add(new DownloadOption(DownloadOption.KIND_VIDEO, labelOf(progressive, height),
                        height, progressive, null, sumLengths(progressive, null)));
            }
        }

        if (audio != null) {
            result.add(new DownloadOption(DownloadOption.KIND_AUDIO, null, 0, null, audio,
                    sumLengths(null, audio)));
        }

        return result;
    }

    /**
     * Best AAC track of the original language. Every language variant of a dubbed video shares
     * the same itag (140), so the variant is recognized the way YouTube tags its URL:
     * {@code xtags=acont=original:lang=en} (surfaced by {@link MediaFormat#getLanguage()} as
     * "en (original)", the same mark the player's track adapter uses), and only when the URL
     * carries no variant at all, the {@code .4} track id. A dubbed or auto-dubbed track is only
     * taken when nothing else exists (issue #29).
     */
    @Nullable
    static MediaFormat pickAudio(@Nullable List<MediaFormat> formats) {
        return pickAudio(formats, null, null);
    }

    /**
     * The audio track a download resolves to: among AAC tracks (of {@code itag}, when given),
     * the one whose {@link #audioTrackKey} equals {@code wantedTrack} when that is given and
     * still offered, else the best by {@link #audioTrackRank}, then bitrate. DRC variants (same
     * itag, {@code isDrc}) are never taken. A re-resolve after a refused link passes the item's
     * itag and track, so it lands on the same language, not on whichever variant comes first.
     */
    @Nullable
    static MediaFormat pickAudio(@Nullable List<MediaFormat> formats, @Nullable String itag,
                                 @Nullable String wantedTrack) {
        if (formats == null) {
            return null;
        }

        MediaFormat best = null;
        long bestRank = -1;

        for (MediaFormat format : formats) {
            if (format.getUrl() == null || format.isDrc()) {
                continue;
            }
            if (itag != null ? !itag.equals(format.getITag()) : !isAacAudio(format)) {
                continue;
            }

            if (wantedTrack != null && isSameTrack(wantedTrack, format)) {
                return format;
            }

            long rank = audioTrackRank(format) * 1_000_000_000L + bitrateOf(format);
            if (rank > bestRank) {
                best = format;
                bestRank = rank;
            }
        }

        return best;
    }

    /** Highest-bitrate H.264 track per height, tallest first. */
    static Map<Integer, MediaFormat> pickVideoRungs(@Nullable List<MediaFormat> formats) {
        TreeMap<Integer, MediaFormat> byHeight = new TreeMap<>(Collections.reverseOrder());

        if (formats == null) {
            return byHeight;
        }

        for (MediaFormat format : formats) {
            if (!isAvcVideo(format) || format.getHeight() <= 0 || format.isOtf()) {
                continue;
            }

            MediaFormat current = byHeight.get(format.getHeight());
            if (current == null || bitrateOf(format) > bitrateOf(current)) {
                byHeight.put(format.getHeight(), format);
            }
        }

        return byHeight;
    }

    static List<MediaFormat> pickProgressive(@Nullable List<MediaFormat> formats) {
        List<MediaFormat> result = new ArrayList<>();

        if (formats == null) {
            return result;
        }

        TreeMap<Integer, MediaFormat> byHeight = new TreeMap<>(Collections.reverseOrder());
        for (MediaFormat format : formats) {
            if (format.getUrl() == null || !mimeStartsWith(format, "video/mp4") || format.getHeight() <= 0) {
                continue;
            }
            MediaFormat current = byHeight.get(format.getHeight());
            if (current == null || bitrateOf(format) > bitrateOf(current)) {
                byHeight.put(format.getHeight(), format);
            }
        }

        result.addAll(byHeight.values());
        return result;
    }

    static boolean isAvcVideo(MediaFormat format) {
        return format.getUrl() != null && mimeStartsWith(format, "video/mp4")
                && mimeContains(format, "avc1");
    }

    static boolean isAacAudio(MediaFormat format) {
        return format.getUrl() != null && mimeStartsWith(format, "audio/mp4")
                && mimeContains(format, "mp4a");
    }

    /**
     * 3 = the original track ({@code acont=original}, or no variant tag but a {@code .4} id),
     * 2 = no variant information at all (single-audio videos), 1 = some other untagged variant,
     * 0 = dubbed, auto-dubbed, descriptive or secondary.
     */
    static int audioTrackRank(MediaFormat format) {
        String variant = audioVariantOf(format.getLanguage());
        if (variant != null) {
            return "original".equals(variant) ? 3 : 0;
        }
        String audioTrackId = format.getAudioTrackId();
        if (audioTrackId == null || audioTrackId.isEmpty()) {
            return 2;
        }
        if (audioTrackId.endsWith(".4")) {
            return 3;
        }
        return 1;
    }

    /** "original" from "en (original)", "dubbed-auto" from "es (dubbed-auto)"; null when untagged. */
    @Nullable
    static String audioVariantOf(@Nullable String language) {
        if (language == null) {
            return null;
        }
        int mark = language.lastIndexOf(" (");
        if (mark <= 0 || !language.endsWith(")")) {
            return null;
        }
        return language.substring(mark + 2, language.length() - 1).trim().toLowerCase(Locale.US);
    }

    /**
     * What identifies an audio track across fresh format infos (links expire, the itag is shared
     * by every language): the URL's language/variant tag plus YouTube's track id;
     * {@link #UNTAGGED_TRACK} for a video with a single, untagged audio track, so that a stored
     * null always means "not recorded" (items saved before 1.15.1). Null only for no format.
     */
    @Nullable
    public static String audioTrackKey(@Nullable MediaFormat format) {
        if (format == null) {
            return null;
        }
        String language = format.getLanguage();
        String id = format.getAudioTrackId();
        return (language != null ? language : "") + "|" + (id != null ? id : "");
    }

    /** {@link #audioTrackKey} of an audio format with neither a language tag nor a track id. */
    public static final String UNTAGGED_TRACK = "|";

    /**
     * Whether a kept audio part must be thrown away before fetching {@code resolvedTrack}: the
     * fetcher resumes from the part's length, which is only right when the part holds a prefix
     * of the very same stream. An unknown stored identity (pre-1.15.1 item), another variant,
     * or a part longer than the stream all mean the bytes may be another language's.
     */
    static boolean mustRestartAudioPart(@Nullable String storedTrack, @Nullable String resolvedTrack,
                                        long partBytes, long resolvedLength) {
        if (partBytes <= 0) {
            return false;
        }
        if (storedTrack == null || !storedTrack.equals(resolvedTrack)) {
            return true;
        }
        return mustRestartPart(partBytes, resolvedLength);
    }

    /**
     * The identity of an audio part lives next to the part itself ({@code <part>.id}), written
     * and synced before any byte is fetched: the registry's JSON is written asynchronously, so a
     * process death right after a fallback could leave it naming the old track while the part
     * already holds the new one's bytes. A missing sidecar (a 1.15.0 part) reads as unknown.
     */
    static File audioIdFile(File part) {
        return new File(part.getParentFile(), part.getName() + ".id");
    }

    @Nullable
    static String readAudioPartTrack(File part) {
        File id = audioIdFile(part);
        if (!id.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(id)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Records, synced to disk, the track the part is about to receive; null clears it. */
    static void writeAudioPartTrack(File part, @Nullable String track) throws IOException {
        File id = audioIdFile(part);
        if (track == null) {
            //noinspection ResultOfMethodCallIgnored
            id.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(id)) {
            out.write(track.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    /** {@link #mustRestartAudioPart} against the identity recorded beside the part file. */
    static boolean mustRestartAudioPart(File part, @Nullable String resolvedTrack, long resolvedLength) {
        long bytes = part.exists() ? part.length() : 0;
        return mustRestartAudioPart(readAudioPartTrack(part), resolvedTrack, bytes, resolvedLength);
    }

    /** A kept part longer than the stream it is resumed against cannot be a prefix of it. */
    static boolean mustRestartPart(long partBytes, long resolvedLength) {
        return partBytes > 0 && resolvedLength > 0 && partBytes > resolvedLength;
    }

    /**
     * The URL's language tag decides when the key has one (a track id is not promised to be
     * stable across /player clients); the track id only for keys without a language.
     */
    static boolean isSameTrack(String wantedTrack, MediaFormat format) {
        int bar = wantedTrack.lastIndexOf('|');
        String wantedLanguage = bar >= 0 ? wantedTrack.substring(0, bar) : wantedTrack;
        String wantedId = bar >= 0 ? wantedTrack.substring(bar + 1) : "";
        if (!wantedLanguage.isEmpty()) {
            return wantedLanguage.equalsIgnoreCase(format.getLanguage());
        }
        return !wantedId.isEmpty() && wantedId.equals(format.getAudioTrackId());
    }

    static int bitrateOf(MediaFormat format) {
        try {
            return format.getBitrate() != null ? Integer.parseInt(format.getBitrate()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long lengthOf(@Nullable MediaFormat format) {
        if (format == null) {
            return 0;
        }
        try {
            return format.getClen() != null ? Long.parseLong(format.getClen()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static long sumLengths(@Nullable MediaFormat video, @Nullable MediaFormat audio) {
        long v = lengthOf(video);
        long a = lengthOf(audio);
        if (v < 0 || a < 0) {
            return -1;
        }
        return v + a;
    }

    private static String labelOf(MediaFormat format, int height) {
        String label = format.getQualityLabel();
        if (label != null && !label.isEmpty()) {
            return label;
        }
        return height + "p";
    }

    private static boolean mimeStartsWith(MediaFormat format, String prefix) {
        String mime = format.getMimeType();
        return mime != null && mime.toLowerCase(Locale.US).startsWith(prefix);
    }

    private static boolean mimeContains(MediaFormat format, String codec) {
        String mime = format.getMimeType();
        return mime != null && mime.toLowerCase(Locale.US).contains(codec);
    }

    /** "245 MB" style, decimal units like the system file manager. */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1000) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"kB", "MB", "GB"};
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        if (unit < 0) {
            return bytes + " B";
        }
        return String.format(Locale.US, value >= 100 || unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    /** "12:34" / "1:02:03" like the card duration badge. */
    public static String formatDuration(long durationMs) {
        long totalSec = durationMs / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }

    /** A file name the media store and every file system accept; falls back to the video id. */
    public static String fileNameFor(@Nullable String title, String videoId, String extension) {
        String base = title == null ? "" : title;
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) {
            base = videoId;
        }
        if (base.length() > 120) {
            base = base.substring(0, 120).trim();
        }
        return base + "." + extension;
    }
}
