package com.newtube.mobile.ui.browse;

import android.annotation.SuppressLint;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.newtube.mobile.ui.common.ChannelAvatars;
import com.newtube.mobile.ui.playback.PlayerTransitionBridge;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Touch video card adapter (port of the gist of
 * {@code com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter}, minus
 * focus-scale/d-pad concerns; ripple/elevation instead via the card's own foreground
 * and {@code MaterialCardView} elevation).
 */
public class VideoCardAdapter extends ListAdapter<Video, RecyclerView.ViewHolder> {
    /**
     * NEWTUBE(ui-options): the list this adapter fills, for its column count (full-width cards only
     * in one column), and a listener that redraws the cards when General's card options change.
     */
    @androidx.annotation.Nullable
    private RecyclerView mRecycler;
    private int mAppearanceSpans = -1;
    private final com.newtube.mobile.ui.common.UiPrefs.Listener mUiPrefsListener = this::onAppearanceChanged;
    private final View.OnLayoutChangeListener mSpanWatcher =
            (v, l, t, r, b, ol, ot, or, ob) -> {
                if (mAppearanceSpans != -1 && spanCount() != mAppearanceSpans) {
                    v.post(this::onSpansChanged); // rotated into (or out of) one column
                }
            };

    /** Regular video card (full-width thumbnail + title/meta). */
    private static final int VIEW_TYPE_VIDEO = 0;
    /** Channel result row (round avatar + name + subs) — e.g. channels in search results. */
    private static final int VIEW_TYPE_CHANNEL = 1;

    public interface OnVideoClickListener {
        void onVideoClick(Video video);
    }

    /** Long-press = the touch equivalent of the TV D-pad OK-long-press context-menu shortcut
     *  (ARCHITECTURE.md section 3's input contract: {@code onVideoItemLongClicked}). Routes to
     *  {@code BrowsePresenter.onVideoItemLongClicked()}, which builds an OptionCategory menu via
     *  {@code VideoMenuPresenter}/{@code AppDialogPresenter} and shows it through the Wave 3
     *  {@code MobileAppDialogActivity} renderer. */
    public interface OnVideoLongClickListener {
        boolean onVideoLongClick(Video video);
    }

    private final OnVideoClickListener mClickListener;
    private final OnVideoLongClickListener mLongClickListener;
    /** NEWTUBE(feed-avatars): set = cards show their channel's avatar, and tapping it calls this. */
    private OnVideoClickListener mChannelClickListener;
    /** Held here; ChannelAvatars holds it weakly, so it goes away with this adapter. */
    private final ChannelAvatars.Listener mAvatarListener = this::onAvatarsChanged;

    public VideoCardAdapter(OnVideoClickListener clickListener) {
        this(clickListener, null);
    }

    public VideoCardAdapter(OnVideoClickListener clickListener, OnVideoLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        mClickListener = clickListener;
        mLongClickListener = longClickListener;
    }

    private static final DiffUtil.ItemCallback<Video> DIFF_CALLBACK = new DiffUtil.ItemCallback<Video>() {
        @Override
        public boolean areItemsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            // Video#equals/hashCode are content-based (videoId/playlistId/channelId/...).
            return oldItem.equals(newItem);
        }

        @Override
        @SuppressLint("DiffUtilEquals")
        public boolean areContentsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            // Video.equals() is identity-like (IDs only). A new instance with the same ID can
            // carry refreshed metadata and must be rebound; the same instance needs no rebind.
            return oldItem == newItem;
        }

        /**
         * NEWTUBE(tab-slide): the same video as a new instance (a tab's fresh page replacing its
         * remembered one) is rebound in place, through the partial bind: text and progress are
         * refreshed, the thumbnail is kept unless its URL changed. Without a payload RecyclerView
         * built a second card for each and cross-faded the two - every card on the page dimmed,
         * went blank and reloaded its thumbnail just after a tab switch.
         */
        @Override
        public Object getChangePayload(@NonNull Video oldItem, @NonNull Video newItem) {
            return PAYLOAD_SYNC;
        }
    };

    @Override
    public int getItemViewType(int position) {
        // isChannel() alone also matches playlists delivered in the playlist-as-channel shape
        // (videoId/playlistId null + a VL... channelId — the standard shape of the user
        // Playlists section): those must stay thumbnail cards, not round-avatar channel rows.
        Video item = getItem(position);
        return item.isChannel() && !item.isPlaylistAsChannel() ? VIEW_TYPE_CHANNEL : VIEW_TYPE_VIDEO;
    }

    /**
     * NEWTUBE(feed-avatars): show each video card's channel avatar (Home, Subscriptions), opening the
     * channel through {@code onChannelClick} when tapped; null hides them. The browse screen calls
     * it once a section's new list is committed, before that list's cards are laid out; switching
     * on or off also refreshes cards kept from the previous list.
     */
    public void setChannelAvatars(OnVideoClickListener onChannelClick) {
        boolean changed = (mChannelClickListener == null) != (onChannelClick == null);
        mChannelClickListener = onChannelClick;
        if (onChannelClick != null) {
            ChannelAvatars.addListener(mAvatarListener);
        }
        if (changed && getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_AVATAR);
        }
    }

    private void onAvatarsChanged(java.util.Set<String> ids) {
        if (mChannelClickListener == null) {
            return;
        }
        boolean all = ids.contains(ChannelAvatars.ALL);
        java.util.List<Video> current = getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            Video video = current.get(i);
            if (!VideoViewHolder.hasChannelAvatar(video)) {
                continue;
            }
            String channelId = ChannelAvatars.channelIdOf(video);
            if (all || ids.contains(video.videoId) || channelId != null && ids.contains(channelId)) {
                notifyItemChanged(i, PAYLOAD_AVATAR);
            }
        }
    }

    /** Full-span rows (channel results) for the grid's SpanSizeLookup in multi-column layouts. */
    public boolean isFullSpan(int position) {
        return position >= 0 && position < getItemCount() && getItemViewType(position) == VIEW_TYPE_CHANNEL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_CHANNEL) {
            return new ChannelViewHolder(inflater.inflate(R.layout.item_mobile_channel_card, parent, false), mClickListener);
        }
        return new VideoViewHolder(inflater.inflate(R.layout.item_video_card, parent, false), mClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ChannelViewHolder) {
            ((ChannelViewHolder) holder).bind(getItem(position), mLongClickListener);
        } else {
            ((VideoViewHolder) holder).bind(getItem(position), mLongClickListener);
            ((VideoViewHolder) holder).bindChannelAvatar(mChannelClickListener);
            applyAppearance((VideoViewHolder) holder);
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder instanceof VideoViewHolder) {
            applyAppearance((VideoViewHolder) holder); // no-op unless the columns changed meanwhile
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecycler = recyclerView;
        com.newtube.mobile.ui.common.UiPrefs.addListener(mUiPrefsListener);
        recyclerView.addOnLayoutChangeListener(mSpanWatcher);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        recyclerView.removeOnLayoutChangeListener(mSpanWatcher);
        com.newtube.mobile.ui.common.UiPrefs.removeListener(mUiPrefsListener);
        if (mRecycler == recyclerView) {
            mRecycler = null;
        }
    }

    /**
     * NEWTUBE(tab-memory): the tab on screen is being left - keep the pictures of its cards on
     * screen (and just off it), so coming back puts them up at once instead of reloading them.
     */
    public void rememberPictures() {
        if (mRecycler == null) {
            return;
        }
        for (int i = 0; i < mRecycler.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = mRecycler.getChildViewHolder(mRecycler.getChildAt(i));
            if (holder instanceof VideoViewHolder) {
                ((VideoViewHolder) holder).rememberPicture();
            }
        }
    }

    /** NEWTUBE(ui-options): General's card options changed, or the column count did: redraw. */
    @SuppressLint("NotifyDataSetChanged")
    private void onAppearanceChanged() {
        mAppearanceSpans = -1;
        notifyDataSetChanged();
    }

    /**
     * NEWTUBE(playlists-grid): the column count changed (a rotation, or a tab with its own columns
     * - Playlists has two): the cards on screen get their new margins where they are. Rebinding
     * them all (notifyDataSetChanged) reloaded every thumbnail, a flash on each tab switch.
     */
    private void onSpansChanged() {
        if (mRecycler == null) {
            onAppearanceChanged();
            return;
        }
        for (int i = 0; i < mRecycler.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = mRecycler.getChildViewHolder(mRecycler.getChildAt(i));
            if (holder instanceof VideoViewHolder) {
                applyAppearance((VideoViewHolder) holder);
            }
        }
        // Cards just off screen come back from RecyclerView's cache without a bind: they are
        // styled as they attach (onViewAttachedToWindow).
        mAppearanceSpans = spanCount();
    }

    private int spanCount() {
        RecyclerView.LayoutManager manager = mRecycler != null ? mRecycler.getLayoutManager() : null;
        return manager instanceof androidx.recyclerview.widget.GridLayoutManager
                ? ((androidx.recyclerview.widget.GridLayoutManager) manager).getSpanCount() : 1;
    }

    /** NEWTUBE(ui-options): full-width cards (one column only), square corners, no ⋮. */
    private void applyAppearance(VideoViewHolder holder) {
        Context context = holder.itemView.getContext();
        int spans = spanCount();
        mAppearanceSpans = spans;
        boolean fullWidth = spans == 1 && com.newtube.mobile.ui.common.UiPrefs.isFullWidthThumbnails(context);
        holder.applyAppearance(fullWidth,
                mRecycler != null ? mRecycler.getPaddingLeft() : 0,
                mRecycler != null ? mRecycler.getPaddingRight() : 0,
                com.newtube.mobile.ui.common.UiPrefs.isRoundedThumbnails(context),
                com.newtube.mobile.ui.common.UiPrefs.isCardMenuHidden(context));
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).unbind();
        } else if (holder instanceof ChannelViewHolder) {
            ((ChannelViewHolder) holder).unbind();
        }
    }

    /**
     * Force a re-bind of the given items in place.
     *
     * Needed for {@code VideoGroup.ACTION_SYNC} (e.g. DeArrow / unlocalized-title overrides):
     * those processors MUTATE the existing {@link Video} instances in place
     * ({@code video.deArrowTitle} / {@code video.altCardImageUrl}) rather than emitting new
     * ones, so {@code submitList()} can't detect the change - {@link #DIFF_CALLBACK}'s
     * {@code areContentsTheSame} sees the same object reference on both sides and skips the
     * bind. We locate each item by content identity ({@link Video#equals}) in the currently
     * displayed list and rebind that row so the crowd-sourced title/thumbnail actually shows.
     */
    public void refreshItems(java.util.List<Video> items) {
        java.util.List<Video> current = getCurrentList();
        for (Video item : items) {
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).equals(item)) {
                    // Payloaded change: RecyclerView reuses the bound holder (no change-animation
                    // cross-fade of the whole card) and the partial bind below skips the Glide
                    // reload unless the thumbnail URL actually changed. A bare notifyItemChanged
                    // here made the just-watched card visibly blink on every Browse resume (the
                    // resume sync only carries a percentWatched update).
                    notifyItemChanged(i, PAYLOAD_SYNC);
                }
            }
        }
    }

    /** Marker payload for in-place item mutations (watch progress, DeArrow overrides). */
    private static final Object PAYLOAD_SYNC = new Object();
    /** NEWTUBE(feed-avatars): the card's channel avatar arrived or changed (ChannelAvatars). */
    private static final Object PAYLOAD_AVATAR = new Object();

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
            @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof VideoViewHolder) {
            VideoViewHolder card = (VideoViewHolder) holder;
            if (payloads.contains(PAYLOAD_SYNC)) {
                card.bindPartial(getItem(position));
            }
            // A sync can also bring the card its channel (Video.sync after playback).
            card.bindChannelAvatar(mChannelClickListener);
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    /**
     * Public for reuse by other screens that render the standard video card (kept public
     * after the channel page's old heterogeneous adapter was replaced by tabs, 2026-07-09).
     */
    public static class VideoViewHolder extends RecyclerView.ViewHolder
            implements com.newtube.mobile.ui.playback.FeedAutoplay.Card {
        private final ImageView mThumbnail;
        private final View mThumbnailFrame;
        private final TextView mBadge;
        private final ProgressBar mWatchProgress;
        private final TextView mTitle;
        private final TextView mMeta;
        private final View mOverflow;
        private final ImageView mAvatar; // null on layouts without one
        private Video mVideo;
        private String mBoundThumbUrl;
        private String mBoundAvatarUrl;

        public VideoViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mThumbnail = itemView.findViewById(R.id.video_thumbnail);
            mThumbnailFrame = itemView.findViewById(R.id.video_thumbnail_frame);
            mBadge = itemView.findViewById(R.id.video_badge);
            mWatchProgress = itemView.findViewById(R.id.video_watch_progress);
            mTitle = itemView.findViewById(R.id.video_title);
            mMeta = itemView.findViewById(R.id.video_meta);
            mOverflow = itemView.findViewById(R.id.video_overflow);
            mAvatar = itemView.findViewById(R.id.video_channel_avatar);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    if (mVideo.videoId != null) {
                        // NEWTUBE(autoplay): a previewing card opens from its live picture, at its time.
                        com.newtube.mobile.ui.playback.FeedAutoplay.beforeOpen(
                                mThumbnailFrame instanceof ViewGroup ? (ViewGroup) mThumbnailFrame : null,
                                mThumbnail, mVideo);
                        PlayerTransitionBridge.prepare(mThumbnailFrame, mBadge, mWatchProgress);
                    } else {
                        PlayerTransitionBridge.clear();
                    }
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        @Override
        public Video video() {
            return mVideo;
        }

        @Override
        public ViewGroup thumbnailFrame() {
            return mThumbnailFrame instanceof ViewGroup ? (ViewGroup) mThumbnailFrame : null;
        }

        @Override
        public ImageView thumbnail() {
            return mThumbnail;
        }

        public void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;
            Context context = itemView.getContext();

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            // ⋮ = the exact long-press context menu, just discoverable (Not interested, Open
            // channel, playlists...). Hidden when the host screen has no menu wired.
            if (mOverflow != null) {
                mOverflow.setVisibility(longClickListener == null ? View.GONE : View.VISIBLE);
                mOverflow.setOnClickListener(v -> {
                    if (mVideo != null && longClickListener != null) {
                        longClickListener.onVideoLongClick(mVideo);
                    }
                });
                // NEWTUBE(a11y): one of 40 identical "More options" otherwise.
                mOverflow.setContentDescription(video.getTitle() != null
                        ? context.getString(R.string.mobile_more_options_for, video.getTitle())
                        : context.getString(R.string.mobile_player_more));
            }

            mTitle.setText(video.getTitle());

            // "Channel · 1.2M views · 3 weeks ago" like YouTube; author-only when the
            // service didn't build the info line (e.g. some playlist rows).
            CharSequence meta = video.getSecondTitle();
            mMeta.setText(meta == null || meta.length() == 0 ? video.getAuthor() : meta);

            bindBadge(context, video);
            bindProgress(video);
            bindReadiness(video);
            bindThumbnail(context, video);
        }

        /**
         * Sync rebind for an item mutated in place (ACTION_SYNC): refresh the text/progress
         * views and only touch the thumbnail if its resolved URL changed (DeArrow override
         * arriving). Keeping the ImageView untouched for a same-URL sync is what stops the
         * just-watched card from blinking when Browse resumes after playback.
         */
        /**
         * NEWTUBE(ui-options): {@code fullWidth} - the card runs past the list's side padding
         * ({@code padLeft}/{@code padRight}) to the screen's edges and its text keeps an inset of
         * its own; {@code rounded} - the thumbnail's corners; {@code hideMenu} - no ⋮ (the same
         * menu is still on a long press).
         */
        void applyAppearance(boolean fullWidth, int padLeft, int padRight, boolean rounded, boolean hideMenu) {
            android.content.res.Resources res = itemView.getResources();
            float density = res.getDisplayMetrics().density;
            int spacing = res.getDimensionPixelSize(R.dimen.mobile_card_spacing);
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                int left = fullWidth ? -padLeft : spacing;
                int right = fullWidth ? -padRight : spacing;
                if (margins.leftMargin != left || margins.rightMargin != right) {
                    margins.leftMargin = left;
                    margins.rightMargin = right;
                    margins.setMarginStart(left);
                    margins.setMarginEnd(right);
                    itemView.setLayoutParams(margins);
                }
            }
            int textInset = Math.round((fullWidth ? 16 : 4) * density);
            // NEWTUBE(feed-avatars): the avatar sits at the inset and the title 12 dp after it; with
            // no avatar the title takes its gone margin, the inset. The meta line follows the title.
            int titleStart = mAvatar != null ? Math.round(12 * density) : textInset;
            int metaStart = mAvatar != null ? 0 : textInset;
            if (mAvatar != null) {
                setHorizontalMargins(mAvatar, textInset, 0);
                setGoneStartMargin(mTitle, textInset);
            }
            setHorizontalMargins(mTitle, titleStart, Math.round(4 * density));
            setHorizontalMargins(mMeta, metaStart, Math.round(4 * density));
            if (mThumbnailFrame instanceof com.google.android.material.card.MaterialCardView) {
                ((com.google.android.material.card.MaterialCardView) mThumbnailFrame).setRadius(rounded
                        ? res.getDimension(R.dimen.mobile_card_corner_radius) : 0f);
            }
            if (hideMenu && mOverflow != null) {
                mOverflow.setVisibility(View.GONE);
            }
            if (mOverflow != null) {
                // With no ⋮ the text runs to the card's end, kept off the edge like its start.
                setHorizontalMargins(mTitle, titleStart, mOverflow.getVisibility() == View.GONE ? textInset
                        : Math.round(4 * density));
                setHorizontalMargins(mMeta, metaStart, mOverflow.getVisibility() == View.GONE ? textInset
                        : Math.round(4 * density));
            }
        }

        private static void setGoneStartMargin(View view, int margin) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params instanceof androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams constraints =
                        (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) params;
                if (constraints.goneStartMargin != margin) {
                    constraints.goneStartMargin = margin;
                    view.setLayoutParams(constraints);
                }
            }
        }

        private static void setHorizontalMargins(View view, int start, int end) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (!(params instanceof ViewGroup.MarginLayoutParams)) {
                return;
            }
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            if (margins.getMarginStart() != start || margins.getMarginEnd() != end) {
                margins.setMarginStart(start);
                margins.setMarginEnd(end);
                margins.leftMargin = start;
                margins.rightMargin = end;
                view.setLayoutParams(margins);
            }
        }

        void bindPartial(Video video) {
            mVideo = video;
            Context context = itemView.getContext();

            mTitle.setText(video.getTitle());
            CharSequence meta = video.getSecondTitle();
            mMeta.setText(meta == null || meta.length() == 0 ? video.getAuthor() : meta);

            bindBadge(context, video);
            bindProgress(video);
            bindReadiness(video);

            String thumbnailUrl = thumbnailUrl(context, video);
            if (!samePicture(thumbnailUrl, mBoundThumbUrl)) {
                bindThumbnail(context, video);
            }
        }

        private void bindBadge(Context context, Video video) {
            String badgeText;
            if (video.hasNewContent) {
                badgeText = context.getString(R.string.badge_new_content);
            } else if (video.isLive) {
                badgeText = context.getString(R.string.badge_live);
            } else if (video.isShorts) {
                badgeText = context.getString(R.string.header_shorts).toUpperCase();
            } else {
                badgeText = video.badge;
            }

            if (badgeText == null || badgeText.isEmpty()) {
                mBadge.setVisibility(View.GONE);
            } else {
                mBadge.setText(badgeText);
                // NEWTUBE(frosted): a rounded pill of dark glass over the thumbnail (red for live).
                com.newtube.mobile.ui.common.BadgeGlassDrawable.apply(mBadge, mThumbnail,
                        video.isLive || video.isUpcoming ? 0xB3D32F2F : 0x73000000);
                mBadge.setVisibility(View.VISIBLE);
            }
        }

        /**
         * NEWTUBE(downloads): a download that is not playable yet (queued, fetching, finishing,
         * failed) keeps its thumbnail dimmed so it never reads as a ready video - the badge and
         * the meta line say what it is doing.
         */
        private void bindReadiness(Video video) {
            mThumbnail.setAlpha(video.isPendingDownload() ? 0.4f : 1f);
        }

        private void bindProgress(Video video) {
            // Count progress that's very close to zero (e.g. user closed the video immediately).
            int progress = video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched);

            if (progress > 0 && progress <= 100) {
                // NEWTUBE(accent): the watched bar in the user's accent.
                mWatchProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                        com.newtube.mobile.ui.common.UiPrefs.getAccent(mWatchProgress.getContext())));
                mWatchProgress.setProgress(progress);
                mWatchProgress.setVisibility(View.VISIBLE);
            } else {
                mWatchProgress.setVisibility(View.GONE);
            }
        }

        /**
         * SCROLL-JANK FIX: decode thumbnails downsampled to the card's real display size (not the
         * layout-determined size Glide would otherwise wait a measure pass for) and in RGB_565.
         * Thumbnails are opaque JPEGs, so 565 halves both decode work and the GPU texture upload
         * per card - the biggest per-frame cost while fling-scrolling the grid. Sized for the
         * portrait single-column feed (the largest card = full screen width); landscape splits
         * the wider dimension across 2+ columns, so this stays a correct downsample bound there.
         */
        private static int sThumbW;
        private static int sThumbH;

        private static int thumbWidth(Context context) {
            if (sThumbW == 0) {
                android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                sThumbW = Math.min(dm.widthPixels, dm.heightPixels);
                sThumbH = sThumbW * 9 / 16;
            }
            return sThumbW;
        }

        /**
         * The card's primary thumbnail request, without its view-only options (transition, error
         * fallback). NEWTUBE(scroll): shared with {@link com.newtube.mobile.ui.common.FeedThumbnailPreloader}
         * so a preloaded image has exactly the memory-cache key the bind will ask for (same URL,
         * size, transformation and decode format) and the card binds it with no network wait and
         * no fade.
         */
        public static com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> thumbnailRequest(
                com.bumptech.glide.RequestManager glide, Context context, Video video) {
            int w = thumbWidth(context);
            return glide
                    .load(thumbnailUrl(context, video))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(w, sThumbH)
                    .centerCrop();
        }

        public static String thumbnailUrl(Context context, Video video) {
            // NEWTUBE(ux): entries saved without an image (local history, link opens) get the
            // i.ytimg.com fallback here, so the preloader and the bind ask for the same URL.
            com.newtube.mobile.ui.common.LocalThumbnails.fill(video);
            return ClickbaitRemover.updateThumbnail(video, MainUIData.instance(context).getThumbQuality());
        }

        /**
         * NEWTUBE(tab-slide): the two URLs show the same picture. YouTube signs its thumbnail links
         * per response ({@code ?sqp=...&rs=...}), so a tab's fresh page brings the same picture
         * under a new link - compared whole, every card reloaded (went dark, faded back in) a
         * moment after a tab switch. The path names the picture (video and size); the query only
         * signs it.
         */
        static boolean samePicture(String a, String b) {
            if (a == null || b == null) {
                return a == null && b == null;
            }
            if (a.equals(b)) {
                return true;
            }
            int qa = a.indexOf('?');
            int qb = b.indexOf('?');
            String pathA = qa >= 0 ? a.substring(0, qa) : a;
            String pathB = qb >= 0 ? b.substring(0, qb) : b;
            if (pathA.equals(pathB) && pathA.contains("ytimg.com/")) {
                return true;
            }
            // The same video's picture at another size (hqdefault / hq720 / sddefault...): a fresh
            // page often names another one than the remembered page did. It is the same picture.
            String idA = ytimgVideoId(pathA);
            return idA != null && idA.equals(ytimgVideoId(pathB));
        }

        /** The video id of an i.ytimg.com/vi/ID/... (or vi_webp) picture link, else null. */
        @androidx.annotation.Nullable
        static String ytimgVideoId(String path) {
            int host = path.indexOf("ytimg.com/");
            if (host < 0) {
                return null;
            }
            String rest = path.substring(host + "ytimg.com/".length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String kind = rest.substring(0, slash);
            if (!kind.equals("vi") && !kind.equals("vi_webp")) {
                return null; // an_webp (animated), sb (storyboards): not the card picture
            }
            String tail = rest.substring(slash + 1);
            int end = tail.indexOf('/');
            String id = end >= 0 ? tail.substring(0, end) : null;
            String file = end >= 0 ? tail.substring(end + 1) : "";
            // Frames like 1.jpg / 2.jpg / hq1.jpg are other moments of the video, not its thumbnail.
            if (id == null || file.matches("(hq|mq|sd)?[0-9]+\\.(jpg|webp)")) {
                return null;
            }
            return id;
        }

        /** NEWTUBE(tab-memory): the video whose picture the thumbnail shows (null: none). */
        @androidx.annotation.Nullable
        private String mVideoIdShown;

        /** The picture on screen, copied (Glide's bitmap returns to its pool once the load is cleared). */
        @androidx.annotation.Nullable
        private android.graphics.drawable.Drawable copyOfShown() {
            android.graphics.Bitmap copy = drawShown();
            return copy != null ? new android.graphics.drawable.BitmapDrawable(mThumbnail.getResources(), copy) : null;
        }

        @androidx.annotation.Nullable
        private android.graphics.Bitmap drawShown() {
            if (mThumbnail.getDrawable() == null || mThumbnail.getWidth() <= 0 || mThumbnail.getHeight() <= 0) {
                return null;
            }
            try {
                android.graphics.Bitmap copy = android.graphics.Bitmap.createBitmap(mThumbnail.getWidth(),
                        mThumbnail.getHeight(), android.graphics.Bitmap.Config.RGB_565);
                android.graphics.Canvas canvas = new android.graphics.Canvas(copy);
                canvas.concat(mThumbnail.getImageMatrix());
                mThumbnail.getDrawable().draw(canvas);
                return copy;
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }

        /** NEWTUBE(tab-memory): a copy of the picture on screen, for when its tab is shown again. */
        void rememberPicture() {
            if (mBoundThumbUrl == null || mThumbReadyAt <= 0 || mThumbnail.getDrawable() == null
                    || mThumbnail.getWidth() <= 0 || mThumbnail.getHeight() <= 0) {
                return;
            }
            // A preview's frame left on the card (an autoplay opened from it) is not its picture:
            // remembered, it came back as the thumbnail of that video on every tab.
            if (com.newtube.mobile.ui.playback.FeedAutoplay.isStill(mThumbnail)) {
                return;
            }
            if (TabPictures.get(mBoundThumbUrl, mThumbnail.getWidth()) != null) {
                return;
            }
            android.graphics.Bitmap copy = drawShown(); // drawn as shown, Glide's bitmap left alone
            if (copy != null) {
                TabPictures.put(mBoundThumbUrl, copy);
            }
        }

        /** NEWTUBE(tab-slide): when this card's thumbnail was put up (0: not yet). */
        private long mThumbReadyAt;

        /** The thumbnail is up and its fade-in done (a slide's picture may give way to it). */
        public boolean isThumbnailSettled() {
            return mThumbReadyAt > 0 && android.os.SystemClock.uptimeMillis() - mThumbReadyAt > 200;
        }

        private void bindThumbnail(Context context, Video video) {
            String thumbnailUrl = thumbnailUrl(context, video);
            // NEWTUBE(tab-slide): the same picture already up (a rebind after a refetch, a tab
            // shown again) - leave it. Loading it again blanked the card for a moment (from the
            // disk cache, a fade in), the flicker after changing tabs.
            boolean still = com.newtube.mobile.ui.playback.FeedAutoplay.takeStill(mThumbnail);
            if (!still && samePicture(thumbnailUrl, mBoundThumbUrl) && mThumbnail.getDrawable() != null
                    && mThumbReadyAt > 0) {
                return;
            }
            // NEWTUBE(tab-memory): this picture as it was on screen when its tab was left - up at
            // once, no load and no fade. A tab switch rebinds the whole grid, and Glide brought the
            // pictures back from its disk cache with a fade in, a moment after the tab landed.
            android.graphics.Bitmap held = !still ? TabPictures.get(thumbnailUrl, mThumbnail.getWidth()) : null;
            // Remembered at another card width (the grid's columns changed with the tab - Playlists
            // has two - and the card is not laid out at its new width yet): it is still the
            // picture, so the load fades in over it instead of over a dark card.
            android.graphics.Bitmap heldElsewhere = !still && held == null ? TabPictures.get(thumbnailUrl, 0) : null;
            if (held != null) {
                Glide.with(context.getApplicationContext()).clear(mThumbnail); // a load still on its way
                mThumbnail.setImageBitmap(held);
                mBoundThumbUrl = thumbnailUrl;
                mVideoIdShown = video.videoId;
                mThumbReadyAt = android.os.SystemClock.uptimeMillis() - 1000;
                mBadge.post(mBadge::invalidate);
                return;
            }
            // NEWTUBE(tab-memory): the card already shows a picture of this video (a new one is
            // coming - DeArrow, another size): the new one fades in over it, not over a dark card.
            android.graphics.drawable.Drawable previous = !still && mThumbReadyAt > 0 && mVideoIdShown != null
                    && mVideoIdShown.equals(video.videoId) ? copyOfShown() : null;
            if (previous == null && heldElsewhere != null) {
                previous = new android.graphics.drawable.BitmapDrawable(mThumbnail.getResources(), heldElsewhere);
            }
            mThumbReadyAt = 0;
            mBoundThumbUrl = thumbnailUrl;
            mVideoIdShown = video.videoId;

            int w = thumbWidth(context);
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request =
                    thumbnailRequest(Glide.with(context), context, video)
                    // Network loads fade in over the placeholder-colored card instead of popping;
                    // memory-cache hits skip the transition entirely, so scrolling stays crisp.
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150));

            // NEWTUBE(startup): release-visible "first thumbnail on screen" milestone. The listener
            // is attached only until it has fired once per process.
            if (com.newtube.mobile.LaunchMilestones.wantsFirstThumb()) {
                request = request.addListener(FIRST_THUMB_LISTENER);
            }

            // At the default thumb quality the primary URL IS the card URL, so a fallback request
            // would be identical - only pay the second RequestBuilder when it can actually differ.
            String fallbackUrl = video.getCardImageUrl();
            if (fallbackUrl != null && !fallbackUrl.equals(thumbnailUrl)) {
                request = request.error(Glide.with(context)
                        .load(fallbackUrl)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(w, sThumbH)
                        .centerCrop());
            }

            request = request.addListener(mBadgeRefresh); // NEWTUBE(frosted): the badge's glass shows it
            if (previous != null) {
                request = request.placeholder(previous);
            }
            request.into(mThumbnail);
        }

        /** The thumbnail arrived (and once its fade-in is done): the badge's glass draws it again. */
        private final com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> mBadgeRefresh =
                new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        mThumbReadyAt = android.os.SystemClock.uptimeMillis()
                                - (dataSource == com.bumptech.glide.load.DataSource.MEMORY_CACHE ? 1000 : 0);
                        mBadge.post(mBadge::invalidate);
                        mBadge.postDelayed(mBadge::invalidate, 200);
                        return false;
                    }
                };

        private static final com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> FIRST_THUMB_LISTENER =
                new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        com.newtube.mobile.LaunchMilestones.onFirstThumb(dataSource);
                        return false;
                    }
                };

        /**
         * NEWTUBE(feed-avatars): the channel avatar left of the title, on a video card of a screen
         * that wants them ({@code onChannelClick} set). Its URL comes from {@link ChannelAvatars};
         * an unknown one shows the placeholder circle and is asked for, and the adapter rebinds
         * this when it arrives.
         */
        public void bindChannelAvatar(OnVideoClickListener onChannelClick) {
            if (mAvatar == null) {
                return;
            }
            Video video = mVideo;
            if (onChannelClick == null || video == null || !hasChannelAvatar(video)) {
                if (mAvatar.getVisibility() != View.GONE) {
                    mAvatar.setVisibility(View.GONE);
                    Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
                    mBoundAvatarUrl = null;
                }
                return;
            }

            Context context = itemView.getContext();
            mAvatar.setVisibility(View.VISIBLE);
            mAvatar.setOnClickListener(v -> {
                if (mVideo != null) {
                    PlayerTransitionBridge.clear();
                    onChannelClick.onVideoClick(mVideo);
                }
            });
            String author = video.getAuthor();
            mAvatar.setContentDescription(author != null && !author.isEmpty()
                    ? context.getString(R.string.mobile_card_go_to_channel, author)
                    : context.getString(R.string.open_channel));

            int size = context.getResources().getDimensionPixelSize(R.dimen.mobile_card_avatar_size);
            ChannelAvatars.request(video); // no-op when known and fresh
            String url = ChannelAvatars.sized(ChannelAvatars.get(video), size);
            if (url == null) {
                mBoundAvatarUrl = null;
                Glide.with(context.getApplicationContext()).clear(mAvatar);
                mAvatar.setImageResource(R.drawable.bg_card_avatar_placeholder);
                return;
            }
            if (url.equals(mBoundAvatarUrl)) {
                return; // already showing (or loading) it: no flash on a rebind
            }
            mBoundAvatarUrl = url;
            Glide.with(context)
                    .load(url)
                    .override(size)
                    .circleCrop()
                    .placeholder(R.drawable.bg_card_avatar_placeholder)
                    .error(R.drawable.bg_card_avatar_placeholder)
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150))
                    .into(mAvatar);
        }

        /**
         * A single video. A mix or a playlist has no one channel to show (YouTube shows none there
         * either; a mix's menu names no channel), nor has a channel row.
         */
        static boolean hasChannelAvatar(Video video) {
            return video.videoId != null && !video.hasPlaylist();
        }

        public void unbind() {
            mVideo = null;
            mBoundThumbUrl = null;
            mVideoIdShown = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mThumbnail);
            if (mAvatar != null && mBoundAvatarUrl != null) {
                Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
                mAvatar.setImageResource(R.drawable.bg_card_avatar_placeholder);
            }
            mBoundAvatarUrl = null;
        }
    }

    /**
     * Channel result row: round avatar (the channel item's cardImageUrl IS the avatar) +
     * name + "subscribers · N videos". Tap routes through the same click listener — the
     * presenters' onVideoItemClicked already opens the mobile channel screen for
     * {@code Video.isChannel()} items; long-press/⋮-less row keeps the context menu too.
     */
    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mAvatar;
        private final TextView mName;
        private final TextView mMeta;
        private Video mVideo;

        ChannelViewHolder(@NonNull View itemView, OnVideoClickListener clickListener) {
            super(itemView);

            mAvatar = itemView.findViewById(R.id.channel_avatar);
            mName = itemView.findViewById(R.id.channel_name);
            mMeta = itemView.findViewById(R.id.channel_meta);

            itemView.setOnClickListener(v -> {
                if (mVideo != null && clickListener != null) {
                    PlayerTransitionBridge.clear();
                    clickListener.onVideoClick(mVideo);
                }
            });
        }

        void bind(Video video, OnVideoLongClickListener longClickListener) {
            mVideo = video;

            itemView.setOnLongClickListener(v ->
                    mVideo != null && longClickListener != null && longClickListener.onVideoLongClick(mVideo));

            mName.setText(video.getTitle());

            // secondTitle = subscriber count, badge = "N videos" (see MediaItemImpl mapping).
            CharSequence subs = video.getSecondTitle();
            String videosCount = video.badge;
            StringBuilder meta = new StringBuilder();
            if (subs != null && subs.length() > 0) {
                meta.append(subs);
            }
            if (videosCount != null && !videosCount.isEmpty()) {
                if (meta.length() > 0) {
                    meta.append(com.newtube.mobile.ui.common.MetaSeparator.DOT);
                }
                meta.append(videosCount);
            }
            mMeta.setText(meta);
            mMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

            Glide.with(itemView.getContext())
                    .load(video.getCardImageUrl())
                    .circleCrop()
                    .placeholder(R.drawable.ic_watch_channel_placeholder)
                    .error(R.drawable.ic_watch_channel_placeholder)
                    .into(mAvatar);
        }

        void unbind() {
            mVideo = null;
            Glide.with(itemView.getContext().getApplicationContext()).clear(mAvatar);
        }
    }

    /**
     * NEWTUBE(tab-memory): the pictures of the cards that were on screen when their tab was left,
     * drawn at their size (so they go back up as they were), most recent last. Keyed by the
     * picture's link without its signature (samePicture).
     */
    static final class TabPictures {
        private static final int MAX = 24; // ~4 cards a tab, 1.3 MB each at full width
        private static final java.util.LinkedHashMap<String, android.graphics.Bitmap> sPictures =
                new java.util.LinkedHashMap<String, android.graphics.Bitmap>(MAX, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> eldest) {
                        return size() > MAX;
                    }
                };

        private TabPictures() {
        }

        private static String key(String url) {
            int query = url.indexOf('?');
            String path = query >= 0 && url.contains("ytimg.com/") ? url.substring(0, query) : url;
            String id = VideoViewHolder.ytimgVideoId(path);
            return id != null ? "yt:" + id : path; // any size of a video's picture is the same picture
        }

        @androidx.annotation.Nullable
        static android.graphics.Bitmap get(@androidx.annotation.Nullable String url, int width) {
            if (url == null) {
                return null;
            }
            android.graphics.Bitmap bitmap = sPictures.get(key(url));
            // Drawn for another card width (columns, rotation): it would be stretched - load it.
            return bitmap != null && (width <= 0 || bitmap.getWidth() == width) ? bitmap : null;
        }

        static void put(String url, android.graphics.Bitmap bitmap) {
            sPictures.put(key(url), bitmap);
        }

        static void clear() {
            sPictures.clear();
        }
    }
}
