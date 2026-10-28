package com.monitorceldas.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.monitorceldas.app.Fmt.NA;

/**
 * Servicio en primer plano: lee celda + GPS cada segundo (alineado al segundo del reloj),
 * escribe los CSV y ejecuta las pruebas de velocidad. Sigue funcionando con la pantalla apagada.
 */
public class LoggerService extends Service {

    static volatile LoggerService instance;

    private static final String CH = "registro";
    private static final int NID = 1;
    static final long GPS_MAX_AGE_MS = 10_000;

    static final String H_SAMPLES = "timestamp;epoch_ms;gps_time;lat;lon;gps_acc_m;alt_m;speed_kmh;gps_age_s;"
            + "operator;mcc_mnc;tech;net_type;display;band;arfcn;dl_mhz;pci;cell_id;enb;sector;tac;"
            + "rsrp;rsrq;rssi;sinr;cqi;ta;bw_mhz;rscp;ecno;"
            + "nr_band;nr_arfcn;nr_pci;nr_ss_rsrp;nr_ss_rsrq;nr_ss_sinr;"
            + "n_vecinas;v1_pci;v1_arfcn;v1_rsrp;v1_rsrq;v2_pci;v2_arfcn;v2_rsrp;v2_rsrq;v3_pci;v3_arfcn;v3_rsrp;v3_rsrq;"
            + "rx_kbps;tx_kbps;cell_age_ms;segmento;punto;indoor;evento;"
            + "test_id;dl_mbps;ul_mbps;latency_ms;jitter_ms;loss_pct";
    static final String H_NEIGH = "timestamp;lat;lon;segmento;punto;indoor;serv_pci;serv_arfcn;"
            + "tech;band;arfcn;pci;rsrp;rsrq;rssi;sinr;rscp;ecno;cell_age_ms";
    static final String H_EVENTS = "timestamp;lat;lon;segmento;punto;indoor;evento;detalle";
    static final String H_TESTS = "test_id;inicio;fin;lat;lon;gps_acc_m;segmento;punto;indoor;operator;tech;band;arfcn;"
            + "pci;cell_id;rsrp;rsrq;sinr;dl_mbps;ul_mbps;latency_ms;jitter_ms;loss_pct;metodo_latencia;red;"
            + "dl_bytes;ul_bytes;servidor_dl;servidor_ul;error";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TelephonyManager tm;
    private LocationManager lm;
    private ConnectivityManager cm;
    private NotificationManager nm;
    private PhoneStateListener psl;
    private LocationListener locListener;
    private ConnectivityManager.NetworkCallback cellCb;
    private PowerManager.WakeLock wl;
    private boolean started;

    // ---- estado en vivo (se lee desde MainActivity en el hilo principal)
    List<CellInfo> cells;
    SignalStrength ss;
    int overrideType;
    Location loc;
    long locElapsed;
    CellReader.Snapshot snap;
    String operator = "", mccMnc = "", netType = "", display = "";
    double rxKbps = Double.NaN, txKbps = Double.NaN;
    private long prevRx = -1, prevTx = -1, prevTrafT;
    Network cellNet;
    boolean wifiActive;
    String segment = "", point = "";
    boolean indoor;
    final float[] hist = new float[180];
    int histPos;
    private String lastKey = "", lastTech = "";
    int hoCount;
    private int tickCount;

    // ---- registro
    boolean logging;
    long logStart;
    int samples;
    String baseName = "";
    String lastError = "";
    String exportMsg = "";
    private OutFile fSamples, fNeigh, fEvents, fTests;
    final ArrayList<Uri> lastUris = new ArrayList<>();
    private final ArrayList<KmlExporter.Pt> track = new ArrayList<>();
    private final ArrayList<KmlExporter.TestPt> testPts = new ArrayList<>();
    private final StringBuilder pendingEvent = new StringBuilder();

    // ---- pruebas de velocidad
    private SpeedTest current;
    private int testSeq;
    boolean testRunning;
    String testStatus = "";
    SpeedTest.Result lastResult;
    private SpeedTest.Result pendingResult;
    double distSinceTest;
    private Location lastDistLoc;
    private long lastTestEnd;
    private KmlExporter.TestPt runningPt;
    private String runningCtx = "";

    // =====================================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Arrays.fill(hist, Float.NaN);
        nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Registro de medición", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            try {
                startForeground(NID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } catch (Exception e) {
                lastError = "No se pudo iniciar el servicio: " + e.getMessage();
                stopSelf();
                return START_NOT_STICKY;
            }
            startSensors();
            started = true;
            h.post(tick);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (logging) stopLogging();
        if (current != null) current.cancel();
        h.removeCallbacksAndMessages(null);
        try {
            if (tm != null && psl != null) tm.listen(psl, PhoneStateListener.LISTEN_NONE);
        } catch (Exception ignored) {
        }
        try {
            if (lm != null && locListener != null) lm.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        try {
            if (cm != null && cellCb != null) cm.unregisterNetworkCallback(cellCb);
        } catch (Exception ignored) {
        }
        io.shutdown();
        instance = null;
        super.onDestroy();
    }

    // =====================================================================================
    // Sensores

    @SuppressWarnings("deprecation")
    private void startSensors() {
        TelephonyManager base = getSystemService(TelephonyManager.class);
        tm = base;
        try {
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm = base.createForSubscriptionId(sub);
        } catch (Exception ignored) {
        }

        psl = new PhoneStateListener() {
            @Override
            public void onCellInfoChanged(List<CellInfo> l) {
                if (l != null && !l.isEmpty()) cells = l;
            }

            @Override
            public void onSignalStrengthsChanged(SignalStrength s) {
                if (s != null) ss = s;
            }

            @Override
            public void onDisplayInfoChanged(TelephonyDisplayInfo d) {
                if (d != null) overrideType = d.getOverrideNetworkType();
            }
        };
        int ev = PhoneStateListener.LISTEN_CELL_INFO | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;
        try {
            tm.listen(psl, Build.VERSION.SDK_INT >= 30 ? ev | PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED : ev);
        } catch (Exception e) {
            try {
                tm.listen(psl, ev);
            } catch (Exception ignored) {
            }
        }

        lm = getSystemService(LocationManager.class);
        locListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                onLocation(l);
            }

            // Implementados explícitamente: en Android 10 no tienen implementación por defecto
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            lastError = "GPS no disponible: " + e.getMessage();
        }

        cm = getSystemService(ConnectivityManager.class);
        cellCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                h.post(() -> cellNet = n);
            }

            @Override
            public void onLost(Network n) {
                h.post(() -> {
                    if (n.equals(cellNet)) cellNet = null;
                });
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.requestNetwork(req, cellCb);
        } catch (Exception e) {
            cellCb = null;
        }
    }

    private void onLocation(Location l) {
        if (l == null) return;
        loc = l;
        locElapsed = SystemClock.elapsedRealtime();
        if (l.hasAccuracy() && l.getAccuracy() <= 25) {
            if (lastDistLoc != null) distSinceTest += lastDistLoc.distanceTo(l);
            lastDistLoc = l;
        }
    }

    boolean locValid() {
        return loc != null && SystemClock.elapsedRealtime() - locElapsed <= GPS_MAX_AGE_MS;
    }

    private void requestCells() {
        try {
            tm.requestCellInfoUpdate(getMainExecutor(), new TelephonyManager.CellInfoCallback() {
                @Override
                public void onCellInfo(List<CellInfo> l) {
                    if (l != null && !l.isEmpty()) cells = l;
                }

                @Override
                public void onError(int errorCode, Throwable detail) {
                }
            });
        } catch (Exception ignored) {
        }
        if (cells == null) {
            try {
                List<CellInfo> l = tm.getAllCellInfo();
                if (l != null && !l.isEmpty()) cells = l;
            } catch (Exception ignored) {
            }
        }
    }

    // =====================================================================================
    // Ciclo principal (cada intervalo, alineado al reloj para sincronizar varios teléfonos)

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            try {
                doTick(now);
            } catch (Exception e) {
                lastError = "Error: " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            long ms = Prefs.logMs(LoggerService.this);
            long delay = ms - (System.currentTimeMillis() % ms);
            if (delay < 150) delay += ms;
            h.postDelayed(this, delay);
        }
    };

    private void doTick(long now) {
        requestCells();
        try {
            SignalStrength s = tm.getSignalStrength();
            if (s != null) ss = s;
        } catch (Exception ignored) {
        }
        String op = "";
        try {
            op = Fmt.nz(tm.getNetworkOperator());
            operator = Fmt.nz(tm.getNetworkOperatorName());
        } catch (Exception ignored) {
        }
        mccMnc = op.length() >= 5 ? op.substring(0, 3) + "-" + op.substring(3) : op;
        try {
            netType = CellReader.netTypeName(tm.getDataNetworkType());
        } catch (Exception e) {
            netType = "";
        }
        display = CellReader.overrideName(overrideType);
        snap = CellReader.build(cells, ss, op);
        traffic();
        try {
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            wifiActive = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }

        // Cambios de celda (handover / cambio de tecnología)
        CellReader.CellData s = snap.serving;
        if (s != null && s.pci != NA && s.arfcn != NA) {
            String key = s.tech + "/" + s.arfcn + "/" + s.pci;
            if (!lastKey.isEmpty() && !key.equals(lastKey)) {
                hoCount++;
                event(s.tech.equals(lastTech) ? "CAMBIO_CELDA" : "CAMBIO_TEC", lastKey + " -> " + key);
            }
            lastKey = key;
            lastTech = s.tech;
        }

        float v = (s != null && s.rsrp != NA) ? s.rsrp : (s != null && s.rscp != NA ? s.rscp : Float.NaN);
        hist[histPos] = v;
        histPos = (histPos + 1) % hist.length;

        // Prueba automática
        if (logging && !testRunning && Prefs.bool(this, Prefs.AUTO_ON, false)) {
            int dist = Prefs.num(this, Prefs.AUTO_DIST, 250);
            int min = Prefs.num(this, Prefs.AUTO_MIN, 0);
            boolean byDist = dist > 0 && distSinceTest >= dist;
            boolean byTime = min > 0 && SystemClock.elapsedRealtime() - lastTestEnd >= min * 60_000L;
            if (byDist || byTime) startTest(true);
        }

        if (logging) writeSample(now);

        if (++tickCount % 5 == 0) {
            try {
                nm.notify(NID, buildNotif());
            } catch (Exception ignored) {
            }
        }
    }

    private void traffic() {
        long rx = TrafficStats.getMobileRxBytes();
        long tx = TrafficStats.getMobileTxBytes();
        long t = SystemClock.elapsedRealtime();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            rxKbps = txKbps = Double.NaN;
            return;
        }
        if (prevRx >= 0 && t > prevTrafT && rx >= prevRx && tx >= prevTx) {
            rxKbps = (rx - prevRx) * 8.0 / (t - prevTrafT);
            txKbps = (tx - prevTx) * 8.0 / (t - prevTrafT);
        }
        prevRx = rx;
        prevTx = tx;
        prevTrafT = t;
    }

    float[] history() {
        float[] out = new float[hist.length];
        for (int i = 0; i < hist.length; i++) out[i] = hist[(histPos + i) % hist.length];
        return out;
    }

    // =====================================================================================
    // Campos de campo (tramo, punto fijo, interior)

    void setSegment(String sgm) {
        sgm = Fmt.txt(sgm);
        if (sgm.equals(segment)) return;
        segment = sgm;
        event("TRAMO", sgm.isEmpty() ? "(ninguno)" : sgm);
    }

    void setPoint(String p) {
        p = Fmt.txt(p);
        if (p.equals(point)) return;
        point = p;
        event("PUNTO", p.isEmpty() ? "(ninguno)" : p);
        String u = p.toUpperCase(Locale.US);
        if (u.startsWith("IN")) setIndoor(true);
        else if (u.startsWith("OUT")) setIndoor(false);
    }

    void setIndoor(boolean in) {
        if (in == indoor) return;
        indoor = in;
        event("INDOOR", in ? "1" : "0");
    }

    private String ctxPrefix(long ms) {
        StringBuilder b = new StringBuilder();
        b.append(Fmt.iso(ms)).append(';');
        if (locValid()) {
            b.append(Fmt.d(loc.getLatitude(), 7)).append(';').append(Fmt.d(loc.getLongitude(), 7)).append(';');
        } else {
            b.append(";;");
        }
        b.append(segment).append(';').append(point).append(';').append(indoor ? 1 : 0);
        return b.toString();
    }

    private void event(String type, String detail) {
        if (pendingEvent.length() > 0) pendingEvent.append('+');
        pendingEvent.append(type);
        if (!logging || fEvents == null) return;
        final OutFile f = fEvents;
        final String row = ctxPrefix(System.currentTimeMillis()) + ";" + type + ";" + Fmt.txt(detail);
        io.execute(() -> {
            f.line(row);
            f.flush();
        });
    }

    // =====================================================================================
    // Registro

    boolean startLogging() {
        if (logging) return true;
        long now = System.currentTimeMillis();
        String model = Build.MODEL == null ? "android" : Build.MODEL.replaceAll("[^A-Za-z0-9_-]", "");
        String op = mccMnc.replace("-", "");
        baseName = "MC_" + model + "_" + (op.isEmpty() ? "SINOP" : op) + "_" + Fmt.fileStamp(now);
        try {
            fSamples = OutFile.create(this, baseName + "_muestras.csv", "text/csv");
            fNeigh = OutFile.create(this, baseName + "_vecinas.csv", "text/csv");
            fEvents = OutFile.create(this, baseName + "_eventos.csv", "text/csv");
            fTests = OutFile.create(this, baseName + "_pruebas.csv", "text/csv");
        } catch (IOException e) {
            lastError = "No se pudo crear el archivo: " + e.getMessage();
            for (OutFile f : new OutFile[]{fSamples, fNeigh, fEvents, fTests}) if (f != null) f.close();
            fSamples = fNeigh = fEvents = fTests = null;
            return false;
        }
        final OutFile a = fSamples, b = fNeigh, c = fEvents, d = fTests;
        io.execute(() -> {
            a.line(H_SAMPLES);
            b.line(H_NEIGH);
            c.line(H_EVENTS);
            d.line(H_TESTS);
        });
        logging = true;
        logStart = now;
        samples = 0;
        hoCount = 0;
        track.clear();
        testPts.clear();
        distSinceTest = 0;
        lastTestEnd = SystemClock.elapsedRealtime();
        exportMsg = "";
        lastError = "";
        PowerManager pm = getSystemService(PowerManager.class);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonitorCeldas:registro");
        wl.acquire(12 * 3600 * 1000L);
        event("INICIO", "modelo=" + Build.MANUFACTURER + " " + Build.MODEL + " android=" + Build.VERSION.RELEASE
                + " operador=" + operator + " " + mccMnc + " intervalo_ms=" + Prefs.logMs(this)
                + " app=" + BuildConfigInfo.VERSION);
        try {
            nm.notify(NID, buildNotif());
        } catch (Exception ignored) {
        }
        return true;
    }

    void stopLogging() {
        if (!logging) return;
        event("FIN", "muestras=" + samples);
        logging = false;
        final OutFile a = fSamples, b = fNeigh, c = fEvents, d = fTests;
        fSamples = fNeigh = fEvents = fTests = null;
        final ArrayList<KmlExporter.Pt> tr = new ArrayList<>(track);
        final ArrayList<KmlExporter.TestPt> tp = new ArrayList<>(testPts);
        final String base = baseName;
        final double maxAcc = Prefs.num(this, Prefs.KML_ACC, 40);
        io.execute(() -> {
            a.close();
            b.close();
            c.close();
            d.close();
            OutFile k = null;
            try {
                k = OutFile.create(this, base + ".kml", "application/vnd.google-earth.kml+xml");
                KmlExporter.write(k, base, tr, tp, maxAcc);
            } catch (Exception ignored) {
            } finally {
                if (k != null) k.close();
            }
            final ArrayList<Uri> uris = new ArrayList<>();
            for (OutFile f : new OutFile[]{a, b, c, d, k}) if (f != null && f.uri != null) uris.add(f.uri);
            final String where = a.uri != null ? "Descargas/" + OutFile.FOLDER : a.location();
            h.post(() -> {
                lastUris.clear();
                lastUris.addAll(uris);
                exportMsg = "Guardado en " + where + " (" + base + ")";
            });
        });
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        try {
            nm.notify(NID, buildNotif());
        } catch (Exception ignored) {
        }
    }

    private static void add(StringBuilder b, String s) {
        b.append(s).append(';');
    }

    private static void empty(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) b.append(';');
    }

    private void writeSample(long now) {
        CellReader.CellData s = snap.serving;
        CellReader.CellData nr = snap.nr;
        boolean lv = locValid();
        StringBuilder b = new StringBuilder(512);
        add(b, Fmt.iso(now));
        add(b, Long.toString(now));
        if (lv) {
            add(b, Fmt.iso(loc.getTime()));
            add(b, Fmt.d(loc.getLatitude(), 7));
            add(b, Fmt.d(loc.getLongitude(), 7));
            add(b, loc.hasAccuracy() ? Fmt.d(loc.getAccuracy(), 1) : "");
            add(b, loc.hasAltitude() ? Fmt.d(loc.getAltitude(), 1) : "");
            add(b, loc.hasSpeed() ? Fmt.d(loc.getSpeed() * 3.6, 1) : "");
            add(b, Fmt.d((SystemClock.elapsedRealtime() - locElapsed) / 1000.0, 1));
        } else {
            empty(b, 6);
            add(b, loc == null ? "" : Fmt.d((SystemClock.elapsedRealtime() - locElapsed) / 1000.0, 1));
        }
        add(b, Fmt.txt(operator));
        add(b, mccMnc);
        add(b, s == null ? "SIN_SERVICIO" : s.tech);
        add(b, netType);
        add(b, display);
        if (s != null) {
            add(b, s.bandLabel());
            add(b, Fmt.i(s.arfcn));
            add(b, Fmt.d(s.dlMhz, 1));
            add(b, Fmt.i(s.pci));
            add(b, Fmt.l(s.cid));
            add(b, Fmt.l(s.enb()));
            add(b, Fmt.l(s.sector()));
            add(b, Fmt.i(s.tac));
            add(b, Fmt.i(s.rsrp));
            add(b, Fmt.i(s.rsrq));
            add(b, Fmt.i(s.rssi));
            add(b, Fmt.i(s.sinr));
            add(b, Fmt.i(s.cqi));
            add(b, Fmt.i(s.ta));
            add(b, s.bwKhz == NA ? "" : Fmt.d(s.bwKhz / 1000.0, 1));
            add(b, Fmt.i(s.rscp));
            add(b, Fmt.i(s.ecno));
        } else {
            empty(b, 17);
        }
        if (nr != null) {
            add(b, nr.bandLabel());
            add(b, Fmt.i(nr.arfcn));
            add(b, Fmt.i(nr.pci));
            add(b, Fmt.i(nr.rsrp));
            add(b, Fmt.i(nr.rsrq));
            add(b, Fmt.i(nr.sinr));
        } else {
            empty(b, 6);
        }
        add(b, Integer.toString(snap.neighbors.size()));
        for (int i = 0; i < 3; i++) {
            if (i < snap.neighbors.size()) {
                CellReader.CellData n = snap.neighbors.get(i);
                int lvl = n.level();
                add(b, Fmt.i(n.pci));
                add(b, Fmt.i(n.arfcn));
                add(b, lvl == -999 ? "" : Integer.toString(lvl));
                add(b, Fmt.i(n.rsrq));
            } else {
                empty(b, 4);
            }
        }
        add(b, Fmt.d(rxKbps, 1));
        add(b, Fmt.d(txKbps, 1));
        add(b, s == null ? "" : Fmt.l(s.ageMs));
        add(b, segment);
        add(b, point);
        add(b, indoor ? "1" : "0");
        add(b, pendingEvent.toString());
        pendingEvent.setLength(0);
        SpeedTest.Result r = pendingResult;
        pendingResult = null;
        if (r != null) {
            add(b, "T" + r.id);
            add(b, Fmt.d(r.dlMbps, 3));
            add(b, Fmt.d(r.ulMbps, 3));
            add(b, Fmt.d(r.latencyMs, 1));
            add(b, Fmt.d(r.jitterMs, 1));
            b.append(Fmt.d(r.lossPct, 1));
        } else {
            add(b, testRunning ? "T" + testSeq : "");
            b.append(";;;;");
        }
        final String row = b.toString();

        // Vecinas
        final ArrayList<String> nRows = new ArrayList<>();
        if (s != null && !snap.neighbors.isEmpty()) {
            String pre = ctxPrefix(now) + ";" + Fmt.i(s.pci) + ";" + Fmt.i(s.arfcn) + ";";
            for (CellReader.CellData n : snap.neighbors) {
                nRows.add(pre + n.tech + ";" + n.bandLabel() + ";" + Fmt.i(n.arfcn) + ";" + Fmt.i(n.pci) + ";"
                        + Fmt.i(n.rsrp) + ";" + Fmt.i(n.rsrq) + ";" + Fmt.i(n.rssi) + ";" + Fmt.i(n.sinr) + ";"
                        + Fmt.i(n.rscp) + ";" + Fmt.i(n.ecno) + ";" + Fmt.l(n.ageMs));
            }
        }

        final OutFile fs = fSamples, fn = fNeigh;
        io.execute(() -> {
            fs.line(row);
            fs.flush();
            if (!nRows.isEmpty()) {
                for (String nr2 : nRows) fn.line(nr2);
                fn.flush();
            }
        });
        samples++;

        if (lv) {
            KmlExporter.Pt p = new KmlExporter.Pt();
            p.ms = now;
            p.lat = loc.getLatitude();
            p.lon = loc.getLongitude();
            p.acc = loc.hasAccuracy() ? loc.getAccuracy() : 999;
            if (s != null) {
                p.tech = s.tech;
                p.band = s.bandLabel();
                p.arfcn = s.arfcn;
                p.pci = s.pci;
                p.rsrp = s.rsrp != NA ? s.rsrp : s.rscp;
                p.rsrq = s.rsrq;
                p.sinr = s.sinr;
            }
            p.segment = segment;
            p.point = point;
            track.add(p);
        }
    }

    // =====================================================================================
    // Pruebas de velocidad

    boolean startTest(boolean auto) {
        if (testRunning) return false;
        testSeq++;
        testRunning = true;
        testStatus = "Iniciando…";
        long now = System.currentTimeMillis();
        CellReader.CellData s = snap != null ? snap.serving : null;
        boolean lv = locValid();

        runningPt = new KmlExporter.TestPt();
        runningPt.ms = now;
        runningPt.lat = lv ? loc.getLatitude() : Double.NaN;
        runningPt.lon = lv ? loc.getLongitude() : Double.NaN;
        runningPt.segment = segment;
        runningPt.point = point;
        if (s != null) {
            runningPt.band = s.bandLabel();
            runningPt.pci = s.pci;
            runningPt.rsrp = s.rsrp;
            runningPt.sinr = s.sinr;
        }
        runningCtx = (lv ? Fmt.d(loc.getLatitude(), 7) + ";" + Fmt.d(loc.getLongitude(), 7) + ";"
                + (loc.hasAccuracy() ? Fmt.d(loc.getAccuracy(), 1) : "") : ";;")
                + ";" + segment + ";" + point + ";" + (indoor ? 1 : 0) + ";" + Fmt.txt(operator) + ";"
                + (s == null ? "SIN_SERVICIO;;;;;;;" : s.tech + ";" + s.bandLabel() + ";" + Fmt.i(s.arfcn) + ";"
                + Fmt.i(s.pci) + ";" + Fmt.l(s.cid) + ";" + Fmt.i(s.rsrp) + ";" + Fmt.i(s.rsrq) + ";" + Fmt.i(s.sinr));

        String netLabel = cellNet != null ? "movil" : (wifiActive ? "WIFI" : "predeterminada");
        final int id = testSeq;
        current = new SpeedTest(id, cellNet, netLabel, !wifiActive,
                Prefs.str(this, Prefs.DL_URL, Prefs.DEF_DL),
                Prefs.str(this, Prefs.UL_URL, Prefs.DEF_UL),
                Prefs.str(this, Prefs.LAT_URL, Prefs.DEF_LAT),
                Prefs.str(this, Prefs.PING_HOST, Prefs.DEF_PING),
                Prefs.testDur(this), Prefs.streams(this),
                new SpeedTest.Listener() {
                    @Override
                    public void progress(String phase, double mbps) {
                        h.post(() -> testStatus = "T" + id + " " + phase
                                + (Double.isNaN(mbps) ? "…" : ": " + Fmt.d(mbps, 1) + " Mbps"));
                    }

                    @Override
                    public void done(SpeedTest.Result r) {
                        h.post(() -> onTestDone(r));
                    }
                });
        event(auto ? "PRUEBA_AUTO_INICIO" : "PRUEBA_INICIO", "T" + id);
        Thread t = new Thread(current, "speedtest-" + id);
        t.start();
        return true;
    }

    void cancelTest() {
        if (current != null && testRunning) current.cancel();
    }

    private void onTestDone(SpeedTest.Result r) {
        testRunning = false;
        current = null;
        lastResult = r;
        pendingResult = r;
        distSinceTest = 0;
        lastTestEnd = SystemClock.elapsedRealtime();
        testStatus = "T" + r.id + " terminada";
        if (logging && fTests != null) {
            final OutFile f = fTests;
            final String row = "T" + r.id + ";" + Fmt.iso(r.startMs) + ";" + Fmt.iso(r.endMs) + ";" + runningCtx + ";"
                    + Fmt.d(r.dlMbps, 3) + ";" + Fmt.d(r.ulMbps, 3) + ";" + Fmt.d(r.latencyMs, 1) + ";"
                    + Fmt.d(r.jitterMs, 1) + ";" + Fmt.d(r.lossPct, 1) + ";" + Fmt.txt(r.latMethod) + ";"
                    + r.network + ";" + r.dlBytes + ";" + r.ulBytes + ";"
                    + Fmt.txt(Prefs.str(this, Prefs.DL_URL, Prefs.DEF_DL)) + ";"
                    + Fmt.txt(Prefs.str(this, Prefs.UL_URL, Prefs.DEF_UL)) + ";" + Fmt.txt(r.error.toString());
            io.execute(() -> {
                f.line(row);
                f.flush();
            });
            if (runningPt != null) {
                runningPt.r = r;
                testPts.add(runningPt);
            }
        }
        event("PRUEBA_FIN", "T" + r.id + " " + SpeedTest.fmt(r));
    }

    // =====================================================================================

    String servingSummary() {
        CellReader.CellData s = snap != null ? snap.serving : null;
        if (s == null) return "Sin servicio";
        String lvl = s.rsrp != NA ? "RSRP " + s.rsrp : (s.rscp != NA ? "RSCP " + s.rscp : (s.rssi != NA ? "RSSI " + s.rssi : ""));
        return s.tech + " " + s.bandLabel() + " PCI " + Fmt.i(s.pci) + " " + lvl + " dBm"
                + (s.sinr != NA ? " SINR " + s.sinr : "");
    }

    private Notification buildNotif() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = logging
                ? "Registrando · " + samples + " muestras · " + Fmt.dur(System.currentTimeMillis() - logStart)
                : "Monitoreando (sin registrar)";
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(snap == null ? "" : servingSummary())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }
}
