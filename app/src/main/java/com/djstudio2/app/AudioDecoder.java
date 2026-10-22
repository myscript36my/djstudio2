package com.djstudio2.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicLong;

/** Hangsáv dekódolása és lejátszása a közös órához igazítva. */
final class AudioDecoder extends Thread {

    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaCodec codec;
    private final MediaClock clock;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final AtomicLong seekReq = new AtomicLong(-1);

    volatile boolean quit;
    final String description;

    private AudioTrack track;
    private boolean trackPlaying;
    private int sampleRate;
    private int inChannels;
    private boolean inputEOS, outputEOS;
    private long skipUntil = -1;
    private byte[] pending;
    private int pendingOff;
    private long pendingPts;

    AudioDecoder(Context ctx, Uri uri, int trackIndex, MediaClock clock) throws IOException {
        super("audio-dec");
        setPriority(Thread.MAX_PRIORITY - 1);
        this.clock = clock;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("A fájl nem nyitható meg");
            extractor.setDataSource(pfd.getFileDescriptor());
        }
        extractor.selectTrack(trackIndex);
        MediaFormat fmt = extractor.getTrackFormat(trackIndex);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();
        createTrack();
        description = mime + " " + sampleRate + " Hz, " + inChannels + " csatorna";
    }

    private void createTrack() {
        int outCh = Math.min(inChannels, 2);
        int mask = outCh == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int halfSecond = sampleRate * outCh * 2 / 2;
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf * 2, halfSecond))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        trackPlaying = false;
    }

    void requestSeek(long us) {
        seekReq.set(us);
    }

    @Override
    public void run() {
        try {
            loop();
        } catch (Throwable ignored) {
            // A hang hibája ne állítsa le a képet.
        } finally {
            try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) { }
            try { codec.stop(); } catch (Exception ignored) { }
            try { codec.release(); } catch (Exception ignored) { }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    private void loop() {
        while (!quit) {
            long s = seekReq.getAndSet(-1);
            if (s >= 0) {
                if (trackPlaying) { track.pause(); trackPlaying = false; }
                track.flush();
                codec.flush();
                extractor.seekTo(s, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                inputEOS = false;
                outputEOS = false;
                pending = null;
                skipUntil = s;
            }

            feedInput();

            if (pending == null && !outputEOS) {
                int idx = codec.dequeueOutputBuffer(info, 5000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (sr != sampleRate || ch != inChannels) {
                        track.release();
                        sampleRate = sr;
                        inChannels = ch;
                        createTrack();
                    }
                } else if (idx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEOS = true;
                    if (info.size > 0) {
                        ByteBuffer b = codec.getOutputBuffer(idx);
                        if (b != null) {
                            b.position(info.offset);
                            b.limit(info.offset + info.size);
                            pending = toStereo(b);
                            pendingOff = 0;
                            pendingPts = info.presentationTimeUs;
                        }
                    }
                    codec.releaseOutputBuffer(idx, false);
                }
            }

            if (pending == null) {
                if (outputEOS) VideoDecoder.sleepUs(10_000);
                continue;
            }
            if (pendingPts < skipUntil) { pending = null; continue; }

            if (!clock.isRunning()) {
                if (trackPlaying) { track.pause(); trackPlaying = false; }
                VideoDecoder.sleepUs(5000);
                continue;
            }

            long delta = pendingPts - clock.nowUs();
            if (delta < -150_000) { pending = null; continue; }  // késő hang: eldobjuk
            if (delta > 200_000) { VideoDecoder.sleepUs(5000); continue; }

            if (!trackPlaying) { track.play(); trackPlaying = true; }
            int w = track.write(pending, pendingOff, pending.length - pendingOff,
                    AudioTrack.WRITE_NON_BLOCKING);
            if (w < 0) { pending = null; continue; }
            pendingOff += w;
            if (pendingOff >= pending.length) pending = null;
            else VideoDecoder.sleepUs(3000);
        }
    }

    /** Több csatornás (pl. 4 mikrofonos) hangból az első kettőt tartja meg. */
    private byte[] toStereo(ByteBuffer b) {
        int size = b.remaining();
        if (inChannels <= 2) {
            byte[] out = new byte[size];
            b.get(out);
            return out;
        }
        ShortBuffer in = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int frames = in.remaining() / inChannels;
        byte[] out = new byte[frames * 4];
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            int base = f * inChannels;
            ob.putShort(in.get(base));
            ob.putShort(in.get(base + 1));
        }
        return out;
    }

    private void feedInput() {
        for (int n = 0; n < 4 && !inputEOS; n++) {
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
}
