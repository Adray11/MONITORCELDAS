package com.monitorceldas.app;

import java.util.List;

/** Genera un KML para Google Earth con la ruta coloreada por RSRP y SINR y las pruebas de velocidad. */
final class KmlExporter {
    private KmlExporter() {}

    static final class Pt {
        long ms;
        double lat, lon, acc;
        int rsrp = Fmt.NA, rsrq = Fmt.NA, sinr = Fmt.NA, pci = Fmt.NA, arfcn = Fmt.NA;
        String tech = "", band = "", segment = "", point = "";
    }

    static final class TestPt {
        long ms;
        double lat, lon;
        SpeedTest.Result r;
        String band = "", segment = "", point = "";
        int pci = Fmt.NA, rsrp = Fmt.NA, sinr = Fmt.NA;
    }

    // Colores KML en formato aabbggrr
    private static final String[][] STYLES = {
            {"exc", "ff00b000"},   // verde
            {"good", "ff00ffaa"},  // verde-amarillo
            {"reg", "ff00a5ff"},   // naranja
            {"bad", "ff0000ff"},   // rojo
            {"nd", "ff9e9e9e"},    // gris (sin dato)
    };

    private static String rsrpStyle(int v) {
        if (v == Fmt.NA) return "nd";
        if (v >= -80) return "exc";
        if (v >= -90) return "good";
        if (v >= -100) return "reg";
        return "bad";
    }

    private static String sinrStyle(int v) {
        if (v == Fmt.NA) return "nd";
        if (v >= 20) return "exc";
        if (v >= 13) return "good";
        if (v >= 0) return "reg";
        return "bad";
    }

    private static String dlStyle(double mbps) {
        if (Double.isNaN(mbps)) return "nd";
        if (mbps >= 20) return "exc";
        if (mbps >= 5) return "good";
        if (mbps >= 1) return "reg";
        return "bad";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void write(OutFile f, String title, List<Pt> pts, List<TestPt> tests, double maxAcc) {
        f.line("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        f.line("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>");
        f.line("<name>" + esc(title) + "</name>");
        for (String[] s : STYLES) {
            f.line("<Style id=\"" + s[0] + "\"><IconStyle><color>" + s[1] + "</color><scale>0.55</scale>"
                    + "<Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle>"
                    + "<LabelStyle><scale>0</scale></LabelStyle></Style>");
            f.line("<Style id=\"t_" + s[0] + "\"><IconStyle><color>" + s[1] + "</color><scale>1.1</scale>"
                    + "<Icon><href>http://maps.google.com/mapfiles/kml/shapes/triangle.png</href></Icon></IconStyle></Style>");
        }

        f.line("<Folder><name>RSRP (≥-80 verde, -80/-90, -90/-100, &lt;-100 rojo)</name>");
        for (Pt p : pts) {
            if (p.acc > maxAcc) continue;
            placemark(f, p, rsrpStyle(p.rsrp));
        }
        f.line("</Folder>");

        f.line("<Folder><name>SINR (≥20, 13-20, 0-13, &lt;0)</name><visibility>0</visibility>");
        for (Pt p : pts) {
            if (p.acc > maxAcc) continue;
            placemark(f, p, sinrStyle(p.sinr));
        }
        f.line("</Folder>");

        f.line("<Folder><name>Pruebas de velocidad (DL ≥20, 5-20, 1-5, &lt;1 Mbps)</name>");
        for (TestPt t : tests) {
            if (Double.isNaN(t.lat)) continue;
            f.line("<Placemark><name>T" + t.r.id + " " + Fmt.d(t.r.dlMbps, 1) + "</name><styleUrl>#t_"
                    + dlStyle(t.r.dlMbps) + "</styleUrl><description><![CDATA["
                    + Fmt.iso(t.ms) + "<br>" + SpeedTest.fmt(t.r)
                    + "<br>Banda " + t.band + " PCI " + Fmt.i(t.pci) + " RSRP " + Fmt.i(t.rsrp)
                    + " SINR " + Fmt.i(t.sinr) + "<br>Tramo " + t.segment + " Punto " + t.point
                    + "]]></description><Point><coordinates>" + Fmt.d(t.lon, 7) + "," + Fmt.d(t.lat, 7)
                    + ",0</coordinates></Point></Placemark>");
        }
        f.line("</Folder>");
        f.line("</Document></kml>");
    }

    private static void placemark(OutFile f, Pt p, String style) {
        f.line("<Placemark><styleUrl>#" + style + "</styleUrl><description><![CDATA["
                + Fmt.iso(p.ms) + "<br>" + p.tech + " " + p.band + " EARFCN " + Fmt.i(p.arfcn) + " PCI " + Fmt.i(p.pci)
                + "<br>RSRP " + Fmt.i(p.rsrp) + " RSRQ " + Fmt.i(p.rsrq) + " SINR " + Fmt.i(p.sinr)
                + "<br>Tramo " + p.segment + " Punto " + p.point
                + "]]></description><Point><coordinates>" + Fmt.d(p.lon, 7) + "," + Fmt.d(p.lat, 7)
                + ",0</coordinates></Point></Placemark>");
    }
}
