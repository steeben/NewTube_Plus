package com.newtube.mobile.ui.playback;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * NEWTUBE(autoplay): reads a googlevideo stream as a run of bounded {@code Range} requests instead
 * of one open-ended GET. googlevideo paces an unbounded transfer of an adaptive stream to about
 * playback speed (see downloads' StreamFetcher), so a feed preview playing one - the 720p picture
 * and its sound, side by side - ran its buffer dry and froze a few seconds in. Ranged reads are
 * served at line rate.
 */
final class RangedDataSource implements DataSource {
    private static final long CHUNK_BYTES = 2L * 1024 * 1024;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory mUpstream;

        Factory(DataSource.Factory upstream) {
            mUpstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new RangedDataSource(mUpstream.createDataSource());
        }
    }

    private final DataSource mUpstream;
    @Nullable
    private DataSpec mSpec;
    /** The stream position of the next byte read. */
    private long mPosition;
    /** Where the whole request ends (exclusive), or {@link C#LENGTH_UNSET} while unknown. */
    private long mEnd = C.LENGTH_UNSET;
    /** Bytes asked for in the open chunk, and read from it so far. */
    private long mChunkLength;
    private long mChunkRead;
    private boolean mChunkOpen;

    private RangedDataSource(DataSource upstream) {
        mUpstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        mUpstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        mSpec = dataSpec;
        mPosition = dataSpec.position;
        mEnd = dataSpec.length != C.LENGTH_UNSET ? dataSpec.position + dataSpec.length : C.LENGTH_UNSET;
        openChunk();
        if (mEnd == C.LENGTH_UNSET) {
            long total = totalFromContentRange(mUpstream.getResponseHeaders());
            if (total > 0) {
                mEnd = total;
            }
        }
        return mEnd != C.LENGTH_UNSET ? mEnd - dataSpec.position : C.LENGTH_UNSET;
    }

    private void openChunk() throws IOException {
        long length = CHUNK_BYTES;
        if (mEnd != C.LENGTH_UNSET) {
            length = Math.min(length, mEnd - mPosition);
        }
        DataSpec spec = mSpec.subrange(mPosition - mSpec.position, length);
        mChunkLength = length;
        mChunkRead = 0;
        mUpstream.open(spec);
        mChunkOpen = true;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (true) {
            if (mEnd != C.LENGTH_UNSET && mPosition >= mEnd) {
                return C.RESULT_END_OF_INPUT;
            }
            if (!mChunkOpen) {
                openChunk();
            }
            int read = mUpstream.read(buffer, offset, length);
            if (read != C.RESULT_END_OF_INPUT) {
                mPosition += read;
                mChunkRead += read;
                return read;
            }
            mUpstream.close();
            mChunkOpen = false;
            if (mChunkRead < mChunkLength) {
                return C.RESULT_END_OF_INPUT; // a short chunk: the stream's real end
            }
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return mUpstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mChunkOpen ? mUpstream.getResponseHeaders() : Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
        mChunkOpen = false;
        mUpstream.close();
    }

    private static long totalFromContentRange(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"content-range".equalsIgnoreCase(entry.getKey())
                    || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String value = entry.getValue().get(0);
            int slash = value.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(value.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
