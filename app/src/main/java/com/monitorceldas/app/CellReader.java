package com.monitorceldas.app;

import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoTdscdma;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.monitorceldas.app.Fmt.NA;
import static com.monitorceldas.app.Fmt.NAL;
import static com.monitorceldas.app.Fmt.valid;

/** Convierte la información de celdas de Android en una "foto" de la celda servidora y vecinas. */
final class CellReader {
    private CellReader() {}

    /** Una celda (servidora o vecina). Para 3G/2G: pci=PSC/BSIC, tac=LAC, cid=CID. */
    static final class CellData {
        String tech = "";
        boolean registered;
        String mcc = "", mnc = "";
        int arfcn = NA, pci = NA, tac = NA, band = NA, bwKhz = NA;
        long cid = NAL;
        double dlMhz = Double.NaN;
        int rsrp = NA, rsrq = NA, rssi = NA, sinr = NA, cqi = NA, ta = NA;
        int rscp = NA, ecno = NA;
        long ageMs = NAL;

        String key() {
            return tech + "/" + arfcn + "/" + pci + "/" + cid;
        }

        /** Nivel principal para ordenar (RSRP en LTE/NR, RSCP en 3G, RSSI en 2G). */
        int level() {
            if (rsrp != NA) return rsrp;
            if (rscp != NA) return rscp;
            if (rssi != NA) return rssi;
            return -999;
        }

        long enb() {
            return ("LTE".equals(tech) && cid != NAL) ? (cid >> 8) : NAL;
        }

        long sector() {
            return ("LTE".equals(tech) && cid != NAL) ? (cid & 0xFF) : NAL;
        }

        String bandLabel() {
            if (band == NA) return "";
            switch (tech) {
                case "LTE": return "B" + band;
                case "NR": return "n" + band;
                case "WCDMA": return "U" + band;
                case "GSM": return "G" + band;
                default: return Integer.toString(band);
            }
        }
    }

    /** Estado completo en un instante. */
    static final class Snapshot {
        CellData serving;               // puede ser null si no hay servicio
        CellData nr;                    // pata NR (NSA) o la servidora NR (SA)
        List<CellData> neighbors = new ArrayList<>();
    }

    static CellData parse(CellInfo info) {
        CellData c = new CellData();
        c.registered = info.isRegistered();
        long tsMs = Build.VERSION.SDK_INT >= 30 ? info.getTimestampMillis() : info.getTimeStamp() / 1_000_000L;
        if (tsMs > 0 && tsMs != NAL) c.ageMs = Math.max(0, SystemClock.elapsedRealtime() - tsMs);

        if (info instanceof CellInfoLte) {
            CellInfoLte l = (CellInfoLte) info;
            CellIdentityLte id = l.getCellIdentity();
            CellSignalStrengthLte s = l.getCellSignalStrength();
            c.tech = "LTE";
            c.mcc = Fmt.nz(id.getMccString());
            c.mnc = Fmt.nz(id.getMncString());
            c.arfcn = valid(id.getEarfcn(), 0, 262143);
            c.pci = valid(id.getPci(), 0, 503);
            c.tac = valid(id.getTac(), 0, 65535);
            int ci = id.getCi();
            c.cid = (ci == NA || ci < 0 || ci > 268435455) ? NAL : ci;
            c.bwKhz = valid(id.getBandwidth(), 1, 100000);
            if (c.arfcn != NA) {
                c.band = Bands.lteBand(c.arfcn);
                c.dlMhz = Bands.lteDlMhz(c.arfcn);
            }
            if (c.band == NA && Build.VERSION.SDK_INT >= 30) {
                int[] b = id.getBands();
                if (b != null && b.length > 0) c.band = b[0];
            }
            fillLte(c, s);
        } else if (info instanceof CellInfoNr) {
            CellInfoNr n = (CellInfoNr) info;
            CellIdentityNr id = (CellIdentityNr) n.getCellIdentity();
            CellSignalStrengthNr s = (CellSignalStrengthNr) n.getCellSignalStrength();
            c.tech = "NR";
            c.mcc = Fmt.nz(id.getMccString());
            c.mnc = Fmt.nz(id.getMncString());
            c.arfcn = valid(id.getNrarfcn(), 0, 3279165);
            c.pci = valid(id.getPci(), 0, 1007);
            c.tac = valid(id.getTac(), 0, 16777215);
            long nci = id.getNci();
            c.cid = (nci == NAL || nci < 0) ? NAL : nci;
            if (c.arfcn != NA) c.dlMhz = Bands.nrFreqMhz(c.arfcn);
            if (Build.VERSION.SDK_INT >= 30) {
                int[] b = id.getBands();
                if (b != null && b.length > 0) c.band = b[0];
            }
            if (c.band == NA) c.band = Bands.nrBand(c.dlMhz);
            fillNr(c, s);
        } else if (info instanceof CellInfoWcdma) {
            CellInfoWcdma w = (CellInfoWcdma) info;
            CellIdentityWcdma id = w.getCellIdentity();
            CellSignalStrengthWcdma s = w.getCellSignalStrength();
            c.tech = "WCDMA";
            c.mcc = Fmt.nz(id.getMccString());
            c.mnc = Fmt.nz(id.getMncString());
            c.arfcn = valid(id.getUarfcn(), 0, 16383);
            c.pci = valid(id.getPsc(), 0, 511);
            c.tac = valid(id.getLac(), 0, 65535);
            int cid = id.getCid();
            c.cid = (cid == NA || cid < 0 || cid > 268435455) ? NAL : cid;
            if (c.arfcn != NA) {
                double[] u = Bands.umts(c.arfcn);
                c.band = (int) u[0];
                c.dlMhz = u[1];
            }
            c.rscp = valid(s.getDbm(), -120, -24);
            if (Build.VERSION.SDK_INT >= 30) c.ecno = valid(s.getEcNo(), -24, 1);
        } else if (info instanceof CellInfoGsm) {
            CellInfoGsm g = (CellInfoGsm) info;
            CellIdentityGsm id = g.getCellIdentity();
            CellSignalStrengthGsm s = g.getCellSignalStrength();
            c.tech = "GSM";
            c.mcc = Fmt.nz(id.getMccString());
            c.mnc = Fmt.nz(id.getMncString());
            c.arfcn = valid(id.getArfcn(), 0, 1023);
            c.pci = valid(id.getBsic(), 0, 63);
            c.tac = valid(id.getLac(), 0, 65535);
            int cid = id.getCid();
            c.cid = (cid == NA || cid < 0 || cid > 65535) ? NAL : cid;
            if (c.arfcn != NA) {
                double[] gb = Bands.gsm(c.arfcn);
                c.band = (int) gb[0];
                c.dlMhz = gb[1];
            }
            c.rssi = valid(s.getDbm(), -113, -51);
            c.ta = valid(s.getTimingAdvance(), 0, 219);
        } else if (info instanceof CellInfoCdma) {
            c.tech = "CDMA";
        } else if (info instanceof CellInfoTdscdma) {
            c.tech = "TDSCDMA";
        } else {
            c.tech = "OTRO";
        }
        return c;
    }

    private static void fillLte(CellData c, CellSignalStrengthLte s) {
        if (c.rsrp == NA) c.rsrp = valid(s.getRsrp(), -140, -43);
        if (c.rsrq == NA) c.rsrq = valid(s.getRsrq(), -34, 3);
        if (c.rssi == NA) c.rssi = valid(s.getRssi(), -125, -25);
        if (c.sinr == NA) c.sinr = valid(s.getRssnr(), -20, 30);
        if (c.cqi == NA) c.cqi = valid(s.getCqi(), 0, 15);
        if (c.ta == NA) c.ta = valid(s.getTimingAdvance(), 0, 1282);
    }

    private static void fillNr(CellData c, CellSignalStrengthNr s) {
        if (c.rsrp == NA) c.rsrp = valid(s.getSsRsrp(), -156, -31);
        if (c.rsrq == NA) c.rsrq = valid(s.getSsRsrq(), -43, 20);
        if (c.sinr == NA) c.sinr = valid(s.getSsSinr(), -23, 40);
    }

    private static int techRank(String t) {
        switch (t) {
            case "LTE": return 0;
            case "NR": return 1;
            case "WCDMA": return 2;
            case "GSM": return 3;
            default: return 4;
        }
    }

    /**
     * @param cells     lista de CellInfo (puede ser null)
     * @param ss        SignalStrength actual (puede ser null)
     * @param netOp     MCC+MNC del SIM de datos, p. ej. "71615" (para teléfonos dual SIM)
     */
    static Snapshot build(List<CellInfo> cells, SignalStrength ss, String netOp) {
        Snapshot snap = new Snapshot();
        List<CellData> all = new ArrayList<>();
        if (cells != null) {
            for (CellInfo ci : cells) {
                if (ci == null) continue;
                try {
                    all.add(parse(ci));
                } catch (RuntimeException ignored) {
                    // algunos fabricantes devuelven objetos incompletos
                }
            }
        }

        // Celda servidora: registrada, del operador de datos, prioridad LTE (ancla) > NR > 3G > 2G
        CellData best = null;
        for (CellData c : all) {
            if (!c.registered) continue;
            boolean opMatch = netOp == null || netOp.isEmpty() || c.mcc.isEmpty()
                    || netOp.equals(c.mcc + c.mnc);
            if (!opMatch) continue;
            if (best == null || techRank(c.tech) < techRank(best.tech)) best = c;
        }
        if (best == null) {
            for (CellData c : all) {
                if (c.registered && (best == null || techRank(c.tech) < techRank(best.tech))) best = c;
            }
        }

        // Completar con SignalStrength (algunos equipos lo actualizan más seguido que CellInfo)
        if (ss != null) {
            List<CellSignalStrengthLte> lte = ss.getCellSignalStrengths(CellSignalStrengthLte.class);
            if (!lte.isEmpty()) {
                if (best == null) {
                    best = new CellData();
                    best.tech = "LTE";
                    best.registered = true;
                }
                if ("LTE".equals(best.tech)) fillLte(best, lte.get(0));
            }
        }
        snap.serving = best;

        // Pata NR: la servidora si es SA; si no, la NR más fuerte (NSA)
        if (best != null && "NR".equals(best.tech)) {
            snap.nr = best;
        } else {
            for (CellData c : all) {
                if ("NR".equals(c.tech) && (snap.nr == null || c.level() > snap.nr.level())) snap.nr = c;
            }
            if (ss != null) {
                List<CellSignalStrengthNr> nr = ss.getCellSignalStrengths(CellSignalStrengthNr.class);
                if (!nr.isEmpty()) {
                    if (snap.nr == null) {
                        snap.nr = new CellData();
                        snap.nr.tech = "NR";
                    }
                    fillNr(snap.nr, nr.get(0));
                }
            }
            if (snap.nr != null && snap.nr.rsrp == NA && snap.nr.arfcn == NA && snap.nr.pci == NA) snap.nr = null;
        }

        // Vecinas: misma tecnología que la servidora, no registradas, ordenadas por nivel
        if (best != null) {
            String key = best.key();
            for (CellData c : all) {
                if (c == best || c.registered || !c.tech.equals(best.tech)) continue;
                if (c.key().equals(key)) continue;
                snap.neighbors.add(c);
            }
            Collections.sort(snap.neighbors, (a, b) -> Integer.compare(b.level(), a.level()));
        }
        return snap;
    }

    static String netTypeName(int t) {
        switch (t) {
            case 1: return "GPRS";
            case 2: return "EDGE";
            case 3: return "UMTS";
            case 8: return "HSDPA";
            case 9: return "HSUPA";
            case 10: return "HSPA";
            case 13: return "LTE";
            case 15: return "HSPA+";
            case 16: return "GSM";
            case 17: return "TD-SCDMA";
            case 18: return "IWLAN";
            case 19: return "LTE_CA";
            case 20: return "NR";
            default: return "";
        }
    }

    static String overrideName(int t) {
        switch (t) {
            case 1: return "LTE_CA";
            case 2: return "LTE_ADV_PRO";
            case 3: return "NR_NSA";
            case 4: return "NR_NSA_MMW";
            case 5: return "NR_ADVANCED";
            default: return "";
        }
    }
}
