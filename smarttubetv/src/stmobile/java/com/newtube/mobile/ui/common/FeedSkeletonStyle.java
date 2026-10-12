package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

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
        if (skeleton instanceof ViewGroup) {
            restoreSingleColumn((ViewGroup) skeleton);
        }
        applySingle(skeleton, oneColumn);
    }

    /**
     * NEWTUBE(playlists-grid): {@code columns} - the feed it covers shows that many cards per row.
     * Two or more: rows of ghost cards side by side, 16:9 thumbnails sized to the column.
     */
    public static void apply(View skeleton, int columns) {
        if (!(skeleton instanceof ViewGroup)) {
            return;
        }
        if (columns >= 2) {
            applyColumns((ViewGroup) skeleton, columns);
            return;
        }
        apply(skeleton, true);
    }

    private static void applySingle(View skeleton, boolean oneColumn) {
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

    private static final int KEY_COLUMNS = R.id.mobile_feed_skeleton;

    /** The single-column skeleton is the layout file's; rows built for columns are dropped for it. */
    private static void restoreSingleColumn(ViewGroup root) {
        Object built = root.getTag(KEY_COLUMNS);
        if (built == null) {
            return;
        }
        root.setTag(KEY_COLUMNS, null);
        root.removeAllViews();
        Context context = root.getContext();
        for (int i = 0; i < 3; i++) {
            root.addView(ghostCard(context, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)));
        }
    }

    private static LinearLayout ghostCard(Context context, ViewGroup.LayoutParams params) {
        LinearLayout card = new LinearLayout(context, null, 0, R.style.MobileSkeletonFeedCard);
        card.setLayoutParams(params);
        card.addView(new View(context, null, 0, R.style.MobileSkeletonFeedThumb),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        card.addView(new View(context, null, 0, R.style.MobileSkeletonFeedLineWide),
                lineParams(context, R.style.MobileSkeletonFeedLineWide));
        card.addView(new View(context, null, 0, R.style.MobileSkeletonFeedLineNarrow),
                lineParams(context, R.style.MobileSkeletonFeedLineNarrow));
        return card;
    }

    private static LinearLayout.LayoutParams lineParams(Context context, int style) {
        float density = context.getResources().getDisplayMetrics().density;
        boolean wide = style == R.style.MobileSkeletonFeedLineWide;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                wide ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round(180 * density),
                Math.round((wide ? 15 : 12) * density));
        params.topMargin = Math.round((wide ? 12 : 8) * density);
        if (wide) {
            params.setMarginEnd(Math.round(48 * density));
        }
        return params;
    }

    /** Rows of {@code columns} ghost cards, laid out like the grid's cards (8dp around each). */
    private static void applyColumns(ViewGroup root, int columns) {
        Resources res = root.getResources();
        Context context = root.getContext();
        float density = res.getDisplayMetrics().density;
        boolean rounded = UiPrefs.isRoundedThumbnails(context);
        int spacing = res.getDimensionPixelSize(R.dimen.mobile_card_spacing);
        root.setPadding(0, root.getPaddingTop(), 0, root.getPaddingBottom());
        View parent = (View) root.getParent();
        int width = parent != null && parent.getWidth() > 0 ? parent.getWidth() : res.getDisplayMetrics().widthPixels;
        int cardWidth = width / columns - 2 * spacing;
        int thumbHeight = Math.round(cardWidth * 9f / 16f);
        int textInset = Math.round(4 * density);
        Object built = root.getTag(KEY_COLUMNS);
        if (!(built instanceof Integer) || (Integer) built != columns) {
            root.removeAllViews();
            int rows = 4;
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                for (int c = 0; c < columns; c++) {
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    params.setMarginStart(spacing);
                    params.setMarginEnd(spacing);
                    row.addView(ghostCard(context, params));
                }
                root.addView(row);
            }
            root.setTag(KEY_COLUMNS, columns);
        }
        for (int r = 0; r < root.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) root.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                ViewGroup card = (ViewGroup) row.getChildAt(c);
                for (int j = 0; j < card.getChildCount(); j++) {
                    View part = card.getChildAt(j);
                    ViewGroup.LayoutParams params = part.getLayoutParams();
                    if (j == 0) {
                        params.height = thumbHeight;
                        part.setBackgroundResource(rounded ? R.drawable.bg_skeleton_block
                                : R.color.mobile_color_placeholder);
                    } else if (params instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) params).setMarginStart(textInset);
                    }
                    part.setLayoutParams(params);
                }
            }
        }
    }
}
