package com.phonecam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.*;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    // ---- palette ----
    static final int BG = 0xFF0E1014, CARD = 0xFF181B22, CARD2 = 0xFF242833, ACCENT = 0xFF4C8DFF,
            GOOD = 0xFF2FBF71, WARN = 0xFFFFB020, BAD = 0xFFFF5A4F, TEXT = 0xFFE8EAF0, MUTED = 0xFF8A90A0,
            GRAYPILL = 0xFF3A3F4B, LIGHTBLUE = 0xFF4FC3F7, DARKTEXT = 0xFF08202E;

    private WsClient ws;
    private volatile boolean connected = false;
    private EditText addr;
    private TextView connPill, arChip, modeChip, posText, statusLine;
    private Button connectBtn, camToggle, blL, blH, blR, photoBtn, holdBtn, rtBtn;
    private FrameLayout menuBtn;
    private MenuIcon menuIcon;
    private GLSurfaceView glView;
    private LinearLayout settingsContent, zoneTop, zoneMid, zoneBottom, layoutRows, bottomBar;
    private ScrollView mainScroll, settingsScroll;
    private View blkStatus, blkCamera, blkSignals, blkRt, blkActions;
    private StreamView streamView;
    private Seg anchorSeg, fitSeg;
    private SensorManager sm;
    private SharedPreferences prefs;
    private final ArTracker ar = new ArTracker();
    private boolean installRequested = false;
    private String arMsg = "";
    private boolean settingsOpen = false, streaming = false, lastRtEnabled = false, rtInit = false;

    private final HashMap<String, Float> cfg = new HashMap<String, Float>();

    // live state
    private final float[] rot = new float[9];
    private volatile boolean haveRot = false;
    private int mode = 0, recenter = 0, camOn = 1;
    private volatile boolean clutch = false;
    private boolean blLeft, blRight, blHaz;
    private int photoCountdown = 0;
    private float shownX, shownY, shownZ;

    // AR tracking state (phone side)
    private long lastSeq = -1;
    private boolean aligned = false;
    private float alignC = 1f, alignS = 0f;
    private float prevHg = 0f; private long prevHgNs = 0; private float gyroRate = 0f;
    private final float[] origin = new float[3];
    private final float[] lastRaw = new float[3];
    private boolean haveRaw = false;
    private final float[] frozen = new float[3];
    private boolean clutchPrev = false;

    // inertial fallback (only when ARCore is unavailable)
    private final float[] pos = new float[3], vel = new float[3], bias = new float[3];
    private long lastAccNs = 0;
    private float stillTime = 0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sender = new Runnable() { public void run() { pushPose(); handler.postDelayed(this, 16); } };
    private final Runnable uiTick = new Runnable() { public void run() { updateStatus(); handler.postDelayed(this, 300); } };

    // ================= settings storage =================
    private static final String[] KEYS = { "track", "master", "sx", "sy", "sz", "shake", "pshake", "pred", "sens", "hlock",
            "fov", "acanchor", "level", "carsm", "ptimer", "cltoggle", "preview",
            "iyaw", "ipitch", "iroll", "ix", "iy", "iz", "ret",
            "anchor", "rtfit", "rtscale", "rtfps", "rtdouble", "holdshow" };
    private static final float[] DEFS = { 1, 1, 1, 1, 1, 0.3f, 0.3f, 50, 1, 0,
            0, 0, 0, 0.7f, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 0.25f,
            0, 0, 0.7f, 30, 1, 1 };

    private float g(String k) { Float v = cfg.get(k); return v == null ? 0f : v; }
    private boolean b(String k) { return g(k) > 0.5f; }

    private void loadSettings() {
        for (int i = 0; i < KEYS.length; i++) cfg.put(KEYS[i], prefs.getFloat(KEYS[i], DEFS[i]));
        mode = (int) g("anchor");
    }

    private void setCfg(String key, float v) {
        cfg.put(key, v);
        prefs.edit().putFloat(key, v).apply();
        if (key.equals("track") && v < 0.5f) { for (int i = 0; i < 3; i++) { pos[i] = 0; vel[i] = 0; } }
    }

    private void onSettingChanged(String key) {
        if (key.equals("preview")) { ar.previewOn = b("preview"); applyPreviewLayout(); }
        else if (key.equals("cltoggle")) { clutch = false; refreshHold(); }
        else if (key.equals("holdshow")) refreshBottomBar();
        else if (key.equals("rtdouble")) streamView.doubleTapExit = b("rtdouble");
        else if (key.equals("rtfit")) streamView.fit = b("rtfit");
    }

    // ================= main-page layout (zones) =================
    private static class Blk { String id; int zone; boolean vis; }
    private final ArrayList<Blk> layout = new ArrayList<Blk>();
    private static final String DEF_LAYOUT = "status:0:1,camera:0:1,signals:1:1,rtcam:2:1,actions:2:1";
    private static final String[] BLK_IDS = { "status", "camera", "signals", "rtcam", "actions" };
    private static final String[] BLK_NAMES = { "Status & position", "Camera ON/OFF", "Turn signals", "Realtime cam button", "Recenter & Photo" };
    private static final String[] ZONE_NAMES = { "Top", "Middle", "Bottom" };

    private String blkName(String id) {
        for (int i = 0; i < BLK_IDS.length; i++) if (BLK_IDS[i].equals(id)) return BLK_NAMES[i];
        return id;
    }

    private void parseLayout(String s) {
        layout.clear();
        for (String part : s.split(",")) {
            String[] f = part.split(":");
            if (f.length != 3) continue;
            boolean known = false;
            for (int i = 0; i < BLK_IDS.length; i++) if (BLK_IDS[i].equals(f[0])) known = true;
            if (!known) continue;
            Blk k = new Blk();
            k.id = f[0];
            try { k.zone = Math.max(0, Math.min(2, Integer.parseInt(f[1]))); } catch (Exception e) { k.zone = 0; }
            k.vis = f[2].equals("1");
            layout.add(k);
        }
        // make sure every block exists exactly once
        String[] defs = DEF_LAYOUT.split(",");
        for (int i = 0; i < defs.length; i++) {
            String[] f = defs[i].split(":");
            boolean found = false;
            for (Blk k : layout) if (k.id.equals(f[0])) found = true;
            if (!found) { Blk k = new Blk(); k.id = f[0]; k.zone = Integer.parseInt(f[1]); k.vis = true; layout.add(k); }
        }
    }

    private void saveLayout() {
        StringBuilder sb = new StringBuilder();
        for (Blk k : layout) { if (sb.length() > 0) sb.append(','); sb.append(k.id).append(':').append(k.zone).append(':').append(k.vis ? 1 : 0); }
        prefs.edit().putString("layout", sb.toString()).apply();
    }

    private View viewFor(String id) {
        if (id.equals("status")) return blkStatus;
        if (id.equals("camera")) return blkCamera;
        if (id.equals("signals")) return blkSignals;
        if (id.equals("rtcam")) return blkRt;
        return blkActions;
    }

    private int heightFor(String id) {
        if (id.equals("camera")) return dp(46);
        if (id.equals("rtcam")) return dp(54);
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private void applyMainLayout() {
        zoneTop.removeAllViews(); zoneMid.removeAllViews(); zoneBottom.removeAllViews();
        for (Blk k : layout) {
            if (!k.vis) continue;
            LinearLayout z = k.zone == 0 ? zoneTop : (k.zone == 1 ? zoneMid : zoneBottom);
            z.addView(viewFor(k.id), lp(-1, heightFor(k.id), 0, 0, 4, 0, 6));
        }
    }

    private void moveBlk(Blk k, int dir) {
        int i = layout.indexOf(k);
        int j = i + dir;
        while (j >= 0 && j < layout.size() && layout.get(j).zone != k.zone) j += dir;
        if (j < 0 || j >= layout.size()) return;
        layout.set(i, layout.get(j));
        layout.set(j, k);
    }

    private void renderLayoutRows() {
        layoutRows.removeAllViews();
        for (int i = 0; i < layout.size(); i++) {
            final Blk k = layout.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = tv(blkName(k.id), 14f, k.vis ? TEXT : MUTED, false);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            Button zone = mkBtn(ZONE_NAMES[k.zone], CARD2, ACCENT, 12f);
            zone.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                k.zone = (k.zone + 1) % 3; saveLayout(); applyMainLayout(); renderLayoutRows(); }});
            row.addView(zone, lp(dp(72), dp(38), 0, 0, 0, 4, 0));
            Button up = mkBtn("\u25B2", CARD2, TEXT, 12f);
            up.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                moveBlk(k, -1); saveLayout(); applyMainLayout(); renderLayoutRows(); }});
            row.addView(up, lp(dp(38), dp(38), 0, 0, 0, 4, 0));
            Button dn = mkBtn("\u25BC", CARD2, TEXT, 12f);
            dn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                moveBlk(k, 1); saveLayout(); applyMainLayout(); renderLayoutRows(); }});
            row.addView(dn, lp(dp(38), dp(38), 0, 0, 0, 6, 0));
            Switch sw = new Switch(this);
            sw.setChecked(k.vis);
            tintSwitch(sw);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton bv, boolean on) {
                    k.vis = on; saveLayout(); applyMainLayout(); renderLayoutRows(); }});
            row.addView(sw);
            layoutRows.addView(row, lp(-1, -2, 0, 0, 6, 0, 0));
        }
    }

    // ================= UI helpers =================
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable rr(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.72f), (int) (Color.green(c) * 0.72f), (int) (Color.blue(c) * 0.72f));
    }

    private StateListDrawable pressable(int color, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, rr(darker(color), radiusDp));
        s.addState(new int[] {}, rr(color, radiusDp));
        return s;
    }

    private Button mkBtn(String text, int color, int textColor, float sp) {
        Button x = new Button(this);
        x.setText(text); x.setAllCaps(false); x.setTextSize(sp); x.setTextColor(textColor);
        x.setTypeface(Typeface.DEFAULT_BOLD);
        x.setBackground(pressable(color, 14));
        x.setStateListAnimator(null);
        x.setPadding(dp(6), dp(2), dp(6), dp(2));
        return x;
    }

    private void paint(Button x, int color, int textColor) {
        x.setBackground(pressable(color, 14)); x.setTextColor(textColor);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int bt) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(bt));
        return p;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rr(CARD, 16));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    private void chip(TextView t, String text, int color, int fg) {
        t.setText(text); t.setTextColor(fg); t.setTextSize(12f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(rr(color, 20));
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
    }

    private void tintSwitch(Switch s) {
        int[][] st = { { android.R.attr.state_checked }, {} };
        s.setThumbTintList(new ColorStateList(st, new int[] { ACCENT, 0xFFB0B5C0 }));
        s.setTrackTintList(new ColorStateList(st, new int[] { 0xFF2A4A85, 0xFF3A3F4B }));
    }

    private interface SegListener { void onSelect(int i); }

    /** Two-option segmented control. */
    private class Seg {
        final LinearLayout view = new LinearLayout(MainActivity.this);
        final Button[] btn = new Button[2];
        Seg(String a, String c, int sel, final SegListener l) {
            view.setBackground(rr(CARD2, 14));
            view.setPadding(dp(3), dp(3), dp(3), dp(3));
            String[] t = { a, c };
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                btn[i] = mkBtn(t[i], ACCENT, Color.WHITE, 14f);
                btn[i].setOnClickListener(new View.OnClickListener() { public void onClick(View v) { set(idx); l.onSelect(idx); }});
                view.addView(btn[i], new LinearLayout.LayoutParams(0, dp(42), 1f));
            }
            set(sel);
        }
        void set(int sel) {
            for (int i = 0; i < 2; i++) {
                if (i == sel) { btn[i].setBackground(pressable(ACCENT, 11)); btn[i].setTextColor(Color.WHITE); }
                else { btn[i].setBackground(null); btn[i].setTextColor(MUTED); }
            }
        }
    }

    // ================= build UI =================
    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        prefs = getSharedPreferences("phonecam", MODE_PRIVATE);
        loadSettings();
        parseLayout(prefs.getString("layout", DEF_LAYOUT));
        buildUi();
        ar.previewOn = b("preview");
        applyPreviewLayout();
        handler.post(sender);
        handler.post(uiTick);
    }

    private void buildUi() {
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(30), dp(10), dp(8));

        // header: title + connection pill
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(tv("ACPCam", 26f, TEXT, true));
        titles.addView(tv("Realtime camera app for Assetto Corsa", 11f, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        connPill = new TextView(this);
        chip(connPill, "Not connected", GRAYPILL, TEXT);
        header.addView(connPill);
        col.addView(header, lp(-1, -2, 0, 4, 0, 4, 8));

        // phone-camera preview strip (must stay attached & visible or ARCore stops)
        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(ar);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        col.addView(glView, lp(-1, dp(150), 0, 0, 0, 0, 6));

        // pages
        buildBlocks();
        FrameLayout content = new FrameLayout(this);
        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.addView(buildMainColumn());
        settingsScroll = new ScrollView(this);
        settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsScroll.addView(settingsContent);
        settingsScroll.setVisibility(View.GONE);
        content.addView(mainScroll, new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsScroll, new FrameLayout.LayoutParams(-1, -1));
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        // bottom bar: hold-to-reposition + square settings button
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.END);
        holdBtn = mkBtn("HOLD  to reposition", 0xFF3A2F12, WARN, 15f);
        holdBtn.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                pressHold(); v.setPressed(true);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                releaseHold(); v.setPressed(false);
            }
            return true;
        }});
        bottomBar.addView(holdBtn, lp(0, dp(60), 1f, 0, 0, 8, 0));
        menuBtn = new FrameLayout(this);
        menuBtn.setClickable(true);
        menuBtn.setBackground(pressable(CARD2, 14));
        menuIcon = new MenuIcon(this);
        menuBtn.addView(menuIcon, new FrameLayout.LayoutParams(-1, -1));
        menuBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleSettings(); }});
        bottomBar.addView(menuBtn, lp(dp(60), dp(60), 0, 0, 0, 0, 0));
        col.addView(bottomBar, lp(-1, -2, 0, 0, 6, 0, 0));

        rootFrame.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // live game view overlay (full screen, no GUI)
        streamView = new StreamView(this);
        streamView.setVisibility(View.GONE);
        streamView.fit = b("rtfit");
        streamView.doubleTapExit = b("rtdouble");
        streamView.exitCallback = new Runnable() { public void run() { closeStream(); } };
        rootFrame.addView(streamView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootFrame);
        buildSettings();
        applyMainLayout();
        refreshMode(); refreshBlinkers(); refreshHold(); refreshCamToggle(); refreshBottomBar();
    }

    /** Creates the movable main-page blocks (each is created once and re-parented by applyMainLayout). */
    private void buildBlocks() {
        // status
        LinearLayout st = card();
        LinearLayout chips = new LinearLayout(this);
        arChip = new TextView(this); chip(arChip, "AR: starting", GRAYPILL, TEXT);
        modeChip = new TextView(this); chip(modeChip, "Car anchor", 0xFF3A4A70, TEXT);
        chips.addView(arChip, lp(-2, -2, 0, 0, 0, 8, 0));
        chips.addView(modeChip);
        st.addView(chips);
        posText = tv("X +0.00   Y +0.00   Z +0.00 m", 12f, MUTED, false);
        posText.setTypeface(Typeface.MONOSPACE);
        posText.setPadding(0, dp(8), 0, 0);
        st.addView(posText);
        statusLine = tv("", 11f, MUTED, false);
        statusLine.setPadding(0, dp(4), 0, 0);
        statusLine.setVisibility(View.GONE);
        st.addView(statusLine);
        blkStatus = st;

        // camera on/off
        camToggle = mkBtn("Camera ON", GOOD, Color.WHITE, 15f);
        camToggle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            camOn = 1 - camOn; refreshCamToggle(); }});
        blkCamera = camToggle;

        // turn signals
        LinearLayout bl = card();
        bl.addView(tv("Turn signals", 12f, MUTED, true));
        LinearLayout row = new LinearLayout(this);
        blL = blinkerButton("\u25C0  Left", "L");
        blH = blinkerButton("\u26A0  Hazards", "H");
        blR = blinkerButton("Right  \u25B6", "R");
        row.addView(blL, lp(0, dp(50), 1f, 0, 0, 4, 0));
        row.addView(blH, lp(0, dp(50), 1.2f, 4, 0, 4, 0));
        row.addView(blR, lp(0, dp(50), 1f, 4, 0, 0, 0));
        bl.addView(row, lp(-1, -2, 0, 0, 6, 0, 0));
        blkSignals = bl;

        // realtime cam button (dark until connected)
        rtBtn = mkBtn("\u25B6  Realtime cam", GRAYPILL, MUTED, 16f);
        rtBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (!connected) { toast("Connect to the PC first"); return; }
            openStream(); }});
        blkRt = rtBtn;

        // recenter + photo (small)
        LinearLayout act = new LinearLayout(this);
        Button recBtn = mkBtn("Recenter", CARD2, TEXT, 13f);
        recBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doRecenter(); }});
        photoBtn = mkBtn("\uD83D\uDCF7  Photo", ACCENT, Color.WHITE, 13f);
        photoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onPhoto(); }});
        act.addView(recBtn, lp(0, dp(44), 1f, 0, 0, 4, 0));
        act.addView(photoBtn, lp(0, dp(44), 1f, 4, 0, 0, 0));
        blkActions = act;
    }

    private LinearLayout buildMainColumn() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        // IP box + connect: part of the scrolling page
        LinearLayout conn = card();
        conn.setOrientation(LinearLayout.HORIZONTAL);
        conn.setGravity(Gravity.CENTER_VERTICAL);
        addr = new EditText(this);
        addr.setHint("PC address  e.g. 192.168.1.20:8765");
        addr.setText(prefs.getString("addr", ""));
        addr.setTextColor(TEXT); addr.setHintTextColor(MUTED); addr.setTextSize(14f);
        addr.setSingleLine(true);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addr.setBackground(rr(CARD2, 10));
        addr.setPadding(dp(12), dp(10), dp(12), dp(10));
        conn.addView(addr, new LinearLayout.LayoutParams(0, -2, 1f));
        connectBtn = mkBtn("Connect", ACCENT, Color.WHITE, 14f);
        connectBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (ws != null) disconnect();
            else { prefs.edit().putString("addr", addr.getText().toString()).apply(); connect(); }
        }});
        conn.addView(connectBtn, lp(dp(104), dp(46), 0, 8, 0, 0, 0));
        c.addView(conn, lp(-1, -2, 0, 0, 4, 0, 6));

        zoneTop = new LinearLayout(this); zoneTop.setOrientation(LinearLayout.VERTICAL);
        zoneMid = new LinearLayout(this); zoneMid.setOrientation(LinearLayout.VERTICAL);
        zoneBottom = new LinearLayout(this); zoneBottom.setOrientation(LinearLayout.VERTICAL);
        c.addView(zoneTop, new LinearLayout.LayoutParams(-1, -2));
        c.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        c.addView(zoneMid, new LinearLayout.LayoutParams(-1, -2));
        c.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        c.addView(zoneBottom, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private Button blinkerButton(String text, final String code) {
        Button x = mkBtn(text, CARD2, TEXT, 13f);
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (ws == null) { toast("Connect to the PC first"); return; }
            ws.send("B " + code);
            if (code.equals("L")) blLeft = !blLeft; else if (code.equals("R")) blRight = !blRight; else blHaz = !blHaz;
            refreshBlinkers();
        }});
        return x;
    }

    // ---- settings page ----
    private LinearLayout section(String title) {
        LinearLayout c = card();
        c.addView(tv(title, 15f, ACCENT, true));
        settingsContent.addView(c, lp(-1, -2, 0, 0, 4, 0, 6));
        return c;
    }

    private void note(LinearLayout c, String s) {
        c.addView(tv(s, 11f, MUTED, false), lp(-1, -2, 0, 0, 6, 0, 0));
    }

    private void swRow(LinearLayout c, String label, String hint, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(label, 14f, TEXT, false));
        if (hint != null) txt.addView(tv(hint, 11f, MUTED, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch s = new Switch(this);
        s.setChecked(b(key));
        tintSwitch(s);
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton bv, boolean on) { setCfg(key, on ? 1f : 0f); onSettingChanged(key); }});
        row.addView(s);
        c.addView(row, lp(-1, -2, 0, 0, 10, 0, 0));
    }

    private String fmt(int type, float v) {
        switch (type) {
            case 1: return v < 20f ? "Auto" : String.format(Locale.US, "%.0f\u00B0", v);
            case 2: return String.format(Locale.US, "x%.2f", v);
            case 3: return String.format(Locale.US, "%.0f ms", v);
            case 4: return v < 0.5f ? "Instant" : String.format(Locale.US, "%.0f s", v);
            case 5: return String.format(Locale.US, "%.0f%%", v * 100f);
            case 6: return String.format(Locale.US, "%.0f fps", v);
            default: return String.format(Locale.US, "%.2f", v);
        }
    }

    private void slider(LinearLayout c, String label, String hint, final String key,
                        final float min, final float max, final float step, final int type) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv(label, 14f, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = tv(fmt(type, g(key)), 14f, ACCENT, true);
        top.addView(val);
        c.addView(top, lp(-1, -2, 0, 0, 10, 0, 0));
        if (hint != null) c.addView(tv(hint, 11f, MUTED, false));
        SeekBar sb = new SeekBar(this);
        sb.setMax(1000);
        sb.setProgress(Math.round((g(key) - min) / (max - min) * 1000f));
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3F4B));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean user) {
                float v = min + pr / 1000f * (max - min);
                v = Math.round(v / step) * step;
                v = Math.max(min, Math.min(max, v));
                val.setText(fmt(type, v));
                if (user) { setCfg(key, v); onSettingChanged(key); }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        c.addView(sb);
    }

    private void buildSettings() {
        settingsContent.removeAllViews();

        LinearLayout c = section("Anchor");
        anchorSeg = new Seg("Car", "World", mode, new SegListener() { public void onSelect(int i) {
            mode = i; setCfg("anchor", i); refreshMode(); }});
        c.addView(anchorSeg.view, lp(-1, -2, 0, 0, 8, 0, 4));
        note(c, "Car: the camera rides with the car.  World: the camera stays fixed in the world (use this in replays).");

        c = section("Realtime cam  (live game view)");
        fitSeg = new Seg("Fill screen", "Fit whole image", (int) g("rtfit"), new SegListener() { public void onSelect(int i) {
            setCfg("rtfit", i); onSettingChanged("rtfit"); }});
        c.addView(fitSeg.view, lp(-1, -2, 0, 0, 8, 0, 4));
        note(c, "Fill: crops the edges to cover the whole screen.  Fit: shows the entire picture with black bars.  Never stretched.");
        slider(c, "Sharpness", "Higher = sharper but more delay. 100% = your phone's full screen resolution.", "rtscale", 0.25f, 1f, 0.05f, 5);
        slider(c, "Max frame rate", "Lower it if the picture lags on Wi-Fi.", "rtfps", 10f, 60f, 5f, 6);
        swRow(c, "Double-tap to exit", "Back always exits too", "rtdouble");
        note(c, "In the live view: Volume Up = hold to reposition, Volume Down = recenter. The PC app must be running; Assetto Corsa must be in windowed or borderless mode.");

        c = section("Phone camera preview");
        swRow(c, "Show the phone's camera picture", "Tracking keeps running when this is off", "preview");

        c = section("Main page layout");
        note(c, "Choose a zone (Top / Middle / Bottom), reorder with the arrows, and switch elements on or off.");
        layoutRows = new LinearLayout(this);
        layoutRows.setOrientation(LinearLayout.VERTICAL);
        c.addView(layoutRows);
        renderLayoutRows();
        swRow(c, "Show Hold to reposition button", "Bottom bar", "holdshow");
        Button rl = mkBtn("Reset layout", CARD2, TEXT, 13f);
        rl.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            parseLayout(DEF_LAYOUT); saveLayout(); applyMainLayout(); renderLayoutRows(); }});
        c.addView(rl, lp(-1, dp(42), 0, 0, 10, 0, 0));

        c = section("3D movement");
        swRow(c, "3D position tracking", "Move the phone = move the camera", "track");
        slider(c, "Master movement scale", "1.00 = real 1:1 metres. Raise it to cover more ground.", "master", 0.25f, 5f, 0.05f, 2);
        slider(c, "Left / right  (X)", null, "sx", 0f, 3f, 0.05f, 0);
        slider(c, "Up / down  (Y)", null, "sy", 0f, 3f, 0.05f, 0);
        slider(c, "Forward / back  (Z)", "Per-axis speed: 1.00 normal, 0 disables that axis.", "sz", 0f, 3f, 0.05f, 0);

        c = section("Smoothing");
        slider(c, "Rotation smoothing", "Anti-shake for looking around. 0 = none, 1 = maximum.", "shake", 0f, 1f, 0.05f, 0);
        slider(c, "Position smoothing", "Anti-shake for movement.", "pshake", 0f, 1f, 0.05f, 0);
        slider(c, "Tracking prediction", "Hides camera latency. Lower it if movement overshoots.", "pred", 0f, 100f, 5f, 3);

        c = section("Look & lens");
        slider(c, "Look sensitivity", "Below 1.00 = slower, more precise turning.", "sens", 0.25f, 2f, 0.05f, 2);
        swRow(c, "Lock horizon", "No roll - the camera stays level", "hlock");
        slider(c, "Field of view", "Auto follows Assetto Corsa's own FOV.", "fov", 0f, 120f, 1f, 1);

        c = section("Car & anchor");
        slider(c, "Car follow smoothing", "Removes jitter at speed. 0 = raw car position.", "carsm", 0f, 1f, 0.05f, 0);
        swRow(c, "Ignore car pitch & roll", "Camera follows heading only (steadier shots)", "level");
        swRow(c, "Anchor to AC's camera", "Only if AC is in a car-following camera (cockpit)", "acanchor");

        c = section("Photo");
        slider(c, "Self-timer", "Delay before the screenshot key is pressed.", "ptimer", 0f, 10f, 1f, 4);
        note(c, "Sends the Steam screenshot key (F12 by default) to the PC. Set the key in the PC app.");

        c = section("Reposition button");
        swRow(c, "Toggle instead of hold", "Tap once to freeze, tap again to continue", "cltoggle");

        c = section("Invert  (if something feels backwards)");
        swRow(c, "Look left / right", null, "iyaw");
        swRow(c, "Look up / down", null, "ipitch");
        swRow(c, "Roll", null, "iroll");
        swRow(c, "Move left / right", null, "ix");
        swRow(c, "Move up / down", null, "iy");
        swRow(c, "Move forward / back", null, "iz");

        Button reset = mkBtn("Reset all settings", CARD2, BAD, 14f);
        reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            for (int i = 0; i < KEYS.length; i++) setCfg(KEYS[i], DEFS[i]);
            mode = 0; ar.previewOn = true;
            streamView.fit = false; streamView.doubleTapExit = true;
            parseLayout(DEF_LAYOUT); saveLayout();
            buildSettings(); applyMainLayout();
            refreshMode(); refreshHold(); refreshBottomBar(); applyPreviewLayout();
            toast("Settings reset");
        }});
        settingsContent.addView(reset, lp(-1, dp(48), 0, 0, 10, 0, 24));
    }

    // ================= UI state =================
    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        settingsScroll.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        mainScroll.setVisibility(settingsOpen ? View.GONE : View.VISIBLE);
        menuBtn.setBackground(pressable(settingsOpen ? ACCENT : CARD2, 14));
        menuIcon.setColor(settingsOpen ? Color.WHITE : TEXT);
        applyPreviewLayout();
    }

    /** The GL surface must stay visible or ARCore stops, so "hidden" = a 4 dp strip. */
    private void applyPreviewLayout() {
        boolean show = !settingsOpen && b("preview");
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) glView.getLayoutParams();
        p.height = show ? dp(150) : dp(4);
        glView.setLayoutParams(p);
    }

    private void refreshBottomBar() {
        holdBtn.setVisibility(b("holdshow") ? View.VISIBLE : View.GONE);
    }

    private void refreshMode() {
        if (anchorSeg != null) anchorSeg.set(mode);
        chip(modeChip, mode == 0 ? "Car anchor" : "World anchor", 0xFF3A4A70, TEXT);
    }

    private void refreshCamToggle() {
        camToggle.setText(camOn == 1 ? "Camera ON" : "Camera OFF  (AC has control)");
        paint(camToggle, camOn == 1 ? GOOD : GRAYPILL, Color.WHITE);
    }

    private void refreshBlinkers() {
        paint(blL, blLeft ? WARN : CARD2, blLeft ? Color.BLACK : TEXT);
        paint(blR, blRight ? WARN : CARD2, blRight ? Color.BLACK : TEXT);
        paint(blH, blHaz ? BAD : CARD2, blHaz ? Color.WHITE : TEXT);
    }

    private void refreshHold() {
        if (clutch) { holdBtn.setText("REPOSITIONING  -  camera frozen"); paint(holdBtn, WARN, Color.BLACK); }
        else {
            holdBtn.setText(b("cltoggle") ? "TAP  to freeze / reposition" : "HOLD  to reposition");
            paint(holdBtn, 0xFF3A2F12, WARN);
        }
    }

    private void refreshRtButton() {
        boolean en = connected && ws != null;
        if (rtInit && en == lastRtEnabled) return;
        rtInit = true;
        lastRtEnabled = en;
        rtBtn.setEnabled(en);
        if (en) { paint(rtBtn, ACCENT, Color.WHITE); rtBtn.setText("\u25B6  Realtime cam"); rtBtn.setAlpha(1f); }
        else { paint(rtBtn, 0xFF1B1E25, 0xFF5A6070); rtBtn.setText("\u25B6  Realtime cam  (connect first)"); rtBtn.setAlpha(0.7f); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void updateStatus() {
        if (ws == null) chip(connPill, "Not connected", GRAYPILL, TEXT);
        else if (connected) chip(connPill, "Connected", LIGHTBLUE, DARKTEXT);
        else chip(connPill, "Connecting...", GRAYPILL, TEXT);
        refreshRtButton();

        if (ar.getSession() == null) chip(arChip, arMsg.length() > 0 ? "AR unavailable" : "AR: starting...", arMsg.length() > 0 ? WARN : GRAYPILL, arMsg.length() > 0 ? Color.BLACK : TEXT);
        else if (ar.state == 1) chip(arChip, "AR tracking", GOOD, Color.WHITE);
        else chip(arChip, "AR searching...", WARN, Color.BLACK);
        posText.setText(String.format(Locale.US, "X %+.2f   Y %+.2f   Z %+.2f m", shownX, shownY, shownZ));
        String s = "";
        if (ar.getSession() != null && ar.state != 1)
            s = "Move slowly, point at textured surfaces in good light" +
                (ar.failure.length() > 0 && !ar.failure.equals("NONE") ? "  [" + ar.failure + "]" : "");
        else if (ar.getSession() == null && arMsg.length() > 0) s = arMsg;
        statusLine.setText(s);
        statusLine.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
    }

    // ================= actions =================
    private void pressHold() {
        if (b("cltoggle")) clutch = !clutch; else clutch = true;
        refreshHold();
    }

    private void releaseHold() {
        if (!b("cltoggle")) clutch = false;
        refreshHold();
    }

    private void doRecenter() {
        recenter++;
        if (haveRaw) { origin[0] = lastRaw[0]; origin[1] = lastRaw[1]; origin[2] = lastRaw[2]; }
        frozen[0] = frozen[1] = frozen[2] = 0;
        for (int i = 0; i < 3; i++) { pos[i] = 0; vel[i] = 0; }
        if (!streaming) toast("Recentered");
    }

    private void onPhoto() {
        if (ws == null) { toast("Connect to the PC first"); return; }
        if (photoCountdown > 0) {
            photoCountdown = 0; handler.removeCallbacks(photoTick);
            photoBtn.setText("\uD83D\uDCF7  Photo"); return;
        }
        int t = Math.round(g("ptimer"));
        if (t <= 0) { shoot(); return; }
        photoCountdown = t;
        photoBtn.setText(photoCountdown + "  (cancel)");
        handler.postDelayed(photoTick, 1000);
    }

    private final Runnable photoTick = new Runnable() { public void run() {
        photoCountdown--;
        if (photoCountdown <= 0) { photoBtn.setText("\uD83D\uDCF7  Photo"); shoot(); }
        else { photoBtn.setText(photoCountdown + "  (cancel)"); handler.postDelayed(this, 1000); }
    }};

    private void shoot() {
        if (ws != null) { ws.send("B P"); if (!streaming) toast("Photo key sent to PC"); }
    }

    // ================= realtime cam =================
    private void openStream() {
        if (ws == null || !connected) return;
        streaming = true;
        streamView.fit = b("rtfit");
        streamView.doubleTapExit = b("rtdouble");
        streamView.clear();
        streamView.setVisibility(View.VISIBLE);
        setFullscreenUi(true);
        sendStreamStart();
        toast("Back = exit   Vol+ = reposition   Vol- = recenter");
    }

    private void closeStream() {
        if (!streaming) return;
        streaming = false;
        if (ws != null) ws.send("V 0");
        streamView.setVisibility(View.GONE);
        streamView.clear();
        setFullscreenUi(false);
        if (!b("cltoggle")) { clutch = false; refreshHold(); }
    }

    private void sendStreamStart() {
        if (ws == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = Math.min(dm.widthPixels, dm.heightPixels), h = Math.max(dm.widthPixels, dm.heightPixels);
        float scale = g("rtscale");
        ws.send(String.format(Locale.US, "V 1 %d %d %.2f %d %d %d", w, h, scale,
                Math.round(g("rtfps")), b("rtfit") ? 1 : 0, Math.round(55 + scale * 30)));
    }

    private void setFullscreenUi(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else d.setSystemUiVisibility(0);
        if (Build.VERSION.SDK_INT >= 28) {            // use the notch / cut-out area too
            try {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.getClass().getField("layoutInDisplayCutoutMode").setInt(lp, on ? 1 : 0);
                getWindow().setAttributes(lp);
            } catch (Exception e) { }
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && streaming) setFullscreenUi(true);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (streaming) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) { if (e.getRepeatCount() == 0) pressHold(); return true; }
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { if (e.getRepeatCount() == 0) doRecenter(); return true; }
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (streaming) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) { releaseHold(); return true; }
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override public void onBackPressed() {
        if (streaming) closeStream();
        else if (settingsOpen) toggleSettings();
        else super.onBackPressed();
    }

    // ================= networking =================
    private void connect() {
        String a = addr.getText().toString().trim();
        if (a.startsWith("ws://")) a = a.substring(5);
        if (a.length() == 0) { toast("Enter the PC address first"); return; }
        String host = a; int port = 8765;
        int i = a.lastIndexOf(':');
        if (i > 0) { host = a.substring(0, i); try { port = Integer.parseInt(a.substring(i + 1)); } catch (Exception e) { } }
        connected = false;
        ws = new WsClient(host, port, "/phone", new WsClient.Listener() {
            public void onStatus(String s) { connected = s.startsWith("Connected"); }
            public void onOpen() { handler.post(new Runnable() { public void run() { if (streaming) sendStreamStart(); }}); }
            public void onText(final String t) {
                if (t.startsWith("E ")) handler.post(new Runnable() { public void run() { toast(t.substring(2)); }});
            }
            public void onBinary(byte[] data) { if (streaming) streamView.pushJpeg(data); }
        });
        ws.start();
        connectBtn.setText("Disconnect");
    }

    private void disconnect() {
        if (streaming) closeStream();
        if (ws != null) ws.stop();
        ws = null;
        connected = false;
        connectBtn.setText("Connect");
    }

    // ================= AR session =================
    private boolean hasCamPerm() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAr() {
        try {
            Session existing = ar.getSession();
            if (existing != null) { existing.resume(); return; }
            ArCoreApk.InstallStatus st = ArCoreApk.getInstance().requestInstall(this, !installRequested);
            if (st == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { installRequested = true; arMsg = ""; return; }
            Session s = new Session(this);
            Config c = new Config(s);
            c.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            c.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            s.configure(c);
            ar.setSession(s);
            s.resume();
            arMsg = "";
        } catch (Exception e) {
            ar.setSession(null);
            arMsg = "ARCore unavailable (" + e.getClass().getSimpleName() + "). Using weaker accelerometer tracking.";
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (hasCamPerm()) startAr();
        else arMsg = "Camera permission denied. Using weaker accelerometer tracking.";
    }

    // ================= pose packet =================
    private static float wrap(float a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    private void pushPose() {
        if (ws == null || !haveRot) return;
        float[] r = rot;
        float[] R = { r[0], r[3], r[6] };
        float[] U = { r[1], r[4], r[7] };
        float[] L = { -r[2], -r[5], -r[8] };

        long nowNs = System.nanoTime();
        float hg = (float) Math.atan2(R[0], R[1]);
        if (prevHgNs != 0) {
            float dts = (nowNs - prevHgNs) * 1e-9f;
            if (dts > 0.001f) gyroRate = Math.abs(wrap(hg - prevHg)) / dts;
        }
        prevHg = hg; prevHgNs = nowNs;

        boolean track = b("track");
        int arState = 0;
        float px = 0, py = 0, pz = 0;
        boolean haveAr = ar.getSession() != null;
        if (haveAr) {
            float[] ap = new float[3], aright = new float[3], av = new float[3];
            long seq, pn; int st = ar.state;
            synchronized (ar.lock) {
                seq = ar.seq; pn = ar.poseNanos;
                System.arraycopy(ar.pos, 0, ap, 0, 3);
                System.arraycopy(ar.right, 0, aright, 0, 3);
                System.arraycopy(ar.vel, 0, av, 0, 3);
            }
            if (st == 1 && seq > 0) {
                if (seq != lastSeq) {
                    lastSeq = seq;
                    alignYaw(aright, R);
                    if (!haveRaw) { origin[0] = ap[0]; origin[1] = ap[1]; origin[2] = ap[2]; }
                }
                float maxPred = g("pred") * 1e-3f;
                float dtp = Math.max(0f, Math.min(maxPred, (nowNs - pn) * 1e-9f));
                for (int i = 0; i < 3; i++) lastRaw[i] = ap[i] + av[i] * dtp;
                haveRaw = true;
                arState = 1;
            } else arState = 2;
            if (haveRaw && track) {
                float[] rel = { lastRaw[0] - origin[0], lastRaw[1] - origin[1], lastRaw[2] - origin[2] };
                if (clutch) {
                    if (!clutchPrev) { frozen[0] = rel[0]; frozen[1] = rel[1]; frozen[2] = rel[2]; }
                    rel[0] = frozen[0]; rel[1] = frozen[1]; rel[2] = frozen[2];
                } else if (clutchPrev) {
                    for (int i = 0; i < 3; i++) origin[i] = lastRaw[i] - frozen[i];
                    rel[0] = frozen[0]; rel[1] = frozen[1]; rel[2] = frozen[2];
                }
                clutchPrev = clutch;
                px = rel[0]; py = rel[1]; pz = rel[2];
            }
        } else if (track) {
            px = pos[0]; py = pos[1]; pz = pos[2];
        }
        shownX = px; shownY = pz; shownZ = py;

        if (aligned && haveAr) {
            float c = alignC, s = alignS;
            float n = (float) Math.sqrt(c * c + s * s); if (n > 1e-6f) { c /= n; s /= n; }
            rotYaw(R, c, s); rotYaw(U, c, s); rotYaw(L, c, s);
        }

        float master = g("master");
        ws.setLatest(String.format(Locale.US,
            "P %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.1f %d %d %d %.4f %.4f %.4f %d %.3f %.3f %.3f %.2f %d %d %d %.2f %.2f %d %d %.2f",
            R[0], R[1], R[2], U[0], U[1], U[2], L[0], L[1], L[2],
            g("fov"), mode, recenter, camOn, px, py, pz, track ? 1 : 0,
            g("sx") * master, g("sy") * master, g("sz") * master, g("shake"), invMask(),
            b("acanchor") ? 1 : 0, arState, g("pshake"), g("sens"), b("hlock") ? 1 : 0, b("level") ? 1 : 0, g("carsm")));
    }

    private static void rotYaw(float[] v, float c, float s) {
        float e = v[0] * c + v[1] * s, n = -v[0] * s + v[1] * c;
        v[0] = e; v[1] = n;
    }

    private void alignYaw(float[] arRight, float[] gyroRight) {
        float la = (float) Math.hypot(arRight[0], arRight[1]);
        float lg = (float) Math.hypot(gyroRight[0], gyroRight[1]);
        if (la < 0.3f || lg < 0.3f) return;
        if (gyroRate > 0.5f) return;
        float d = wrap((float) Math.atan2(arRight[0], arRight[1]) - (float) Math.atan2(gyroRight[0], gyroRight[1]));
        if (!aligned) { alignC = (float) Math.cos(d); alignS = (float) Math.sin(d); aligned = true; }
        else {
            alignC += ((float) Math.cos(d) - alignC) * 0.08f;
            alignS += ((float) Math.sin(d) - alignS) * 0.08f;
        }
    }

    private int invMask() {
        String[] k = { "iyaw", "ipitch", "iroll", "ix", "iy", "iz" };
        int m = 0;
        for (int i = 0; i < 6; i++) if (b(k[i])) m |= (1 << i);
        return m;
    }

    // ================= lifecycle / sensors =================
    @Override protected void onResume() {
        super.onResume();
        glView.onResume();
        if (hasCamPerm()) startAr();
        else requestPermissions(new String[] { Manifest.permission.CAMERA }, 1);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s == null) { arMsg = "No rotation sensor on this phone"; return; }
        sm.registerListener(this, s, 10000);
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (a != null) sm.registerListener(this, a, 10000);
        lastAccNs = 0;
    }

    @Override protected void onPause() {
        super.onPause();
        sm.unregisterListener(this);
        glView.onPause();
        Session s = ar.getSession();
        if (s != null) { try { s.pause(); } catch (Exception e) { } }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (ws != null) { if (streaming) ws.send("V 0"); ws.stop(); }
        Session s = ar.getSession();
        if (s != null) { ar.setSession(null); try { s.close(); } catch (Exception e) { } }
        super.onDestroy();
    }

    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) { onLinearAccel(e); return; }
        float[] tmp = new float[9];
        SensorManager.getRotationMatrixFromVector(tmp, e.values);
        System.arraycopy(tmp, 0, rot, 0, 9);
        haveRot = true;
    }

    /** Fallback only: inertial position estimate when ARCore is not available. */
    private void onLinearAccel(SensorEvent e) {
        if (ar.getSession() != null || !haveRot || !b("track")) { lastAccNs = e.timestamp; return; }
        if (lastAccNs == 0) { lastAccNs = e.timestamp; return; }
        float dt = (e.timestamp - lastAccNs) * 1e-9f;
        lastAccNs = e.timestamp;
        if (dt <= 0f || dt > 0.1f) return;
        float ax = e.values[0], ay = e.values[1], az = e.values[2];
        float[] r = rot;
        float[] a = { r[0] * ax + r[1] * ay + r[2] * az, r[3] * ax + r[4] * ay + r[5] * az, r[6] * ax + r[7] * ay + r[8] * az };
        float[] c = new float[3];
        float mag = 0f;
        for (int i = 0; i < 3; i++) { c[i] = a[i] - bias[i]; mag += c[i] * c[i]; }
        mag = (float) Math.sqrt(mag);
        float th = 0.04f + 0.12f * g("shake");
        if (mag < th * 2f) stillTime += dt; else stillTime = 0f;
        boolean still = stillTime > 0.2f;
        if (still) { float k = Math.min(1f, dt); for (int i = 0; i < 3; i++) bias[i] += (a[i] - bias[i]) * k; }
        float damp = (float) Math.exp(-dt * (still ? 12f : 0.5f));
        float ret = (float) Math.exp(-dt * g("ret") * 0.6f);
        for (int i = 0; i < 3; i++) {
            float acc = mag < th ? 0f : c[i];
            vel[i] = (vel[i] + acc * dt) * damp;
            pos[i] = (pos[i] + vel[i] * dt) * ret;
            if (pos[i] > 2.5f) pos[i] = 2.5f; else if (pos[i] < -2.5f) pos[i] = -2.5f;
        }
    }

    public void onAccuracyChanged(Sensor s, int a) {}
}
