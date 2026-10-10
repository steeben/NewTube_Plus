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
 * NEWTUBE(accent): Settings > Appearance > Accent. First a row of common colours as circles (YouTube
 * red first, the default); the last circle, with a colour dropper in it, opens the custom picker -
 * a colour wheel, a brightness slider and a
 * colour code field that follow each other. The field takes a code with or without its "#" (it
 * puts one in front, once) and three- or six-digit codes; Default puts the red back.
 */
final class AccentDialog {
    private AccentDialog() {
    }

    /** The preset accents, in the order of the circles; the first is the default red. */
    private static final int[] PRESETS = {
            UiPrefs.DEFAULT_ACCENT, // YouTube red
            0xFFFF6D00, // orange
            0xFFFFC400, // amber
            0xFF00C853, // green
            0xFF00BFA5, // teal
            0xFF00B8D4, // cyan
            0xFF2979FF, // blue
            0xFF651FFF, // violet
            0xFFD500F9, // purple
            0xFFF50057, // pink
            0xFF9E9E9E, // grey
    };
    private static final int COLUMNS = 4;

    static void show(Context context, Runnable onChanged) {
        float density = context.getResources().getDisplayMetrics().density;
        int current = UiPrefs.getAccent(context);
        boolean isPreset = false;
        for (int preset : PRESETS) {
            isPreset |= (preset | 0xFF000000) == current;
        }

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        grid.setPadding(pad, Math.round(16 * density), pad, Math.round(4 * density));

        androidx.appcompat.app.AlertDialog[] dialog = {null};
        int count = PRESETS.length + 1;
        LinearLayout row = null;
        for (int i = 0; i < count; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = Math.round(12 * density);
                grid.addView(row, rowParams);
            }
            boolean custom = i == PRESETS.length;
            int color = custom ? current : PRESETS[i];
            boolean selected = custom ? !isPreset : (color | 0xFF000000) == current;
            View circle = circle(context, color, custom, selected, density);
            circle.setContentDescription(custom
                    ? context.getString(R.string.mobile_appearance_accent_custom) : hex(color));
            circle.setOnClickListener(v -> {
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                if (custom) {
                    showCustom(context, onChanged);
                } else {
                    UiPrefs.setAccent(context, color);
                    onChanged.run();
                }
            });
            android.widget.FrameLayout cell = new android.widget.FrameLayout(context);
            int size = Math.round(48 * density);
            android.widget.FrameLayout.LayoutParams circleParams =
                    new android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER);
            cell.addView(circle, circleParams);
            row.addView(cell, new LinearLayout.LayoutParams(0, Math.round(56 * density), 1f));
        }
        // The last row filled out with empty cells, so its circles line up with the ones above.
        int rest = (COLUMNS - count % COLUMNS) % COLUMNS;
        for (int i = 0; i < rest && row != null; i++) {
            row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        }

        dialog[0] = com.newtube.mobile.ui.common.FrostedDialog.frost(new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_appearance_accent)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    /**
     * One circle of the picker: the colour, a check on the chosen one (dark or light against it),
     * and for the custom circle a rainbow ring around the dropper - in the chosen colour once a
     * custom colour is the accent.
     */
    private static View circle(Context context, int color, boolean custom, boolean selected, float density) {
        android.widget.ImageView view = new android.widget.ImageView(context);
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        int ink;
        if (custom) {
            fill.setGradientType(GradientDrawable.SWEEP_GRADIENT);
            fill.setColors(new int[] {0xFFFF0033, 0xFFFFC400, 0xFF00C853, 0xFF00B8D4, 0xFF2979FF,
                    0xFFD500F9, 0xFFFF0033});
            GradientDrawable inner = new GradientDrawable();
            inner.setShape(GradientDrawable.OVAL);
            inner.setColor(selected ? color : 0xFF2A2A2A);
            int ring = Math.round(4 * density);
            view.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
                    fill, new android.graphics.drawable.InsetDrawable(inner, ring)}));
            ink = selected && isLight(color) ? 0xFF0F0F0F : 0xFFFFFFFF;
            view.setImageResource(R.drawable.ic_color_dropper);
        } else {
            fill.setColor(color);
            view.setBackground(fill);
            ink = isLight(color) ? 0xFF0F0F0F : 0xFFFFFFFF;
            if (selected) {
                view.setImageResource(R.drawable.ic_mobile_check);
            }
        }
        view.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        view.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, mask));
        return view;
    }

    private static boolean isLight(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.6;
    }

    /** The custom picker: wheel, brightness and code. */
    private static void showCustom(Context context, Runnable onChanged) {
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

        com.newtube.mobile.ui.common.FrostedDialog.frost(new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
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
                .show());
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
