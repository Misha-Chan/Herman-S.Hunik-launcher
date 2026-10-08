package com.hunik.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
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
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    // ------------------------------------------------------------------
    // Design tokens (one place to restyle the whole launcher)
    // ------------------------------------------------------------------
    private static final int C_SCRIM = 0x40000000;      // veil over the wallpaper on the home screen
    private static final int C_DRAWER = 0xE6101014;     // veil when the app drawer is open
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT_DIM = 0xCCFFFFFF;
    private static final int C_SHADOW = 0x99000000;
    private static final int C_DOCK = 0x33FFFFFF;
    private static final int C_SLOT_DRAG = 0x22FFFFFF;
    private static final int C_SLOT_HOVER = 0x55FFFFFF;
    private static final int C_SURFACE = 0xFFFFFFFF;    // cards, icon plates
    private static final int C_ON_SURFACE = 0xFF1E1E1E;
    private static final int C_ON_SURFACE_DIM = 0xFF6B6B6B;
    private static final int C_ACCENT = 0xFF7A1F2B;     // maroon, taken from the icon set

    private static final int SP_ICON = 64;              // dp, icon tile bitmap
    private static final int SP_DOCK_ICON = 56;         // dp, icon shown in the dock
    private static final int SP_RADIUS_CARD = 20;       // dp
    private static final float TILE_RADIUS = 0.22f;     // fraction of tile size

    private static final int COLS = 4;                  // home shortcut columns
    private static final int DOCK_SIZE = 5;

    private static final int SRC_DRAWER = 0, SRC_HOME = 1, SRC_DOCK = 2;

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

    private static final View.OnTouchListener ROW_PRESS = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackgroundColor(0x14000000);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackgroundColor(0);
                    break;
                default:
                    break;
            }
            return false;
        }
    };

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private Store store;
    private Root root;
    private LinearLayout home;
    private ShortcutGrid shortcuts;
    private final FrameLayout[] slots = new FrameLayout[DOCK_SIZE];
    private final ImageView[] slotIcons = new ImageView[DOCK_SIZE];
    private FrameLayout drawer;
    private GridView appGrid;
    private TextView drawerTitle;
    private EyeView eye;
    private FrameLayout menuLayer;
    private BaseAdapter adapter;

    private final Map<String, Item> all = new LinkedHashMap<String, Item>();
    private final List<Item> drawerList = new ArrayList<Item>();
    private final Map<String, Bitmap> tileCache = Collections.synchronizedMap(new HashMap<String, Bitmap>());
    private String lastSig = "";
    private boolean loading, shown, hiddenMode, drawerOpen;
    private float progress;
    private ValueAnimator anim;
    private final ArgbEvaluator argb = new ArgbEvaluator();

    // drag state
    private String dragPkg;
    private int dragSource, dragIndex, hoverSlot = -1;
    private View dragView;
    private boolean dragMoved, dragBase;
    private float dragX0, dragY0;

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
    // Setup
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        if (!store.hasDock()) store.setDock(defaultDock());

        root = new Root(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(C_SCRIM);

        buildHome();
        buildDrawer();
        buildMenuLayer();

        ViewGroup.LayoutParams full = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(home, full);
        root.addView(drawer, full);
        root.addView(menuLayer, full);
        root.setOnDragListener(dragListener);

        setContentView(root);
        setProgress(0f);
    }

    /** Firefox, Gmail, YouTube, Phone, SMS (phone/SMS are looked up on the device). */
    private String[] defaultDock() {
        String sms = null;
        try {
            sms = Telephony.Sms.getDefaultSmsPackage(this);
        } catch (Throwable ignored) {
        }
        if (sms == null || sms.length() == 0 || getPackageManager().getLaunchIntentForPackage(sms) == null) {
            sms = resolveLauncherPkg(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:123")));
        }
        String phone = resolveLauncherPkg(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")));
        return new String[]{"org.mozilla.firefox", "com.hunik.gmailweb", "com.hunik.ytweb", phone, sms};
    }

    private String resolveLauncherPkg(Intent i) {
        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pm.getLaunchIntentForPackage(pkg) != null) return pkg;
        }
        return "";
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(4));
        row.setOnTouchListener(PRESS);
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(SP_ICON), dp(SP_ICON)));
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(C_TEXT);
        tv.setShadowLayer(dp(2), 0, dp(1), C_SHADOW);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(0, dp(6), 0, 0);
        row.addView(iv);
        row.addView(tv);
        return row;
    }

    private void bindRow(LinearLayout row, Item it) {
        ((ImageView) row.getChildAt(0)).setImageBitmap(it.tile);
        ((TextView) row.getChildAt(1)).setText(it.label);
    }

    // ------------------------------------------------------------------
    // Home: clock, shortcuts, dock
    // ------------------------------------------------------------------
    private void buildHome() {
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.CENTER_HORIZONTAL);
        clockCol.setPadding(0, dp(28), 0, dp(8));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("H:mm");
        time.setFormat12Hour("h:mm");
        time.setTextSize(64);
        time.setTextColor(C_TEXT);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(dp(3), 0, dp(1), C_SHADOW);
        time.setIncludeFontPadding(false);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextSize(16);
        date.setTextColor(C_TEXT_DIM);
        date.setShadowLayer(dp(2), 0, dp(1), C_SHADOW);

        clockCol.addView(time);
        clockCol.addView(date);
        home.addView(clockCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        shortcuts = new ShortcutGrid(this);
        home.addView(shortcuts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // swipe-up hint
        View handle = new View(this);
        handle.setBackground(round(0x66FFFFFF, dp(2)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(36), dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = dp(2);
        hl.bottomMargin = dp(10);
        home.addView(handle, hl);

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(round(C_DOCK, dp(24)));
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        for (int i = 0; i < DOCK_SIZE; i++) {
            final int idx = i;
            FrameLayout slot = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setOnTouchListener(PRESS);
            slot.addView(icon, new FrameLayout.LayoutParams(dp(SP_DOCK_ICON), dp(SP_DOCK_ICON), Gravity.CENTER));
            slot.setPadding(0, dp(4), 0, dp(4));
            icon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Item it = all.get(store.dock(DOCK_SIZE)[idx]);
                    if (it != null) launch(it);
                }
            });
            icon.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    String pkg = store.dock(DOCK_SIZE)[idx];
                    if (all.get(pkg) == null) return true;
                    beginDrag(v, pkg, SRC_DOCK, idx);
                    return true;
                }
            });
            slots[i] = slot;
            slotIcons[i] = icon;
            dock.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.setMargins(dp(12), 0, dp(12), dp(12));
        home.addView(dock, dl);

        // Long press on empty home = wallpaper
        View.OnLongClickListener wp = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showWallpaperDialog();
                return true;
            }
        };
        home.setOnLongClickListener(wp);
        shortcuts.setOnLongClickListener(wp);
    }

    /** Fixed 4-column grid of shortcuts; the cell index is row * COLS + col. */
    private class ShortcutGrid extends ViewGroup {
        int cw, ch, rows = 1, hover = -1;
        final Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();

        ShortcutGrid(Context c) {
            super(c);
            setWillNotDraw(false);
            hp.setColor(0x33FFFFFF);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
            cw = Math.max(1, w / COLS);
            rows = Math.max(1, h / dp(104));
            ch = Math.max(1, h / rows);
            setMeasuredDimension(w, h);
            int cs = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY);
            int hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cs, hs);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                Object tag = c.getTag();
                int cell = tag instanceof Integer ? (Integer) tag : 0;
                int row = cell / COLS, col = cell % COLS;
                if (row >= rows) c.layout(0, 0, 0, 0);
                else c.layout(col * cw, row * ch, col * cw + cw, row * ch + ch);
            }
        }

        int cellAt(float x, float y) {
            int c = (int) Math.max(0, Math.min(COLS - 1, x / cw));
            int r = (int) Math.max(0, Math.min(rows - 1, y / ch));
            return r * COLS + c;
        }

        void setHover(int cell) {
            if (cell != hover) {
                hover = cell;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (hover < 0) return;
            float pad = dp(4);
            int row = hover / COLS, col = hover % COLS;
            rect.set(col * cw + pad, row * ch + pad, (col + 1) * cw - pad, (row + 1) * ch - pad);
            c.drawRoundRect(rect, dp(14), dp(14), hp);
        }
    }

    private void refreshHome() {
        shortcuts.removeAllViews();
        for (Map.Entry<String, Integer> e : store.home().entrySet()) {
            final Item it = all.get(e.getKey());
            if (it == null) continue;
            LinearLayout row = makeRow();
            bindRow(row, it);
            row.setTag(e.getValue());
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launch(it);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    beginDrag(v, it.pkg, SRC_HOME, (Integer) v.getTag());
                    return true;
                }
            });
            shortcuts.addView(row);
        }
        String[] dock = store.dock(DOCK_SIZE);
        for (int i = 0; i < DOCK_SIZE; i++) {
            Item it = all.get(dock[i]);
            slotIcons[i].setImageBitmap(it == null ? null : it.tile);
        }
    }

    // ------------------------------------------------------------------
    // Drawer
    // ------------------------------------------------------------------
    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), 0, dp(12), 0);

        drawerTitle = new TextView(this);
        drawerTitle.setTextSize(18);
        drawerTitle.setTextColor(C_TEXT);
        drawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(drawerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        eye = new EyeView(this);
        eye.setOnTouchListener(PRESS);
        eye.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hiddenMode = !hiddenMode;
                rebuildLists();
                appGrid.setSelection(0);
            }
        });
        bar.addView(eye, new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        appGrid = new GridView(this);
        appGrid.setNumColumns(GridView.AUTO_FIT);
        appGrid.setColumnWidth(dp(88));
        appGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        appGrid.setVerticalSpacing(dp(8));
        appGrid.setPadding(dp(8), dp(8), dp(8), dp(16));
        appGrid.setClipToPadding(false);
        appGrid.setSelector(android.R.color.transparent);
        col.addView(appGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        adapter = new BaseAdapter() {
            @Override public int getCount() { return drawerList.size(); }
            @Override public Object getItem(int i) { return drawerList.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                LinearLayout row = cv == null ? makeRow() : (LinearLayout) cv;
                bindRow(row, drawerList.get(pos));
                return row;
            }
        };
        appGrid.setAdapter(adapter);

        appGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                launch(drawerList.get(pos));
            }
        });
        appGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                beginDrag(v, drawerList.get(pos).pkg, SRC_DRAWER, pos);
                return true;
            }
        });
    }

    /** Eye icon (drawn in code): toggles the list of hidden apps. */
    private class EyeView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private boolean active;

        EyeView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            stroke.setColor(C_TEXT);
            fill.setColor(C_TEXT);
            bg.setColor(0x33FFFFFF);
        }

        void setActive(boolean a) {
            active = a;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (active) c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2f, bg);
            oval.set(w * 0.18f, h * 0.33f, w * 0.82f, h * 0.67f);
            c.drawOval(oval, stroke);
            c.drawCircle(w / 2, h / 2, w * 0.09f, fill);
        }
    }

    private void rebuildLists() {
        drawerList.clear();
        Set<String> hid = store.hidden();
        for (Item it : all.values()) {
            if (hid.contains(it.pkg) == hiddenMode) drawerList.add(it);
        }
        Collections.sort(drawerList, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        adapter.notifyDataSetChanged();
        drawerTitle.setText(hiddenMode ? R.string.hidden_apps : R.string.all_apps);
        eye.setActive(hiddenMode);
    }

    private void launch(Item it) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(it.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
        if (drawerOpen || progress > 0f) closeDrawerNow();
    }

    // ------------------------------------------------------------------
    // Drawer open / close (progress 0 = home, 1 = drawer)
    // ------------------------------------------------------------------
    private void setProgress(float p) {
        progress = p;
        int h = root.getHeight();
        drawer.setTranslationY((1f - p) * h);
        drawer.setVisibility(p <= 0f ? View.INVISIBLE : View.VISIBLE);
        home.setAlpha(1f - p);
        root.setBackgroundColor((Integer) argb.evaluate(p, C_SCRIM, C_DRAWER));
    }

    private void layers(boolean on) {
        int t = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        home.setLayerType(t, null);
        drawer.setLayerType(t, null);
    }

    private void animateTo(final float target, long ms) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                setProgress((Float) a.getAnimatedValue());
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                layers(false);
                drawerOpen = progress > 0.5f;
                if (progress <= 0f && hiddenMode) {
                    hiddenMode = false;
                    rebuildLists();
                }
            }
        });
        layers(true);
        anim.start();
    }

    private void closeDrawerNow() {
        if (anim != null) anim.cancel();
        layers(false);
        setProgress(0f);
        drawerOpen = false;
        if (hiddenMode) {
            hiddenMode = false;
            rebuildLists();
        }
    }

    /** Handles the swipe-up (open) and swipe-down (close) gestures for the drawer. */
    private class Root extends FrameLayout {
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private float downX, downY, startY, startProgress;
        private boolean swiping;
        private VelocityTracker vt;

        Root(Context c) {
            super(c);
        }

        private boolean gridAtTop() {
            if (appGrid.getChildCount() == 0) return true;
            return appGrid.getFirstVisiblePosition() == 0
                    && appGrid.getChildAt(0).getTop() >= appGrid.getPaddingTop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (dragPkg != null || menuLayer.getVisibility() == View.VISIBLE) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    swiping = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        boolean vertical = Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.5f;
                        boolean openish = progress > 0.5f;
                        if (vertical && ((!openish && dy < 0) || (openish && dy > 0 && gridAtTop()))) {
                            swiping = true;
                            startY = e.getY();
                            startProgress = progress;
                            if (anim != null) anim.cancel();
                            vt = VelocityTracker.obtain();
                            vt.addMovement(e);
                            layers(true);
                        }
                    }
                    break;
                default:
                    break;
            }
            return swiping;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!swiping) return super.onTouchEvent(e);
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float p = startProgress - (e.getY() - startY) / Math.max(1f, getHeight() * 0.6f);
                    setProgress(Math.max(0f, Math.min(1f, p)));
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                    swiping = false;
                    boolean open = v < -800 ? true : (v > 800 ? false : progress > 0.5f);
                    animateTo(open ? 1f : 0f, 220);
                    break;
                }
                default:
                    break;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Drag & drop (drawer -> home / dock, home <-> dock, move on home)
    // ------------------------------------------------------------------
    private void beginDrag(View v, String pkg, int source, int index) {
        dragPkg = pkg;
        dragSource = source;
        dragIndex = index;
        dragView = v;
        dragMoved = false;
        dragBase = false;
        showMenu(v, pkg, source, index);
        boolean ok = false;
        try {
            ok = v.startDrag(ClipData.newPlainText("pkg", pkg), new View.DragShadowBuilder(v), null, 0);
        } catch (Exception ignored) {
        }
        if (ok) {
            v.setAlpha(0.3f);
        } else {
            dragPkg = null;
            dragView = null;
        }
    }

    private final View.OnDragListener dragListener = new View.OnDragListener() {
        @Override
        public boolean onDrag(View v, DragEvent e) {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return dragPkg != null;
                case DragEvent.ACTION_DRAG_LOCATION:
                    if (dragPkg == null) return true;
                    if (!dragBase) {
                        dragBase = true;
                        dragX0 = e.getX();
                        dragY0 = e.getY();
                        return true;
                    }
                    if (!dragMoved && Math.hypot(e.getX() - dragX0, e.getY() - dragY0) > dp(14)) {
                        dragMoved = true;
                        hideMenu();
                        if (dragSource == SRC_DRAWER) animateTo(0f, 180);
                        showDockTargets(true);
                    }
                    if (dragMoved) updateHover(e.getX(), e.getY());
                    return true;
                case DragEvent.ACTION_DROP:
                    if (dragPkg != null && dragMoved) handleDrop(e.getX(), e.getY());
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    endDrag();
                    return true;
                default:
                    return true;
            }
        }
    };

    private float[] local(View v, float x, float y) {
        int[] a = new int[2], r = new int[2];
        v.getLocationInWindow(a);
        root.getLocationInWindow(r);
        return new float[]{x - (a[0] - r[0]), y - (a[1] - r[1])};
    }

    private boolean hit(View v, float x, float y) {
        float[] l = local(v, x, y);
        return l[0] >= 0 && l[1] >= 0 && l[0] < v.getWidth() && l[1] < v.getHeight();
    }

    private int slotAt(float x, float y) {
        for (int i = 0; i < DOCK_SIZE; i++) if (hit(slots[i], x, y)) return i;
        return -1;
    }

    private void showDockTargets(boolean on) {
        for (int i = 0; i < DOCK_SIZE; i++) {
            slots[i].setBackground(on ? round(C_SLOT_DRAG, dp(14)) : null);
            slots[i].setScaleX(1f);
            slots[i].setScaleY(1f);
        }
        hoverSlot = -1;
    }

    private void updateHover(float x, float y) {
        int slot = slotAt(x, y);
        if (slot != hoverSlot) {
            if (hoverSlot >= 0) {
                slots[hoverSlot].setBackground(round(C_SLOT_DRAG, dp(14)));
                slots[hoverSlot].animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            if (slot >= 0) {
                slots[slot].setBackground(round(C_SLOT_HOVER, dp(14)));
                slots[slot].animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).start();
            }
            hoverSlot = slot;
        }
        int cell = -1;
        if (slot < 0 && hit(shortcuts, x, y)) {
            float[] l = local(shortcuts, x, y);
            cell = shortcuts.cellAt(l[0], l[1]);
        }
        shortcuts.setHover(cell);
    }

    private void handleDrop(float x, float y) {
        int slot = slotAt(x, y);
        if (slot >= 0) {
            placeInDock(dragPkg, slot, dragSource, dragIndex);
        } else if (hit(shortcuts, x, y)) {
            float[] l = local(shortcuts, x, y);
            placeOnHome(dragPkg, shortcuts.cellAt(l[0], l[1]), dragSource, dragIndex);
        }
        refreshHome();
    }

    private void endDrag() {
        if (dragView != null) dragView.setAlpha(1f);
        showDockTargets(false);
        shortcuts.setHover(-1);
        if (dragMoved) hideMenu();
        dragPkg = null;
        dragView = null;
        dragMoved = false;
        dragBase = false;
    }

    private void placeInDock(String pkg, int slot, int src, int srcIndex) {
        String[] d = store.dock(DOCK_SIZE);
        int j = -1;
        for (int i = 0; i < d.length; i++) if (d[i].equals(pkg)) j = i;
        String old = d[slot];
        d[slot] = pkg;
        if (j >= 0 && j != slot) d[j] = old;   // same app already in the dock: swap
        store.setDock(d);
        if (src == SRC_HOME) {                 // moving a home shortcut to the dock
            Map<String, Integer> h = store.home();
            h.remove(pkg);
            store.setHome(h);
        }
    }

    private void placeOnHome(String pkg, int cell, int src, int srcIndex) {
        LinkedHashMap<String, Integer> h = store.home();
        String occupant = null;
        for (Map.Entry<String, Integer> e : h.entrySet()) {
            if (e.getValue() == cell && !e.getKey().equals(pkg)) occupant = e.getKey();
        }
        Integer old = h.get(pkg);
        if (occupant != null) {
            if (old != null) {
                h.put(occupant, old);          // swap two shortcuts
            } else {
                cell = nearestFree(h, cell);
                if (cell < 0) {
                    Toast.makeText(this, R.string.t_full, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
        h.put(pkg, cell);
        store.setHome(h);
        if (src == SRC_DOCK) {                 // moving a dock app to the home screen
            String[] d = store.dock(DOCK_SIZE);
            d[srcIndex] = "";
            store.setDock(d);
        }
    }

    private int nearestFree(Map<String, Integer> h, int cell) {
        boolean[] used = new boolean[COLS * shortcuts.rows];
        for (int c : h.values()) if (c >= 0 && c < used.length) used[c] = true;
        int r0 = cell / COLS, c0 = cell % COLS, best = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < used.length; i++) {
            if (used[i]) continue;
            int d = Math.abs(i / COLS - r0) + Math.abs(i % COLS - c0);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Long-press menu (3 options)
    // ------------------------------------------------------------------
    private void buildMenuLayer() {
        menuLayer = new FrameLayout(this);
        menuLayer.setVisibility(View.GONE);
        menuLayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMenu();
            }
        });
    }

    private void hideMenu() {
        menuLayer.setVisibility(View.GONE);
        menuLayer.removeAllViews();
    }

    private void addMenuRow(LinearLayout card, int label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(C_ON_SURFACE);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setOnTouchListener(ROW_PRESS);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMenu();
                action.run();
            }
        });
        card.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void showMenu(View anchor, final String pkg, final int src, final int index) {
        menuLayer.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(8), 0, dp(8));
        card.setBackground(round(C_SURFACE, dp(16)));
        card.setClickable(true);

        Runnable uninstall = new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
                } catch (Exception ignored) {
                }
            }
        };
        Runnable info = new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + pkg)));
                } catch (Exception ignored) {
                }
            }
        };

        if (src == SRC_DRAWER) {
            final boolean unhide = hiddenMode;
            addMenuRow(card, R.string.m_uninstall, uninstall);
            addMenuRow(card, R.string.m_info, info);
            addMenuRow(card, unhide ? R.string.m_unhide : R.string.m_hide, new Runnable() {
                @Override
                public void run() {
                    Set<String> hid = store.hidden();
                    if (unhide) hid.remove(pkg);
                    else hid.add(pkg);
                    store.setHidden(hid);
                    rebuildLists();
                    Toast.makeText(MainActivity.this, unhide ? R.string.t_unhidden : R.string.t_hidden,
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            addMenuRow(card, R.string.m_remove_home, new Runnable() {
                @Override
                public void run() {
                    if (src == SRC_HOME) {
                        Map<String, Integer> h = store.home();
                        h.remove(pkg);
                        store.setHome(h);
                    } else {
                        String[] d = store.dock(DOCK_SIZE);
                        d[index] = "";
                        store.setDock(d);
                    }
                    refreshHome();
                }
            });
            addMenuRow(card, R.string.m_info, info);
            addMenuRow(card, R.string.m_uninstall, uninstall);
        }

        int[] a = new int[2], r = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r);
        int ax = a[0] - r[0] - root.getPaddingLeft();
        int ay = a[1] - r[1] - root.getPaddingTop();
        int cw = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int chh = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        int cardW = dp(220), cardH = dp(48) * 3 + dp(16);
        int left = Math.max(dp(8), Math.min(cw - cardW - dp(8), ax + anchor.getWidth() / 2 - cardW / 2));
        int top = ay + anchor.getHeight() + dp(6);
        if (top + cardH > chh - dp(8)) top = ay - cardH - dp(6);
        if (top < dp(8)) top = dp(8);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.topMargin = top;
        menuLayer.addView(card, lp);
        menuLayer.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Wallpaper dialog (long press on the home screen)
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
                @Override
                public void onClick(View v) {
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
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception ignored) {
                }
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
            @Override
            public void onClick(View v) {
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
    // Lifecycle + app list
    // ------------------------------------------------------------------
    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideMenu();
        if (drawerOpen) animateTo(0f, 220);
    }

    @Override
    public void onBackPressed() {
        if (menuLayer.getVisibility() == View.VISIBLE) hideMenu();
        else if (drawerOpen) animateTo(0f, 220);
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
        new AsyncTask<Void, Void, Map<String, Item>>() {
            @Override
            protected Map<String, Item> doInBackground(Void... v) {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                Map<String, Item> out = new LinkedHashMap<String, Item>();
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
                    Item it = new Item();
                    it.pkg = pkg;
                    it.component = new ComponentName(pkg, ri.activityInfo.name);
                    it.label = String.valueOf(ri.loadLabel(pm));
                    Bitmap t = tileCache.get(pkg);
                    if (t == null) {
                        Integer res = ICONS.get(pkg);
                        t = buildTile(res == null ? ri.loadIcon(pm) : null, res);
                        tileCache.put(pkg, t);
                    }
                    it.tile = t;
                    out.put(pkg, it);
                }
                return out;
            }

            @Override
            protected void onPostExecute(Map<String, Item> result) {
                loading = false;
                StringBuilder sb = new StringBuilder();
                for (Item it : result.values()) sb.append(it.pkg).append('=').append(it.label).append(';');
                String sig = sb.toString();
                if (sig.equals(lastSig)) return;      // nothing installed/removed since last time
                lastSig = sig;
                boolean first = !shown;
                if (first) home.setAlpha(0f);
                all.clear();
                all.putAll(result);
                rebuildLists();
                refreshHome();
                if (first) {
                    home.animate().alpha(1f).setDuration(240).start();
                    shown = true;
                }
            }
        }.execute();
    }
}
