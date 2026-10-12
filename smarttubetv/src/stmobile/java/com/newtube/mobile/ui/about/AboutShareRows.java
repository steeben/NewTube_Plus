package com.newtube.mobile.ui.about;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The two rows at the end of Settings > About that help others find the app: "Star NewTube on
 * GitHub" (opens the repository) and "Share NewTube" (the system share sheet with one line and
 * the site link). They only act when tapped: no prompt, badge or counter anywhere else.
 */
public final class AboutShareRows {
    private static final String TAG = AboutShareRows.class.getSimpleName();

    private AboutShareRows() {
    }

    /** NewTube+'s own: star and share this fork's repository. */
    public static List<OptionItem> create(Context context) {
        List<OptionItem> rows = new ArrayList<>();

        rows.add(UiOptionItem.from(
                context.getString(R.string.mobile_about_fork_star),
                context.getString(R.string.mobile_about_fork_star_desc),
                option -> Utils.openLinkExt(context, context.getString(R.string.mobile_about_repo_url))));

        rows.add(UiOptionItem.from(
                context.getString(R.string.mobile_about_fork_share),
                context.getString(R.string.mobile_about_fork_share_desc),
                option -> share(context, R.string.mobile_about_fork_share, R.string.mobile_about_fork_share_text,
                        R.string.mobile_about_repo_url)));

        return rows;
    }

    /** The original NewTube's, as About showed them before the fork: star, and share its site. */
    public static List<OptionItem> createOriginal(Context context) {
        List<OptionItem> rows = new ArrayList<>();

        rows.add(UiOptionItem.from(
                context.getString(R.string.mobile_about_star),
                context.getString(R.string.mobile_about_star_desc),
                option -> Utils.openLinkExt(context, context.getString(R.string.mobile_about_original_repo_url))));

        rows.add(UiOptionItem.from(
                context.getString(R.string.mobile_about_share),
                context.getString(R.string.mobile_about_share_desc),
                option -> share(context, R.string.mobile_about_share, R.string.mobile_about_share_text,
                        R.string.mobile_about_site_url)));

        return rows;
    }

    private static void share(Context context, int titleRes, int textRes, int linkRes) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, context.getString(textRes, context.getString(linkRes)));

        Intent chooser = Intent.createChooser(send, context.getString(titleRes));
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownTextTargets(context, send));
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        try {
            context.startActivity(chooser);
        } catch (Exception e) {
            Log.e(TAG, "Share sheet failed: " + e.getMessage());
        }
    }

    /** NewTube itself accepts shared text (search); sharing the app into itself makes no sense. */
    private static ComponentName[] ownTextTargets(Context context, Intent send) {
        List<ComponentName> own = new ArrayList<>();
        try {
            for (ResolveInfo info : context.getPackageManager().queryIntentActivities(send, 0)) {
                if (info.activityInfo != null && context.getPackageName().equals(info.activityInfo.packageName)) {
                    own.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't list own share targets: " + e.getMessage());
        }
        return own.toArray(new ComponentName[0]);
    }
}
