package com.newtube.mobile.ui.common;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(ui-options): the feed's loading skeleton (mobile_feed_skeleton.xml) laid out like the
 * cards it stands in for: with General > Video cards > Full-width thumbnails on (one column), its
 * ghost thumbnails run edge to edge at 16:9 and its ghost lines take the cards' text inset; with
 * rounded corners off, its thumbnails are square.
 */
public final class FeedSkeletonStyle {
    private static final int DEFAULT_THUMB_DP = 204;

    private FeedSkeletonStyle() {
    }

    /** {@code oneColumn}: the feed it covers shows one card per row. */
    public static void apply(View skeleton, boolean oneColumn) {
        if (!(skeleton instanceof ViewGroup)) {
            return;
        }
        Resources res = skeleton.getResources();
        float density = res.getDisplayMetrics().density;
        boolean fullWidth = oneColumn && UiPrefs.isFullWidthThumbnails(skeleton.getContext());
        boolean rounded = UiPrefs.isRoundedThumbnails(skeleton.getContext());
        int spacing = res.getDimensionPixelSize(R.dimen.mobile_card_spacing);
        int side = fullWidth ? 0 : spacing;
        skeleton.setPadding(side, skeleton.getPaddingTop(), side, skeleton.getPaddingBottom());

        View parent = (View) skeleton.getParent();
        int width = parent != null && parent.getWidth() > 0 ? parent.getWidth() : res.getDisplayMetrics().widthPixels;
        int thumbHeight = fullWidth ? Math.round(width * 9f / 16f) : Math.round(DEFAULT_THUMB_DP * density);
        int textInset = Math.round((fullWidth ? 16 : 0) * density);

        ViewGroup root = (ViewGroup) skeleton;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (!(root.getChildAt(i) instanceof ViewGroup)) {
                continue;
            }
            ViewGroup card = (ViewGroup) root.getChildAt(i);
            for (int j = 0; j < card.getChildCount(); j++) {
                View part = card.getChildAt(j);
                ViewGroup.LayoutParams params = part.getLayoutParams();
                if (j == 0) {
                    params.height = thumbHeight;
                    part.setBackgroundResource(rounded ? R.drawable.bg_skeleton_block
                            : R.color.mobile_color_placeholder);
                } else if (params instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) params).setMarginStart(textInset);
                    ((ViewGroup.MarginLayoutParams) params).leftMargin = textInset;
                }
                part.setLayoutParams(params);
            }
        }
    }
}
