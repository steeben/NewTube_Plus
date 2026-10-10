package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NEWTUBE(diagnostics): Settings &gt; About &gt; "Send diagnostic log".
 *
 * <p>Testers can't run adb, and the device-wide logcat buffer is shared with every other app, so a
 * report sent a few minutes after a failure may no longer hold the failing lines. This keeps the
 * app's own recent logcat lines - every tag: NetPath from all modules, media3, crashes - in a
 * bounded in-memory ring fed by one {@code logcat} child started at launch. An app without
 * READ_LOGS sees only its OWN uid's lines, so the stream is this app and nothing else, including
 * the previous process's lines still buffered (the session before a force-close, or a crash).</p>
 *
 * <p>Nothing leaves the device unless the user taps the button and picks a share target; the
 * export passes every line through {@link DiagnosticRedactor}.</p>
 */
public final class DiagnosticLog {
    private static final String TAG = "DiagnosticLog";
    static final int MAX_LINES = 8_000;
    static final int MAX_CHARS = 1_500_000;
    static final int MAX_LINE_CHARS = 2_000;
    /** Keep the logcat spawn out of launch; logcat replays the buffered lines, so nothing is lost. */
    private static final long START_DELAY_MS = 3_000;
    private static final int MAX_RESTARTS = 3;
    private static final String DIR = "diagnostics";
    /** An earlier report may still be on its way into a chat app; only clearly stale ones go. */
    private static final long KEEP_REPORT_MS = 60 * 60 * 1000;
    private static final long DUMP_TIMEOUT_MS = 10_000;
    private static final Object sReportLock = new Object();

    private static final LineRing sRing = new LineRing(MAX_LINES, MAX_CHARS, MAX_LINE_CHARS);
    private static volatile boolean sStarted;
    private static volatile String sRecorderState = "not started";
    private static volatile long sRecordingSinceMs;
    /** Real threadtime lines seen: logcat's own error text (merged stderr) must not count as a log. */
    private static final AtomicInteger sLogLines = new AtomicInteger();

    private DiagnosticLog() {
    }

    /** Idempotent. Main process only; returns immediately (the reader runs on its own thread). */
    public static synchronized void start(Context context) {
        if (sStarted || context == null || "robolectric".equals(Build.FINGERPRINT)
                || !isMainProcess(context)) {
            return;
        }
        sStarted = true;
        Thread reader = new Thread(DiagnosticLog::runReader, "diagnostic-log");
        reader.setDaemon(true);
        reader.setPriority(Thread.MIN_PRIORITY);
        reader.start();
    }

    private static void runReader() {
        try {
            Thread.sleep(START_DELAY_MS);
        } catch (InterruptedException e) {
            return;
        }
        String lastStamp = null;
        for (int attempt = 0; attempt <= MAX_RESTARTS; attempt++) {
            Process process = null;
            try {
                List<String> command = new ArrayList<>(Arrays.asList("logcat", "-v", "threadtime"));
                if (lastStamp != null) {
                    // A restart resumes where the dead reader stopped instead of replaying the buffer.
                    command.add("-T");
                    command.add(lastStamp);
                }
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
                if (sRecordingSinceMs == 0) {
                    sRecordingSinceMs = System.currentTimeMillis();
                }
                sRecorderState = "recording";
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 16 * 1024);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("--------- beginning of")) {
                        continue;
                    }
                    sRing.add(line);
                    String stamp = timestampOf(line);
                    if (stamp != null) {
                        lastStamp = stamp;
                        sLogLines.incrementAndGet();
                    }
                }
                sRecorderState = "logcat exited, restarts=" + attempt;
            } catch (IOException | RuntimeException e) {
                sRecorderState = "unavailable: " + e.getClass().getSimpleName();
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
            try {
                Thread.sleep(5_000L * (attempt + 1));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** {@code MM-DD HH:MM:SS.mmm} prefix of a threadtime line, the format {@code logcat -T} takes. */
    @Nullable
    static String timestampOf(String line) {
        if (line == null || line.length() < 18 || line.charAt(2) != '-' || line.charAt(5) != ' '
                || line.charAt(8) != ':' || line.charAt(14) != '.') {
            return null;
        }
        return line.substring(0, 18);
    }

    /**
     * Builds the redacted report off the main thread and opens the share sheet. Failures say so in
     * a message instead of failing silently: this is the button people press when something broke.
     */
    public static void share(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        // The screen may be gone by the time the file is ready; don't keep it alive for that.
        WeakReference<Context> screen = new WeakReference<>(context);
        Handler main = new Handler(Looper.getMainLooper());
        Thread builder = new Thread(() -> {
            File report;
            try {
                report = writeReport(appContext);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w(TAG, "report failed: " + e);
                main.post(() -> com.liskovsoft.smartyoutubetv2.common.misc.ErrorMessages.showMessage(appContext, R.string.diagnostic_log_failed));
                return;
            }
            main.post(() -> {
                Context target = screen.get();
                boolean usable = target instanceof Activity
                        && !((Activity) target).isFinishing() && !((Activity) target).isDestroyed();
                startShare(usable ? target : appContext, report);
            });
        }, "diagnostic-report");
        builder.start();
    }

    private static void startShare(Context context, File report) {
        Uri uri = FileHelpers.getFileUri(context, report);
        if (uri == null) {
            com.liskovsoft.smartyoutubetv2.common.misc.ErrorMessages.showMessage(context, R.string.diagnostic_log_failed);
            return;
        }
        String subject = context.getString(R.string.diagnostic_log_subject,
                context.getString(R.string.app_name), versionName(context));
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.setClipData(ClipData.newRawUri(subject, uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, context.getString(R.string.diagnostic_log_send));
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            com.liskovsoft.smartyoutubetv2.common.misc.ErrorMessages.showMessage(context, R.string.diagnostic_log_failed);
        }
    }

    static File writeReport(Context context) throws IOException {
        synchronized (sReportLock) {
            return writeReportLocked(context);
        }
    }

    private static File writeReportLocked(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("can't create " + dir);
        }
        File[] old = dir.listFiles();
        long now = System.currentTimeMillis();
        if (old != null) {
            for (File file : old) {
                if (now - file.lastModified() > KEEP_REPORT_MS) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date(now));
        File report = new File(dir, "newtube-diagnostics-" + stamp + ".txt");

        List<String> lines = sRing.snapshot();
        String source = "recorder";
        if (sLogLines.get() == 0) {
            // Recorder never ran (or the ROM refused the stream): take what logcat still holds.
            lines = dumpLogcatOnce();
            source = "one-shot dump";
        }

        try (Writer out = new OutputStreamWriter(new java.io.FileOutputStream(report), StandardCharsets.UTF_8)) {
            out.write(DiagnosticRedactor.redact(buildHeader(context, lines.size(), source)));
            out.write("\n==== Log: this app only, oldest first ====\n");
            for (String line : lines) {
                out.write(DiagnosticRedactor.redact(line));
                out.write('\n');
            }
        }
        return report;
    }

    private static List<String> dumpLogcatOnce() {
        List<String> lines = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start();
            // `logcat -d` exits on its own; an OEM build that hangs it must not hang the share.
            Process dump = process;
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(DUMP_TIMEOUT_MS);
                    dump.destroy();
                } catch (InterruptedException ignored) {
                    // finished in time
                }
            }, "diagnostic-dump-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            LineRing ring = new LineRing(MAX_LINES, MAX_CHARS, MAX_LINE_CHARS);
            String line;
            while ((line = reader.readLine()) != null) {
                ring.add(line);
            }
            lines = ring.snapshot();
            watchdog.interrupt();
        } catch (IOException | RuntimeException e) {
            lines.add("(logcat unavailable: " + e.getClass().getSimpleName() + ")");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return lines;
    }

    private static String buildHeader(Context context, int lineCount, String source) {
        StringBuilder header = new StringBuilder(1024);
        header.append(context.getString(R.string.app_name)).append(" diagnostic log\n");
        header.append("Generated: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n');
        header.append("App: ").append(versionName(context)).append(" (").append(versionCode(context)).append(") ")
                .append(isDebuggable(context) ? "debug" : "release")
                .append(", package ").append(context.getPackageName()).append('\n');
        header.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), ").append(Build.SUPPORTED_ABIS.length > 0
                        ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        header.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
                .append("), build ").append(Build.DISPLAY).append(", ").append(Build.ID).append('\n');
        header.append("Locale: ").append(Locale.getDefault().toLanguageTag()).append('\n');
        header.append("Process uptime: ")
                .append((SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000)
                .append(" s\n");
        header.append("Account: ").append(signedInState()).append('\n');
        header.append("Network: ").append(NetPath.networkSnapshot(context)).append('\n');
        header.append("Player: ").append(playerState(context)).append('\n');
        header.append("Recorder: ").append(sRecorderState);
        if (sRecordingSinceMs > 0) {
            header.append(" since ").append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(sRecordingSinceMs)));
        }
        header.append(", ").append(lineCount).append(" lines (").append(source).append(", max ")
                .append(MAX_LINES).append(")\n");
        return header.toString();
    }

    private static String signedInState() {
        try {
            return YouTubeSignInService.instance().isSigned() ? "signed in" : "signed out";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String playerState(Context context) {
        try {
            PlayerData data = PlayerData.instance(context);
            FormatItem video = data.getFormat(FormatItem.TYPE_VIDEO);
            FormatItem subtitle = data.getFormat(FormatItem.TYPE_SUBTITLE);
            String subtitleLanguage = subtitle != null ? subtitle.getLanguage() : null;
            return "video=" + (video == null ? "default" : video.getHeight() + "p" + (video.isPreset() ? "-preset" : ""))
                    + " subtitles=" + (subtitleLanguage == null || subtitleLanguage.isEmpty() ? "off" : subtitleLanguage)
                    + " buffer=" + data.getVideoBufferType()
                    + " speed=" + data.getSpeed();
        } catch (RuntimeException e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String versionName(Context context) {
        PackageInfo info = packageInfo(context);
        return info != null && info.versionName != null ? info.versionName : "?";
    }

    private static long versionCode(Context context) {
        PackageInfo info = packageInfo(context);
        if (info == null) {
            return -1;
        }
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @Nullable
    private static PackageInfo packageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static boolean isMainProcess(Context context) {
        String name = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : readProcessName();
        return name == null || name.equals(context.getPackageName());
    }

    @Nullable
    private static String readProcessName() {
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int length = in.read(buffer);
            int end = 0;
            while (end < length && buffer[end] != 0) {
                end++;
            }
            return length > 0 ? new String(buffer, 0, end, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Bounded FIFO of log lines: oldest dropped first once either the line or char budget is spent. */
    static final class LineRing {
        private final int mMaxLines;
        private final int mMaxChars;
        private final int mMaxLineChars;
        private final ArrayDeque<String> mLines = new ArrayDeque<>();
        private int mChars;

        LineRing(int maxLines, int maxChars, int maxLineChars) {
            mMaxLines = maxLines;
            mMaxChars = maxChars;
            mMaxLineChars = maxLineChars;
        }

        synchronized void add(String line) {
            if (line == null) {
                return;
            }
            if (line.length() > mMaxLineChars) {
                line = line.substring(0, mMaxLineChars) + "…";
            }
            mLines.addLast(line);
            mChars += line.length();
            while (mLines.size() > mMaxLines || (mChars > mMaxChars && mLines.size() > 1)) {
                mChars -= mLines.removeFirst().length();
            }
        }

        synchronized List<String> snapshot() {
            return new ArrayList<>(mLines);
        }

        synchronized int size() {
            return mLines.size();
        }
    }
}
