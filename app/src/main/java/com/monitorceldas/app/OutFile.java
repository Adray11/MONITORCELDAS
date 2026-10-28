package com.monitorceldas.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Archivo de salida en Descargas/MonitorCeldas (visible desde el administrador de archivos y la PC).
 * Si MediaStore falla, se escribe en la carpeta privada de la app.
 */
final class OutFile {
    static final String FOLDER = "MonitorCeldas";

    final String name;
    Uri uri;           // si se guardó vía MediaStore
    File file;         // si se usó la carpeta privada
    private BufferedWriter w;

    private OutFile(String name) {
        this.name = name;
    }

    static OutFile create(Context ctx, String name, String mime) throws IOException {
        OutFile f = new OutFile(name);
        OutputStream os = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            // Usar el tipo que Android asocia a la extensión evita que se agregue una extensión extra
            String ext = name.substring(name.lastIndexOf('.') + 1);
            String sysMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            v.put(MediaStore.MediaColumns.MIME_TYPE, sysMime != null ? sysMime : mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u != null) {
                os = cr.openOutputStream(u, "w");
                if (os != null) f.uri = u;
            }
        } catch (Exception e) {
            os = null;
        }
        if (os == null) {
            File dir = new File(ctx.getExternalFilesDir(null), FOLDER);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            f.file = new File(dir, name);
            os = new FileOutputStream(f.file);
        }
        f.w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 32 * 1024);
        return f;
    }

    String location() {
        return uri != null ? "Descargas/" + FOLDER + "/" + name : (file != null ? file.getAbsolutePath() : name);
    }

    synchronized void line(String s) {
        if (w == null) return;
        try {
            w.write(s);
            w.write("\n");
        } catch (IOException ignored) {
        }
    }

    synchronized void write(String s) {
        if (w == null) return;
        try {
            w.write(s);
        } catch (IOException ignored) {
        }
    }

    synchronized void flush() {
        if (w == null) return;
        try {
            w.flush();
        } catch (IOException ignored) {
        }
    }

    synchronized void close() {
        if (w == null) return;
        try {
            w.flush();
            w.close();
        } catch (IOException ignored) {
        }
        w = null;
    }
}
