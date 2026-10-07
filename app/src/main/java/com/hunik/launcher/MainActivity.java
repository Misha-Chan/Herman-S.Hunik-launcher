package com.hunik.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    // ------------------------------------------------------------------
    // Design tokens (one place to restyle the whole launcher)
    // ------------------------------------------------------------------
    private static final int C_SCRIM = 0x40000000;      // soft dark veil over any wallpaper
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT_DIM = 0xCCFFFFFF;
    private static final int C_SHADOW = 0x99000000;
    private static final int C_PILL = 0x40FFFFFF;       // translucent button on the wallpaper
    private static final int C_SURFACE = 0xFFFFFFFF;    // dialog card / icon plate
    private static final int C_ON_SURFACE = 0xFF1E1E1E;
    private static final int C_ON_SURFACE_DIM = 0xFF6B6B6B;
    private static final int C_ACCENT = 0xFF7A1F2B;     // maroon, taken from the icon set

    private static final int SP_ICON = 64;              // dp, icon tile size
    private static final int SP_RADIUS_CARD = 20;       // dp
    private static final float TILE_RADIUS = 0.22f;     // fraction of tile size

    /** Wallpaper presets: {top-left colour, bottom-right colour}. */
    private static final int[][] PRESETS = {
            {0xFF993636, 0xFF43000A},   // maroon
            {0xFF2B2B30, 0xFF050506},   // onyx
            {0xFF6B2D5C, 0xFF1A0A16},   // plum
            {0xFF2A3F6B, 0xFF05080F},   // midnight
            {0xFF77777D, 0xFF1C1C1F},   // graphite
    };
    private static final int[] PRESET_NAMES = {
            R.string.wp_maroon, R.string.wp_onyx, R.string.wp_plum,
            R.string.wp_midnight, R.string.wp_graphite
    };

    private static final int REQ_PICK = 1;

    /** package name -> custom icon (res/drawable-nodpi). Add a line to restyle another app. */
    private static final Map<String, Integer> ICONS = new HashMap<String, Integer>();
    static {
        ICONS.put("com.hunik.ytweb", R.drawable.ic_youtube);
        ICONS.put("com.hunik.gmailweb", R.drawable.ic_gmail);
        ICONS.put("com.facebook.lite", R.drawable.ic_facebook);
        ICONS.put("org.mozilla.firefox", R.drawable.ic_firefox);
        ICONS.put("com.hunik.gphotoweb", R.drawable.ic_gphotos);
    }

    private static class Item {
        String label;
        String pkg;
        ComponentName component;
        Bitmap tile;
    }

    /** Soft "press" feedback: shrink a little while touched. */
    private static final View.OnTouchListener PRESS = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        }
    };

    private final List<Item> items = new ArrayList<Item>();
    private GridView grid;
    private BaseAdapter adapter;
    private boolean loading;
    private boolean shown;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_SCRIM);
        root.setFitsSystemWindows(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildHeader());

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(88));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(8), dp(8), dp(8), dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        content.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                LinearLayout row;
                ImageView iv;
                TextView tv;
                if (cv == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.CENTER_HORIZONTAL);
                    row.setPadding(dp(4), dp(8), dp(4), dp(4));
                    row.setOnTouchListener(PRESS);
                    iv = new ImageView(MainActivity.this);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(dp(SP_ICON), dp(SP_ICON)));
                    tv = new TextView(MainActivity.this);
                    tv.setTextSize(12);
                    tv.setTextColor(C_TEXT);
                    tv.setShadowLayer(dp(2), 0, dp(1), C_SHADOW);
                    tv.setGravity(Gravity.CENTER);
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    tv.setPadding(0, dp(6), 0, 0);
                    row.addView(iv);
                    row.addView(tv);
                } else {
                    row = (LinearLayout) cv;
                    iv = (ImageView) row.getChildAt(0);
                    tv = (TextView) row.getChildAt(1);
                }
                Item it = items.get(pos);
                iv.setImageBitmap(it.tile);
                tv.setText(it.label);
                return row;
            }
        };
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Intent i = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(items.get(pos).component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try { startActivity(i); } catch (Exception ignored) { }
            }
        });

        // Long press = app info screen (uninstall / clear data / force stop).
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + items.get(pos).pkg)));
                } catch (Exception ignored) { }
                return true;
            }
        });

        setContentView(root);
    }

    /** Big clock + date on the left, wallpaper button on the right. */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(24), dp(16), dp(20), dp(8));

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("H:mm");
        time.setFormat12Hour("h:mm");
        time.setTextSize(56);
        time.setTextColor(C_TEXT);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(dp(3), 0, dp(1), C_SHADOW);
        time.setIncludeFontPadding(false);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextSize(15);
        date.setTextColor(C_TEXT_DIM);
        date.setShadowLayer(dp(2), 0, dp(1), C_SHADOW);

        clockCol.addView(time);
        clockCol.addView(date);
        header.addView(clockCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = new TextView(this);
        btn.setText(R.string.wallpaper);
        btn.setTextSize(13);
        btn.setTextColor(C_TEXT);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));
        btn.setBackground(round(C_PILL, dp(20)));
        btn.setOnTouchListener(PRESS);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showWallpaperDialog(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        header.addView(btn, lp);
        return header;
    }

    // ------------------------------------------------------------------
    // Wallpaper dialog
    // ------------------------------------------------------------------
    private void showWallpaperDialog() {
        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(16));
        card.setBackground(round(C_SURFACE, dp(SP_RADIUS_CARD)));

        TextView title = new TextView(this);
        title.setText(R.string.wallpaper);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_ON_SURFACE);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        LinearLayout swatches = new LinearLayout(this);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(16);
        sl.bottomMargin = dp(8);
        card.addView(swatches, sl);

        for (int i = 0; i < PRESETS.length; i++) {
            final int c1 = PRESETS[i][0];
            final int c2 = PRESETS[i][1];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setOnTouchListener(PRESS);

            View dot = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(1), 0x22000000);
            dot.setBackground(g);
            cell.addView(dot, new LinearLayout.LayoutParams(dp(48), dp(48)));

            TextView name = new TextView(this);
            name.setText(PRESET_NAMES[i]);
            name.setTextSize(11);
            name.setTextColor(C_ON_SURFACE_DIM);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(4), 0, 0);
            cell.addView(name);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    new WallpaperTask(c1, c2, null, false).execute();
                }
            });
            swatches.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        TextView pick = new TextView(this);
        pick.setText(R.string.wp_pick);
        pick.setTextSize(14);
        pick.setTextColor(C_TEXT);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(dp(16), dp(12), dp(16), dp(12));
        pick.setBackground(round(C_ACCENT, dp(24)));
        pick.setOnTouchListener(PRESS);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(i, REQ_PICK); } catch (Exception ignored) { }
            }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(8);
        card.addView(pick, pl);

        TextView reset = new TextView(this);
        reset.setText(R.string.wp_reset);
        reset.setTextSize(13);
        reset.setTextColor(C_ON_SURFACE_DIM);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(16), dp(12), dp(16), dp(8));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                new WallpaperTask(0, 0, null, true).execute();
            }
        });
        card.addView(reset);

        TextView ver = new TextView(this);
        ver.setText("Launcher Herman S.Hunik  " + versionName());
        ver.setTextSize(10);
        ver.setTextColor(0xFFA0A0A0);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(4), 0, 0);
        card.addView(ver);

        dlg.setContentView(card);
        Window w = dlg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dlg.show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            new WallpaperTask(0, 0, data.getData(), false).execute();
        }
    }

    /** Builds the wallpaper bitmap off the UI thread and hands it to the system. */
    private class WallpaperTask extends AsyncTask<Void, Void, Boolean> {
        private final int c1, c2;
        private final Uri uri;
        private final boolean clear;

        WallpaperTask(int c1, int c2, Uri uri, boolean clear) {
            this.c1 = c1;
            this.c2 = c2;
            this.uri = uri;
            this.clear = clear;
        }

        @Override
        protected Boolean doInBackground(Void... v) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(MainActivity.this);
                if (clear) {
                    wm.clear();
                    return true;
                }
                int w = wm.getDesiredMinimumWidth();
                int h = wm.getDesiredMinimumHeight();
                if (w <= 0 || h <= 0) {
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    w = dm.widthPixels;
                    h = dm.heightPixels;
                }
                Bitmap b = uri != null ? decodeCover(uri, w, h) : gradient(w, h, c1, c2);
                wm.setBitmap(b);
                b.recycle();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            Toast.makeText(MainActivity.this, ok ? R.string.wp_done : R.string.wp_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private static Bitmap gradient(int w, int h, int c1, int c2) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, w, h, p);
        return b;
    }

    /** Decodes with sub-sampling (low RAM) and centre-crops to exactly tw x th. */
    private Bitmap decodeCover(Uri uri, int tw, int th) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(in, null, o);
        if (in != null) in.close();

        int sample = 1;
        while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = getContentResolver().openInputStream(uri);
        Bitmap src = BitmapFactory.decodeStream(in, null, o2);
        if (in != null) in.close();
        if (src == null) throw new IOException("decode failed");

        float sc = Math.max((float) tw / src.getWidth(), (float) th / src.getHeight());
        Matrix m = new Matrix();
        m.postScale(sc, sc);
        m.postTranslate((tw - src.getWidth() * sc) / 2f, (th - src.getHeight() * sc) / 2f);
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    // ------------------------------------------------------------------
    // App list
    // ------------------------------------------------------------------
    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grid.smoothScrollToPosition(0); // Home pressed while already on the launcher
    }

    @Override
    public void onBackPressed() {
        // Home screen: do nothing.
    }

    /** One uniform rounded white tile per app; custom icons fill it, system icons sit inside it. */
    private Bitmap buildTile(Drawable system, Integer customRes) {
        int s = dp(SP_ICON);
        float r = s * TILE_RADIUS;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF rect = new RectF(0, 0, s, s);
        if (customRes != null) {
            Bitmap src = BitmapFactory.decodeResource(getResources(), customRes);
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float k = (float) s / src.getWidth();
            m.setScale(k, k);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            c.drawRoundRect(rect, r, r, p);
            src.recycle();
        } else {
            p.setColor(C_SURFACE);
            c.drawRoundRect(rect, r, r, p);
            int pad = s / 6;
            system.setBounds(pad, pad, s - pad, s - pad);
            system.draw(c);
        }
        return bmp;
    }

    private void load() {
        if (loading) return;
        loading = true;
        new AsyncTask<Void, Void, List<Item>>() {
            @Override
            protected List<Item> doInBackground(Void... v) {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<Item> out = new ArrayList<Item>();
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    Item it = new Item();
                    it.pkg = pkg;
                    it.component = new ComponentName(pkg, ri.activityInfo.name);
                    it.label = String.valueOf(ri.loadLabel(pm));
                    Integer res = ICONS.get(pkg);
                    it.tile = buildTile(res == null ? ri.loadIcon(pm) : null, res);
                    out.add(it);
                }
                Collections.sort(out, new Comparator<Item>() {
                    @Override
                    public int compare(Item a, Item b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                return out;
            }

            @Override
            protected void onPostExecute(List<Item> result) {
                boolean first = !shown;
                if (first) grid.setAlpha(0f);
                items.clear();
                items.addAll(result);
                adapter.notifyDataSetChanged();
                if (first) {
                    grid.animate().alpha(1f).setDuration(240).start();
                    shown = true;
                }
                loading = false;
            }
        }.execute();
    }
}
