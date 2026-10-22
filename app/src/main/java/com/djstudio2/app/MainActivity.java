package com.djstudio2.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity implements Player.Listener {

    private static final int REQ_OPEN = 1;
    private static final String[] MODE_NAMES = {
            "360° (ekvirektanguláris MP4)",
            "Dupla fisheye – 2 külön sáv (OSV)",
            "Dupla fisheye – egymás mellett",
            "Lapos / nyers kép"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GLSurfaceView glView;
    private PanoRenderer renderer;
    private Surface[] surfaces;
    private Player player;

    private Uri currentUri;
    private String fileName = "";
    private Uri pendingUri;
    private long pendingStartUs;

    private View topBar, bottomBar;
    private TextView playBtn, timeText, durText, infoText, hintText, modeBtn;
    private SeekBar seekBar;
    private boolean userSeeking;
    private boolean showInfo;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("lens", MODE_PRIVATE);

        renderer = new PanoRenderer(s -> ui.post(() -> onSurfacesReady(s)));
        loadLensPrefs();

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setupGestures();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        hintText = new TextView(this);
        hintText.setText("DJ Studio 2 – 360° lejátszó\n\nKoppints a „Megnyitás” gombra, és válassz\nOSV, LRF vagy MP4 fájlt.");
        hintText.setTextColor(Color.WHITE);
        hintText.setTextSize(18);
        hintText.setGravity(Gravity.CENTER);
        root.addView(hintText, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildTopBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        root.addView(buildBottomBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(12);
        infoText.setBackgroundColor(0x99000000);
        infoText.setPadding(dp(10), dp(8), dp(10), dp(8));
        infoText.setVisibility(View.GONE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ilp.topMargin = dp(64);
        ilp.leftMargin = dp(12);
        root.addView(infoText, ilp);

        setContentView(root);

        Intent i = getIntent();
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            pendingUri = i.getData();
        }
        ui.post(ticker);
    }

    // ---------- Felület ----------

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackground(gradient(true));
        bar.addView(button("Megnyitás", v -> openPicker()));
        modeBtn = button("Mód", v -> chooseMode());
        bar.addView(modeBtn);
        bar.addView(button("Lencse", v -> lensDialog()));
        bar.addView(button("Info", v -> {
            showInfo = !showInfo;
            infoText.setVisibility(showInfo ? View.VISIBLE : View.GONE);
        }));
        bar.addView(button("Nézet ⟲", v -> resetView()));
        topBar = bar;
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(12), dp(8), dp(12));
        bar.setBackground(gradient(false));

        bar.addView(button("−10", v -> skip(-10_000_000L)));
        playBtn = button("▶", v -> togglePlay());
        playBtn.setTextSize(22);
        bar.addView(playBtn);
        bar.addView(button("+10", v -> skip(10_000_000L)));

        timeText = label("0:00");
        bar.addView(timeText);
        seekBar = new SeekBar(this);
        bar.addView(seekBar, new LinearLayout.LayoutParams(0, -2, 1f));
        durText = label("0:00");
        bar.addView(durText);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeText.setText(fmt(p * 1000L));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; touchControls(); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                if (player != null) player.seekTo(s.getProgress() * 1000L);
                touchControls();
            }
        });
        bottomBar = bar;
        return bar;
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> { touchControls(); l.onClick(v); });
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setPadding(dp(6), 0, dp(6), 0);
        return t;
    }

    private GradientDrawable gradient(boolean top) {
        return new GradientDrawable(
                top ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- Gesztusok ----------

    private void setupGestures() {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (renderer.mode == PanoRenderer.MODE_FLAT) return false;
                float degPerPx = renderer.fovDeg / Math.max(1, glView.getHeight());
                renderer.yawDeg -= dx * degPerPx;
                renderer.pitchDeg = clamp(renderer.pitchDeg - dy * degPerPx, -89f, 89f);
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                boolean visible = topBar.getVisibility() == View.VISIBLE;
                setControlsVisible(!visible);
                if (!visible) touchControls();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlay();
                return true;
            }
        });
        ScaleGestureDetector sd = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        renderer.fovDeg = clamp(renderer.fovDeg / d.getScaleFactor(), 30f, 130f);
                        return true;
                    }
                });
        glView.setOnTouchListener((v, ev) -> {
            sd.onTouchEvent(ev);
            if (!sd.isInProgress()) gd.onTouchEvent(ev);
            return true;
        });
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void resetView() {
        renderer.yawDeg = 0;
        renderer.pitchDeg = 0;
        renderer.fovDeg = 90;
    }

    private final Runnable hideControls = () -> {
        if (player != null && player.isPlaying()) setControlsVisible(false);
    };

    private void touchControls() {
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, 4000);
    }

    private void setControlsVisible(boolean v) {
        int vis = v ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);
    }

    // ---------- Fájl és lejátszás ----------

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            openUri(u, 0, true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openUri(intent.getData(), 0, true);
        }
    }

    private void onSurfacesReady(Surface[] s) {
        boolean reopen = player != null;
        long pos = reopen ? player.positionUs() : 0;
        releasePlayer();
        glView.queueEvent(renderer::releaseStale);
        surfaces = s;
        if (reopen && currentUri != null) {
            openUri(currentUri, pos, false);
        } else if (pendingUri != null) {
            Uri u = pendingUri;
            pendingUri = null;
            openUri(u, pendingStartUs, true);
        }
    }

    private void openUri(Uri u, long startUs, boolean autoPlay) {
        if (surfaces == null) {
            pendingUri = u;
            pendingStartUs = startUs;
            return;
        }
        releasePlayer();
        currentUri = u;
        fileName = queryName(u);
        hintText.setText("Betöltés…\n" + fileName);
        hintText.setVisibility(View.VISIBLE);
        player = new Player(this, u, surfaces, this);
        player.prepare(startUs, autoPlay);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        String p = u.getLastPathSegment();
        return p != null ? p : u.toString();
    }

    @Override
    public void onPrepared(Player p) {
        if (p != player) return;
        hintText.setVisibility(View.GONE);
        Player.TrackInfo t = p.tracks.get(0);
        renderer.textureCount = p.tracks.size();
        renderer.videoAspect = (float) t.width / t.height;
        setMode(detectMode(p, t));
        seekBar.setMax((int) (p.durationUs / 1000));
        durText.setText(fmt(p.durationUs));
        resetView();
        touchControls();

        StringBuilder warn = new StringBuilder();
        for (Player.TrackInfo ti : p.tracks) {
            if (!ti.hardware) warn.append("• Nincs hardveres dekóder ehhez: ").append(ti.mime).append('\n');
            else if (!ti.rateSupported) warn.append("• A dekóder hivatalosan nem bírja: ")
                    .append(ti.width).append('×').append(ti.height)
                    .append(ti.fps > 0 ? String.format(Locale.US, " @%.0f fps", ti.fps) : "").append('\n');
            if (!ti.twoInstances) warn.append("• A telefon nem enged két ilyen dekódert egyszerre\n");
        }
        if (warn.length() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Figyelem: lehet, hogy akadni fog")
                    .setMessage(warn + "\nA lejátszó a lemaradó kockákat eldobja, hogy a kép és a hang " +
                            "szinkronban maradjon. Ha nagyon akad, próbáld ki a kamera .LRF (előnézeti) " +
                            "fájlját, vagy vegyél fel 6K/30 fps-sel.")
                    .setPositiveButton("Rendben", null)
                    .show();
        }
    }

    private int detectMode(Player p, Player.TrackInfo t) {
        String n = fileName.toLowerCase(Locale.ROOT);
        float a = (float) t.width / t.height;
        if (p.tracks.size() >= 2) return PanoRenderer.MODE_DUAL;
        boolean twoToOne = Math.abs(a - 2f) < 0.05f;
        if (twoToOne && (n.endsWith(".osv") || n.endsWith(".lrf"))) return PanoRenderer.MODE_SBS;
        if (twoToOne) return PanoRenderer.MODE_EQUIRECT;
        return PanoRenderer.MODE_FLAT;
    }

    private void setMode(int m) {
        renderer.mode = m;
        modeBtn.setText(m == PanoRenderer.MODE_FLAT ? "Mód: lapos" : "Mód: 360°");
    }

    private void chooseMode() {
        new AlertDialog.Builder(this)
                .setTitle("Megjelenítési mód")
                .setSingleChoiceItems(MODE_NAMES, renderer.mode, (d, which) -> {
                    setMode(which);
                    d.dismiss();
                })
                .show();
    }

    private void togglePlay() {
        if (player == null) { openPicker(); return; }
        if (player.isPlaying()) player.pause();
        else player.play();
        touchControls();
    }

    private void skip(long deltaUs) {
        if (player != null) player.seekTo(player.positionUs() + deltaUs);
    }

    @Override
    public void onError(String msg) {
        hintText.setVisibility(View.GONE);
        new AlertDialog.Builder(this)
                .setTitle("Nem sikerült lejátszani")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    public void onCompleted() {
        setControlsVisible(true);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            Player p = player;
            if (p != null) {
                long pos = p.positionUs();
                if (!userSeeking) {
                    seekBar.setProgress((int) (pos / 1000));
                    timeText.setText(fmt(pos));
                }
                playBtn.setText(p.isPlaying() ? "⏸" : "▶");
                if (showInfo) infoText.setText(describe(p));
            }
            ui.postDelayed(this, 250);
        }
    };

    private String describe(Player p) {
        StringBuilder sb = new StringBuilder();
        sb.append(fileName).append('\n');
        int k = 1;
        for (Player.TrackInfo t : p.tracks) {
            sb.append(p.tracks.size() > 1 ? "Lencse " + k++ : "Videó").append(": ")
                    .append(t.mime.replace("video/", "")).append(' ')
                    .append(t.width).append('×').append(t.height);
            if (t.fps > 0) sb.append(String.format(Locale.US, " @%.0f fps", t.fps));
            sb.append("\n   ").append(t.decoder).append(t.hardware ? " (HW)" : " (SW)")
                    .append(t.rateSupported ? " ✓" : " ⚠").append('\n');
        }
        sb.append("Hang: ").append(p.audioInfo).append('\n');
        sb.append("Mód: ").append(MODE_NAMES[renderer.mode]).append('\n');
        sb.append("Eldobott képkockák: ").append(p.droppedFrames());
        return sb.toString();
    }

    private static String fmt(long us) {
        long s = us / 1_000_000L;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---------- Lencse beállítások ----------

    private void loadLensPrefs() {
        renderer.lensFovDeg = prefs.getFloat("fov", 195f);
        renderer.circleScale = prefs.getFloat("circle", 1.0f);
        renderer.blendDeg = prefs.getFloat("blend", 3f);
        renderer.frontRollDeg = prefs.getFloat("frontRoll", 0f);
        renderer.backRollDeg = prefs.getFloat("backRoll", 0f);
        renderer.swapLenses = prefs.getBoolean("swap", false);
    }

    private void saveLensPrefs() {
        prefs.edit()
                .putFloat("fov", renderer.lensFovDeg)
                .putFloat("circle", renderer.circleScale)
                .putFloat("blend", renderer.blendDeg)
                .putFloat("frontRoll", renderer.frontRollDeg)
                .putFloat("backRoll", renderer.backRollDeg)
                .putBoolean("swap", renderer.swapLenses)
                .apply();
    }

    private interface FloatSetter { void set(float v); }

    private void addSlider(LinearLayout parent, String title, float min, float max, float step,
                           float value, String unit, FloatSetter setter) {
        TextView t = new TextView(this);
        t.setTextSize(14);
        parent.addView(t);
        SeekBar sb = new SeekBar(this);
        int steps = Math.round((max - min) / step);
        sb.setMax(steps);
        sb.setProgress(Math.round((value - min) / step));
        Runnable upd = () -> t.setText(String.format(Locale.US, "%s: %.1f%s", title, min + sb.getProgress() * step, unit));
        upd.run();
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                setter.set(min + p * step);
                upd.run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(sb, new LinearLayout.LayoutParams(-1, -2));
    }

    private void lensDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        addSlider(box, "Lencse látószöge", 170, 230, 0.5f, renderer.lensFovDeg, "°", v -> renderer.lensFovDeg = v);
        addSlider(box, "Képkör mérete", 0.80f, 1.15f, 0.005f, renderer.circleScale, "×", v -> renderer.circleScale = v);
        addSlider(box, "Varrási átmenet", 0, 10, 0.5f, renderer.blendDeg, "°", v -> renderer.blendDeg = v);
        addSlider(box, "Első lencse forgatása", -180, 180, 0.5f, renderer.frontRollDeg, "°", v -> renderer.frontRollDeg = v);
        addSlider(box, "Hátsó lencse forgatása", -180, 180, 0.5f, renderer.backRollDeg, "°", v -> renderer.backRollDeg = v);
        CheckBox swap = new CheckBox(this);
        swap.setText("Első / hátsó lencse csere");
        swap.setChecked(renderer.swapLenses);
        swap.setOnCheckedChangeListener((b, c) -> renderer.swapLenses = c);
        box.addView(swap);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box, new ViewGroup.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle("Lencse / varrás")
                .setView(sv)
                .setPositiveButton("Kész", (d, w) -> saveLensPrefs())
                .setNeutralButton("Alapértékek", (d, w) -> {
                    prefs.edit().clear().apply();
                    loadLensPrefs();
                })
                .setOnCancelListener(d -> saveLensPrefs())
                .show();
    }

    // ---------- Életciklus ----------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    @Override
    protected void onPause() {
        if (player != null) player.pause();
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
