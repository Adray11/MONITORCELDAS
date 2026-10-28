package com.monitorceldas.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Gráfico de los últimos ~3 minutos de RSRP con las líneas de umbral −80/−90/−100 dBm. */
final class RsrpGraphView extends View {
    private static final float MIN = -130f, MAX = -60f;
    private float[] data = new float[0];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint();
    private final float density;

    RsrpGraphView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        line.setStrokeWidth(2.5f * density);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setColor(0x55888888);
        grid.setStrokeWidth(density);
        text.setColor(0xAA999999);
        text.setTextSize(10 * density);
    }

    void setData(float[] d) {
        data = d;
        invalidate();
    }

    static int colorFor(float v) {
        return Ui.rsrpColor(Math.round(v));
    }

    private float y(float v) {
        float h = getHeight();
        float c = Math.max(MIN, Math.min(MAX, v));
        return h - (c - MIN) / (MAX - MIN) * h;
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float w = getWidth();
        // Zonas de calidad de fondo (Tabla 1)
        float[][] zones = {{MAX, -80}, {-80, -90}, {-90, -100}, {-100, MIN}};
        int[] zc = {Ui.EXC, Ui.GOOD, Ui.REG, Ui.BAD};
        for (int i = 0; i < zones.length; i++) {
            zone.setColor((zc[i] & 0x00FFFFFF) | 0x1A000000);
            cv.drawRect(0, y(zones[i][0]), w, y(zones[i][1]), zone);
        }
        for (float t : new float[]{-80, -90, -100, -110}) {
            float yy = y(t);
            cv.drawLine(0, yy, w, yy, grid);
            cv.drawText(Integer.toString((int) t), 2 * density, yy - 2 * density, text);
        }
        int n = data.length;
        if (n < 2) return;
        float dx = w / (n - 1);
        for (int i = 1; i < n; i++) {
            float a = data[i - 1], b = data[i];
            if (Float.isNaN(a) || Float.isNaN(b)) continue;
            line.setColor(colorFor(b));
            cv.drawLine((i - 1) * dx, y(a), i * dx, y(b), line);
        }
    }
}
