package com.monitorceldas.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.monitorceldas.app.Fmt.NA;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView tvBig, tvQual, tvTech, tvWarn, tvLog, tvTest, tvInfo, tvNeigh;
    private Button btnLog, btnTest;
    private Spinner spSeg, spPt;
    private CheckBox cbIndoor, cbAuto;
    private RsrpGraphView graph;
    private String[] segs, pts;
    private String permMsg = "";

    // ========================================================================= ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        if (missing.isEmpty()) {
            startSvc();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            permMsg = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                    ? "" : "Sin permiso de teléfono: no se verá el tipo de red (LTE_CA / NR_NSA).";
            startSvc();
        } else {
            permMsg = "La app necesita el permiso de UBICACIÓN PRECISA para leer las celdas y el GPS. "
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

    // ========================================================================= interfaz

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView mono() {
        TextView t = text(13, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(13, true);
        t.setText(s);
        t.setTextColor(0xFF4FC3F7);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : vs) {
            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return r;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(v);

        // Cabecera: RSRP grande + tecnología
        tvBig = text(46, true);
        tvBig.setText("—");
        tvTech = text(18, true);
        tvQual = text(15, false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        col.addView(tvTech);
        col.addView(tvQual);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tvBig);
        top.addView(col);
        v.addView(top);

        graph = new RsrpGraphView(this);
        v.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));

        tvWarn = text(13, true);
        tvWarn.setTextColor(0xFFFFA726);
        v.addView(tvWarn);

        // Registro
        btnLog = button("▶  INICIAR REGISTRO", x -> toggleLog());
        btnLog.setTextSize(18);
        v.addView(btnLog, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        tvLog = text(13, false);
        v.addView(tvLog);

        // Tramo / punto fijo / interior
        segs = Prefs.list(this, Prefs.SEGMENTS, Prefs.DEF_SEGMENTS);
        pts = Prefs.list(this, Prefs.POINTS, Prefs.DEF_POINTS);
        TextView lSeg = text(14, false);
        lSeg.setText("Tramo");
        spSeg = spinner(segs);
        LinearLayout rSeg = new LinearLayout(this);
        rSeg.setGravity(Gravity.CENTER_VERTICAL);
        rSeg.addView(lSeg, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));
        rSeg.addView(spSeg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        v.addView(rSeg);

        TextView lPt = text(14, false);
        lPt.setText("Punto");
        spPt = spinner(pts);
        cbIndoor = new CheckBox(this);
        cbIndoor.setText("Interior");
        LinearLayout rPt = new LinearLayout(this);
        rPt.setGravity(Gravity.CENTER_VERTICAL);
        rPt.addView(lPt, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT));
        rPt.addView(spPt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rPt.addView(cbIndoor);
        v.addView(rPt);

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
        cbIndoor.setOnCheckedChangeListener((b, checked) -> {
            LoggerService s = LoggerService.instance;
            if (s != null) s.setIndoor(checked);
        });

        // Throughput
        v.addView(header("THROUGHPUT"));
        btnTest = button("⚡ Prueba de velocidad", x -> toggleTest());
        cbAuto = new CheckBox(this);
        cbAuto.setChecked(Prefs.bool(this, Prefs.AUTO_ON, false));
        cbAuto.setOnCheckedChangeListener((b, c) -> Prefs.putBool(this, Prefs.AUTO_ON, c));
        v.addView(row(btnTest, cbAuto));
        tvTest = text(14, false);
        v.addView(tvTest);

        // Detalle
        v.addView(header("CELDA SERVIDORA"));
        tvInfo = mono();
        v.addView(tvInfo);
        v.addView(header("VECINAS"));
        tvNeigh = mono();
        v.addView(tvNeigh);

        v.addView(header("ARCHIVOS Y AJUSTES"));
        v.addView(row(button("Compartir", x -> share()), button("Ajustes", x -> settings())));
        v.addView(row(button("Batería sin límites", x -> battery()), button("Salir", x -> exit())));
        TextView foot = text(11, false);
        foot.setTextColor(Color.GRAY);
        foot.setText("MonitorCeldas " + BuildConfigInfo.VERSION + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + "\nArchivos en Descargas/" + OutFile.FOLDER + " (CSV separado por ';', hora ISO −05:00)");
        foot.setPadding(0, dp(10), 0, 0);
        v.addView(foot);

        setContentView(sv);
    }

    // ========================================================================= acciones

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
                    .setMessage(s.samples + " muestras en " + Fmt.dur(System.currentTimeMillis() - s.logStart))
                    .setPositiveButton("Detener", (d, w) -> s.stopLogging())
                    .setNegativeButton("Seguir", null)
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
            toast("Ya está exenta del ahorro de batería.");
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
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(18), dp(8), dp(18), dp(8));
        final EditText[] eds = new EditText[f.length];
        for (int i = 0; i < f.length; i++) {
            TextView l = text(12, false);
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

    // ========================================================================= refresco

    private static int indexOf(String[] arr, String v) {
        if (v == null || v.isEmpty()) return 0;
        for (int i = 1; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    private static String qualRsrp(int v) {
        if (v >= -80) return "Excelente";
        if (v >= -90) return "Buena";
        if (v >= -100) return "Regular";
        return "Mala";
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
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
        cbAuto.setText("Auto cada " + Prefs.num(this, Prefs.AUTO_DIST, 250) + " m");
        if (s == null) {
            tvWarn.setText(permMsg.isEmpty() ? "Iniciando servicio…" : permMsg);
            return;
        }

        // Sincronizar controles con el servicio
        int si = indexOf(segs, s.segment);
        if (si >= 0 && spSeg.getSelectedItemPosition() != si) spSeg.setSelection(si);
        int pi = indexOf(pts, s.point);
        if (pi >= 0 && spPt.getSelectedItemPosition() != pi) spPt.setSelection(pi);
        if (cbIndoor.isChecked() != s.indoor) cbIndoor.setChecked(s.indoor);

        CellReader.Snapshot snap = s.snap;
        CellReader.CellData c = snap != null ? snap.serving : null;

        // Cabecera
        int main = c == null ? NA : (c.rsrp != NA ? c.rsrp : (c.rscp != NA ? c.rscp : c.rssi));
        if (main != NA) {
            tvBig.setText(Integer.toString(main));
            tvBig.setTextColor(RsrpGraphView.colorFor(main));
            String metric = c.rsrp != NA ? (c.tech.equals("NR") ? "SS-RSRP" : "RSRP") : (c.rscp != NA ? "RSCP" : "RSSI");
            tvQual.setText(metric + " dBm · " + qualRsrp(main));
        } else {
            tvBig.setText("—");
            tvBig.setTextColor(Color.GRAY);
            tvQual.setText(c == null ? "Sin servicio / sin datos" : "Nivel no reportado");
        }
        tvTech.setText(c == null ? dash(s.operator)
                : dash(s.operator) + " · " + c.tech + " " + c.bandLabel() + (s.display.isEmpty() ? "" : " · " + s.display));
        graph.setData(s.history());

        // Avisos
        StringBuilder w = new StringBuilder();
        if (!permMsg.isEmpty()) w.append(permMsg).append('\n');
        if (!s.locValid()) w.append("GPS sin posición reciente (en interiores es normal).\n");
        if (s.wifiActive) w.append("WiFi activo: apágalo durante la medición (el ping ICMP se reemplaza por latencia HTTP).\n");
        if (!s.lastError.isEmpty()) w.append(s.lastError).append('\n');
        tvWarn.setText(w.toString().trim());
        tvWarn.setVisibility(w.length() == 0 ? View.GONE : View.VISIBLE);

        // Registro
        if (s.logging) {
            btnLog.setText("■  DETENER REGISTRO");
            btnLog.setBackgroundColor(0xFFC62828);
            btnLog.setTextColor(Color.WHITE);
            tvLog.setText(String.format(Locale.US, "● %s · %d muestras · %s\n%s", Fmt.dur(System.currentTimeMillis() - s.logStart),
                    s.samples, s.hoCount + " cambios de celda", s.baseName));
        } else {
            btnLog.setText("▶  INICIAR REGISTRO");
            btnLog.setBackgroundColor(0xFF2E7D32);
            btnLog.setTextColor(Color.WHITE);
            tvLog.setText(s.exportMsg.isEmpty() ? "Sin registrar. La pantalla en vivo funciona igual." : s.exportMsg);
        }

        // Throughput
        btnTest.setText(s.testRunning ? "✕ Cancelar prueba" : "⚡ Prueba de velocidad");
        StringBuilder t = new StringBuilder();
        if (!s.testStatus.isEmpty()) t.append(s.testStatus).append('\n');
        if (s.lastResult != null) {
            t.append("Última: ").append(SpeedTest.fmt(s.lastResult));
            if (!s.lastResult.latMethod.isEmpty()) t.append(" (").append(s.lastResult.latMethod).append(")");
            if (s.lastResult.error.length() > 0) t.append("\n⚠ ").append(s.lastResult.error);
            t.append('\n');
        }
        t.append("Tráfico móvil: ↓ ").append(dash(Fmt.d(s.rxKbps / 1000.0, 2))).append(" Mbps  ↑ ")
                .append(dash(Fmt.d(s.txKbps / 1000.0, 2))).append(" Mbps");
        if (s.logging && Prefs.bool(this, Prefs.AUTO_ON, false)) {
            t.append(String.format(Locale.US, "\nPróxima prueba auto: %.0f / %d m", s.distSinceTest,
                    Prefs.num(this, Prefs.AUTO_DIST, 250)));
        }
        tvTest.setText(t.toString());

        // Detalle celda
        StringBuilder b = new StringBuilder();
        b.append(pad("Operador", 10)).append(dash(s.operator)).append(" (").append(dash(s.mccMnc)).append(")\n");
        b.append(pad("Red", 10)).append(c == null ? "—" : c.tech).append("  datos: ").append(dash(s.netType));
        if (!s.display.isEmpty()) b.append("  ").append(s.display);
        b.append('\n');
        if (c != null) {
            boolean lte = c.tech.equals("LTE"), nr = c.tech.equals("NR"), w3 = c.tech.equals("WCDMA"), g2 = c.tech.equals("GSM");
            String ch = lte ? "EARFCN" : nr ? "NRARFCN" : w3 ? "UARFCN" : "ARFCN";
            b.append(pad("Banda", 10)).append(dash(c.bandLabel())).append("  ").append(ch).append(' ')
                    .append(dash(Fmt.i(c.arfcn))).append("  ").append(dash(Fmt.d(c.dlMhz, 1))).append(" MHz");
            if (c.bwKhz != NA) b.append("  BW ").append(Fmt.d(c.bwKhz / 1000.0, 1)).append(" MHz");
            b.append('\n');
            b.append(pad(w3 ? "PSC" : g2 ? "BSIC" : "PCI", 10)).append(dash(Fmt.i(c.pci)))
                    .append("   ").append(lte || nr ? "TAC " : "LAC ").append(dash(Fmt.i(c.tac))).append('\n');
            b.append(pad(lte ? "ECI" : nr ? "NCI" : "CID", 10)).append(dash(Fmt.l(c.cid)));
            if (lte && c.cid != Fmt.NAL) b.append("  eNB ").append(c.enb()).append('-').append(c.sector());
            b.append('\n');
            if (lte || nr) {
                b.append(String.format(Locale.US, "RSRP %s dBm   RSRQ %s dB\n", dash(Fmt.i(c.rsrp)), dash(Fmt.i(c.rsrq))));
                b.append(String.format(Locale.US, "RSSI %s dBm   SINR %s dB\n", dash(Fmt.i(c.rssi)), dash(Fmt.i(c.sinr))));
                b.append(String.format(Locale.US, "CQI %s   TA %s\n", dash(Fmt.i(c.cqi)), dash(Fmt.i(c.ta))));
            } else if (w3) {
                b.append(String.format(Locale.US, "RSCP %s dBm   Ec/No %s dB\n", dash(Fmt.i(c.rscp)), dash(Fmt.i(c.ecno))));
            } else if (g2) {
                b.append(String.format(Locale.US, "RSSI %s dBm   TA %s\n", dash(Fmt.i(c.rssi)), dash(Fmt.i(c.ta))));
            }
            if (c.ageMs != Fmt.NAL) b.append(pad("Edad dato", 10)).append(Fmt.d(c.ageMs / 1000.0, 1)).append(" s\n");
        }
        CellReader.CellData nr = snap != null ? snap.nr : null;
        if (nr != null && (c == null || nr != c)) {
            b.append(pad("5G NR", 10)).append(dash(nr.bandLabel())).append(" ARFCN ").append(dash(Fmt.i(nr.arfcn)))
                    .append(" PCI ").append(dash(Fmt.i(nr.pci))).append('\n');
            b.append(String.format(Locale.US, "          SS-RSRP %s  RSRQ %s  SINR %s\n",
                    dash(Fmt.i(nr.rsrp)), dash(Fmt.i(nr.rsrq)), dash(Fmt.i(nr.sinr))));
        }
        if (s.loc != null) {
            double age = (SystemClock.elapsedRealtime() - s.locElapsed) / 1000.0;
            b.append(pad("GPS", 10)).append(Fmt.d(s.loc.getLatitude(), 6)).append(", ").append(Fmt.d(s.loc.getLongitude(), 6)).append('\n');
            b.append(pad("", 10)).append("±").append(s.loc.hasAccuracy() ? Fmt.d(s.loc.getAccuracy(), 0) : "?").append(" m  ")
                    .append(s.loc.hasSpeed() ? Fmt.d(s.loc.getSpeed() * 3.6, 0) : "?").append(" km/h  hace ")
                    .append(Fmt.d(age, 0)).append(" s");
        } else {
            b.append(pad("GPS", 10)).append("buscando…");
        }
        tvInfo.setText(b.toString());

        // Vecinas
        StringBuilder nb = new StringBuilder();
        if (snap == null || snap.neighbors.isEmpty()) {
            nb.append("(el teléfono no reporta vecinas)");
        } else {
            boolean w3 = c != null && c.tech.equals("WCDMA");
            nb.append(w3 ? "PSC   UARFCN  Banda  RSCP  Ec/No\n" : "PCI   ARFCN   Banda  RSRP  RSRQ\n");
            int n = 0;
            for (CellReader.CellData x : snap.neighbors) {
                if (n++ >= 10) break;
                int lvl = x.level();
                nb.append(pad(dash(Fmt.i(x.pci)), 6)).append(pad(dash(Fmt.i(x.arfcn)), 8))
                        .append(pad(dash(x.bandLabel()), 7)).append(pad(lvl == -999 ? "—" : Integer.toString(lvl), 6))
                        .append(dash(Fmt.i(w3 ? x.ecno : x.rsrq))).append('\n');
            }
        }
        tvNeigh.setText(nb.toString().trim());
    }
}
