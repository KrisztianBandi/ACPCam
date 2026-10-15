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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    // ---- palette ----
    static final int BG = 0xFF0E1014, CARD = 0xFF181B22, CARD2 = 0xFF242833, ACCENT = 0xFF4C8DFF,
            GOOD = 0xFF2FBF71, WARN = 0xFFFFB020, BAD = 0xFFFF5A4F, TEXT = 0xFFE8EAF0, MUTED = 0xFF8A90A0;

    private WsClient ws;
    private EditText addr;
    private TextView connPill, arChip, modeChip, posText, statusLine, previewOffText;
    private Button connectBtn, modeCar, modeWorld, camToggle, blL, blH, blR, photoBtn, holdBtn;
    private GLSurfaceView glView;
    private LinearLayout previewHeader, dock, settingsContent;
    private ScrollView cameraPage, settingsPage;
    private TextView tabCamera, tabSettings;
    private Switch previewSwitch;
    private SensorManager sm;
    private SharedPreferences prefs;
    private final ArTracker ar = new ArTracker();
    private boolean installRequested = false;
    private String arMsg = "";
    private int tab = 0;

    // settings
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
            "iyaw", "ipitch", "iroll", "ix", "iy", "iz", "ret" };
    private static final float[] DEFS = { 1, 1, 1, 1, 1, 0.3f, 0.3f, 50, 1, 0,
            0, 0, 0, 0.7f, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 0.25f };

    private float g(String k) { Float v = cfg.get(k); return v == null ? 0f : v; }
    private boolean b(String k) { return g(k) > 0.5f; }

    private void loadSettings() {
        for (int i = 0; i < KEYS.length; i++) cfg.put(KEYS[i], prefs.getFloat(KEYS[i], DEFS[i]));
    }

    private void setCfg(String key, float v) {
        cfg.put(key, v);
        prefs.edit().putFloat(key, v).apply();
        if (key.equals("track") && v < 0.5f) { for (int i = 0; i < 3; i++) { pos[i] = 0; vel[i] = 0; } }
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
        x.setPadding(dp(8), dp(4), dp(8), dp(4));
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

    private void chip(TextView t, String text, int color) {
        t.setText(text); t.setTextColor(Color.WHITE); t.setTextSize(12f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(rr(color, 20));
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
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
        buildUi();
        ar.previewOn = b("preview");
        applyPreviewLayout();
        handler.post(sender);
        handler.post(uiTick);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(30), dp(10), dp(6));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(tv("AC PhoneCam", 22f, TEXT, true));
        titles.addView(tv("Phone camera for Assetto Corsa", 11f, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        connPill = new TextView(this);
        chip(connPill, "Not connected", 0xFF3A3F4B);
        header.addView(connPill);
        root.addView(header, lp(-1, -2, 0, 4, 0, 4, 8));

        // connection row
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
        root.addView(conn, lp(-1, -2, 0, 0, 0, 0, 8));

        // preview header (camera tab only) + GL view (always attached & visible so AR keeps running)
        previewHeader = new LinearLayout(this);
        previewHeader.setGravity(Gravity.CENTER_VERTICAL);
        previewHeader.setBackground(rr(CARD, 14));
        previewHeader.setPadding(dp(14), dp(8), dp(10), dp(8));
        previewHeader.addView(tv("Camera preview", 15f, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        previewSwitch = new Switch(this);
        previewSwitch.setChecked(b("preview"));
        tintSwitch(previewSwitch);
        previewSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton bv, boolean on) {
                setCfg("preview", on ? 1f : 0f); ar.previewOn = on; applyPreviewLayout();
            }});
        previewHeader.addView(previewSwitch);
        root.addView(previewHeader, lp(-1, -2, 0, 0, 0, 0, 6));

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(ar);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, lp(-1, dp(150), 0, 0, 0, 0, 0));
        previewOffText = tv("Preview off  -  position tracking is still running", 12f, MUTED, false);
        previewOffText.setGravity(Gravity.CENTER);
        previewOffText.setPadding(0, dp(4), 0, dp(4));
        root.addView(previewOffText);

        // pages
        FrameLayout pages = new FrameLayout(this);
        cameraPage = new ScrollView(this);
        cameraPage.addView(buildCameraPage());
        settingsPage = new ScrollView(this);
        settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        buildSettings();
        settingsPage.addView(settingsContent);
        pages.addView(cameraPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1f));

        // fixed action dock (camera tab)
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dockRow = new LinearLayout(this);
        Button recBtn = mkBtn("Recenter", CARD2, TEXT, 15f);
        recBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doRecenter(); }});
        photoBtn = mkBtn("\uD83D\uDCF7  Photo", ACCENT, Color.WHITE, 16f);
        photoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onPhoto(); }});
        dockRow.addView(recBtn, lp(0, dp(54), 1f, 0, 0, 4, 0));
        dockRow.addView(photoBtn, lp(0, dp(54), 1.4f, 4, 0, 0, 0));
        dock.addView(dockRow, lp(-1, -2, 0, 0, 6, 0, 6));
        holdBtn = mkBtn("HOLD  to reposition", 0xFF3A2F12, WARN, 15f);
        holdBtn.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (b("cltoggle")) clutch = !clutch; else clutch = true;
                v.setPressed(true); refreshHold();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (!b("cltoggle")) clutch = false;
                v.setPressed(false); refreshHold();
            }
            return true;
        }});
        dock.addView(holdBtn, lp(-1, dp(66), 0, 0, 0, 0, 6));
        root.addView(dock);

        // tab bar
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(rr(CARD, 16));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabCamera = tabView("Camera");
        tabSettings = tabView("Settings");
        tabCamera.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(0); }});
        tabSettings.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(1); }});
        tabs.addView(tabCamera, new LinearLayout.LayoutParams(0, dp(44), 1f));
        tabs.addView(tabSettings, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(tabs, lp(-1, -2, 0, 0, 2, 0, 0));

        statusLine = tv("", 11f, MUTED, false);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.setPadding(0, dp(4), 0, 0);
        root.addView(statusLine);

        setContentView(root);
        showTab(0);
        refreshMode(); refreshBlinkers(); refreshHold(); refreshCamToggle();
    }

    private TextView tabView(String s) {
        TextView t = tv(s, 14f, MUTED, true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout buildCameraPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        // status card
        LinearLayout st = card();
        LinearLayout chips = new LinearLayout(this);
        arChip = new TextView(this); chip(arChip, "AR: starting", 0xFF3A3F4B);
        modeChip = new TextView(this); chip(modeChip, "Car", 0xFF3A3F4B);
        chips.addView(arChip, lp(-2, -2, 0, 0, 0, 8, 0));
        chips.addView(modeChip);
        st.addView(chips);
        posText = tv("X 0.00   Y 0.00   Z 0.00 m", 12f, MUTED, false);
        posText.setTypeface(Typeface.MONOSPACE);
        posText.setPadding(0, dp(8), 0, 0);
        st.addView(posText);
        p.addView(st, lp(-1, -2, 0, 0, 4, 0, 8));

        // mode + camera on/off
        LinearLayout mc = card();
        mc.addView(tv("Anchor", 12f, MUTED, true));
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(rr(CARD2, 14));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        modeCar = mkBtn("Car", ACCENT, Color.WHITE, 14f);
        modeWorld = mkBtn("World", ACCENT, Color.WHITE, 14f);
        modeCar.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { mode = 0; refreshMode(); }});
        modeWorld.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { mode = 1; refreshMode(); }});
        seg.addView(modeCar, new LinearLayout.LayoutParams(0, dp(42), 1f));
        seg.addView(modeWorld, new LinearLayout.LayoutParams(0, dp(42), 1f));
        mc.addView(seg, lp(-1, -2, 0, 0, 6, 0, 8));
        TextView hint = tv("Car: camera rides with the car.  World: camera stays fixed in the world (replays).", 11f, MUTED, false);
        mc.addView(hint);
        camToggle = mkBtn("Camera ON", GOOD, Color.WHITE, 15f);
        camToggle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            camOn = 1 - camOn; refreshCamToggle(); }});
        mc.addView(camToggle, lp(-1, dp(48), 0, 0, 10, 0, 0));
        p.addView(mc, lp(-1, -2, 0, 0, 0, 0, 8));

        // blinkers
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
        p.addView(bl, lp(-1, -2, 0, 0, 0, 0, 8));
        return p;
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

    private void tintSwitch(Switch s) {
        int[][] st = { { android.R.attr.state_checked }, {} };
        s.setThumbTintList(new ColorStateList(st, new int[] { ACCENT, 0xFFB0B5C0 }));
        s.setTrackTintList(new ColorStateList(st, new int[] { 0xFF2A4A85, 0xFF3A3F4B }));
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
            public void onCheckedChanged(CompoundButton bv, boolean on) {
                setCfg(key, on ? 1f : 0f);
                if (key.equals("cltoggle")) { clutch = false; refreshHold(); }
            }});
        row.addView(s);
        c.addView(row, lp(-1, -2, 0, 0, 10, 0, 0));
    }

    private String fmt(int type, float v) {
        switch (type) {
            case 1: return v < 20f ? "Auto" : String.format(Locale.US, "%.0f\u00B0", v);
            case 2: return String.format(Locale.US, "x%.2f", v);
            case 3: return String.format(Locale.US, "%.0f ms", v);
            case 4: return v < 0.5f ? "Instant" : String.format(Locale.US, "%.0f s", v);
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
                if (user) setCfg(key, v);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        c.addView(sb);
    }

    private void buildSettings() {
        settingsContent.removeAllViews();
        LinearLayout c = section("3D movement");
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
        c.addView(tv("Sends the Steam screenshot key (F12 by default) to the PC. Set the key in the PC app.", 11f, MUTED, false),
                lp(-1, -2, 0, 0, 8, 0, 0));

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
            previewSwitch.setChecked(true); ar.previewOn = true; applyPreviewLayout();
            buildSettings(); toast("Settings reset");
        }});
        settingsContent.addView(reset, lp(-1, dp(48), 0, 0, 10, 0, 24));
    }

    // ================= UI state =================
    private void showTab(int t) {
        tab = t;
        cameraPage.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(t == 1 ? View.VISIBLE : View.GONE);
        dock.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        tabCamera.setBackground(t == 0 ? rr(ACCENT, 12) : null);
        tabCamera.setTextColor(t == 0 ? Color.WHITE : MUTED);
        tabSettings.setBackground(t == 1 ? rr(ACCENT, 12) : null);
        tabSettings.setTextColor(t == 1 ? Color.WHITE : MUTED);
        applyPreviewLayout();
    }

    /** The GL surface must stay visible or ARCore stops, so "hidden" = a 4 dp strip. */
    private void applyPreviewLayout() {
        boolean show = tab == 0 && b("preview");
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) glView.getLayoutParams();
        p.height = show ? dp(150) : dp(4);
        glView.setLayoutParams(p);
        previewHeader.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        previewOffText.setVisibility(tab == 0 && !b("preview") ? View.VISIBLE : View.GONE);
    }

    private void refreshMode() {
        paint(modeCar, mode == 0 ? ACCENT : CARD2, mode == 0 ? Color.WHITE : MUTED);
        paint(modeWorld, mode == 1 ? ACCENT : CARD2, mode == 1 ? Color.WHITE : MUTED);
        modeCar.setBackground(mode == 0 ? pressable(ACCENT, 11) : null);
        modeWorld.setBackground(mode == 1 ? pressable(ACCENT, 11) : null);
        chip(modeChip, mode == 0 ? "Car anchor" : "World anchor", 0xFF3A4A70);
    }

    private void refreshCamToggle() {
        camToggle.setText(camOn == 1 ? "Camera ON" : "Camera OFF  (AC has control)");
        paint(camToggle, camOn == 1 ? GOOD : 0xFF3A3F4B, Color.WHITE);
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

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void updateStatus() {
        // AR chip
        if (ar.getSession() == null) chip(arChip, arMsg.length() > 0 ? "AR unavailable - inertial mode" : "AR: starting...", arMsg.length() > 0 ? WARN : 0xFF3A3F4B);
        else if (ar.state == 1) chip(arChip, "AR tracking", GOOD);
        else chip(arChip, "AR searching...", WARN);
        posText.setText(String.format(Locale.US, "X %+.2f   Y %+.2f   Z %+.2f m", shownX, shownY, shownZ));
        String s = "";
        if (ar.getSession() != null && ar.state != 1)
            s = "Move slowly, point at textured surfaces in good light" +
                (ar.failure.length() > 0 && !ar.failure.equals("NONE") ? "  [" + ar.failure + "]" : "");
        else if (ar.getSession() == null && arMsg.length() > 0) s = arMsg;
        statusLine.setText(s);
    }

    // ================= actions =================
    private void doRecenter() {
        recenter++;
        if (haveRaw) { origin[0] = lastRaw[0]; origin[1] = lastRaw[1]; origin[2] = lastRaw[2]; }
        frozen[0] = frozen[1] = frozen[2] = 0;
        for (int i = 0; i < 3; i++) { pos[i] = 0; vel[i] = 0; }
        toast("Recentered");
    }

    private void onPhoto() {
        if (ws == null) { toast("Connect to the PC first"); return; }
        if (photoCountdown > 0) {                     // second tap cancels the timer
            photoCountdown = 0; handler.removeCallbacks(photoTick);
            photoBtn.setText("\uD83D\uDCF7  Photo"); return;
        }
        int t = Math.round(g("ptimer"));
        if (t <= 0) { shoot(); return; }
        photoCountdown = t;
        photoBtn.setText(photoCountdown + "  (tap to cancel)");
        handler.postDelayed(photoTick, 1000);
    }

    private final Runnable photoTick = new Runnable() { public void run() {
        photoCountdown--;
        if (photoCountdown <= 0) { photoBtn.setText("\uD83D\uDCF7  Photo"); shoot(); }
        else { photoBtn.setText(photoCountdown + "  (tap to cancel)"); handler.postDelayed(this, 1000); }
    }};

    private void shoot() {
        if (ws != null) { ws.send("B P"); toast("Photo key sent to PC"); }
    }

    // ================= networking =================
    private void connect() {
        String a = addr.getText().toString().trim();
        if (a.startsWith("ws://")) a = a.substring(5);
        if (a.length() == 0) { toast("Enter the PC address first"); return; }
        String host = a; int port = 8765;
        int i = a.lastIndexOf(':');
        if (i > 0) { host = a.substring(0, i); try { port = Integer.parseInt(a.substring(i + 1)); } catch (Exception e) { } }
        chip(connPill, "Connecting...", WARN);
        ws = new WsClient(host, port, "/phone", new WsClient.Listener() {
            public void onStatus(final String s) { handler.post(new Runnable() { public void run() {
                if (ws == null) return;
                if (s.startsWith("Connected")) chip(connPill, "Connected", GOOD);
                else if (s.startsWith("Connecting")) chip(connPill, "Connecting...", WARN);
                else chip(connPill, "Retrying...", BAD);
            }}); }
        });
        ws.start();
        connectBtn.setText("Disconnect");
    }

    private void disconnect() {
        if (ws != null) ws.stop();
        ws = null;
        connectBtn.setText("Connect");
        chip(connPill, "Not connected", 0xFF3A3F4B);
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
        shownX = px; shownY = pz; shownZ = py;   // display as X right, Y forward, Z up

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
        if (ws != null) ws.stop();
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
