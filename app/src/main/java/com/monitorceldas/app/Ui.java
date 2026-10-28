package com.monitorceldas.app;

import static com.monitorceldas.app.Fmt.NA;

/** Paleta y umbrales (Tabla 1 del informe) usados por la interfaz, el gráfico y el KML. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0F1216;
    static final int CARD = 0xFF1A1F26;
    static final int CARD2 = 0xFF232A33;
    static final int TXT = 0xFFECEFF1;
    static final int SUB = 0xFF90A4AE;
    static final int ACCENT = 0xFF4FC3F7;
    static final int GOLD = 0xFFD4AF37;
    static final int BLUE = 0xFF1E88E5;
    static final int GREEN_BTN = 0xFF2E7D32;
    static final int RED_BTN = 0xFFC62828;

    static final int EXC = 0xFF43A047;
    static final int GOOD = 0xFF9CCC65;
    static final int REG = 0xFFFFA726;
    static final int BAD = 0xFFE53935;
    static final int NONE = 0xFF607D8B;

    // RSRP: ≥ −80 excelente, −80 a −90 buena, −90 a −100 regular, < −100 mala
    static int rsrpColor(int v) {
        if (v == NA) return NONE;
        if (v >= -80) return EXC;
        if (v >= -90) return GOOD;
        if (v >= -100) return REG;
        return BAD;
    }

    static String rsrpLabel(int v) {
        if (v == NA) return "sin dato";
        if (v >= -80) return "Excelente";
        if (v >= -90) return "Buena";
        if (v >= -100) return "Regular";
        return "Mala";
    }

    // RSRQ: ≥ −10 buena, −10 a −15 regular, < −15 mala
    static int rsrqColor(int v) {
        if (v == NA) return NONE;
        if (v >= -10) return GOOD;
        if (v >= -15) return REG;
        return BAD;
    }

    static String rsrqLabel(int v) {
        if (v == NA) return "sin dato";
        if (v >= -10) return "Buena";
        if (v >= -15) return "Regular";
        return "Mala";
    }

    // SINR: ≥ 20 excelente, 13 a 20 buena, 0 a 13 regular, < 0 mala
    static int sinrColor(int v) {
        if (v == NA) return NONE;
        if (v >= 20) return EXC;
        if (v >= 13) return GOOD;
        if (v >= 0) return REG;
        return BAD;
    }

    static String sinrLabel(int v) {
        if (v == NA) return "no reportado";
        if (v >= 20) return "Excelente";
        if (v >= 13) return "Buena";
        if (v >= 0) return "Regular";
        return "Mala";
    }

    static int dlColor(double mbps) {
        if (Double.isNaN(mbps)) return NONE;
        if (mbps >= 20) return EXC;
        if (mbps >= 5) return GOOD;
        if (mbps >= 1) return REG;
        return BAD;
    }
}
