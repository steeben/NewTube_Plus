package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(pills): the floating pill look (Settings > Appearance > Floating pills). One place for
 * its measures and colours, shared by Home (whose pills are glass over the feed), the list
 * screens' copies of the bottom navigation and the docked mini bar.
 *
 * <p>The glass is tinted with the page colour: dark glass in the dark theme, light glass in the
 * light one, with a faint rim so the pill's edge reads over any picture.</p>
 */
public final class Pills {
    /** Space between a pill and the screen's side edges. */
    public static final int SIDE_DP = 12;
    /** Space under the bottom navigation pill (above the gesture area) and between stacked pills. */
    public static final int GAP_DP = 8;
    /** The top row's pills: height, and the space above them (under the status bar). */
    public static final int TOP_HEIGHT_DP = 44;
    public static final int TOP_MARGIN_DP = 6;

    private Pills() {
    }

    public static boolean isOn(Context context) {
        return UiPrefs.isPillLayout(context);
    }

    public static int px(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static int page(Context context) {
        return ContextCompat.getColor(context, R.color.mobile_color_background);
    }

    /** The glass's tint over the blur: the page colour, part see-through. */
    public static int glassTint(Context context) {
        return (page(context) & 0x00FFFFFF) | 0xA6000000;
    }

    /** A pill's surface where nothing scrolls under it (no blur): the page colour, lifted a little. */
    public static int solidSurface(Context context) {
        int onSurface = ContextCompat.getColor(context, R.color.mobile_color_on_surface);
        return androidx.core.graphics.ColorUtils.blendARGB(page(context), onSurface, 0.07f) | 0xFF000000;
    }

    /** The pill's rim: a light line on dark glass, a faint dark one on light glass. */
    public static int rim(Context context) {
        return ThemeMode.isLight(context) ? 0x1A000000 : 0x2EFFFFFF;
    }

    /** The selected tab's own pill, inside the navigation pill. */
    public static int indicator(Context context) {
        return ThemeMode.isLight(context) ? 0x14000000 : 0x26FFFFFF;
    }

    /** A rounded-ends outline that follows the view's size (the view clips its drawing to it). */
    public static final ViewOutlineProvider OUTLINE = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Math.min(view.getWidth(), view.getHeight()) / 2f);
        }
    };

    /** The rim, drawn over the pill's surface. */
    public static Drawable rimDrawable(Context context) {
        return rimDrawable(context, false);
    }

    /**
     * {@code liquid}: Liquid glass's hairline (BitChord's GLASS_EDGE: 0.5dp of 10% white) - the
     * glass draws its own rim of light inside it.
     */
    public static Drawable rimDrawable(Context context, boolean liquid) {
        GradientDrawable rim = new GradientDrawable();
        rim.setShape(GradientDrawable.RECTANGLE);
        rim.setCornerRadius(px(context, 999));
        rim.setColor(0);
        if (liquid) {
            float density = context.getResources().getDisplayMetrics().density;
            rim.setStroke(Math.max(1, Math.round(0.5f * density)), 0x1AFFFFFF);
        } else {
            rim.setStroke(Math.max(1, px(context, 1)), rim(context));
        }
        return rim;
    }

    /** Make {@code view} a pill over {@code surface} (glass, or null for the solid surface). */
    public static void shape(View view, @Nullable Drawable surface) {
        shape(view, surface, false);
    }

    /** As above; {@code liquid} - the surface is Liquid glass (its own hairline). */
    public static void shape(View view, @Nullable Drawable surface, boolean liquid) {
        Context context = view.getContext();
        if (surface == null) {
            GradientDrawable solid = new GradientDrawable();
            solid.setCornerRadius(px(context, 999));
            solid.setColor(solidSurface(context));
            surface = solid;
        }
        view.setBackground(new LayerDrawable(new Drawable[] {surface, rimDrawable(context, liquid)}));
        view.setOutlineProvider(OUTLINE);
        view.setClipToOutline(true);
        view.setElevation(0f);
    }

    /**
     * The bottom navigation as a floating pill: inset from the sides, resting {@link #GAP_DP} above
     * the gesture area (it no longer paints through it), the selected tab on a pill of its own.
     * {@code surface}: its glass, or null for the solid surface.
     */
    public static void applyToNav(BottomNavigationView nav, @Nullable Drawable surface) {
        applyToNav(nav, surface, false);
    }

    /**
     * As above; {@code liquid} - Liquid glass, whose selected tab is drawn by {@link LiquidNav}
     * (the travelling pill that lifts into a lens), so the tabs get no pill or ripple of their own.
     */
    public static void applyToNav(BottomNavigationView nav, @Nullable Drawable surface, boolean liquid) {
        Context context = nav.getContext();
        shape(nav, surface, liquid);
        applyMargins(nav);
        if (liquid) {
            nav.setItemBackground(null);
            nav.setItemRippleColor(null);
            return;
        }
        // The selected tab: a softer pill inside, under the ripple.
        GradientDrawable selected = new GradientDrawable();
        selected.setCornerRadius(px(context, 999));
        selected.setColor(indicator(context));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_checked},
                new InsetDrawable(selected, px(context, 6), px(context, 5), px(context, 6), px(context, 5)));
        states.addState(new int[0], new GradientDrawable());
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(px(context, 999));
        mask.setColor(0xFFFFFFFF);
        nav.setItemBackground(new RippleDrawable(ColorStateList.valueOf(indicator(context)), states,
                new InsetDrawable(mask, px(context, 6), px(context, 5), px(context, 6), px(context, 5))));
    }

    /** The navigation pill's place: in from the sides, above the gesture area. */
    private static void applyMargins(BottomNavigationView nav) {
        Context context = nav.getContext();
        ViewGroup.LayoutParams params = nav.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            int side = px(context, SIDE_DP);
            margins.leftMargin = side;
            margins.rightMargin = side;
            margins.setMarginStart(side);
            margins.setMarginEnd(side);
            margins.bottomMargin = px(context, GAP_DP);
            nav.setLayoutParams(margins);
        }
        // Material pads the bar by the gesture inset (it painted through it); the pill sits above
        // it instead, by its bottom margin.
        nav.setPadding(0, 0, 0, 0);
        ViewCompat.setOnApplyWindowInsetsListener(nav, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            int bottom = bars.bottom + px(context, GAP_DP);
            if (lp instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) lp).bottomMargin != bottom) {
                ((ViewGroup.MarginLayoutParams) lp).bottomMargin = bottom;
                view.setLayoutParams(lp);
            }
            if (view.getPaddingBottom() != 0 || view.getPaddingTop() != 0) {
                view.setPadding(0, 0, 0, 0);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(nav);
    }

    /** Its bottom margin: the gesture area under it and the gap. */
    public static int navBottomMargin(View nav) {
        ViewGroup.LayoutParams lp = nav.getLayoutParams();
        return lp instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) lp).bottomMargin : 0;
    }

    /**
     * How far above the content's bottom (which stops at the gesture area) a docked mini player
     * rests its margin on: the navigation pill and the gap under it - for the hosts' own
     * getMiniCardBottomOffsetPx.
     */
    public static int miniOffsetAboveNav(View nav) {
        Context context = nav.getContext();
        int height = nav.getHeight() > 0 ? nav.getHeight() : px(context, 56);
        return height + px(context, GAP_DP);
    }

    /**
     * What a bar minimize uncovers as the navigation pill slides away: the pill, the gap under it
     * and the gap above it (where the mini bar rests) - for the hosts' getBottomBarHeightPx.
     */
    public static int navExtent(View nav) {
        return miniOffsetAboveNav(nav) + px(nav.getContext(), GAP_DP);
    }

    /** Apply {@link #applyToNav} to a list screen's copy of the navigation, once. */
    public static void applyToNavOnce(BottomNavigationView nav) {
        if (nav.getTag(R.id.mobile_pill_applied) == null) {
            nav.setTag(R.id.mobile_pill_applied, Boolean.TRUE);
            applyToNav(nav, null);
        }
    }
}
