package org.polterheist.mobile;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;

/** An app-private copy of exactly the document the user selected, never their whole library. */
final class AudioFile {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    final File file;
    final String name;
    final long durationMs;
    AudioFile(File file, String name, long durationMs) { this.file = file; this.name = name; this.durationMs = durationMs; }

    static AudioFile copy(Context context, Uri uri) throws IOException {
        String name = "Audio seleccionado";
        try (Cursor c = context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (s >= 0 && !c.isNull(s) && c.getLong(s) > MAX_BYTES) throw new IOException("El audio supera el límite de 512 MiB.");
            }
        }
        if (name == null || name.trim().isEmpty()) name = "Audio seleccionado";
        name = name.substring(0,Math.min(256,name.length()));
        File dir = new File(context.getCacheDir(),"selected-audio");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No se pudo preparar la carpeta temporal.");
        File dest = File.createTempFile("audio-",".media",dir);
        boolean keep = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("No se pudo abrir el archivo.");
                byte[] buffer = new byte[64 * 1024]; long total = 0; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Importación cancelada.");
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("El audio supera el límite de 512 MiB.");
                    out.write(buffer,0,count);
                }
            }
            MediaExtractor extractor = new MediaExtractor();
            boolean audio = false; long duration = 0;
            try {
                extractor.setDataSource(dest.getPath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audio = true;
                        if (f.containsKey(MediaFormat.KEY_DURATION)) duration = Math.max(duration,f.getLong(MediaFormat.KEY_DURATION)/1000);
                    }
                }
            } finally { extractor.release(); }
            if (!audio) throw new IOException("Android no reconoce una pista de audio en este archivo. Prueba MP3, M4A, WAV, Ogg o FLAC.");
            keep = true;
            return new AudioFile(dest,name,duration);
        } finally { if (!keep) dest.delete(); }
    }
}
