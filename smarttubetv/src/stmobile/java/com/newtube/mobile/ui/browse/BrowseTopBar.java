package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(you-subscreen): Browse's top bar. On a bottom-nav tab it is the "NewTube" wordmark; a
 * section opened from a You row (Sports, Playlists, ...) is a sub-screen of You, so it gets a back
 * arrow and the section's name - like YouTube's You sub-pages - instead of a wordmark that gave
 * no hint where the user was or that back returns to You.
 */
final class BrowseTopBar {
    private static final float WORDMARK_SP = 22f;
    private static final float SUBSCREEN_TITLE_SP = 20f;
    /** NEWTUBE(branding): League Gothic is condensed - the same visual weight takes a larger size. */
    private static final float WORDMARK_FONT_SP = 28f;
    private static final float LOGO_DP = 28f;

    private final TextView mTitle;
    private final View mBack;
    private final int mWordmarkInset;
    private final int mBesideBackInset;

    BrowseTopBar(Activity activity, Runnable onBack) {
        mTitle = activity.findViewById(R.id.mobile_title_bar);
        mBack = activity.findViewById(R.id.mobile_title_back);
        mBack.setOnClickListener(v -> onBack.run());
        mWordmarkInset = mTitle.getPaddingStart();
        mBesideBackInset = activity.getResources()
                .getDimensionPixelSize(R.dimen.mobile_dialog_title_inset_with_back);
    }

    /**
     * A tab shows the wordmark; a sub-screen shows the back arrow and {@code title} (the wordmark
     * text if the section came without one - the arrow is what matters).
     */
    void show(boolean subScreen, CharSequence title) {
        mSubScreen = subScreen;
        mBack.setVisibility(subScreen ? View.VISIBLE : View.GONE);
        mTitle.setText(subScreen && title != null && title.length() > 0 ? title : wordmark());
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subScreen ? SUBSCREEN_TITLE_SP : WORDMARK_SP);
        mTitle.setPaddingRelative(subScreen ? mBesideBackInset : mWordmarkInset,
                mTitle.getPaddingTop(), mTitle.getPaddingEnd(), mTitle.getPaddingBottom());
        applyBranding();
    }

    private boolean mSubScreen;
    @androidx.annotation.Nullable
    private android.graphics.Typeface mWordmarkFont;
    private android.graphics.Typeface mTitleFont;
    @androidx.annotation.Nullable
    private android.graphics.drawable.Drawable mCustomLogo;
    private long mCustomLogoStamp;

    /** NEWTUBE(branding): Appearance > Header text - none, NewTube, or the user's own. */
    private CharSequence wordmark() {
        android.content.Context context = mTitle.getContext();
        switch (com.newtube.mobile.ui.common.UiPrefs.getHeader(context)) {
            case com.newtube.mobile.ui.common.UiPrefs.HEADER_NONE:
                return "";
            case com.newtube.mobile.ui.common.UiPrefs.HEADER_CUSTOM: {
                String text = com.newtube.mobile.ui.common.UiPrefs.getHeaderText(context);
                return text != null && !text.trim().isEmpty() ? text.trim() : context.getString(R.string.app_name);
            }
            default:
                return context.getString(R.string.app_name);
        }
    }

    /**
     * NEWTUBE(branding): Settings > Appearance > Logo and Header text, on a tab's wordmark (a
     * sub-screen's title sits beside its back arrow and keeps the plain font): the logo before the
     * text - none, NewTube's, or the user's picture - and the text in League Gothic.
     */
    void applyBranding() {
        android.content.Context context = mTitle.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        if (mTitleFont == null) {
            mTitleFont = mTitle.getTypeface();
        }
        if (!mSubScreen) {
            mTitle.setText(wordmark());
            if (mWordmarkFont == null) {
                try {
                    mWordmarkFont = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.league_gothic);
                } catch (RuntimeException e) {
                    mWordmarkFont = mTitleFont; // a font that will not load is no reason to fail
                }
            }
            mTitle.setTypeface(mWordmarkFont);
            mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, WORDMARK_FONT_SP);
            mTitle.setLetterSpacing(0.02f);
        } else {
            mTitle.setTypeface(mTitleFont);
            mTitle.setLetterSpacing(0f);
        }
        android.graphics.drawable.Drawable mark = null;
        if (!mSubScreen) {
            switch (com.newtube.mobile.ui.common.UiPrefs.getLogo(context)) {
                case com.newtube.mobile.ui.common.UiPrefs.LOGO_NEWTUBE:
                    mark = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_newtube_brand);
                    break;
                case com.newtube.mobile.ui.common.UiPrefs.LOGO_CUSTOM:
                    mark = customLogo(context);
                    break;
                case com.newtube.mobile.ui.common.UiPrefs.LOGO_PLAY:
                    mark = playLogo(context);
                    break;
                default:
                    break;
            }
        }
        int size = Math.round(LOGO_DP * density);
        if (mark != null) {
            mark.setBounds(0, 0, size, size);
        }
        mTitle.setCompoundDrawablesRelative(mark, null, null, null);
        CharSequence text = mTitle.getText();
        mTitle.setCompoundDrawablePadding(text != null && text.length() > 0 ? Math.round(8 * density) : 0);
    }

    /** NEWTUBE(branding): a play triangle on a circle of the accent colour. */
    static android.graphics.drawable.Drawable playLogo(android.content.Context context) {
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(com.newtube.mobile.ui.common.UiPrefs.getAccent(context));
        android.graphics.drawable.Drawable glyph = androidx.core.content.ContextCompat.getDrawable(context,
                R.drawable.ic_logo_play_glyph);
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {circle, glyph});
    }

    /** The user's picture, read again only when it changed. Rounded like the NewTube icon. */
    @androidx.annotation.Nullable
    private android.graphics.drawable.Drawable customLogo(android.content.Context context) {
        java.io.File file = com.newtube.mobile.ui.common.UiPrefs.customLogoFile(context);
        long stamp = file.isFile() ? file.lastModified() : 0;
        if (stamp == 0) {
            return null;
        }
        if (mCustomLogo == null || stamp != mCustomLogoStamp) {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                return null;
            }
            androidx.core.graphics.drawable.RoundedBitmapDrawable rounded =
                    androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(context.getResources(), bitmap);
            rounded.setCornerRadius(bitmap.getWidth() * 26f / 108f); // the NewTube icon's corners
            mCustomLogo = rounded;
            mCustomLogoStamp = stamp;
        }
        return mCustomLogo.getConstantState() != null
                ? mCustomLogo.getConstantState().newDrawable(context.getResources()).mutate() : mCustomLogo;
    }
}
