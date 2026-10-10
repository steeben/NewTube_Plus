package com.newtube.mobile.ui.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.DiagnosticLog;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.about.AboutShareRows;
import com.newtube.mobile.ui.browse.AccountsSheet;
import com.newtube.mobile.ui.common.ThemeMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone Settings tree (issue #2), modelled on YouTube's: a top level of sections with icons,
 * each opening one short page. Pages are built on demand from the prefs classes; page ids are plain
 * strings so a recreated screen rebuilds the same pages.
 *
 * <pre>
 * Settings
 *   [ Search settings ]          (SettingsSearchFragment: every row of every page)
 *   Account                      (the accounts sheet)
 *   App:    General · Tabs and feeds · History and privacy
 *   Video:  Playback · Video quality · Captions · SponsorBlock · DeArrow
 *   Other:  Backup and restore · Advanced · About
 * </pre>
 */
public final class SettingsPages {
    public static final String ROOT = "root";
    public static final String GENERAL = "general";
    public static final String FEEDS = "feeds";
    public static final String FEEDS_TABS = "feeds_tabs";
    public static final String FEEDS_HIDDEN = "feeds_hidden";
    public static final String FEEDS_MENU = "feeds_menu";
    public static final String PRIVACY = "privacy";
    public static final String PLAYBACK = "playback";
    public static final String QUALITY = "quality";
    public static final String CAPTIONS = "captions";
    public static final String SPONSORBLOCK = "sponsorblock";
    public static final String SPONSORBLOCK_MARKS = "sponsorblock_marks";
    public static final String DEARROW = "dearrow";
    public static final String BACKUP = "backup";
    public static final String ADVANCED = "advanced";
    public static final String ABOUT = "about";

    /** A built page: its title and rows. */
    public static final class Page {
        final CharSequence title;
        final List<SettingsRow> rows;

        Page(CharSequence title, List<SettingsRow> rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    private SettingsPages() {
    }

    @NonNull
    static Page build(@NonNull Context context, @NonNull String id) {
        switch (id) {
            case GENERAL:
                return AppPages.general(context);
            case FEEDS:
                return AppPages.feeds(context);
            case FEEDS_TABS:
                return AppPages.tabs(context);
            case FEEDS_HIDDEN:
                return AppPages.hidden(context);
            case FEEDS_MENU:
                return AppPages.videoMenu(context);
            case PRIVACY:
                return AppPages.privacy(context);
            case PLAYBACK:
                return PlayerPages.playback(context);
            case QUALITY:
                return PlayerPages.quality(context);
            case CAPTIONS:
                return PlayerPages.captions(context);
            case SPONSORBLOCK:
                return PlayerPages.sponsorBlock(context);
            case SPONSORBLOCK_MARKS:
                return PlayerPages.sponsorBlockMarks(context);
            case DEARROW:
                return PlayerPages.deArrow(context);
            case BACKUP:
                return AppPages.backup(context);
            case ADVANCED:
                return PlayerPages.advanced(context);
            case ABOUT:
                return about(context);
            case ROOT:
            default:
                return root(context);
        }
    }

    private static Page root(Context context) {
        List<SettingsRow> rows = new ArrayList<>();

        Account account = MediaServiceManager.instance().getSelectedAccount();
        boolean signedIn = account != null && !account.isEmpty();
        rows.add(SettingsRow.action(R.drawable.ic_settings_account,
                signedIn && account.getName() != null ? account.getName() : context.getString(R.string.mobile_settings_account),
                () -> signedIn ? (account.getEmail() != null ? account.getEmail() : context.getString(R.string.mobile_settings_account_switch))
                        : context.getString(R.string.mobile_settings_account_signed_out),
                page -> AccountsSheet.show(page.requireActivity(), page::rebuild)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_group_app)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_general, context.getString(R.string.mobile_settings_general),
                context.getString(R.string.mobile_settings_general_summary), GENERAL)
                .summary(() -> generalSummary(context)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_feeds, context.getString(R.string.mobile_settings_feeds),
                context.getString(R.string.mobile_settings_feeds_summary), FEEDS));
        rows.add(SettingsRow.page(R.drawable.ic_settings_privacy, context.getString(R.string.mobile_settings_privacy),
                context.getString(R.string.mobile_settings_privacy_summary), PRIVACY));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_group_video)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_playback, context.getString(R.string.mobile_settings_playback),
                context.getString(R.string.mobile_settings_playback_summary), PLAYBACK));
        rows.add(SettingsRow.page(R.drawable.ic_settings_quality, context.getString(R.string.mobile_settings_quality),
                null, QUALITY).summary(() -> PlayerPages.qualitySummary(context)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_captions, context.getString(R.string.mobile_settings_captions),
                context.getString(R.string.mobile_settings_captions_summary), CAPTIONS));
        rows.add(SettingsRow.page(R.drawable.ic_settings_sponsorblock, context.getString(R.string.content_block_provider),
                null, SPONSORBLOCK).summary(() -> PlayerPages.sponsorBlockSummary(context)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_dearrow, context.getString(R.string.dearrow_provider),
                null, DEARROW).summary(() -> PlayerPages.deArrowSummary(context)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_group_other)));
        rows.add(SettingsRow.page(R.drawable.ic_settings_backup, context.getString(R.string.mobile_settings_backup),
                context.getString(R.string.mobile_settings_backup_summary), BACKUP));
        rows.add(SettingsRow.page(R.drawable.ic_settings_advanced, context.getString(R.string.mobile_settings_advanced),
                context.getString(R.string.mobile_settings_advanced_summary), ADVANCED));
        rows.add(SettingsRow.page(R.drawable.ic_settings_about, context.getString(R.string.mobile_settings_about),
                versionLine(context), ABOUT));

        return new Page(context.getString(R.string.header_settings), rows);
    }

    /** "Dark theme · English" style: the two settings people come to General for. */
    private static CharSequence generalSummary(Context context) {
        int theme = ThemeMode.get();
        String themeLabel = context.getString(theme == ThemeMode.LIGHT ? R.string.mobile_settings_theme_light_short
                : theme == ThemeMode.DARK ? R.string.mobile_settings_theme_dark_short
                : theme == ThemeMode.OLED ? R.string.mobile_settings_theme_oled_short
                : theme == ThemeMode.SYSTEM_OLED ? R.string.mobile_settings_theme_system_oled_short
                : R.string.mobile_settings_theme_system_short);
        return context.getString(R.string.mobile_settings_general_summary_value, themeLabel);
    }

    private static String versionLine(Context context) {
        return context.getString(R.string.app_name) + " " + AppInfoHelpers.getAppVersionName(context);
    }

    private static Page about(Context context) {
        List<SettingsRow> rows = new ArrayList<>();

        if (AppUpdatePresenter.isInAppUpdatesDisabled()) {
            // Store build (-Pfdroid): the version stays, the update row and switch go.
            rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_version), versionLine(context), page -> { }));
        } else {
            rows.add(SettingsRow.action(context.getString(R.string.check_for_updates), versionLine(context),
                    page -> AppUpdatePresenter.instance(page.requireContext()).start(true)));
            AppUpdateChecker checker = new AppUpdateChecker(context, null);
            rows.add(SettingsRow.toggle(context.getString(R.string.check_updates_auto),
                    context.getString(R.string.mobile_settings_update_notify_summary),
                    checker::isUpdateCheckEnabled, checker::setUpdateCheckEnabled));
        }

        rows.add(SettingsRow.divider());
        rows.add(SettingsRow.action(context.getString(R.string.diagnostic_log_send),
                context.getString(R.string.diagnostic_log_send_desc),
                page -> DiagnosticLog.share(page.requireContext())));
        for (OptionItem item : AboutShareRows.create(context)) {
            rows.add(SettingsRow.action(item.getTitle(), item.getDescription(), page -> item.onSelect(true)));
        }
        rows.add(SettingsRow.action(context.getString(R.string.about_source_code),
                context.getString(R.string.about_source_code_url).replace("https://", ""),
                page -> Utils.openLinkExt(page.requireContext(), context.getString(R.string.about_source_code_url))));
        rows.add(SettingsRow.action(context.getString(R.string.about_license),
                context.getString(R.string.about_license_name),
                page -> Utils.openLinkExt(page.requireContext(), context.getString(R.string.about_license_url))));

        return new Page(context.getString(R.string.about_app, context.getString(R.string.app_name)), rows);
    }
}
