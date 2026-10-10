package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.UiPrefs;

import java.util.Locale;

/**
 * NEWTUBE(accent): Settings > Appearance > Accent - a colour wheel, a brightness slider and a
 * colour code field that follow each other. The field takes a code with or without its "#" (it
 * puts one in front, once) and three- or six-digit codes; Default puts the red back.
 */
final class AccentDialog {
    private AccentDialog() {
    }

    static void show(Context context, Runnable onChanged) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, Math.round(12 * density), pad, 0);

        ColorWheelView wheel = new ColorWheelView(context);
        LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wheelParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(wheel, wheelParams);

        SeekBar brightness = new SeekBar(context);
        brightness.setMax(100);
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderParams.topMargin = Math.round(12 * density);
        column.addView(brightness, sliderParams);
        brightness.setContentDescription(context.getString(R.string.mobile_appearance_accent_brightness));

        LinearLayout codeRow = new LinearLayout(context);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        View swatch = new View(context);
        GradientDrawable swatchShape = new GradientDrawable();
        swatchShape.setShape(GradientDrawable.OVAL);
        swatch.setBackground(swatchShape);
        int swatchSize = Math.round(32 * density);
        codeRow.addView(swatch, new LinearLayout.LayoutParams(swatchSize, swatchSize));
        EditText code = new EditText(context);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setFilters(new InputFilter[] {new InputFilter.LengthFilter(10)});
        code.setHint(R.string.mobile_appearance_accent_code_hint);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        codeParams.setMarginStart(Math.round(12 * density));
        codeRow.addView(code, codeParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Math.round(8 * density);
        column.addView(codeRow, rowParams);

        TextView note = new TextView(context);
        note.setText(R.string.mobile_appearance_accent_note);
        note.setTextColor(context.getResources().getColor(R.color.mobile_color_on_surface_secondary));
        note.setTextSize(12f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = Math.round(4 * density);
        column.addView(note, noteParams);

        // The colour, in HSV, that all three show.
        float[] hsv = new float[3];
        Color.colorToHSV(UiPrefs.getAccent(context), hsv);
        boolean[] syncing = {false};

        Runnable showColor = () -> {
            int color = Color.HSVToColor(hsv);
            swatchShape.setColor(color);
            brightness.getProgressDrawable().setTint(color);
            brightness.getThumb().setTint(color);
        };
        Runnable showCode = () -> {
            syncing[0] = true;
            code.setText(hex(Color.HSVToColor(hsv)));
            code.setSelection(code.length());
            syncing[0] = false;
        };

        wheel.setHueSaturation(hsv[0], hsv[1]);
        brightness.setProgress(Math.round(hsv[2] * 100));
        showColor.run();
        showCode.run();

        wheel.setListener((hue, saturation) -> {
            hsv[0] = hue;
            hsv[1] = saturation;
            showColor.run();
            showCode.run();
        });
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                hsv[2] = progress / 100f;
                showColor.run();
                showCode.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (syncing[0]) {
                    return;
                }
                // One "#" in front, however the code was typed or pasted; only hex digits after it.
                String digits = text.toString().replace("#", "").replaceAll("[^0-9a-fA-F]", "");
                if (digits.length() > 6) {
                    digits = digits.substring(0, 6);
                }
                String wanted = digits.isEmpty() ? "" : "#" + digits.toUpperCase(Locale.US);
                if (!wanted.contentEquals(text)) {
                    syncing[0] = true;
                    text.replace(0, text.length(), wanted);
                    syncing[0] = false;
                }
                Integer parsed = parse(digits);
                if (parsed != null) {
                    Color.colorToHSV(parsed, hsv);
                    wheel.setHueSaturation(hsv[0], hsv[1]);
                    brightness.setProgress(Math.round(hsv[2] * 100));
                    showColor.run();
                }
            }
        });

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mobile_appearance_accent)
                .setView(column)
                .setPositiveButton(R.string.mobile_appearance_apply, (dialog, which) -> {
                    UiPrefs.setAccent(context, Color.HSVToColor(hsv));
                    onChanged.run();
                })
                .setNeutralButton(R.string.mobile_appearance_default, (dialog, which) -> {
                    UiPrefs.setAccent(context, UiPrefs.DEFAULT_ACCENT);
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "#RRGGBB" for a colour. */
    static String hex(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    /** A six- or three-digit code (no "#"), or null while it is not one yet. */
    private static Integer parse(String digits) {
        if (digits.length() == 3) {
            digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1)
                    + digits.charAt(2) + digits.charAt(2);
        }
        if (digits.length() != 6) {
            return null;
        }
        try {
            return 0xFF000000 | Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
