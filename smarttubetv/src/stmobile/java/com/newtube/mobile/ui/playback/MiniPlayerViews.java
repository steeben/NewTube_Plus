package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.google.android.material.card.MaterialCardView;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(mini-style): the views of one mini player (mobile_mini_player_overlay.xml) and how each
 * {@link MiniPlayerStyle} lays them out. The host screens' mini player (MobileMiniPlayerController)
 * uses it, and so does the player itself for the bars' minimize: it draws the bar's title and
 * buttons over (or, for the plain bar, beside) the shrinking video and fades them in as the video
 * lands, from a copy laid out exactly like the host's (MobilePlaybackActivity#applyBarSlide).
 */
final class MiniPlayerViews {
    /** The frosted glass's picture of the video: size and blur. */
    private static final int FROST_WIDTH = 64;
    private static final int FROST_HEIGHT = 36;
    private static final int FROST_BLUR_RADIUS = 2;

    final View card;
    final FrameLayout frame;
    final ImageView freeze;
    final ImageButton playPause;
    final ImageButton close;
    final ProgressBar progress;
    final View backdrop;
    final View dim;
    final LinearLayout text;
    final TextView title;
    final TextView channel;
    final ImageView flash;
    /** NEWTUBE(mini-next): skip to the playlist's next video; shown only while there is one. */
    final ImageButton next;

    int style = -1;
    private boolean mHasNext;
    private final MiniProgressDrawable mProgressDrawable;
    boolean cardLeft;

    @Nullable
    private FrostedScrimDrawable mTitleFrost;
    /** The video-background bar's play/pause and close: icons of frosted glass. */
    @Nullable
    private FrostedIconDrawable mPlayIcon;
    @Nullable
    private FrostedIconDrawable mCloseIcon;
    @Nullable
    private FrostedIconDrawable mNextIcon;
    private boolean mPlaying = true;
    /** The card's own outline and clipping, put back when the rounded-top bar is left. */
    private final android.view.ViewOutlineProvider mCardOutline;
    private final boolean mCardClips;
    private float mTopRadiusPx;
    private final android.view.ViewOutlineProvider mTopRoundedOutline = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            int width = view.getWidth();
            int height = view.getHeight();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.graphics.Path path = new android.graphics.Path();
                float r = mTopRadiusPx;
                path.addRoundRect(0, 0, width, height, new float[] {r, r, r, r, 0, 0, 0, 0},
                        android.graphics.Path.Direction.CW);
                outline.setPath(path);
            } else {
                // Before Android 13 only a rounded rectangle clips: one that runs past the bottom
                // edge (which the view's bounds cut anyway) rounds only the top corners.
                outline.setRoundRect(0, 0, width, height + Math.round(mTopRadiusPx), mTopRadiusPx);
            }
        }
    };
    @Nullable
    private Bitmap mFrostSample;
    @Nullable
    private Bitmap mFrostBlur;
    @Nullable
    private int[] mFrostPixels;
    @Nullable
    private int[] mFrostScratch;
    private final Rect mFrostSource = new Rect();

    /** NEWTUBE(mini-underlap): px the video bar reaches below its bar, behind the host's navigation. */
    private int mUnderlapPx;
    /** NEWTUBE(pills): what the card's bottom margin adds to clear a floating navigation pill. */
    private int mNavClearancePx;
    private final int mOrigBottomToTop;
    private final int mOrigBottomToBottom;

    MiniPlayerViews(View card) {
        this.card = card;
        ViewGroup.LayoutParams original = card.getLayoutParams();
        if (original instanceof ConstraintLayout.LayoutParams) {
            mOrigBottomToTop = ((ConstraintLayout.LayoutParams) original).bottomToTop;
            mOrigBottomToBottom = ((ConstraintLayout.LayoutParams) original).bottomToBottom;
        } else {
            mOrigBottomToTop = ConstraintLayout.LayoutParams.UNSET;
            mOrigBottomToBottom = ConstraintLayout.LayoutParams.UNSET;
        }
        frame = card.findViewById(R.id.mobile_mini_player_frame);
        freeze = card.findViewById(R.id.mobile_mini_freeze);
        playPause = card.findViewById(R.id.mobile_mini_play_pause);
        close = card.findViewById(R.id.mobile_mini_close);
        progress = card.findViewById(R.id.mobile_mini_progress);
        backdrop = card.findViewById(R.id.mobile_mini_backdrop);
        dim = card.findViewById(R.id.mobile_mini_dim);
        text = card.findViewById(R.id.mobile_mini_text);
        title = card.findViewById(R.id.mobile_mini_title);
        channel = card.findViewById(R.id.mobile_mini_channel);
        flash = card.findViewById(R.id.mobile_mini_flash);
        // Built here rather than in the layout: the same overlay is inflated by the hosts and by
        // the player's minimize copy, and both get it from this one place.
        next = new ImageButton(card.getContext());
        next.setId(R.id.mobile_mini_next);
        next.setContentDescription(card.getContext().getString(R.string.mobile_mini_next));
        next.setImageResource(R.drawable.ic_player_next);
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setVisibility(View.GONE);
        ViewGroup buttons = (ViewGroup) playPause.getParent();
        buttons.addView(next, buttons.indexOfChild(playPause) + 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // NEWTUBE(accent): the progress line glows like the seek bar; taller, for the glow's room.
        float density = card.getResources().getDisplayMetrics().density;
        mProgressDrawable = new MiniProgressDrawable(density);
        progress.setProgressTintList(null);
        progress.setProgressBackgroundTintList(null);
        progress.setProgressDrawable(mProgressDrawable);
        ViewGroup.LayoutParams progressParams = progress.getLayoutParams();
        progressParams.height = px(10, density);
        progress.setLayoutParams(progressParams);
        mCardOutline = card.getOutlineProvider();
        mCardClips = card.getClipToOutline();
    }

    /**
     * The video-background bar's top corners: the video cards' (when theirs are rounded). Also run
     * when the card is shown again, because the setting may have been switched meanwhile.
     */
    void refreshBarCorners() {
        Context context = card.getContext();
        if (style == MiniPlayerStyle.BAR_VIDEO && MiniPlayerStyle.isBarRounded(context) && !mPill) {
            mTopRadiusPx = MiniPlayerStyle.CARD_CORNER_DP * context.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(mTopRoundedOutline);
            card.setClipToOutline(true);
            card.invalidateOutline();
        } else {
            if (card.getOutlineProvider() == mTopRoundedOutline) {
                card.setOutlineProvider(mCardOutline);
                card.invalidateOutline();
            }
            // NEWTUBE(pills): the video pill clips its video to its rounded ends; the plain pill's
            // views clip themselves (see applyStyle).
            card.setClipToOutline(mPill ? style == MiniPlayerStyle.BAR_VIDEO : mCardClips);
        }
    }

    /** NEWTUBE(pills): the bar is a floating pill (see MiniPlayerStyle#isPill). */
    private boolean mPill;
    /**
     * NEWTUBE(pills): the plain pill bar's inset video. Its left end follows the pill's rounded end
     * (concentric with it, so the margin is the same all round) and its right corners are the video
     * cards'. Before Android 13 only a rounded rectangle clips: one that runs past the right edge
     * (which the frame's bounds cut) rounds only the left end.
     */
    private final android.view.ViewOutlineProvider mThumbOutline = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            int width = view.getWidth();
            int height = view.getHeight();
            float density = view.getResources().getDisplayMetrics().density;
            float start = height / 2f;
            float end = Math.min(start, MiniPlayerStyle.PILL_THUMB_CORNER_DP * density);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.graphics.Path path = new android.graphics.Path();
                path.addRoundRect(0, 0, width, height, new float[] {start, start, end, end, end, end, start, start},
                        android.graphics.Path.Direction.CW);
                outline.setPath(path);
            } else {
                outline.setRoundRect(0, 0, width + Math.round(start), height, start);
            }
        }
    };

    /** NEWTUBE(pills): erases what lies outside the thumbnail's outline (see applyStyle). */
    private final Drawable mThumbCorners = new Drawable() {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path mPath = new android.graphics.Path();
        private final android.graphics.RectF mRect = new android.graphics.RectF();

        {
            mPaint.setColor(Color.BLACK);
            mPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) {
                return;
            }
            float density = card.getResources().getDisplayMetrics().density;
            float start = bounds.height() / 2f;
            float end = Math.min(start, MiniPlayerStyle.PILL_THUMB_CORNER_DP * density);
            mRect.set(bounds);
            mPath.reset();
            mPath.setFillType(android.graphics.Path.FillType.INVERSE_WINDING);
            mPath.addRoundRect(mRect, new float[] {start, start, end, end, end, end, start, start},
                    android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipRect(bounds);
            canvas.drawPath(mPath, mPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    };

    private final View.OnLayoutChangeListener mThumbCornersBounds = (v, l, t, r, b, ol, ot, or, ob) ->
            mThumbCorners.setBounds(0, 0, r - l, b - t);

    /**
     * NEWTUBE(pills): the plain pill bar's line, cut to the pill it lies along the bottom of: the
     * pill's rounded rectangle, reaching up past the line's top to the pill's.
     */
    private final android.view.ViewOutlineProvider mLineOutline = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            int pillHeight = px(MiniPlayerStyle.barHeightDp(style), view.getResources().getDisplayMetrics().density);
            int top = view.getHeight() - pillHeight;
            outline.setRoundRect(0, top, view.getWidth(), view.getHeight(), pillHeight / 2f);
        }
    };

    private float mAppliedBoxAspect = 16f / 9f;

    /** NEWTUBE(black-bars): the card takes the player box's shape; laid out again if it changed. */
    void refreshCardAspect() {
        if (style >= 0 && !MiniPlayerStyle.isBar(style) && mAppliedBoxAspect != MiniPlayerBridge.getBoxAspect()) {
            applyStyle(style, cardLeft);
        }
    }

    /** NEWTUBE(mini-underlap): see {@link #mUnderlapPx}; lays the card out again when it changes. */
    void setUnderlapPx(int px) {
        px = Math.max(0, px);
        if (px == mUnderlapPx) {
            return;
        }
        mUnderlapPx = px;
        if (style >= 0) {
            applyStyle(style, cardLeft);
        }
    }

    /** NEWTUBE(pills): see MiniHost.getMiniNavClearancePx; lays the card out again when it changes. */
    void setNavClearancePx(int px) {
        px = Math.max(0, px);
        if (px == mNavClearancePx) {
            return;
        }
        mNavClearancePx = px;
        if (style >= 0) {
            applyStyle(style, cardLeft);
        }
    }

    /** The part of the card under the host's navigation now (0 unless the video bar reaches there). */
    int underlapPx() {
        return style == MiniPlayerStyle.BAR_VIDEO && !mPill ? mUnderlapPx : 0;
    }

    private static void setBottomMargin(View view, int px) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) params).bottomMargin != px) {
            ((ViewGroup.MarginLayoutParams) params).bottomMargin = px;
            view.setLayoutParams(params);
        }
    }

    /** Lay the card and its children out for {@code style}, docked left or right if it is the card. */
    void applyStyle(int style, boolean cardLeft) {
        this.style = style;
        this.cardLeft = cardLeft;
        Context context = card.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean bar = MiniPlayerStyle.isBar(style);
        // NEWTUBE(pills): a bar is a wide pill, in from the sides and above the navigation pill.
        boolean pill = bar && MiniPlayerStyle.isPill(context);
        mPill = pill;
        int pillInset = pill ? Math.round(MiniPlayerStyle.pillInsetPx(style, density)) : 0;
        int pillThumbWidth = Math.round(MiniPlayerStyle.pillThumbWidthPx(density));
        int endPad = pill ? px(4, density) : 0; // the buttons clear of the rounded end
        int barHeight = px(MiniPlayerStyle.barHeightDp(style), density);
        int thumbWidth = Math.round(MiniPlayerStyle.barThumbWidthPx(density));
        int buttonSize = px(48, density);
        // NEWTUBE(mini-next): a Next button beside play/pause while a playlist has a next video.
        boolean showNext = mHasNext && style != MiniPlayerStyle.CARD_GESTURE;
        int nextSlot = showNext ? buttonSize : 0;

        // The card: in a bottom corner, or a square-cornered bar across the screen.
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int margin = bar ? 0 : px(MiniPlayerStyle.CARD_MARGIN_DP, density);
            int startMargin = !bar && cardLeft ? margin : 0;
            int endMargin = !bar && !cardLeft ? margin : 0;
            if (pill) {
                startMargin = Math.round(MiniPlayerStyle.pillSidePx(density));
                endMargin = startMargin;
                margin = Math.round(MiniPlayerStyle.pillGapPx(density));
            }
            int under = underlapPx();
            margins.height = bar ? barHeight + under : Math.round(MiniPlayerStyle.cardHeightPx(density));
            mAppliedBoxAspect = MiniPlayerBridge.getBoxAspect();
            margins.width = bar ? ViewGroup.LayoutParams.MATCH_PARENT : px(MiniPlayerStyle.CARD_WIDTH_DP, density);
            margins.leftMargin = startMargin;
            margins.rightMargin = endMargin;
            margins.setMarginStart(startMargin);
            margins.setMarginEnd(endMargin);
            margins.bottomMargin = margin + mNavClearancePx;
            if (params instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams constraints = (ConstraintLayout.LayoutParams) params;
                if (bar) {
                    constraints.width = 0; // MATCH_CONSTRAINT between the two edges
                }
                constraints.startToStart = bar || cardLeft
                        ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                constraints.endToEnd = bar || !cardLeft
                        ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                // NEWTUBE(mini-underlap): down to the screen's bottom, behind the navigation.
                if (under > 0) {
                    constraints.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
                    constraints.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                } else {
                    constraints.bottomToTop = mOrigBottomToTop;
                    constraints.bottomToBottom = mOrigBottomToBottom;
                }
                constraints.validate();
            } else if (params instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) params).gravity = bar ? Gravity.BOTTOM
                        : Gravity.BOTTOM | (cardLeft ? Gravity.START : Gravity.END);
            }
            card.setLayoutParams(params);
        }
        if (card instanceof MaterialCardView) {
            MaterialCardView materialCard = (MaterialCardView) card;
            materialCard.setRadius(pill ? barHeight / 2f : bar ? 0f : MiniPlayerStyle.CARD_CORNER_DP * density);
            materialCard.setCardElevation(bar ? 0f : 8f * density);
            // NEWTUBE(pills): the pill's rim, as on the navigation pill.
            materialCard.setStrokeWidth(pill ? Math.max(1, px(1, density)) : 0);
            materialCard.setStrokeColor(com.newtube.mobile.ui.common.Pills.rim(context));
            materialCard.setCardBackgroundColor(style == MiniPlayerStyle.BAR ? Color.TRANSPARENT : Color.BLACK);
        }
        refreshBarCorners();

        // The plain bar's surface is the page colour, the same as the bottom navigation bar's.
        backdrop.setVisibility(style == MiniPlayerStyle.BAR ? View.VISIBLE : View.GONE);
        boolean showDim = style == MiniPlayerStyle.BAR_VIDEO || style == MiniPlayerStyle.CARD_GESTURE;
        dim.setVisibility(showDim ? View.VISIBLE : View.GONE);
        dim.animate().cancel();
        dim.setAlpha(style == MiniPlayerStyle.CARD_GESTURE ? 0f : 1f);
        flash.animate().cancel();
        flash.setVisibility(View.GONE);

        // The video: the whole card, the bar's 16:9 thumbnail slot, or the whole bar cropped.
        FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) frame.getLayoutParams();
        if (style == MiniPlayerStyle.BAR && pill) {
            // NEWTUBE(pills): inset in the pill, with rounded corners.
            frameParams.width = pillThumbWidth;
            frameParams.height = barHeight - 2 * pillInset;
            frameParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        } else if (style == MiniPlayerStyle.BAR) {
            frameParams.width = thumbWidth;
            frameParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        } else if (style == MiniPlayerStyle.BAR_VIDEO && mUnderlapPx > 0) {
            // Centred on the bar (not on the bar and what is under the navigation), so the bar
            // shows the same band of the video as the minimize landed on.
            frameParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.height = barHeight + 2 * mUnderlapPx;
            // Centred with the parent (bar + underlap) less half the underlap: the frame's middle on
            // the bar's middle whatever height the zoom gives it (a 4:3 picture grows taller).
            frameParams.gravity = Gravity.CENTER;
        } else {
            frameParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.gravity = Gravity.CENTER;
        }
        frameParams.topMargin = 0;
        frameParams.bottomMargin = style == MiniPlayerStyle.BAR_VIDEO ? underlapPx() / 2 : 0;
        frameParams.leftMargin = pillInset;
        frameParams.setMarginStart(pillInset);
        frame.setLayoutParams(frameParams);
        // NEWTUBE(pills): one clip per view, never nested - with the video's TextureView inside a
        // clipped parent inside the clipped card, the thumbnail's square corners flickered through
        // on the device. On the plain pill bar the card clips nothing: the thumbnail, the glass and
        // the line each cut themselves to the pill. The video pill keeps the card's clip (its
        // zoomed video is larger than the bar), and its video clips nothing of its own.
        boolean plainPill = style == MiniPlayerStyle.BAR && pill;
        clipTo(frame, plainPill ? mThumbOutline : null);
        // The clip still let the video's square right corners through on some frames (the video's
        // TextureView is redrawn on its own, every frame): the thumbnail is drawn into a layer of
        // its own, video and all, and that whole picture is cut to the outline.
        int layer = plainPill ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (frame.getLayerType() != layer) {
            frame.setLayerType(layer, null);
        }
        // Even so the square corners came through now and then: the corners are also erased from
        // that layer outright (an overlay drawn after the video), which no clip can miss.
        frame.getOverlay().remove(mThumbCorners);
        frame.removeOnLayoutChangeListener(mThumbCornersBounds);
        if (plainPill) {
            mThumbCorners.setBounds(0, 0, frame.getWidth(), frame.getHeight());
            frame.getOverlay().add(mThumbCorners);
            frame.addOnLayoutChangeListener(mThumbCornersBounds);
        }
        clipTo(backdrop, plainPill ? com.newtube.mobile.ui.common.Pills.OUTLINE : null);
        clipTo(progress, plainPill ? mLineOutline : null);
        if (frame.getParent() instanceof View) {
            clipTo((View) frame.getParent(), null);
        }
        if (frame instanceof AspectRatioFrameLayout) {
            // The plain pill's thumbnail keeps its 16:9 slot whatever the video's shape (FILL, the
            // picture cropped to it: MiniPlayerBridge.applyCropTransform).
            ((AspectRatioFrameLayout) frame).setResizeMode(style == MiniPlayerStyle.BAR_VIDEO
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : plainPill ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        frame.setBackgroundColor(style == MiniPlayerStyle.BAR ? Color.BLACK : Color.TRANSPARENT);

        // The title (and channel): beside the thumbnail, or on frosted glass over the video.
        FrameLayout.LayoutParams textParams = (FrameLayout.LayoutParams) text.getLayoutParams();
        textParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        int onSurface = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        int onVideo = ContextCompat.getColor(context, R.color.mobile_player_on_video);
        if (style == MiniPlayerStyle.BAR_VIDEO) {
            textParams.setMarginStart(px(pill ? 16 : 12, density));
            textParams.setMarginEnd(buttonSize + nextSlot + endPad + px(12, density)); // only play/pause (and next)
            if (mTitleFrost == null) {
                mTitleFrost = new FrostedScrimDrawable(14f * density,
                        ContextCompat.getColor(context, R.color.mobile_mini_frost_tint));
            }
            text.setBackground(mTitleFrost);
            text.setPadding(px(12, density), px(6, density), px(12, density), px(6, density));
            title.setTextColor(onVideo);
            channel.setVisibility(View.GONE);
            // At most 45% of the bar; a longer title ends in an ellipsis.
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            limitTitleWidth();
        } else {
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMaxWidth(Integer.MAX_VALUE);
            textParams.setMarginStart((pill ? pillInset + pillThumbWidth : thumbWidth) + px(12, density));
            textParams.setMarginEnd(buttonSize + nextSlot + endPad + px(8, density)); // no X: swiped away
            text.setBackground(null);
            text.setPadding(0, 0, 0, 0);
            title.setTextColor(onSurface);
            channel.setVisibility(View.VISIBLE);
        }
        text.setLayoutParams(textParams);
        text.setVisibility(bar ? View.VISIBLE : View.GONE);

        // Play/pause and close: over the card's video, side by side at the bar's end, or - on the
        // gesture card - none at all.
        FrameLayout.LayoutParams play = (FrameLayout.LayoutParams) playPause.getLayoutParams();
        FrameLayout.LayoutParams closeParams = (FrameLayout.LayoutParams) close.getLayoutParams();
        if (bar) {
            play.width = buttonSize;
            play.height = buttonSize;
            play.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            // The bars have no X (they close by swiping down or sideways): play/pause takes its place.
            play.setMarginEnd(px(4, density) + nextSlot + endPad);
            closeParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            closeParams.setMarginEnd(endPad);
        } else {
            play.width = px(40, density);
            play.height = px(40, density);
            play.gravity = Gravity.CENTER;
            play.setMarginEnd(0);
            closeParams.gravity = Gravity.TOP | Gravity.END;
            closeParams.setMarginEnd(0);
        }
        FrameLayout.LayoutParams nextParams = (FrameLayout.LayoutParams) next.getLayoutParams();
        if (bar) {
            // Right after play/pause, at the bar's end.
            nextParams.width = buttonSize;
            nextParams.height = buttonSize;
            nextParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            nextParams.leftMargin = 0;
            nextParams.setMarginStart(0);
            nextParams.setMarginEnd(px(4, density) + endPad);
        } else {
            // On the card, the same circle beside the centred play/pause.
            nextParams.width = px(40, density);
            nextParams.height = px(40, density);
            nextParams.gravity = Gravity.CENTER;
            nextParams.setMarginEnd(0);
            nextParams.setMarginStart(0);
            nextParams.rightMargin = 0;
            nextParams.leftMargin = px(56, density);
        }
        next.setLayoutParams(nextParams);
        closeParams.width = buttonSize;
        closeParams.height = buttonSize;
        playPause.setLayoutParams(play);
        close.setLayoutParams(closeParams);
        if (style == MiniPlayerStyle.BAR) {
            // Plain icons on the page colour, like the bar's text.
            playPause.setBackground(borderlessRipple(context));
            close.setBackground(borderlessRipple(context));
            setPadding(playPause, px(12, density));
            setPadding(close, px(14, density));
            playPause.setImageTintList(ColorStateList.valueOf(onSurface));
            close.setImageTintList(ColorStateList.valueOf(onSurface));
            next.setBackground(borderlessRipple(context));
            // Next's glyph is 12/24 of its box, play/pause's 14/24: a larger box, the same height.
            setPadding(next, px(10, density));
            next.setImageTintList(ColorStateList.valueOf(onSurface));
            next.setImageResource(R.drawable.ic_player_next);
        } else if (style == MiniPlayerStyle.BAR_VIDEO) {
            // Just the two icons, no circle: each one nearly-white frosted glass over the video.
            if (mPlayIcon == null) {
                int tint = ContextCompat.getColor(context, R.color.mobile_mini_button_frost_tint);
                mPlayIcon = new FrostedIconDrawable(icon(context, mPlaying), tint);
                mCloseIcon = new FrostedIconDrawable(
                        ContextCompat.getDrawable(context, R.drawable.ic_sheet_close), tint);
            }
            playPause.setBackground(borderlessRipple(context));
            close.setBackground(borderlessRipple(context));
            setPadding(playPause, px(11, density));
            setPadding(close, px(13, density));
            playPause.setImageTintList(null);
            close.setImageTintList(null);
            playPause.setImageDrawable(mPlayIcon);
            close.setImageDrawable(mCloseIcon);
            if (mNextIcon == null) {
                mNextIcon = new FrostedIconDrawable(ContextCompat.getDrawable(context, R.drawable.ic_player_next),
                        ContextCompat.getColor(context, R.color.mobile_mini_button_frost_tint));
            }
            next.setBackground(borderlessRipple(context));
            setPadding(next, px(9, density)); // as tall as play/pause (see the plain bar)
            next.setImageTintList(null);
            next.setImageDrawable(mNextIcon);
        } else {
            // White icons on dark circles over the card's video.
            playPause.setBackgroundResource(R.drawable.bg_mini_scrim_circle);
            close.setBackgroundResource(R.drawable.bg_mini_scrim_circle_touch);
            setPadding(playPause, px(9, density));
            setPadding(close, px(15, density));
            playPause.setImageTintList(null);
            close.setImageTintList(ColorStateList.valueOf(onVideo));
            next.setBackgroundResource(R.drawable.bg_mini_scrim_circle);
            setPadding(next, px(7, density)); // as tall as play/pause (see the plain bar)
            next.setImageTintList(ColorStateList.valueOf(onVideo));
            next.setImageResource(R.drawable.ic_player_next);
        }
        if (style != MiniPlayerStyle.BAR_VIDEO) {
            close.setImageResource(R.drawable.ic_sheet_close);
            setPlaying(mPlaying);
        }
        int buttons = style == MiniPlayerStyle.CARD_GESTURE ? View.GONE : View.VISIBLE;
        playPause.setVisibility(buttons);
        close.setVisibility(bar ? View.GONE : buttons);
        next.setVisibility(showNext ? View.VISIBLE : View.GONE);

        // The tap answer: over the gesture card's middle, or a soft light ring on the video bar's
        // play/pause (whose icon stays).
        FrameLayout.LayoutParams flashParams = (FrameLayout.LayoutParams) flash.getLayoutParams();
        if (style == MiniPlayerStyle.BAR_VIDEO) {
            flashParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            // On play/pause - which Next, when there is one, pushes in from the end.
            flashParams.setMarginEnd(px(4, density) + nextSlot + endPad + (buttonSize - px(44, density)) / 2);
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setColor(ContextCompat.getColor(context, R.color.mobile_mini_play_flash));
            flash.setBackground(ring);
            flash.setImageDrawable(null);
        } else if (style == MiniPlayerStyle.BAR) {
            // NEWTUBE(pills): a soft glow of the accent behind play/pause when it is tapped.
            flashParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            flashParams.setMarginEnd(px(4, density) + nextSlot + endPad + (buttonSize - px(44, density)) / 2);
            android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable();
            glow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
            glow.setGradientRadius(px(22, density));
            int accent = MiniPlayerStyle.accent(context);
            glow.setColors(new int[] {(accent & 0x00FFFFFF) | 0x8C000000, (accent & 0x00FFFFFF) | 0x33000000,
                    accent & 0x00FFFFFF});
            flash.setBackground(glow);
            flash.setImageDrawable(null);
        } else {
            flashParams.gravity = Gravity.CENTER;
            flashParams.setMarginEnd(0);
            flash.setBackgroundResource(R.drawable.bg_mini_scrim_circle);
        }
        flash.setLayoutParams(flashParams);

        // NEWTUBE(mini-underlap): the title, buttons and line stay on the bar, above the navigation.
        int under = underlapPx();
        for (View view : new View[] {text, playPause, next, flash, close}) {
            setBottomMargin(view, under / 2);
        }
        setBottomMargin(progress, under);
        // NEWTUBE(pills): the line runs the pill's whole width along its bottom edge; the pill's
        // shape cuts it where the ends round off.
        FrameLayout.LayoutParams progressParams = (FrameLayout.LayoutParams) progress.getLayoutParams();
        int lineStart = 0;
        int lineEnd = 0;
        if (pill) {
            setBottomMargin(progress, 0); // the whole width, on the bottom edge, cut by the pill
        }
        progressParams.leftMargin = lineStart;
        progressParams.rightMargin = lineEnd;
        progressParams.setMarginStart(lineStart);
        progressParams.setMarginEnd(lineEnd);
        progress.setLayoutParams(progressParams);

        updateProgressColors();

        card.requestLayout();
    }

    /**
     * NEWTUBE(mini-next): whether the playlist has a video after this one - Next shows, and the
     * title makes room for it. Lays the card out again only when that changes.
     */
    void setHasNext(boolean hasNext) {
        if (hasNext == mHasNext) {
            return;
        }
        mHasNext = hasNext;
        if (style == MiniPlayerStyle.CARD_GESTURE) {
            return; // no buttons on it (and laying it out again would reset its paused dim)
        }
        if (style >= 0) {
            applyStyle(style, cardLeft);
        }
    }

    private final View.OnLayoutChangeListener mTitleWidthLimit = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l != or - ol) {
            limitTitleWidth();
        }
    };

    /** NEWTUBE(mini-style): the video bar's title takes no more than 45% of the bar's width. */
    private void limitTitleWidth() {
        card.removeOnLayoutChangeListener(mTitleWidthLimit);
        if (style != MiniPlayerStyle.BAR_VIDEO) {
            return;
        }
        card.addOnLayoutChangeListener(mTitleWidthLimit);
        int width = card.getWidth();
        if (width <= 0) {
            return; // the listener sets it once the bar is laid out
        }
        int max = Math.max(0, Math.round(width * 0.45f) - text.getPaddingLeft() - text.getPaddingRight());
        if (title.getMaxWidth() != max) {
            title.setMaxWidth(max);
        }
    }

    /** The progress line in the accent (which may have changed) over this style's track. */
    /** NEWTUBE(wave): the player's wave on the line - the video-background bar only. */
    void setWave(boolean on, @Nullable int[] colors) {
        // The humps only on the video bar (and only when they are wanted); the colours on every one.
        mProgressDrawable.setWave(on && style == MiniPlayerStyle.BAR_VIDEO, colors);
    }

    void updateProgressColors() {
        Context context = card.getContext();
        mProgressDrawable.setColors(MiniPlayerStyle.accent(context), ContextCompat.getColor(context,
                style == MiniPlayerStyle.BAR ? R.color.mobile_color_track : R.color.mobile_color_progress_track),
                com.newtube.mobile.ui.common.ThemeMode.isLight(context));
    }

    /** Show play/pause's state: the pause icon while it plays. */
    void setPlaying(boolean playing) {
        mPlaying = playing;
        mProgressDrawable.setPlaying(playing); // NEWTUBE(wave)
        if (style == MiniPlayerStyle.BAR_VIDEO && mPlayIcon != null) {
            mPlayIcon.setIcon(icon(card.getContext(), playing));
            if (playPause.getDrawable() != mPlayIcon) {
                playPause.setImageDrawable(mPlayIcon);
            }
        } else {
            playPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        }
    }

    private static Drawable icon(Context context, boolean playing) {
        return ContextCompat.getDrawable(context, playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
    }

    /** Everything drawn over or around the video (what fades in when a minimized video lands). */
    View[] chromeViews() {
        if (style == MiniPlayerStyle.CARD_GESTURE) {
            return new View[] {progress}; // its dim shows the paused state
        }
        return new View[] {playPause, close, next, progress, backdrop, dim, text};
    }

    /**
     * Refresh the frosted glass of the video-background bar from {@code texture}: a tiny copy of the
     * current frame, blurred, of which each glass shape draws the part under it.
     */
    void updateFrost(TextureView texture) {
        if (!ensureFrostBuffers()) {
            return;
        }
        try {
            texture.getBitmap(mFrostSample);
        } catch (RuntimeException e) {
            return; // the texture went away under us; the glass keeps its last picture
        }
        blurAndApply();
    }

    /** As {@link #updateFrost(TextureView)}, from any picture of the whole frame. */
    void updateFrost(Bitmap frame) {
        if (!ensureFrostBuffers() || frame.isRecycled()) {
            return;
        }
        Canvas canvas = new Canvas(mFrostSample);
        canvas.drawBitmap(frame, null, new Rect(0, 0, FROST_WIDTH, FROST_HEIGHT),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        blurAndApply();
    }

    private boolean ensureFrostBuffers() {
        if (style != MiniPlayerStyle.BAR_VIDEO || mTitleFrost == null) {
            return false;
        }
        if (mFrostSample == null) {
            mFrostSample = Bitmap.createBitmap(FROST_WIDTH, FROST_HEIGHT, Bitmap.Config.ARGB_8888);
            mFrostBlur = Bitmap.createBitmap(FROST_WIDTH, FROST_HEIGHT, Bitmap.Config.ARGB_8888);
            mFrostPixels = new int[FROST_WIDTH * FROST_HEIGHT];
            mFrostScratch = new int[FROST_WIDTH * FROST_HEIGHT];
        }
        return true;
    }

    private void blurAndApply() {
        mFrostSample.getPixels(mFrostPixels, 0, FROST_WIDTH, 0, 0, FROST_WIDTH, FROST_HEIGHT);
        boxBlur(mFrostPixels, mFrostScratch, FROST_WIDTH, FROST_HEIGHT, FROST_BLUR_RADIUS);
        boxBlur(mFrostPixels, mFrostScratch, FROST_WIDTH, FROST_HEIGHT, FROST_BLUR_RADIUS);
        mFrostBlur.setPixels(mFrostPixels, 0, FROST_WIDTH, 0, 0, FROST_WIDTH, FROST_HEIGHT);
        if (sourceFor(text, false) && mTitleFrost != null) {
            mTitleFrost.setFrame(mFrostBlur, mFrostSource);
        }
        if (sourceFor(playPause, true) && mPlayIcon != null) {
            mPlayIcon.setFrame(mFrostBlur, mFrostSource);
        }
        if (sourceFor(close, true) && mCloseIcon != null) {
            mCloseIcon.setFrame(mFrostBlur, mFrostSource);
        }
        if (next.getVisibility() == View.VISIBLE && sourceFor(next, true) && mNextIcon != null) {
            mNextIcon.setFrame(mFrostBlur, mFrostSource);
        }
    }

    /**
     * Put in {@link #mFrostSource} the part of the blurred frame under {@code view} - its content
     * box when {@code content} (an icon inside its padding). False if there is nothing to map.
     */
    private boolean sourceFor(View view, boolean content) {
        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();
        if (frameWidth <= 0 || frameHeight <= 0 || view.getWidth() <= 0) {
            return false;
        }
        // The view's box in the frame's pixels (siblings in one FrameLayout), then the copy's.
        float left = view.getLeft() + (content ? view.getPaddingLeft() : 0) - frame.getLeft();
        float top = view.getTop() + (content ? view.getPaddingTop() : 0) - frame.getTop();
        float right = view.getRight() - (content ? view.getPaddingRight() : 0) - frame.getLeft();
        float bottom = view.getBottom() - (content ? view.getPaddingBottom() : 0) - frame.getTop();
        // NEWTUBE(black-bars): the frame shows the cropped part of the picture; the copy is all of it.
        float[] crop = MiniPlayerBridge.getCrop();
        float cl = crop != null ? crop[2] : 0f;
        float cw = crop != null ? 1f - crop[2] - crop[3] : 1f;
        float ct = crop != null ? crop[0] : 0f;
        float ch = crop != null ? 1f - crop[0] - crop[1] : 1f;
        mFrostSource.set(Math.round((cl + left / frameWidth * cw) * FROST_WIDTH),
                Math.round((ct + top / frameHeight * ch) * FROST_HEIGHT),
                Math.round((cl + right / frameWidth * cw) * FROST_WIDTH),
                Math.round((ct + bottom / frameHeight * ch) * FROST_HEIGHT));
        if (mFrostSource.width() <= 0) {
            mFrostSource.right = mFrostSource.left + 1;
        }
        if (mFrostSource.height() <= 0) {
            mFrostSource.bottom = mFrostSource.top + 1;
        }
        return true;
    }

    /** One horizontal and one vertical box-blur pass over {@code pixels}, in place. */
    private static void boxBlur(int[] pixels, int[] scratch, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = pixels[row + Math.max(0, Math.min(width - 1, x + k))];
                    a += c >>> 24;
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                scratch[row + x] = ((a / window) << 24) | ((r / window) << 16) | ((g / window) << 8) | (b / window);
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = scratch[Math.max(0, Math.min(height - 1, y + k)) * width + x];
                    a += c >>> 24;
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                pixels[y * width + x] = ((a / window) << 24) | ((r / window) << 16) | ((g / window) << 8) | (b / window);
            }
        }
    }

    @Nullable
    private static Drawable borderlessRipple(Context context) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
                || value.resourceId == 0) {
            return null;
        }
        return ContextCompat.getDrawable(context, value.resourceId);
    }

    /**
     * NEWTUBE(pills): each new video frame on the plain pill bar, the whole pill is drawn again,
     * not the video alone. The thumbnail's square corners only ever flickered through while the
     * page sat still - when the video's own frames were the only thing on screen being redrawn -
     * and never while it scrolled, when the pill's glass is redrawn with every frame too.
     */
    void onVideoFrame() {
        if (mPill && style == MiniPlayerStyle.BAR && card.getVisibility() == View.VISIBLE) {
            backdrop.invalidate();
            frame.invalidate();
        }
    }

    /** Clip {@code view} to {@code outline}, or (null) let it draw unclipped with its usual outline. */
    private static void clipTo(View view, @Nullable android.view.ViewOutlineProvider outline) {
        view.setOutlineProvider(outline != null ? outline : android.view.ViewOutlineProvider.BACKGROUND);
        view.setClipToOutline(outline != null);
        view.invalidateOutline();
    }

    private static void setPadding(View view, int padding) {
        view.setPadding(padding, padding, padding, padding);
    }

    static int px(float dp, float density) {
        return Math.round(dp * density);
    }
}
