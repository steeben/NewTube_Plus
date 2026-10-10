package com.newtube.mobile.ui.settings;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.UiPrefs;

import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * NEWTUBE(branding): Settings > Appearance > Logo > Custom - picks a picture with the system's
 * picker and keeps a square copy of it (its middle, 256 px) as the top bar's logo, then closes.
 * A screen of its own because Settings' pages are built on demand and cannot register for a
 * result. Nothing is shown: the picker is the whole screen.
 */
public final class BrandLogoPickActivity extends Activity {
    private static final int REQUEST_PICK = 1;
    private static final int SIZE_PX = 256;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return; // the picker is already open
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQUEST_PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.mobile_appearance_logo_failed, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null) {
            if (save(uri)) {
                UiPrefs.setLogo(this, UiPrefs.LOGO_CUSTOM);
            } else {
                Toast.makeText(this, R.string.mobile_appearance_logo_failed, Toast.LENGTH_SHORT).show();
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean save(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            int shortest = Math.min(bounds.outWidth, bounds.outHeight);
            options.inSampleSize = 1;
            while (shortest / (options.inSampleSize * 2) >= SIZE_PX) {
                options.inSampleSize *= 2;
            }
            Bitmap source;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                source = BitmapFactory.decodeStream(in, null, options);
            }
            if (source == null) {
                return false;
            }
            int side = Math.min(source.getWidth(), source.getHeight());
            int left = (source.getWidth() - side) / 2;
            int top = (source.getHeight() - side) / 2;
            Bitmap square = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888);
            new Canvas(square).drawBitmap(source, new Rect(left, top, left + side, top + side),
                    new Rect(0, 0, SIZE_PX, SIZE_PX), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            source.recycle();
            try (FileOutputStream out = new FileOutputStream(UiPrefs.customLogoFile(this))) {
                square.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            square.recycle();
            return true;
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }
}
