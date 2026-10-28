package com.monitorceldas.app;

/**
 * Conversión de canal (EARFCN / NR-ARFCN / UARFCN / ARFCN) a banda y frecuencia de bajada.
 * LTE: F_DL = F_DL,low + 0,1·(N_DL − N_offs-DL)  (3GPP TS 36.101, tabla 5.7.3-1)
 */
final class Bands {
    private Bands() {}

    // {banda, F_DL_low (MHz), N_offs_DL, N_DL min, N_DL max}
    private static final double[][] LTE = {
            {1, 2110, 0, 0, 599},
            {2, 1930, 600, 600, 1199},
            {3, 1805, 1200, 1200, 1949},
            {4, 2110, 1950, 1950, 2399},
            {5, 869, 2400, 2400, 2649},
            {7, 2620, 2750, 2750, 3449},
            {8, 925, 3450, 3450, 3799},
            {9, 1844.9, 3800, 3800, 4149},
            {10, 2110, 4150, 4150, 4749},
            {11, 1475.9, 4750, 4750, 4949},
            {12, 729, 5010, 5010, 5179},
            {13, 746, 5180, 5180, 5279},
            {14, 758, 5280, 5280, 5379},
            {17, 734, 5730, 5730, 5849},
            {18, 860, 5850, 5850, 5999},
            {19, 875, 6000, 6000, 6149},
            {20, 791, 6150, 6150, 6449},
            {21, 1495.9, 6450, 6450, 6599},
            {24, 1525, 7700, 7700, 8039},
            {25, 1930, 8040, 8040, 8689},
            {26, 859, 8690, 8690, 9039},
            {28, 758, 9210, 9210, 9659},
            {29, 717, 9660, 9660, 9769},
            {30, 2350, 9770, 9770, 9869},
            {32, 1452, 9920, 9920, 10359},
            {33, 1900, 36000, 36000, 36199},
            {34, 2010, 36200, 36200, 36349},
            {35, 1850, 36350, 36350, 36949},
            {36, 1930, 36950, 36950, 37549},
            {37, 1910, 37550, 37550, 37749},
            {38, 2570, 37750, 37750, 38249},
            {39, 1880, 38250, 38250, 38649},
            {40, 2300, 38650, 38650, 39649},
            {41, 2496, 39650, 39650, 41589},
            {42, 3400, 41590, 41590, 43589},
            {43, 3600, 43590, 43590, 45589},
            {46, 5150, 46790, 46790, 54539},
            {48, 3550, 55240, 55240, 56739},
            {66, 2110, 66436, 66436, 67335},
            {71, 617, 68586, 68586, 68935},
    };

    private static double[] lteRow(int earfcn) {
        for (double[] b : LTE) {
            if (earfcn >= b[3] && earfcn <= b[4]) return b;
        }
        return null;
    }

    static int lteBand(int earfcn) {
        double[] b = lteRow(earfcn);
        return b == null ? Fmt.NA : (int) b[0];
    }

    static double lteDlMhz(int earfcn) {
        double[] b = lteRow(earfcn);
        return b == null ? Double.NaN : b[1] + 0.1 * (earfcn - b[2]);
    }

    /** NR-ARFCN → frecuencia (MHz), raster global de TS 38.104 §5.4.2.1 */
    static double nrFreqMhz(int n) {
        if (n < 0) return Double.NaN;
        if (n < 600000) return 0.005 * n;
        if (n < 2016667) return 3000 + 0.015 * (n - 600000);
        if (n <= 3279165) return 24250.08 + 0.06 * (n - 2016667);
        return Double.NaN;
    }

    // {banda NR, F_DL min, F_DL max} — si hay solapamiento se toma la primera coincidencia
    private static final double[][] NR = {
            {28, 758, 803}, {71, 617, 652}, {5, 869, 894}, {8, 925, 960}, {20, 791, 821},
            {3, 1805, 1880}, {2, 1930, 1990}, {1, 2110, 2170}, {66, 2110, 2200},
            {7, 2620, 2690}, {41, 2496, 2690}, {40, 2300, 2400}, {38, 2570, 2620},
            {78, 3300, 3800}, {77, 3300, 4200}, {79, 4400, 5000},
            {258, 24250, 27500}, {257, 26500, 29500}, {261, 27500, 28350}, {260, 37000, 40000},
    };

    static int nrBand(double fMhz) {
        if (Double.isNaN(fMhz)) return Fmt.NA;
        for (double[] b : NR) {
            if (fMhz >= b[1] && fMhz <= b[2]) return (int) b[0];
        }
        return Fmt.NA;
    }

    /** UARFCN de bajada → {banda, frecuencia}. Bandas usuales en Latinoamérica. */
    static double[] umts(int u) {
        if (u >= 10562 && u <= 10838) return new double[]{1, u / 5.0};
        if (u >= 9662 && u <= 9938) return new double[]{2, u / 5.0};
        if (u >= 412 && u <= 687) return new double[]{2, u / 5.0 + 1850.1};
        if (u >= 1537 && u <= 1738) return new double[]{4, u / 5.0 + 1805};
        if (u >= 1887 && u <= 2087) return new double[]{4, u / 5.0 + 1735.1};
        if (u >= 4357 && u <= 4458) return new double[]{5, u / 5.0};
        if (u >= 1007 && u <= 1087) return new double[]{5, u / 5.0 + 670.1};
        if (u >= 2937 && u <= 3088) return new double[]{8, u / 5.0 + 340};
        return new double[]{Fmt.NA, Double.NaN};
    }

    /** ARFCN GSM → {banda (MHz nominal), frecuencia DL}. 512–810 se asume PCS 1900 (América). */
    static double[] gsm(int n) {
        if (n >= 128 && n <= 251) return new double[]{850, 869.2 + 0.2 * (n - 128)};
        if (n >= 0 && n <= 124) return new double[]{900, 935 + 0.2 * n};
        if (n >= 975 && n <= 1023) return new double[]{900, 935 + 0.2 * (n - 1024)};
        if (n >= 512 && n <= 810) return new double[]{1900, 1930.2 + 0.2 * (n - 512)};
        if (n >= 811 && n <= 885) return new double[]{1800, 1805.2 + 0.2 * (n - 512)};
        return new double[]{Fmt.NA, Double.NaN};
    }
}
