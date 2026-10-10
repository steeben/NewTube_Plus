package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
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

    int style = -1;
    boolean cardLeft;

    @Nullable
    private FrostedScrimDrawable mTitleFrost;
    @Nullable
    private FrostedScrimDrawable mPlayFrost;
    @Nullable
    private FrostedScrimDrawable mCloseFrost;
    @Nullable
    private Bitmap mFrostSample;
    @Nullable
    private Bitmap mFrostBlur;
    @Nullable
    private int[] mFrostPixels;
    @Nullable
    private int[] mFrostScratch;
    private final Rect mFrostSource = new Rect();

    MiniPlayerViews(View card) {
        this.card = card;
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
    }

    /** Lay the card and its children out for {@code style}, docked left or right if it is the card. */
    void applyStyle(int style, boolean cardLeft) {
        this.style = style;
        this.cardLeft = cardLeft;
        Context context = card.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean bar = MiniPlayerStyle.isBar(style);
        int barHeight = px(MiniPlayerStyle.BAR_HEIGHT_DP, density);
        int thumbWidth = Math.round(MiniPlayerStyle.barThumbWidthPx(density));
        int buttonSize = px(48, density);

        // The card: in a bottom corner, or a square-cornered bar across the screen.
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int margin = bar ? 0 : px(MiniPlayerStyle.CARD_MARGIN_DP, density);
            int startMargin = !bar && cardLeft ? margin : 0;
            int endMargin = !bar && !cardLeft ? margin : 0;
            margins.height = bar ? barHeight : px(MiniPlayerStyle.CARD_HEIGHT_DP, density);
            margins.width = bar ? ViewGroup.LayoutParams.MATCH_PARENT : px(MiniPlayerStyle.CARD_WIDTH_DP, density);
            margins.leftMargin = startMargin;
            margins.rightMargin = endMargin;
            margins.setMarginStart(startMargin);
            margins.setMarginEnd(endMargin);
            margins.bottomMargin = margin;
            if (params instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams constraints = (ConstraintLayout.LayoutParams) params;
                if (bar) {
                    constraints.width = 0; // MATCH_CONSTRAINT between the two edges
                }
                constraints.startToStart = bar || cardLeft
                        ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                constraints.endToEnd = bar || !cardLeft
                        ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                constraints.validate();
            } else if (params instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) params).gravity = bar ? Gravity.BOTTOM
                        : Gravity.BOTTOM | (cardLeft ? Gravity.START : Gravity.END);
            }
            card.setLayoutParams(params);
        }
        if (card instanceof MaterialCardView) {
            MaterialCardView materialCard = (MaterialCardView) card;
            materialCard.setRadius(bar ? 0f : MiniPlayerStyle.CARD_CORNER_DP * density);
            materialCard.setCardElevation(bar ? 0f : 8f * density);
            materialCard.setCardBackgroundColor(style == MiniPlayerStyle.BAR ? Color.TRANSPARENT : Color.BLACK);
        }

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
        if (style == MiniPlayerStyle.BAR) {
            frameParams.width = thumbWidth;
            frameParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        } else {
            frameParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            frameParams.gravity = Gravity.CENTER;
        }
        frame.setLayoutParams(frameParams);
        if (frame instanceof AspectRatioFrameLayout) {
            ((AspectRatioFrameLayout) frame).setResizeMode(style == MiniPlayerStyle.BAR_VIDEO
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        frame.setBackgroundColor(style == MiniPlayerStyle.BAR ? Color.BLACK : Color.TRANSPARENT);

        // The title (and channel): beside the thumbnail, or on frosted glass over the video.
        FrameLayout.LayoutParams textParams = (FrameLayout.LayoutParams) text.getLayoutParams();
        textParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        int onSurface = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        int onVideo = ContextCompat.getColor(context, R.color.mobile_player_on_video);
        if (style == MiniPlayerStyle.BAR_VIDEO) {
            textParams.setMarginStart(px(12, density));
            textParams.setMarginEnd(2 * buttonSize + px(8, density));
            if (mTitleFrost == null) {
                mTitleFrost = new FrostedScrimDrawable(14f * density,
                        ContextCompat.getColor(context, R.color.mobile_mini_frost_tint));
            }
            text.setBackground(mTitleFrost);
            text.setPadding(px(12, density), px(6, density), px(12, density), px(6, density));
            title.setTextColor(onVideo);
            channel.setVisibility(View.GONE);
        } else {
            textParams.setMarginStart(thumbWidth + px(12, density));
            textParams.setMarginEnd(2 * buttonSize + px(4, density));
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
            play.setMarginEnd(buttonSize);
            closeParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        } else {
            play.width = px(40, density);
            play.height = px(40, density);
            play.gravity = Gravity.CENTER;
            play.setMarginEnd(0);
            closeParams.gravity = Gravity.TOP | Gravity.END;
        }
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
        } else if (style == MiniPlayerStyle.BAR_VIDEO) {
            // Nearly-white frosted glass circles (40dp inside the 48dp target), dark icons.
            if (mPlayFrost == null) {
                int tint = ContextCompat.getColor(context, R.color.mobile_mini_button_frost_tint);
                mPlayFrost = new FrostedScrimDrawable(-1f, tint);
                mCloseFrost = new FrostedScrimDrawable(-1f, tint);
            }
            int inset = px(4, density);
            playPause.setBackground(new InsetDrawable(mPlayFrost, inset));
            close.setBackground(new InsetDrawable(mCloseFrost, inset));
            setPadding(playPause, px(13, density));
            setPadding(close, px(15, density));
            int icon = ContextCompat.getColor(context, R.color.mobile_mini_button_frost_icon);
            playPause.setImageTintList(ColorStateList.valueOf(icon));
            close.setImageTintList(ColorStateList.valueOf(icon));
        } else {
            // White icons on dark circles over the card's video.
            playPause.setBackgroundResource(R.drawable.bg_mini_scrim_circle);
            close.setBackgroundResource(R.drawable.bg_mini_scrim_circle_touch);
            setPadding(playPause, px(9, density));
            setPadding(close, px(15, density));
            playPause.setImageTintList(null);
            close.setImageTintList(ColorStateList.valueOf(onVideo));
        }
        int buttons = style == MiniPlayerStyle.CARD_GESTURE ? View.GONE : View.VISIBLE;
        playPause.setVisibility(buttons);
        close.setVisibility(buttons);

        progress.setProgressBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
                style == MiniPlayerStyle.BAR ? R.color.mobile_color_track : R.color.mobile_color_progress_track)));

        card.requestLayout();
    }

    /** Everything drawn over or around the video (what fades in when a minimized video lands). */
    View[] chromeViews() {
        if (style == MiniPlayerStyle.CARD_GESTURE) {
            return new View[] {progress}; // its dim shows the paused state
        }
        return new View[] {playPause, close, progress, backdrop, dim, text};
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
        float inset = 4f * card.getResources().getDisplayMetrics().density;
        applyFrost(mTitleFrost, text, 0f);
        applyFrost(mPlayFrost, playPause, inset);
        applyFrost(mCloseFrost, close, inset);
    }

    /** The part of the blurred frame under {@code view} (shrunk by {@code inset}) to its glass. */
    private void applyFrost(@Nullable FrostedScrimDrawable glass, View view, float inset) {
        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();
        if (glass == null || frameWidth <= 0 || frameHeight <= 0 || view.getWidth() <= 0) {
            return;
        }
        // The view's box in the frame's pixels (siblings in one FrameLayout), then the copy's.
        float left = view.getLeft() + inset - frame.getLeft();
        float top = view.getTop() + inset - frame.getTop();
        float right = view.getRight() - inset - frame.getLeft();
        float bottom = view.getBottom() - inset - frame.getTop();
        float sx = (float) FROST_WIDTH / frameWidth;
        float sy = (float) FROST_HEIGHT / frameHeight;
        mFrostSource.set(Math.round(left * sx), Math.round(top * sy), Math.round(right * sx), Math.round(bottom * sy));
        if (mFrostSource.width() <= 0) {
            mFrostSource.right = mFrostSource.left + 1;
        }
        if (mFrostSource.height() <= 0) {
            mFrostSource.bottom = mFrostSource.top + 1;
        }
        glass.setFrame(mFrostBlur, mFrostSource);
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

    private static void setPadding(View view, int padding) {
        view.setPadding(padding, padding, padding, padding);
    }

    static int px(float dp, float density) {
        return Math.round(dp * density);
    }
}
