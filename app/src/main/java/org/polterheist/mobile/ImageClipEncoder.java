package org.polterheist.mobile;

import android.graphics.*;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;

/** DMR is a video player: a still H.264 clip avoids depending on photo support. */
public final class ImageClipEncoder {
    public static MediaFile encode(MediaFile image) throws Exception {
        final int width = 1280, height = 720, frames = 60, fps = 30;
        Bitmap source = BitmapFactory.decodeFile(image.file.getPath());
        if (source == null) throw new IOException("No se puede preparar la imagen para Cast");
        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(frame);
            canvas.drawColor(Color.BLACK);
            float scale = Math.min((float) width / source.getWidth(), (float) height / source.getHeight());
            float w = source.getWidth() * scale, h = source.getHeight() * scale;
            canvas.drawBitmap(source, null, new RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2), new Paint(Paint.FILTER_BITMAP_FLAG));
        } finally { source.recycle(); }
        int[] pixels = new int[width * height];
        frame.getPixels(pixels, 0, width, 0, 0, width, height);
        frame.recycle();
        byte[][] yuv = new byte[][]{new byte[width * height], new byte[width * height / 4], new byte[width * height / 4]};
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = pixels[y * width + x], r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            yuv[0][y * width + x] = clamp(((66*r + 129*g + 25*b + 128) >> 8) + 16);
            if ((x & 1) == 0 && (y & 1) == 0) {
                int at = y / 2 * (width / 2) + x / 2;
                yuv[1][at] = clamp(((-38*r - 74*g + 112*b + 128) >> 8) + 128);
                yuv[2][at] = clamp(((112*r - 94*g - 18*b + 128) >> 8) + 128);
            }
        }
        File output = File.createTempFile("cast-photo-", ".mp4", image.file.getParentFile());
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean started = false, muxing = false, success = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start(); started = true;
            muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int submitted = 0, track = -1;
            boolean eosInput = false, eosOutput = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!eosOutput) {
                if (System.nanoTime() > deadline) throw new IOException("El codificador de vídeo del móvil no responde");
                if (!eosInput) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        if (submitted == frames) {
                            codec.queueInputBuffer(index, 0, 0, submitted * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosInput = true;
                        } else {
                            try (Image input = codec.getInputImage(index)) {
                                if (input == null) throw new IOException("El codificador del móvil no admite imágenes YUV. Usa DLNA o modo navegador.");
                                Image.Plane[] planes = input.getPlanes();
                                for (int p = 0; p < 3; p++) {
                                    int pw = p == 0 ? width : width / 2, ph = p == 0 ? height : height / 2;
                                    ByteBuffer buffer = planes[p].getBuffer();
                                    int offset = buffer.position(), stride = planes[p].getRowStride(), step = planes[p].getPixelStride();
                                    for (int row = 0; row < ph; row++) {
                                        if (step == 1) { buffer.position(offset + row * stride); buffer.put(yuv[p], row * pw, pw); }
                                        else for (int col = 0; col < pw; col++) buffer.put(offset + row * stride + col * step, yuv[p][row * pw + col]);
                                    }
                                }
                            }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, submitted * 1_000_000L / fps, 0);
                            submitted++;
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxing = true;
                } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && buffer != null) {
                        if (!muxing) throw new IOException("El codificador no indicó el formato");
                        buffer.position(info.offset); buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    eosOutput = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
            }
            muxer.stop(); muxing = false;
            success = true;
            return new MediaFile(output, image.name, "video/mp4", 2000);
        } finally {
            if (codec != null) { try { if (started) codec.stop(); } finally { codec.release(); } }
            if (muxer != null) { try { if (muxing) muxer.stop(); } finally { muxer.release(); } }
            if (!success) output.delete();
        }
    }
    private static byte clamp(int n) { return (byte) Math.max(0, Math.min(255, n)); }
}
