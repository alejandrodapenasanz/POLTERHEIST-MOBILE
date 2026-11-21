package org.polterheist.mobile;

/** A single RFC 9110 byte range. Multiple ranges are deliberately unsupported. */
public final class ByteRange {
    public final long start, end;
    public final boolean partial;

    private ByteRange(long start, long end, boolean partial) {
        this.start = start;
        this.end = end;
        this.partial = partial;
    }

    public long length() { return Math.max(0, end - start + 1); }

    public static ByteRange parse(String header, long size) {
        if (size < 0) throw new IllegalArgumentException("Unknown size");
        if (header == null) return new ByteRange(0, size - 1, false);
        if (size == 0 || !header.matches("bytes=[0-9]*-[0-9]*")) {
            throw new IllegalArgumentException("Invalid range");
        }
        String[] parts = header.substring(6).split("-", -1);
        try {
            long start, end;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) throw new IllegalArgumentException("Empty suffix");
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
            }
            if (start >= size || end < start) throw new IllegalArgumentException("Unsatisfiable range");
            return new ByteRange(start, end, true);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number", e);
        }
    }
}
