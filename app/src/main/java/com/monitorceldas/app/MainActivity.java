package com.monitorceldas.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.monitorceldas.app.Fmt.NA;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final String DASH = "—";

    private final Handler h = new Handler(Looper.getMainLooper());
    private String permMsg = "";
    private String[] segs, pts;

    // Cabecera y estado
    private TextView chipRec, chipGps, chipWifi, tvWarn;
    // Señal
    private TextView tvBig, tvBigUnit, tvQualBadge, tvTech, tvOper;
    private Tile tRsrq, tSinr, tRssi;
    private RsrpGraphView graph;
    // Registro
    private Button btnLog;
    private Tile tDur, tSamples, tHo;
    private TextView tvLogInfo;
    private Spinner spSeg, spPt;
    private Switch swIndoor;
    // Velocidad
    private Tile tDl, tUl, tLat;
    private TextView tvTestExtra, tvTestPhase, tvTraffic;
    private ProgressBar pbTest;
    private Button btnTest;
    private Switch swAuto;
    // Celda
    private final List<Kv> kvs = new ArrayList<>();
    private LinearLayout nrRow;
    // Vecinas
    private LinearLayout neighBox;
    private String neighSig = "";

    /** Recuadro con etiqueta, valor grande y calificación. */
    private static final class Tile {
        LinearLayout root;
        TextView label, value, sub;
    }

    /** Par etiqueta/valor de la tarjeta de celda. */
    private static final class Kv {
        LinearLayout root;
        TextView label, value;
    }

    // ===================================================================================== ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        checkPerms();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private void checkPerms() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        need.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) startSvc();
        else requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permMsg = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                    ? "" : "Sin permiso de teléfono: no se verá el tipo de red (LTE_CA / NR_NSA).";
            startSvc();
        } else {
            permMsg = "Falta el permiso de UBICACIÓN PRECISA (necesario para leer celdas y GPS). "
                    + "Actívalo en Ajustes > Apps > MonitorCeldas > Permisos.";
        }
    }

    private void startSvc() {
        try {
            startForegroundService(new Intent(this, LoggerService.class));
        } catch (Exception e) {
            permMsg = "No se pudo iniciar el servicio: " + e.getMessage();
        }
    }

    // ===================================================================================== helpers de UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int hgt, float weight) {
        return new LinearLayout.LayoutParams(w, hgt, weight);
    }

    private LinearLayout hRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout vCol() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = vCol();
        c.setBackground(round(Ui.CARD, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        p.bottomMargin = dp(12);
        parent.addView(c, p);
        if (title != null) {
            TextView t = text(12, Ui.ACCENT, true);
            t.setText(title);
            t.setLetterSpacing(0.08f);
            t.setPadding(0, 0, 0, dp(10));
            c.addView(t);
        }
        return c;
    }

    private TextView chip() {
        TextView t = text(12, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        return t;
    }

    private void setChip(TextView t, String s, int color) {
        t.setText(s);
        t.setBackground(round((color & 0x00FFFFFF) | 0x40000000, 14));
        t.setTextColor(color);
    }

    private Tile tile(LinearLayout row, String label, float valueSp) {
        Tile t = new Tile();
        t.root = vCol();
        t.root.setGravity(Gravity.CENTER);
        t.root.setBackground(round(Ui.CARD2, 14));
        t.root.setPadding(dp(6), dp(10), dp(6), dp(10));
        t.label = text(11, Ui.SUB, false);
        t.label.setText(label);
        t.label.setGravity(Gravity.CENTER);
        t.value = text(valueSp, Ui.TXT, true);
        t.value.setGravity(Gravity.CENTER);
        t.value.setText(DASH);
        t.sub = text(11, Ui.SUB, false);
        t.sub.setGravity(Gravity.CENTER);
        t.root.addView(t.label);
        t.root.addView(t.value);
        t.root.addView(t.sub);
        LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (row.getChildCount() > 0) p.leftMargin = dp(8);
        row.addView(t.root, p);
        return t;
    }

    private void setTile(Tile t, String value, int color, String sub) {
        t.value.setText(value == null || value.isEmpty() ? DASH : value);
        t.value.setTextColor(color);
        t.sub.setText(sub);
        t.sub.setTextColor(color == Ui.TXT ? Ui.SUB : color);
    }

    private Kv kv(LinearLayout row) {
        Kv k = new Kv();
        k.root = vCol();
        k.root.setPadding(0, dp(4), dp(8), dp(4));
        k.label = text(11, Ui.SUB, false);
        k.value = text(15, Ui.TXT, true);
        k.root.addView(k.label);
        k.root.addView(k.value);
        row.addView(k.root, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        kvs.add(k);
        return k;
    }

    private void setKv(int i, String label, String value) {
        Kv k = kvs.get(i);
        k.label.setText(label);
        k.value.setText(value == null || value.isEmpty() ? DASH : value);
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color, 14));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private Button smallBtn(String s, View.OnClickListener l) {
        Button b = button(s, Ui.CARD2, l);
        b.setTextSize(18);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setBackground(round(Ui.CARD2, 12));
        s.setPadding(dp(10), 0, dp(10), 0);
        return s;
    }

    /** Fila "Etiqueta  [◀] [ selector ] [▶]" */
    private Spinner selectorRow(LinearLayout parent, String label, String[] items) {
        LinearLayout r = hRow();
        r.setPadding(0, dp(6), 0, dp(6));
        TextView l = text(14, Ui.TXT, true);
        l.setText(label);
        r.addView(l, lp(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        final Spinner sp = spinner(items);
        Button prev = smallBtn("◀", x -> step(sp, -1));
        Button next = smallBtn("▶", x -> step(sp, 1));
        r.addView(prev, lp(dp(44), dp(44), 0));
        LinearLayout.LayoutParams sp1 = lp(0, dp(44), 1);
        sp1.leftMargin = dp(6);
        sp1.rightMargin = dp(6);
        r.addView(sp, sp1);
        r.addView(next, lp(dp(44), dp(44), 0));
        parent.addView(r);
        return sp;
    }

    private void step(Spinner sp, int d) {
        int n = sp.getCount();
        if (n == 0) return;
        int p = sp.getSelectedItemPosition() + d;
        if (p < 0) p = 0;
        if (p >= n) p = n - 1;
        sp.setSelection(p);
    }

    // ===================================================================================== construcción

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = vCol();
        root.setPadding(dp(12), dp(8), dp(12), dp(24));
        sv.addView(root);

        // ---------- barra superior con escudo UNMSM
        LinearLayout bar = hRow();
        bar.setPadding(dp(4), dp(4), 0, dp(10));
        LinearLayout titles = vCol();
        TextView title = text(24, Ui.TXT, true);
        title.setText("MonitorCeldas");
        TextView subtitle = text(12, Ui.GOLD, false);
        subtitle.setText("Drive Test · FIEE · UNMSM");
        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_unmsm);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Universidad Nacional Mayor de San Marcos");
        logo.setOnClickListener(x -> about());
        bar.addView(logo, lp(dp(84), dp(54), 0));
        root.addView(bar);

        // ---------- chips de estado
        LinearLayout chips = hRow();
        chipRec = chip();
        chipGps = chip();
        chipWifi = chip();
        TextView[] cs = {chipRec, chipGps, chipWifi};
        for (int i = 0; i < cs.length; i++) {
            LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) p.leftMargin = dp(6);
            chips.addView(cs[i], p);
        }
        root.addView(chips);
        tvWarn = text(13, Ui.REG, false);
        tvWarn.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(tvWarn);
        View spacer = new View(this);
        root.addView(spacer, lp(1, dp(10), 0));

        // ---------- tarjeta SEÑAL
        LinearLayout sig = card(root, "SEÑAL");
        LinearLayout big = hRow();
        tvBig = text(56, Ui.NONE, true);
        tvBig.setText(DASH);
        tvBig.setIncludeFontPadding(false);
        big.addView(tvBig);
        LinearLayout bigR = vCol();
        bigR.setPadding(dp(12), 0, 0, 0);
        tvBigUnit = text(13, Ui.SUB, false);
        tvBigUnit.setText("RSRP · dBm");
        tvQualBadge = chip();
        tvQualBadge.setTextSize(14);
        bigR.addView(tvBigUnit);
        LinearLayout.LayoutParams bp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        bp.topMargin = dp(4);
        bigR.addView(tvQualBadge, bp);
        big.addView(bigR);
        sig.addView(big);
        tvTech = text(16, Ui.TXT, true);
        tvTech.setPadding(0, dp(8), 0, 0);
        sig.addView(tvTech);
        tvOper = text(13, Ui.SUB, false);
        sig.addView(tvOper);
        LinearLayout tiles = hRow();
        tiles.setPadding(0, dp(12), 0, dp(10));
        tRsrq = tile(tiles, "RSRQ (dB)", 22);
        tSinr = tile(tiles, "SINR (dB)", 22);
        tRssi = tile(tiles, "RSSI (dBm)", 22);
        sig.addView(tiles);
        graph = new RsrpGraphView(this);
        graph.setBackground(round(Ui.CARD2, 12));
        sig.addView(graph, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(100), 0));
        TextView gl = text(11, Ui.SUB, false);
        gl.setText("RSRP de los últimos 3 minutos");
        gl.setPadding(0, dp(4), 0, 0);
        sig.addView(gl);

        // ---------- tarjeta REGISTRO
        LinearLayout reg = card(root, "REGISTRO DE RUTA");
        btnLog = button("▶  INICIAR REGISTRO", Ui.GREEN_BTN, x -> toggleLog());
        btnLog.setTextSize(19);
        reg.addView(btnLog, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(62), 0));
        tvLogInfo = text(12, Ui.SUB, false);
        tvLogInfo.setPadding(0, dp(8), 0, dp(4));
        reg.addView(tvLogInfo);
        LinearLayout st = hRow();
        st.setPadding(0, dp(4), 0, dp(8));
        tDur = tile(st, "Duración", 17);
        tSamples = tile(st, "Muestras", 17);
        tHo = tile(st, "Cambios de celda", 17);
        reg.addView(st);

        segs = Prefs.list(this, Prefs.SEGMENTS, Prefs.DEF_SEGMENTS);
        pts = Prefs.list(this, Prefs.POINTS, Prefs.DEF_POINTS);
        spSeg = selectorRow(reg, "Tramo", segs);
        spPt = selectorRow(reg, "Punto", pts);
        swIndoor = new Switch(this);
        swIndoor.setText("Estoy en interior");
        swIndoor.setTextColor(Ui.TXT);
        swIndoor.setTextSize(15);
        swIndoor.setPadding(0, dp(6), 0, 0);
        reg.addView(swIndoor);

        spSeg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                LoggerService s = LoggerService.instance;
                if (s != null) s.setSegment(pos == 0 ? "" : segs[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        spPt.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                LoggerService s = LoggerService.instance;
                if (s != null) s.setPoint(pos == 0 ? "" : pts[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        swIndoor.setOnCheckedChangeListener((b, checked) -> {
            LoggerService s = LoggerService.instance;
            if (s != null) s.setIndoor(checked);
        });

        // ---------- tarjeta VELOCIDAD
        LinearLayout spd = card(root, "PRUEBA DE VELOCIDAD");
        LinearLayout tt = hRow();
        tDl = tile(tt, "↓ Descarga", 22);
        tUl = tile(tt, "↑ Subida", 22);
        tLat = tile(tt, "Latencia", 22);
        spd.addView(tt);
        tvTestExtra = text(12, Ui.SUB, false);
        tvTestExtra.setPadding(0, dp(8), 0, 0);
        spd.addView(tvTestExtra);
        pbTest = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbTest.setMax(1000);
        LinearLayout.LayoutParams pbp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(8), 0);
        pbp.topMargin = dp(10);
        spd.addView(pbTest, pbp);
        tvTestPhase = text(13, Ui.ACCENT, true);
        spd.addView(tvTestPhase);
        btnTest = button("⚡  INICIAR PRUEBA", Ui.BLUE, x -> toggleTest());
        LinearLayout.LayoutParams btp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), 0);
        btp.topMargin = dp(10);
        spd.addView(btnTest, btp);
        swAuto = new Switch(this);
        swAuto.setTextColor(Ui.TXT);
        swAuto.setTextSize(14);
        swAuto.setPadding(0, dp(10), 0, 0);
        swAuto.setChecked(Prefs.bool(this, Prefs.AUTO_ON, false));
        swAuto.setOnCheckedChangeListener((b, c) -> Prefs.putBool(this, Prefs.AUTO_ON, c));
        spd.addView(swAuto);
        tvTraffic = text(12, Ui.SUB, false);
        tvTraffic.setPadding(0, dp(8), 0, 0);
        spd.addView(tvTraffic);

        // ---------- tarjeta CELDA
        LinearLayout cell = card(root, "CELDA SERVIDORA");
        for (int r = 0; r < 7; r++) {
            LinearLayout row = hRow();
            kv(row);
            kv(row);
            cell.addView(row);
            if (r == 5) nrRow = row;
        }

        // ---------- tarjeta VECINAS
        LinearLayout nb = card(root, "CELDAS VECINAS");
        neighBox = vCol();
        nb.addView(neighBox);

        // ---------- acciones
        LinearLayout a1 = hRow();
        Button bShare = button("📤  Compartir", Ui.CARD, x -> share());
        Button bSet = button("⚙  Ajustes", Ui.CARD, x -> settings());
        a1.addView(bShare, lp(0, dp(52), 1));
        LinearLayout.LayoutParams m = lp(0, dp(52), 1);
        m.leftMargin = dp(8);
        a1.addView(bSet, m);
        root.addView(a1);
        LinearLayout a2 = hRow();
        a2.setPadding(0, dp(8), 0, 0);
        Button bBat = button("🔋  Batería sin límites", Ui.CARD, x -> battery());
        Button bExit = button("⏻  Salir", Ui.CARD, x -> exit());
        a2.addView(bBat, lp(0, dp(52), 1));
        LinearLayout.LayoutParams m2 = lp(0, dp(52), 1);
        m2.leftMargin = dp(8);
        a2.addView(bExit, m2);
        root.addView(a2);

        TextView foot = text(11, Ui.SUB, false);
        foot.setGravity(Gravity.CENTER);
        foot.setText("MonitorCeldas " + BuildConfigInfo.VERSION + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + "\nArchivos en Descargas/" + OutFile.FOLDER);
        foot.setPadding(0, dp(14), 0, 0);
        root.addView(foot);

        setContentView(sv);
    }

    // ===================================================================================== acciones

    private void about() {
        LinearLayout v = vCol();
        v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.logo_unmsm_full);
        img.setAdjustViewBounds(true);
        img.setBackground(round(Color.WHITE, 12));
        img.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.addView(img, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        TextView t = text(14, Ui.TXT, false);
        t.setPadding(0, dp(14), 0, 0);
        t.setText("MonitorCeldas " + BuildConfigInfo.VERSION + "\n\n"
                + "Herramienta de drive test: parámetros de celda LTE / 5G NR / 3G / 2G, GPS y throughput "
                + "(descarga, subida, latencia, jitter y pérdida) en un solo registro.\n\n"
                + "Facultad de Ingeniería Electrónica y Eléctrica\nUniversidad Nacional Mayor de San Marcos");
        v.addView(t);
        new AlertDialog.Builder(this).setView(v).setPositiveButton("Cerrar", null).show();
    }

    private void toggleLog() {
        LoggerService s = LoggerService.instance;
        if (s == null) {
            toast("El servicio aún no inicia (revisa permisos).");
            checkPerms();
            return;
        }
        if (!s.logging) {
            if (!s.startLogging()) toast(s.lastError);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("¿Detener el registro?")
                    .setMessage(s.samples + " muestras en " + Fmt.dur(System.currentTimeMillis() - s.logStart)
                            + ".\nSe guardarán los CSV y el KML.")
                    .setPositiveButton("Detener y guardar", (d, w) -> s.stopLogging())
                    .setNegativeButton("Seguir registrando", null)
                    .show();
        }
        refresh.run();
    }

    private void toggleTest() {
        LoggerService s = LoggerService.instance;
        if (s == null) return;
        if (s.testRunning) s.cancelTest();
        else s.startTest(false);
        refresh.run();
    }

    private void share() {
        LoggerService s = LoggerService.instance;
        if (s == null || s.lastUris.isEmpty()) {
            toast("Aún no hay archivos: detén un registro primero. También están en Descargas/" + OutFile.FOLDER);
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("*/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<Uri>(s.lastUris));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir registro"));
    }

    private void battery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("Listo: la app ya está exenta del ahorro de batería.");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void exit() {
        LoggerService s = LoggerService.instance;
        Runnable doExit = () -> {
            stopService(new Intent(this, LoggerService.class));
            finish();
        };
        if (s != null && s.logging) {
            new AlertDialog.Builder(this)
                    .setTitle("Hay un registro en curso")
                    .setMessage("Se detendrá y guardará antes de salir.")
                    .setPositiveButton("Detener y salir", (d, w) -> doExit.run())
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            doExit.run();
        }
    }

    private void settings() {
        final String[][] f = {
                {"Intervalo de muestreo (ms)", Prefs.LOG_MS, "1000", "n"},
                {"Duración de cada fase DL/UL (s)", Prefs.TEST_DUR, "10", "n"},
                {"Conexiones simultáneas", Prefs.STREAMS, "4", "n"},
                {"Prueba automática cada (m)", Prefs.AUTO_DIST, "250", "n"},
                {"Prueba automática cada (min, 0 = no)", Prefs.AUTO_MIN, "0", "n"},
                {"Precisión GPS máx. para KML (m)", Prefs.KML_ACC, "40", "n"},
                {"URL de descarga", Prefs.DL_URL, Prefs.DEF_DL, "t"},
                {"URL de subida (POST)", Prefs.UL_URL, Prefs.DEF_UL, "t"},
                {"URL de latencia HTTP", Prefs.LAT_URL, Prefs.DEF_LAT, "t"},
                {"Host para ping ICMP", Prefs.PING_HOST, Prefs.DEF_PING, "t"},
                {"Tramos (separados por |)", Prefs.SEGMENTS, Prefs.DEF_SEGMENTS, "m"},
                {"Puntos fijos (separados por |)", Prefs.POINTS, Prefs.DEF_POINTS, "m"},
        };
        LinearLayout v = vCol();
        v.setPadding(dp(18), dp(8), dp(18), dp(8));
        final EditText[] eds = new EditText[f.length];
        for (int i = 0; i < f.length; i++) {
            TextView l = text(12, Ui.SUB, false);
            l.setText(f[i][0]);
            l.setPadding(0, dp(8), 0, 0);
            v.addView(l);
            EditText e = new EditText(this);
            e.setText(Prefs.str(this, f[i][1], f[i][2]));
            e.setTextSize(14);
            if ("n".equals(f[i][3])) e.setInputType(InputType.TYPE_CLASS_NUMBER);
            else if ("t".equals(f[i][3])) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            eds[i] = e;
            v.addView(e);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(sv)
                .setPositiveButton("Guardar", (d, w) -> {
                    for (int i = 0; i < f.length; i++) Prefs.put(this, f[i][1], eds[i].getText().toString().trim());
                    reloadLists();
                    toast("Ajustes guardados");
                })
                .setNeutralButton("Valores por defecto", (d, w) -> {
                    for (String[] x : f) Prefs.put(this, x[1], x[2]);
                    reloadLists();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void reloadLists() {
        segs = Prefs.list(this, Prefs.SEGMENTS, Prefs.DEF_SEGMENTS);
        pts = Prefs.list(this, Prefs.POINTS, Prefs.DEF_POINTS);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, segs);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSeg.setAdapter(a);
        ArrayAdapter<String> b = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pts);
        b.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPt.setAdapter(b);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ===================================================================================== refresco

    private static int indexOf(String[] arr, String v) {
        if (v == null || v.isEmpty()) return 0;
        for (int i = 1; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    private static String nn(String s) {
        return s == null || s.isEmpty() ? DASH : s;
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            h.removeCallbacks(this);
            try {
                update();
            } catch (Exception ignored) {
            }
            h.postDelayed(this, 500);
        }
    };

    private void update() {
        LoggerService s = LoggerService.instance;
        int autoDist = Prefs.num(this, Prefs.AUTO_DIST, 250);
        swAuto.setText("Prueba automática cada " + autoDist + " m (solo registrando)");
        if (s == null) {
            setChip(chipRec, "Iniciando…", Ui.NONE);
            setChip(chipGps, "GPS", Ui.NONE);
            setChip(chipWifi, "WiFi", Ui.NONE);
            tvWarn.setText(permMsg);
            tvWarn.setVisibility(permMsg.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }

        // Sincronizar selectores con el servicio
        int si = indexOf(segs, s.segment);
        if (si >= 0 && spSeg.getSelectedItemPosition() != si) spSeg.setSelection(si);
        int pi = indexOf(pts, s.point);
        if (pi >= 0 && spPt.getSelectedItemPosition() != pi) spPt.setSelection(pi);
        if (swIndoor.isChecked() != s.indoor) swIndoor.setChecked(s.indoor);

        // ---------- chips
        long logMs = System.currentTimeMillis() - s.logStart;
        if (s.logging) setChip(chipRec, "● REC " + Fmt.dur(logMs), Ui.BAD);
        else setChip(chipRec, "○ Sin registrar", Ui.SUB);
        if (s.locValid() && s.loc.hasAccuracy()) {
            float acc = s.loc.getAccuracy();
            setChip(chipGps, "GPS ±" + Math.round(acc) + " m", acc <= 20 ? Ui.EXC : Ui.REG);
        } else {
            setChip(chipGps, "GPS sin señal", Ui.REG);
        }
        if (s.wifiActive) setChip(chipWifi, "WiFi ACTIVO", Ui.BAD);
        else setChip(chipWifi, "WiFi apagado", Ui.EXC);

        StringBuilder w = new StringBuilder();
        if (!permMsg.isEmpty()) w.append(permMsg).append('\n');
        if (s.wifiActive) w.append("⚠ Apaga el WiFi para que todo el tráfico y el ping vayan por la red móvil.\n");
        if (!s.lastError.isEmpty()) w.append("⚠ ").append(s.lastError).append('\n');
        tvWarn.setText(w.toString().trim());
        tvWarn.setVisibility(w.length() == 0 ? View.GONE : View.VISIBLE);

        // ---------- señal
        CellReader.Snapshot snap = s.snap;
        CellReader.CellData c = snap != null ? snap.serving : null;
        boolean lte = c != null && c.tech.equals("LTE"), nr = c != null && c.tech.equals("NR");
        boolean w3 = c != null && c.tech.equals("WCDMA"), g2 = c != null && c.tech.equals("GSM");
        int main = c == null ? NA : (c.rsrp != NA ? c.rsrp : (c.rscp != NA ? c.rscp : c.rssi));
        int mainColor = Ui.rsrpColor(main);
        tvBig.setText(main == NA ? DASH : Integer.toString(main));
        tvBig.setTextColor(mainColor);
        tvBigUnit.setText((c == null ? "RSRP" : (nr ? "SS-RSRP" : w3 ? "RSCP" : g2 ? "RSSI" : "RSRP")) + " · dBm");
        setChip(tvQualBadge, c == null ? "Sin servicio" : Ui.rsrpLabel(main), mainColor);
        if (c != null) {
            StringBuilder tl = new StringBuilder(c.tech);
            if (!c.bandLabel().isEmpty()) tl.append(" · ").append(c.bandLabel());
            if (!Double.isNaN(c.dlMhz)) tl.append(" · ").append(Fmt.d(c.dlMhz, 1)).append(" MHz");
            if (c.pci != NA) tl.append(" · ").append(w3 ? "PSC " : g2 ? "BSIC " : "PCI ").append(c.pci);
            tvTech.setText(tl.toString());
        } else {
            tvTech.setText("Sin servicio");
        }
        tvOper.setText(nn(s.operator) + " (" + nn(s.mccMnc) + ") · datos " + nn(s.netType)
                + (s.display.isEmpty() ? "" : " · " + s.display));

        if (w3) {
            tRsrq.label.setText("Ec/No (dB)");
            setTile(tRsrq, c == null ? "" : Fmt.i(c.ecno), Ui.TXT, "");
            tSinr.label.setText("RSCP (dBm)");
            setTile(tSinr, Fmt.i(c.rscp), Ui.rsrpColor(c.rscp), "");
            tRssi.label.setText("UARFCN");
            setTile(tRssi, Fmt.i(c.arfcn), Ui.TXT, "");
        } else {
            tRsrq.label.setText(nr ? "SS-RSRQ (dB)" : "RSRQ (dB)");
            tSinr.label.setText(nr ? "SS-SINR (dB)" : "SINR (dB)");
            tRssi.label.setText(g2 ? "TA" : "RSSI (dBm)");
            int rq = c == null ? NA : c.rsrq, sn = c == null ? NA : c.sinr;
            setTile(tRsrq, Fmt.i(rq), Ui.rsrqColor(rq), Ui.rsrqLabel(rq));
            setTile(tSinr, Fmt.i(sn), Ui.sinrColor(sn), Ui.sinrLabel(sn));
            if (g2) setTile(tRssi, Fmt.i(c.ta), Ui.TXT, "");
            else setTile(tRssi, c == null ? "" : Fmt.i(c.rssi), Ui.TXT, c == null || c.rssi == NA ? "no reportado" : "");
        }
        graph.setData(s.history());

        // ---------- registro
        if (s.logging) {
            btnLog.setText("■  DETENER REGISTRO");
            btnLog.setBackground(round(Ui.RED_BTN, 14));
            tvLogInfo.setText("Archivo: " + s.baseName + "\nMarca el tramo y el punto fijo al llegar a cada uno.");
            setTile(tDur, Fmt.dur(logMs), Ui.TXT, "");
            setTile(tSamples, Integer.toString(s.samples), Ui.TXT, "");
            setTile(tHo, Integer.toString(s.hoCount), Ui.TXT, "");
        } else {
            btnLog.setText("▶  INICIAR REGISTRO");
            btnLog.setBackground(round(Ui.GREEN_BTN, 14));
            tvLogInfo.setText(s.exportMsg.isEmpty()
                    ? "Antes de iniciar: 1) apaga el WiFi  2) espera GPS  3) elige el tramo."
                    : "✔ " + s.exportMsg);
            setTile(tDur, DASH, Ui.SUB, "");
            setTile(tSamples, DASH, Ui.SUB, "");
            setTile(tHo, DASH, Ui.SUB, "");
        }

        // ---------- velocidad
        SpeedTest.Result r = s.lastResult;
        if (s.testRunning) {
            boolean dl = "Descarga".equals(s.testPhase), ul = "Subida".equals(s.testPhase);
            String live = Fmt.d(s.testLive, 1);
            setTile(tDl, dl ? live : (ul && r != null ? "" : DASH), dl ? Ui.ACCENT : Ui.SUB, dl ? "midiendo…" : "Mbps");
            setTile(tUl, ul ? live : DASH, ul ? Ui.ACCENT : Ui.SUB, ul ? "midiendo…" : "Mbps");
            setTile(tLat, DASH, Ui.SUB, s.testPhase.equals("Latencia") || s.testPhase.isEmpty() ? "midiendo…" : "ms");
            pbTest.setVisibility(View.VISIBLE);
            int dur = Prefs.testDur(this) * 1000;
            long el = SystemClock.elapsedRealtime() - s.testPhaseStart;
            int prog;
            if (dl) prog = 100 + (int) Math.min(450, 450L * el / dur);
            else if (ul) prog = 550 + (int) Math.min(450, 450L * el / dur);
            else prog = (int) Math.min(100, el / 40);
            pbTest.setProgress(prog);
            tvTestPhase.setText(s.testStatus);
            tvTestPhase.setVisibility(View.VISIBLE);
            btnTest.setText("✕  CANCELAR PRUEBA");
            btnTest.setBackground(round(Ui.RED_BTN, 14));
        } else {
            pbTest.setVisibility(View.GONE);
            tvTestPhase.setVisibility(View.GONE);
            btnTest.setText("⚡  INICIAR PRUEBA");
            btnTest.setBackground(round(Ui.BLUE, 14));
            if (r != null) {
                setTile(tDl, Fmt.d(r.dlMbps, 1), Ui.dlColor(r.dlMbps), "Mbps");
                setTile(tUl, Fmt.d(r.ulMbps, 1), Ui.dlColor(r.ulMbps), "Mbps");
                setTile(tLat, Fmt.d(r.latencyMs, 0), Ui.TXT, "ms");
            } else {
                setTile(tDl, DASH, Ui.SUB, "Mbps");
                setTile(tUl, DASH, Ui.SUB, "Mbps");
                setTile(tLat, DASH, Ui.SUB, "ms");
            }
        }
        if (r != null) {
            String e = "T" + r.id + " · " + Fmt.hms(r.endMs) + " · jitter " + nn(Fmt.d(r.jitterMs, 1)) + " ms"
                    + (Double.isNaN(r.lossPct) ? "" : " · pérdida " + Fmt.d(r.lossPct, 0) + " %")
                    + (r.latMethod.isEmpty() ? "" : " · " + r.latMethod);
            if (r.error.length() > 0) e += "\n⚠ " + r.error;
            tvTestExtra.setText(e);
        } else {
            tvTestExtra.setText("Mide descarga, subida, latencia, jitter y pérdida por la red móvil.");
        }
        String traffic = "Tráfico móvil ahora: ↓ " + nn(Fmt.d(s.rxKbps / 1000.0, 2)) + " Mbps   ↑ "
                + nn(Fmt.d(s.txKbps / 1000.0, 2)) + " Mbps";
        if (s.logging && Prefs.bool(this, Prefs.AUTO_ON, false) && !s.testRunning) {
            traffic += String.format(Locale.US, "\nPróxima prueba automática en %.0f m", Math.max(0, autoDist - s.distSinceTest));
        }
        tvTraffic.setText(traffic);

        // ---------- celda servidora
        if (c != null) {
            String ch = lte ? "EARFCN" : nr ? "NR-ARFCN" : w3 ? "UARFCN" : "ARFCN";
            setKv(0, "Banda", c.bandLabel());
            setKv(1, ch, Fmt.i(c.arfcn));
            setKv(2, "Frecuencia DL", Double.isNaN(c.dlMhz) ? "" : Fmt.d(c.dlMhz, 1) + " MHz");
            setKv(3, "Ancho de banda", c.bwKhz == NA ? "" : Fmt.d(c.bwKhz / 1000.0, 0) + " MHz");
            setKv(4, w3 ? "PSC" : g2 ? "BSIC" : "PCI", Fmt.i(c.pci));
            setKv(5, lte || nr ? "TAC" : "LAC", Fmt.i(c.tac));
            setKv(6, lte ? "ECI" : nr ? "NCI" : "CID", Fmt.l(c.cid));
            setKv(7, lte ? "eNB - sector" : "", lte && c.cid != Fmt.NAL ? c.enb() + " - " + c.sector() : "");
            setKv(8, "CQI", Fmt.i(c.cqi));
            setKv(9, "Timing Advance", Fmt.i(c.ta));
        } else {
            for (int i = 0; i < 10; i++) setKv(i, i == 0 ? "Celda" : "", i == 0 ? "sin servicio" : "");
        }
        CellReader.CellData n5 = snap != null ? snap.nr : null;
        if (n5 != null && n5 != c) {
            nrRow.setVisibility(View.VISIBLE);
            setKv(10, "5G NR (NSA)", n5.bandLabel() + "  PCI " + nn(Fmt.i(n5.pci)));
            setKv(11, "SS-RSRP / SINR", nn(Fmt.i(n5.rsrp)) + " / " + nn(Fmt.i(n5.sinr)));
        } else {
            nrRow.setVisibility(View.GONE);
        }
        if (s.loc != null) {
            double age = (SystemClock.elapsedRealtime() - s.locElapsed) / 1000.0;
            setKv(12, "GPS (hace " + Fmt.d(age, 0) + " s)",
                    Fmt.d(s.loc.getLatitude(), 6) + ", " + Fmt.d(s.loc.getLongitude(), 6));
            setKv(13, "Velocidad / edad dato celda",
                    (s.loc.hasSpeed() ? Fmt.d(s.loc.getSpeed() * 3.6, 0) : "?") + " km/h / "
                            + (c != null && c.ageMs != Fmt.NAL ? Fmt.d(c.ageMs / 1000.0, 1) + " s" : DASH));
        } else {
            setKv(12, "GPS", "buscando…");
            setKv(13, "Edad dato celda", c != null && c.ageMs != Fmt.NAL ? Fmt.d(c.ageMs / 1000.0, 1) + " s" : "");
        }

        // ---------- vecinas
        updateNeighbors(snap, c);
    }

    private void updateNeighbors(CellReader.Snapshot snap, CellReader.CellData c) {
        boolean w3 = c != null && c.tech.equals("WCDMA");
        StringBuilder sig = new StringBuilder();
        if (snap != null) {
            int n = 0;
            for (CellReader.CellData x : snap.neighbors) {
                if (n++ >= 8) break;
                sig.append(x.key()).append(x.level()).append(x.rsrq).append(x.ecno).append('|');
            }
        }
        String sg = sig.toString();
        if (sg.equals(neighSig)) return;
        neighSig = sg;
        neighBox.removeAllViews();
        if (snap == null || snap.neighbors.isEmpty()) {
            TextView t = text(13, Ui.SUB, false);
            t.setText("El teléfono no reporta celdas vecinas en este momento.");
            neighBox.addView(t);
            return;
        }
        String[] hdr = w3 ? new String[]{"PSC", "UARFCN", "Banda", "RSCP", "Ec/No"}
                : new String[]{"PCI", "ARFCN", "Banda", "RSRP", "RSRQ"};
        neighBox.addView(neighRow(hdr, null, true));
        int n = 0;
        for (CellReader.CellData x : snap.neighbors) {
            if (n++ >= 8) break;
            int lvl = x.level();
            String[] vals = {nn(Fmt.i(x.pci)), nn(Fmt.i(x.arfcn)), nn(x.bandLabel()),
                    lvl == -999 ? DASH : Integer.toString(lvl), nn(Fmt.i(w3 ? x.ecno : x.rsrq))};
            neighBox.addView(neighRow(vals, lvl == -999 ? null : Ui.rsrpColor(lvl), false));
        }
    }

    private LinearLayout neighRow(String[] vals, Integer levelColor, boolean header) {
        LinearLayout r = hRow();
        r.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (!header) r.setBackground(round(Ui.CARD2, 10));
        for (int i = 0; i < vals.length; i++) {
            TextView t = text(header ? 11 : 14, header ? Ui.SUB : Ui.TXT, !header && i == 3);
            t.setText(vals[i]);
            if (!header && i == 3 && levelColor != null) t.setTextColor(levelColor);
            r.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout wrap = vCol();
        wrap.addView(r);
        wrap.setPadding(0, 0, 0, dp(4));
        return wrap;
    }
}
