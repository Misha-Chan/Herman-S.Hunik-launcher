package com.hunik.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    /** package name -> custom icon (res/drawable-nodpi). Add a line here to restyle another app. */
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
        Drawable icon;
    }

    private final List<Item> items = new ArrayList<Item>();
    private GridView grid;
    private BaseAdapter adapter;
    private boolean loading;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        grid = new GridView(this);
        grid.setBackgroundColor(0xFFFFFFFF);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(96));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(8), dp(16), dp(8), dp(8));
        grid.setClipToPadding(false);

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
                    iv = new ImageView(MainActivity.this);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    tv = new TextView(MainActivity.this);
                    tv.setTextSize(12);
                    tv.setTextColor(0xFF222222);
                    tv.setGravity(Gravity.CENTER);
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    tv.setPadding(0, dp(4), 0, 0);
                    row.addView(iv);
                    row.addView(tv);
                } else {
                    row = (LinearLayout) cv;
                    iv = (ImageView) row.getChildAt(0);
                    tv = (TextView) row.getChildAt(1);
                }
                Item it = items.get(pos);
                iv.setImageDrawable(it.icon);
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

        setContentView(grid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grid.setSelection(0); // Home pressed while already on the launcher
    }

    @Override
    public void onBackPressed() {
        // Home screen: do nothing.
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
                    it.icon = res != null ? getResources().getDrawable(res) : ri.loadIcon(pm);
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
                items.clear();
                items.addAll(result);
                adapter.notifyDataSetChanged();
                loading = false;
            }
        }.execute();
    }
}
