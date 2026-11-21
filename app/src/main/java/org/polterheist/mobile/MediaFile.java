package org.polterheist.mobile;

import java.io.File;

public final class MediaFile {
    public final File file;
    public final String name, mime;
    public final long durationMs;

    public MediaFile(File file, String name, String mime, long durationMs) {
        if (mime == null || !mime.matches("(?:image|video)/[A-Za-z0-9.+-]+")) throw new IllegalArgumentException("Tipo de archivo multimedia no válido");
        this.file = file;
        this.name = name == null ? "Contenido" : name.substring(0, Math.min(name.length(), 512));
        this.mime = mime;
        this.durationMs = durationMs;
    }

    public boolean isImage() { return mime.startsWith("image/"); }
}
