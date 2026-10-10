package com.newtube.mobile.ui.settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/** Renders a page's {@link SettingsRow}s. Clicks go back to the page, which applies them. */
final class SettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    /** Re-read the values (summaries, switch states) without rebuilding the views. */
    static final Object PAYLOAD_VALUES = new Object();

    interface Listener {
        void onRowClicked(@NonNull SettingsRow row);
    }

    private final Listener mListener;
    private List<SettingsRow> mRows = new ArrayList<>();

    SettingsAdapter(Listener listener) {
        mListener = listener;
    }

    void submit(List<SettingsRow> rows) {
        if (rows.size() == mRows.size() && sameShape(mRows, rows)) {
            mRows = rows;
            notifyItemRangeChanged(0, rows.size(), PAYLOAD_VALUES);
        } else {
            mRows = rows;
            notifyDataSetChanged();
        }
    }

    /** Same kinds and titles in the same places: a value changed, not the page. */
    private static boolean sameShape(List<SettingsRow> a, List<SettingsRow> b) {
        for (int i = 0; i < a.size(); i++) {
            SettingsRow x = a.get(i);
            SettingsRow y = b.get(i);
            if (x.kind != y.kind || !String.valueOf(x.title).equals(String.valueOf(y.title))) {
                return false;
            }
        }
        return true;
    }

    /** Where the row with this title is (a search result pointing at it), or -1. */
    int indexOf(@NonNull String title) {
        for (int i = 0; i < mRows.size(); i++) {
            SettingsRow row = mRows.get(i);
            if (row.kind != SettingsRow.KIND_HEADER && row.kind != SettingsRow.KIND_NOTE
                    && title.equals(String.valueOf(row.title))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return mRows.get(position).kind;
    }

    @Override
    public int getItemCount() {
        return mRows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case SettingsRow.KIND_HEADER:
                return new TextHolder(inflater.inflate(R.layout.item_mobile_settings_header, parent, false));
            case SettingsRow.KIND_NOTE:
                return new TextHolder(inflater.inflate(R.layout.item_mobile_settings_note, parent, false));
            case SettingsRow.KIND_DIVIDER:
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_mobile_settings_divider, parent, false)) {};
            default:
                return new RowHolder(inflater.inflate(R.layout.item_mobile_settings_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SettingsRow row = mRows.get(position);
        if (holder instanceof TextHolder) {
            ((TextHolder) holder).text.setText(row.title);
        } else if (holder instanceof RowHolder) {
            ((RowHolder) holder).bind(row, mListener);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        holder.itemView.setForeground(null); // a search result's highlight, if it was still glowing
    }


    private static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView text;

        TextHolder(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView title;
        private final TextView summary;
        private final SwitchMaterial toggle;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.settings_row_icon);
            title = itemView.findViewById(R.id.settings_row_title);
            summary = itemView.findViewById(R.id.settings_row_summary);
            toggle = itemView.findViewById(R.id.settings_row_switch);
        }

        void bind(SettingsRow row, Listener listener) {
            if (row.icon != 0) {
                icon.setImageResource(row.icon);
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }

            title.setText(row.title);
            title.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    row.destructive ? R.color.mobile_color_primary : R.color.mobile_color_on_surface));

            CharSequence text = row.summaryText();
            if (text != null && text.length() > 0) {
                summary.setText(text);
                summary.setVisibility(View.VISIBLE);
            } else {
                summary.setVisibility(View.GONE);
            }

            boolean isSwitch = row.kind == SettingsRow.KIND_SWITCH;
            toggle.setVisibility(isSwitch ? View.VISIBLE : View.GONE);
            if (isSwitch && row.checked != null) {
                boolean on = row.checked.getAsBoolean();
                if (toggle.isChecked() != on) {
                    toggle.setChecked(on);
                }
                tintSwitch(toggle); // NEWTUBE(accent)
                itemView.setContentDescription(row.title + (text != null ? ", " + text : ""));
            } else {
                itemView.setContentDescription(null);
            }
            itemView.setAccessibilityDelegate(isSwitch ? SwitchRole.INSTANCE : null);

            boolean enabled = row.isEnabled();
            itemView.setEnabled(enabled);
            itemView.setAlpha(enabled ? 1f : 0.38f);
            toggle.setEnabled(enabled);
            itemView.setOnClickListener(enabled ? v -> listener.onRowClicked(row) : null);
            itemView.setClickable(enabled);
        }
    }

    /** TalkBack reads a switch row as a switch with its state, like a platform preference. */
    private static final class SwitchRole extends View.AccessibilityDelegate {
        static final SwitchRole INSTANCE = new SwitchRole();

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            SwitchMaterial toggle = host.findViewById(R.id.settings_row_switch);
            info.setClassName(android.widget.Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(toggle != null && toggle.isChecked());
        }
    }

    /** NEWTUBE(accent): a switch that is on wears the accent (thumb, and its track a lighter shade). */
    static void tintSwitch(SwitchMaterial toggle) {
        android.content.Context context = toggle.getContext();
        int accent = com.newtube.mobile.ui.common.UiPrefs.getAccent(context);
        boolean light = com.newtube.mobile.ui.common.ThemeMode.isLight(context);
        int[][] states = {new int[] {android.R.attr.state_checked}, new int[] {}};
        toggle.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[] {accent, light ? 0xFFFAFAFA : 0xFFB9B9B9}));
        toggle.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[] {(accent & 0x00FFFFFF) | 0x80000000, light ? 0x42000000 : 0x4DFFFFFF}));
    }
}
