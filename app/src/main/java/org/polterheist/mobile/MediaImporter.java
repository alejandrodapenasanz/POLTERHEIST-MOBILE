package org.polterheist.mobile;

import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;

public final class MediaImporter {
    public static MediaFile prepare(Context context, Uri uri) throws Exception {
        File directory = new File(context.getCacheDir(), "selected-media");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("No se puede preparar el almacenamiento");
        String name = "Contenido";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        String mime = context.getContentResolver().getType(uri);
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) throw new IOException("Selecciona una imagen o un vídeo");
        boolean image = mime.startsWith("image/");
        File raw = File.createTempFile("input-", ".media", directory);
        File prepared = null;
        boolean success = false;
        try {
            long limit = image ? 50L * 1024 * 1024 : 1024L * 1024 * 1024;
            try (InputStream input = context.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(raw)) {
                if (input == null) throw new IOException("No se ha podido abrir el archivo");
                byte[] buffer = new byte[128 * 1024];
                long size = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    size += n;
                    if (size > limit) throw new IOException(image ? "La imagen supera 50 MB" : "El vídeo supera 1 GB");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (directory.getUsableSpace() < 32L * 1024 * 1024) throw new IOException("Falta espacio en el móvil");
                    output.write(buffer, 0, n);
                }
                if (size == 0) throw new IOException("El archivo está vacío");
            }
            if (image) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(raw.getPath(), options);
                if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Android no puede leer esta imagen");
                if ((long) options.outWidth * options.outHeight > 100_000_000) throw new IOException("La imagen supera 100 megapíxeles");
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2560) options.inSampleSize *= 2;
                Bitmap decoded = BitmapFactory.decodeFile(raw.getPath(), options);
                if (decoded == null) throw new IOException("No se puede decodificar la imagen");
                Bitmap rotated = decoded;
                try {
                    ExifInterface exif = new ExifInterface(raw);
                    Matrix matrix = new Matrix();
                    if (exif.isFlipped()) matrix.postScale(-1, 1);
                    matrix.postRotate(exif.getRotationDegrees());
                    rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                    float scale = Math.min(1f, 1920f / Math.max(rotated.getWidth(), rotated.getHeight()));
                    Bitmap output = Bitmap.createBitmap(Math.max(1, Math.round(rotated.getWidth() * scale)), Math.max(1, Math.round(rotated.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    try {
                        Canvas canvas = new Canvas(output);
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(rotated, null, new Rect(0, 0, output.getWidth(), output.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
                        prepared = File.createTempFile("image-", ".jpg", directory);
                        try (OutputStream stream = new FileOutputStream(prepared)) {
                            if (!output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw new IOException("No se pudo preparar el JPEG");
                        }
                    } finally { output.recycle(); }
                } finally {
                    if (rotated != decoded) rotated.recycle();
                    decoded.recycle();
                }
                success = true;
                return new MediaFile(prepared, name, "image/jpeg", 0);
            }
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(raw.getPath());
                if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) throw new IOException("El archivo no contiene vídeo legible");
                String detected = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                prepared = raw;
                success = true;
                return new MediaFile(raw, name, detected != null && detected.startsWith("video/") ? detected : mime,
                        duration == null ? 0 : Long.parseLong(duration));
            } finally { metadata.release(); }
        } finally {
            if (!success || prepared != raw) raw.delete();
            if (!success && prepared != null) prepared.delete();
        }
    }
}
