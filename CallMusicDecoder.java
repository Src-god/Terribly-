package org.telegram.messenger.gramify;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * CallMusicDecoder — Android 9 aur purane phones ke liye music-to-call fallback.
 *
 * Android 10 se pehle playback capture nahi hota, isliye gaana khud decode karte hain:
 * <pre>
 *   JioSaavn URL ──(HttpURLConnection)──► cache file ──(MediaExtractor+MediaCodec)──►
 *                    PCM 48k mono  ──► CallMixer ring ──► mic PCM me mix ──► saamne wala sunta hai
 * </pre>
 *
 * Gaana aap bhi sunte ho — MediaPlayer ke apne speaker output se (wo alag chalta rehta hai).
 *
 * Ye sirf tab chalta hai jab API 29+ ka capture available na ho.
 */
public final class CallMusicDecoder {

    private static final int TARGET_RATE = 48000;

    private static volatile boolean running = false;
    private static volatile String status = "off";
    private static volatile long decodedFrames = 0;

    private static Thread thread;
    private static File cachedFile;

    private CallMusicDecoder() {}

    public static boolean isRunning() { return running; }

    public static String getStatus() { return status; }

    public static long getDecodedFrames() { return decodedFrames; }

    /* ------------------------------------------------------------------ */

    public static synchronized void start(Context context, String url) {
        stop();
        clearCacheIfOther(url);
        if (context == null || url == null || url.isEmpty()) {
            status = "URL nahi hai";
            return;
        }
        running = true;
        decodedFrames = 0;
        status = "preparing…";
        final Context app = context.getApplicationContext();
        thread = new Thread(() -> run(app, url), "GramifyMusicDecode");
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        final Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(800); } catch (Throwable ignore) {}
        }
        if (status.startsWith("decoding")) status = "off";
    }

    /** doosra gaana aa gaya to purani cache file hata do (storage saaf rahe) */
    private static void clearCacheIfOther(String url) {
        final File f = cachedFile;
        if (f != null && !f.getName().equals("share_" + Math.abs(url.hashCode()) + ".bin")) {
            try { if (f.exists()) f.delete(); } catch (Throwable ignore) {}
            cachedFile = null;
        }
    }

    public static void clearCache() {
        final File f = cachedFile;
        cachedFile = null;
        try { if (f != null && f.exists()) f.delete(); } catch (Throwable ignore) {}
    }

    /* ------------------------------------------------------------------ */

    private static void run(Context context, String url) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            final File src = download(context, url);
            if (src == null || !running) {
                status = "download fail";
                return;
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(src.getAbsolutePath());

            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                status = "audio track nahi mila";
                return;
            }
            extractor.selectTrack(trackIndex);

            final String mime = format.getString(MediaFormat.KEY_MIME);
            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            status = "decoding " + srcRate + "Hz/" + srcChannels + "ch";
            final Resampler resampler = new Resampler(srcRate, srcChannels, TARGET_RATE);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean outputDone = false;

            while (running && !outputDone) {
                if (!inputDone) {
                    final int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        final ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        final int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                final int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        final ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            final ShortBuffer shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer();
                            final int n = shorts.remaining();
                            final short[] chunk = new short[n];
                            shorts.get(chunk);
                            // 48k mono me badal kar ring me daalo
                            resampler.push(chunk, n);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;

                    // ring bhar gaya to ruk jao (real-time speed pe chalna hai)
                    while (running && CallMixer.freeFrames() < 4800) {
                        try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                    }
                }
            }
            if (running) status = "decoding done (" + decodedFrames + " frames)";
            running = false;   // song khatam — replay karne pe dobara start ho sake
        } catch (Throwable t) {
            status = "error: " + t.getClass().getSimpleName();
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignore) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignore) {}
        }
    }

    private static File download(Context context, String url) {
        InputStream in = null;
        FileOutputStream out = null;
        HttpURLConnection conn = null;
        try {
            final File dir = new File(context.getCacheDir(), "gramify_call");
            if (!dir.exists() && !dir.mkdirs()) return null;
            final File dst = new File(dir, "share_" + Math.abs(url.hashCode()) + ".bin");
            if (dst.exists() && dst.length() > 100000) {
                cachedFile = dst;
                return dst;   // pehle se download hai
            }

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() >= 400) {
                status = "HTTP " + conn.getResponseCode();
                return null;
            }
            in = conn.getInputStream();
            out = new FileOutputStream(dst);
            final byte[] buf = new byte[65536];
            int r;
            while (running && (r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            out.flush();
            cachedFile = dst;
            return dst;
        } catch (Throwable t) {
            status = "download error: " + t.getClass().getSimpleName();
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /* ------------------------------------------------------------------ */

    /** simple streaming resampler: kisi bhi rate/channels -> 48k mono, linear interpolation */
    static final class Resampler {
        private final double step;
        private final int channels;
        private double phase = 0.0;
        private short[] pending = new short[4096];
        private int pendingLen = 0;

        Resampler(int srcRate, int srcChannels, int dstRate) {
            this.step = (double) srcRate / (double) dstRate;
            this.channels = Math.max(1, srcChannels);
            if (srcRate == dstRate && this.channels == 1) phase = -1; // mono same-rate fast path
        }

        void push(short[] interleaved, int shorts) {
            final int frames = shorts / channels;
            final short[] mono = new short[frames];
            for (int f = 0; f < frames; f++) {
                int acc = 0;
                for (int c = 0; c < channels; c++) acc += interleaved[f * channels + c];
                mono[f] = (short) (acc / channels);
            }
            if (phase == -1) {
                // same rate + mono: seedha bhej do
                CallMixer.write(mono, 0, frames);
                decodedFrames += frames;
                return;
            }
            convert(mono, frames);
        }

        private void convert(short[] mono, int frames) {
            // pending buffer me append
            if (pendingLen + frames > pending.length) {
                final short[] n = new short[pendingLen + frames + 4096];
                System.arraycopy(pending, 0, n, 0, pendingLen);
                pending = n;
            }
            System.arraycopy(mono, 0, pending, pendingLen, frames);
            pendingLen += frames;

            final int outMax = (int) ((pendingLen - 1) / step) + 1;
            final short[] out = new short[outMax];
            int produced = 0;
            while (phase + 1.0 < pendingLen) {
                final int i0 = (int) phase;
                final double frac = phase - i0;
                final double v = pending[i0] * (1.0 - frac) + pending[i0 + 1] * frac;
                out[produced++] = (short) v;
                phase += step;
            }
            // jo use ho gaya wo hata do
            final int consumed = (int) phase;
            if (consumed > 0) {
                final int rest = pendingLen - consumed;
                if (rest > 0) System.arraycopy(pending, consumed, pending, 0, rest);
                pendingLen = Math.max(0, rest);
                phase -= consumed;
            }
            if (produced > 0) {
                CallMixer.write(out, 0, produced);
                decodedFrames += produced;
            }
        }
    }
}
