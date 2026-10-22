package com.djstudio2.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Egy videósáv dekódolása saját szálon, hardveres MediaCodec-kel, közvetlenül
 * egy GPU textúrába (Surface) – nincs CPU-s képmásolás, így 8K is elfér.
 */
final class VideoDecoder extends Thread {

    private static final class SeekReq {
        final long us;
        final CountDownLatch latch;
        SeekReq(long us, CountDownLatch latch) { this.us = us; this.latch = latch; }
    }

    interface Owner {
        void onVideoEnded(VideoDecoder d);
        void onDecoderError(String what, Throwable t);
    }

    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaCodec codec;
    private final MediaClock clock;
    private final Owner owner;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final AtomicReference<SeekReq> seekReq = new AtomicReference<>();

    final String codecName;
    volatile boolean quit;
    volatile int dropped;
    volatile int rendered;
    volatile boolean ended;

    private boolean inputEOS;
    private long skipUntil = -1;
    private CountDownLatch previewLatch;
    private int heldIndex = -1;
    private long heldPts;
    private long frameDurUs;
    private long lastPts = -1;

    VideoDecoder(Context ctx, Uri uri, int track, String decoderName, float fps,
                 Surface surface, MediaClock clock, Owner owner) throws IOException {
        super("video-dec-" + track);
        setPriority(Thread.MAX_PRIORITY);
        this.clock = clock;
        this.owner = owner;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("A fájl nem nyitható meg");
            extractor.setDataSource(pfd.getFileDescriptor());
        }
        extractor.selectTrack(track);
        MediaFormat fmt = extractor.getTrackFormat(track);
        String mime = fmt.getString(MediaFormat.KEY_MIME);

        MediaCodec c = decoderName != null
                ? MediaCodec.createByCodecName(decoderName)
                : MediaCodec.createDecoderByType(mime);
        try {
            // Valós idejű prioritás + várható képkockasebesség: a dekóder teljes órajelen fut.
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (fps > 0) fmt.setFloat(MediaFormat.KEY_OPERATING_RATE, fps);
            c.configure(fmt, surface, null, 0);
        } catch (Exception e) {
            c.reset();
            c.configure(extractor.getTrackFormat(track), surface, null, 0);
        }
        c.start();
        codec = c;
        codecName = c.getName();
        frameDurUs = (long) (1_000_000f / (fps > 0 ? fps : 30f));
    }

    void requestSeek(long us, CountDownLatch latch) {
        SeekReq old = seekReq.getAndSet(new SeekReq(us, latch));
        if (old != null && old.latch != null) old.latch.countDown();
    }

    @Override
    public void run() {
        try {
            loop();
        } catch (Throwable t) {
            if (!quit) owner.onDecoderError(getName(), t);
        } finally {
            if (previewLatch != null) previewLatch.countDown();
            try { codec.stop(); } catch (Exception ignored) { }
            try { codec.release(); } catch (Exception ignored) { }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    private void loop() {
        while (!quit) {
            SeekReq r = seekReq.getAndSet(null);
            if (r != null) doSeek(r);

            feedInput();

            if (heldIndex < 0 && !ended) {
                int idx = codec.dequeueOutputBuffer(info, 2000);
                if (idx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        codec.releaseOutputBuffer(idx, false);
                        markEnded();
                        continue;
                    }
                    heldIndex = idx;
                    heldPts = info.presentationTimeUs;
                    if (lastPts >= 0 && heldPts > lastPts) {
                        long d = heldPts - lastPts;
                        if (d < 200_000) frameDurUs = (frameDurUs * 7 + d) / 8;
                    }
                    lastPts = heldPts;
                }
            }

            if (heldIndex >= 0) {
                handleHeld();
            } else if (ended) {
                sleepUs(10_000);
            }
        }
    }

    private void handleHeld() {
        long pts = heldPts;

        // Pontos tekerés: a kulcskockától a célig dekódol, de nem jelenít meg.
        if (skipUntil >= 0) {
            if (pts < skipUntil - frameDurUs / 2) {
                release(false);
                return;
            }
            release(true); // előnézeti kocka a célpozíción
            rendered++;
            skipUntil = -1;
            if (previewLatch != null) { previewLatch.countDown(); previewLatch = null; }
            return;
        }

        if (!clock.isRunning()) {
            sleepUs(4000);
            return;
        }

        long delta = pts - clock.nowUs();
        if (delta < -Math.max(frameDurUs * 2, 40_000)) {
            // Lemaradt kocka: eldobjuk, hogy a kép ne lassuljon le és ne csússzon el a hangtól.
            release(false);
            dropped++;
            return;
        }
        if (delta <= 4000) {
            release(true);
            rendered++;
            return;
        }
        sleepUs(Math.min(delta - 3000, 8000));
    }

    private void release(boolean render) {
        codec.releaseOutputBuffer(heldIndex, render);
        heldIndex = -1;
    }

    private void doSeek(SeekReq r) {
        if (heldIndex >= 0) release(false);
        if (previewLatch != null) previewLatch.countDown();
        codec.flush();
        extractor.seekTo(r.us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        inputEOS = false;
        ended = false;
        lastPts = -1;
        skipUntil = r.us;
        previewLatch = r.latch;
    }

    private void markEnded() {
        ended = true;
        skipUntil = -1;
        if (previewLatch != null) { previewLatch.countDown(); previewLatch = null; }
        owner.onVideoEnded(this);
    }

    private void feedInput() {
        for (int n = 0; n < 8 && !inputEOS; n++) {
            int idx = codec.dequeueInputBuffer(0);
            if (idx < 0) return;
            ByteBuffer buf = codec.getInputBuffer(idx);
            int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
            if (size < 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEOS = true;
            } else {
                codec.queueInputBuffer(idx, 0, size, extractor.getSampleTime(), 0);
                extractor.advance();
            }
        }
    }

    static void sleepUs(long us) {
        if (us > 0) LockSupport.parkNanos(us * 1000L);
    }
}
