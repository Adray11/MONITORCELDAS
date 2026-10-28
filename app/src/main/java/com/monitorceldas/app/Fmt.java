package com.monitorceldas.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Utilidades de formato. Los valores no disponibles se escriben como celda vacía. */
final class Fmt {
    static final int NA = Integer.MAX_VALUE;
    static final long NAL = Long.MAX_VALUE;

    private Fmt() {}

    static String i(int v) {
        return v == NA ? "" : Integer.toString(v);
    }

    static String l(long v) {
        return v == NAL ? "" : Long.toString(v);
    }

    static String d(double v, int dec) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.format(Locale.US, "%." + dec + "f", v);
    }

    /** Valida un entero dentro de un rango; si está fuera devuelve NA. */
    static int valid(int v, int min, int max) {
        return (v == NA || v < min || v > max) ? NA : v;
    }

    /** Fecha ISO 8601 con milisegundos y desfase horario local, p. ej. 2026-10-29T10:00:00.000-05:00 */
    static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date(ms));
    }

    static String fileStamp(long ms) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(ms));
    }

    static String hms(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    /** Limpia texto para CSV separado por ';'. */
    static String txt(String s) {
        if (s == null) return "";
        return s.replace(';', ',').replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String dur(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
