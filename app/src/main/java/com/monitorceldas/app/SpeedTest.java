package com.monitorceldas.app;

import android.net.Network;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prueba activa de throughput tipo Speedtest:
 * 1) Latencia/jitter/pérdida con ping ICMP (o HTTP si ICMP no está disponible)
 * 2) Descarga multi-hilo durante N s
 * 3) Subida multi-hilo durante N s
 * El throughput se calcula excluyendo el arranque inicial (slow start): min(2 s, N/4).
 */
final class SpeedTest implements Runnable {

    interface Listener {
        void progress(String phase, double mbps);

        void done(Result r);
    }

    static final class Result {
        int id;
        long startMs, endMs;
        double dlMbps = Double.NaN, ulMbps = Double.NaN;
        double latencyMs = Double.NaN, jitterMs = Double.NaN, lossPct = Double.NaN;
        String latMethod = "";
        String network = "";
        long dlBytes, ulBytes;
        StringBuilder error = new StringBuilder();

        void err(String s) {
            if (error.length() > 0) error.append(" | ");
            error.append(s);
        }
    }

    private static final Pattern RTT = Pattern.compile("time[=<]([0-9.]+)");
    private static final Pattern LOSS = Pattern.compile("([0-9.]+)% packet loss");
    private static final byte[] UP_BUF = new byte[64 * 1024];

    static {
        new Random().nextBytes(UP_BUF);
    }

    private final Result r;
    private final Network net;
    private final boolean icmpAllowed;
    private final String dlUrl, ulUrl, latUrl, pingHost;
    private final int durS, streams;
    private final Listener listener;
    private volatile boolean cancelled;
    private final List<HttpURLConnection> active = Collections.synchronizedList(new ArrayList<>());

    SpeedTest(int id, Network net, String netLabel, boolean icmpAllowed, String dlUrl, String ulUrl,
              String latUrl, String pingHost, int durS, int streams, Listener l) {
        this.r = new Result();
        r.id = id;
        r.network = netLabel;
        this.net = net;
        this.icmpAllowed = icmpAllowed;
        this.dlUrl = dlUrl;
        this.ulUrl = ulUrl;
        this.latUrl = latUrl;
        this.pingHost = pingHost;
        this.durS = durS;
        this.streams = streams;
        this.listener = l;
    }

    void cancel() {
        cancelled = true;
        abortAll();
    }

    @Override
    public void run() {
        r.startMs = System.currentTimeMillis();
        try {
            listener.progress("Latencia", Double.NaN);
            latency();
        } catch (Exception e) {
            r.err("latencia: " + msg(e));
        }
        if (!cancelled) {
            try {
                r.dlMbps = transfer(true);
            } catch (Exception e) {
                r.err("descarga: " + msg(e));
            }
        }
        if (!cancelled) {
            try {
                r.ulMbps = transfer(false);
            } catch (Exception e) {
                r.err("subida: " + msg(e));
            }
        }
        if (cancelled) r.err("cancelada");
        r.endMs = System.currentTimeMillis();
        listener.done(r);
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m != null ? (": " + m) : "");
    }

    private HttpURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "MonitorCeldas/1.0 (Android drive test)");
        return c;
    }

    // ---------------------------------------------------------------- latencia

    private void latency() throws IOException {
        List<Double> rtts = new ArrayList<>();
        double loss = Double.NaN;
        if (icmpAllowed && pingHost != null && !pingHost.isEmpty()) {
            try {
                loss = icmp(rtts);
            } catch (Exception e) {
                rtts.clear();
            }
        }
        if (rtts.size() >= 2) {
            r.latMethod = "ICMP " + pingHost;
            r.lossPct = loss;
        } else {
            rtts.clear();
            httpLatency(rtts);
            r.latMethod = "HTTP";
        }
        if (rtts.isEmpty()) throw new IOException("sin respuestas");
        List<Double> sorted = new ArrayList<>(rtts);
        Collections.sort(sorted);
        int n = sorted.size();
        r.latencyMs = (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        if (rtts.size() >= 2) {
            double s = 0;
            for (int i = 1; i < rtts.size(); i++) s += Math.abs(rtts.get(i) - rtts.get(i - 1));
            r.jitterMs = s / (rtts.size() - 1);
        }
    }

    private double icmp(List<Double> rtts) throws Exception {
        final Process p = new ProcessBuilder("ping", "-c", "10", "-i", "0.2", "-W", "2", pingHost)
                .redirectErrorStream(true).start();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(20000);
            } catch (InterruptedException ignored) {
                return;
            }
            p.destroy();
        });
        killer.setDaemon(true);
        killer.start();
        double loss = Double.NaN;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = RTT.matcher(line);
                if (m.find()) rtts.add(Double.parseDouble(m.group(1)));
                Matcher lm = LOSS.matcher(line);
                if (lm.find()) loss = Double.parseDouble(lm.group(1));
                if (cancelled) break;
            }
        } finally {
            killer.interrupt();
            p.destroy();
        }
        return loss;
    }

    private void httpLatency(List<Double> rtts) {
        byte[] buf = new byte[4096];
        int fails = 0;
        for (int i = 0; i < 11 && !cancelled; i++) {
            try {
                HttpURLConnection c = open(latUrl);
                long t0 = System.nanoTime();
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                if (in != null) {
                    //noinspection StatementWithEmptyBody
                    while (in.read(buf) > 0) { }
                    in.close();
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                if (i > 0) rtts.add(ms); // la primera incluye DNS + TLS: se descarta
            } catch (IOException e) {
                fails++;
            }
        }
        if (fails > 0) r.err("latencia HTTP: " + fails + " fallas");
    }

    // ---------------------------------------------------------------- descarga / subida

    private double transfer(final boolean down) throws Exception {
        final AtomicLong bytes = new AtomicLong();
        final AtomicReference<String> err = new AtomicReference<>();
        final long t0 = SystemClock.elapsedRealtime();
        final long end = t0 + durS * 1000L;
        Thread[] th = new Thread[streams];
        for (int i = 0; i < streams; i++) {
            th[i] = new Thread(() -> {
                while (!cancelled && SystemClock.elapsedRealtime() < end) {
                    try {
                        if (down) downOnce(bytes, end);
                        else upOnce(bytes, end);
                    } catch (Exception e) {
                        if (SystemClock.elapsedRealtime() < end) {
                            err.set(msg(e));
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException ie) {
                                return;
                            }
                        }
                    }
                }
            });
            th[i].start();
        }

        long warm = Math.min(2000L, durS * 1000L / 4);
        long bWarm = -1, tWarm = t0, lastB = 0, lastT = t0;
        String phase = down ? "Descarga" : "Subida";
        long now = t0;
        while (!cancelled && now < end) {
            Thread.sleep(200);
            now = SystemClock.elapsedRealtime();
            long b = bytes.get();
            if (bWarm < 0 && now - t0 >= warm) {
                bWarm = b;
                tWarm = now;
            }
            if (now - lastT >= 1000) {
                listener.progress(phase, (b - lastB) * 8.0 / ((now - lastT) * 1000.0));
                lastB = b;
                lastT = now;
            }
        }
        long bEnd = bytes.get();
        long tEnd = SystemClock.elapsedRealtime();
        abortAll();
        for (Thread t : th) t.join(3000);

        if (down) r.dlBytes = bEnd;
        else r.ulBytes = bEnd;
        if (bEnd == 0) {
            throw new IOException(err.get() != null ? err.get() : "sin datos");
        }
        if (bWarm < 0 || tEnd - tWarm < 500) {
            bWarm = 0;
            tWarm = t0;
        }
        return (bEnd - bWarm) * 8.0 / ((tEnd - tWarm) * 1000.0);
    }

    private void downOnce(AtomicLong bytes, long end) throws IOException {
        HttpURLConnection c = open(dlUrl);
        c.setRequestProperty("Accept-Encoding", "identity");
        active.add(c);
        try {
            InputStream in = c.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.addAndGet(n);
                if (cancelled || SystemClock.elapsedRealtime() >= end) break;
            }
        } finally {
            active.remove(c);
            c.disconnect();
        }
    }

    private void upOnce(AtomicLong bytes, long end) throws IOException {
        final long size = 25L * 1024 * 1024;
        HttpURLConnection c = open(ulUrl);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode(size);
        active.add(c);
        try {
            OutputStream out = c.getOutputStream();
            long sent = 0;
            boolean complete = true;
            while (sent < size) {
                int n = (int) Math.min(UP_BUF.length, size - sent);
                out.write(UP_BUF, 0, n);
                sent += n;
                bytes.addAndGet(n);
                if (cancelled || SystemClock.elapsedRealtime() >= end) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                out.close();
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
            }
        } finally {
            active.remove(c);
            c.disconnect();
        }
    }

    private void abortAll() {
        List<HttpURLConnection> copy;
        synchronized (active) {
            copy = new ArrayList<>(active);
        }
        for (HttpURLConnection c : copy) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    static String fmt(Result r) {
        return String.format(Locale.US, "DL %s Mbps · UL %s Mbps · Lat %s ms · Jit %s ms%s",
                Fmt.d(r.dlMbps, 2).isEmpty() ? "—" : Fmt.d(r.dlMbps, 2),
                Fmt.d(r.ulMbps, 2).isEmpty() ? "—" : Fmt.d(r.ulMbps, 2),
                Fmt.d(r.latencyMs, 0).isEmpty() ? "—" : Fmt.d(r.latencyMs, 0),
                Fmt.d(r.jitterMs, 1).isEmpty() ? "—" : Fmt.d(r.jitterMs, 1),
                Double.isNaN(r.lossPct) ? "" : " · Pérdida " + Fmt.d(r.lossPct, 0) + " %");
    }
}
