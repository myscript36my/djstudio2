package com.djstudio2.app;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lejátszó: egy fájl összes képsávját (OSV-nél a két lencse) párhuzamosan,
 * külön hardveres dekóderrel játssza le, közös órán szinkronban.
 */
final class Player implements VideoDecoder.Owner {

    interface Listener {
        void onPrepared(Player p);
        void onError(String msg);
        void onCompleted();
    }

    static final class TrackInfo {
        int index;
        String mime;
        int width, height;
        float fps;
        String decoder;
        boolean hardware;
        boolean rateSupported = true;
        boolean twoInstances = true;
    }

    private final Context ctx;
    private final Uri uri;
    private final Surface[] surfaces;
    private final Listener listener;
    private final HandlerThread ctlThread = new HandlerThread("player-ctl");
    private final Handler ctl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaClock clock = new MediaClock();
    private final List<VideoDecoder> videos = new CopyOnWriteArrayList<>();
    private final AtomicLong pendingSeek = new AtomicLong(-1);
    private volatile AudioDecoder audio;

    final List<TrackInfo> tracks = new ArrayList<>();
    long durationUs;
    String audioInfo = "nincs";

    private volatile boolean released;
    private volatile boolean completed;

    Player(Context ctx, Uri uri, Surface[] surfaces, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.uri = uri;
        this.surfaces = surfaces;
        this.listener = listener;
        ctlThread.start();
        ctl = new Handler(ctlThread.getLooper());
    }

    void prepare(long startUs, boolean autoPlay) {
        ctl.post(() -> {
            try {
                doPrepare();
                doSeek(startUs);
                if (released) return;
                main.post(() -> { if (!released) listener.onPrepared(this); });
                if (autoPlay) clock.start();
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                main.post(() -> { if (!released) listener.onError(msg); });
            }
        });
    }

    private void doPrepare() throws IOException {
        MediaExtractor ex = new MediaExtractor();
        int audioIndex = -1;
        List<TrackInfo> found = new ArrayList<>();
        try {
            try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) throw new IOException("A fájl nem nyitható meg");
                ex.setDataSource(pfd.getFileDescriptor());
            }
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = Math.max(durationUs, f.getLong(MediaFormat.KEY_DURATION));
                }
                if (mime.startsWith("video/")) {
                    TrackInfo t = new TrackInfo();
                    t.index = i;
                    t.mime = mime;
                    t.width = f.getInteger(MediaFormat.KEY_WIDTH);
                    t.height = f.getInteger(MediaFormat.KEY_HEIGHT);
                    t.fps = readFps(f);
                    found.add(t);
                } else if (mime.startsWith("audio/") && audioIndex < 0) {
                    audioIndex = i;
                }
            }
        } finally {
            ex.release();
        }
        if (found.isEmpty()) throw new IOException("Nincs videósáv a fájlban (nem támogatott formátum?)");

        // A legnagyobb felbontású sávok kellenek (az OSV-ben a két lencse), legfeljebb kettő.
        long maxPix = 0;
        for (TrackInfo t : found) maxPix = Math.max(maxPix, (long) t.width * t.height);
        for (TrackInfo t : found) {
            if ((long) t.width * t.height == maxPix && tracks.size() < surfaces.length) tracks.add(t);
        }

        for (int k = 0; k < tracks.size(); k++) {
            TrackInfo t = tracks.get(k);
            pickDecoder(t, tracks.size());
            VideoDecoder vd = new VideoDecoder(ctx, uri, t.index, t.decoder, t.fps,
                    surfaces[k], clock, this);
            t.decoder = vd.codecName;
            videos.add(vd);
        }
        for (VideoDecoder vd : videos) vd.start();

        if (audioIndex >= 0) {
            try {
                audio = new AudioDecoder(ctx, uri, audioIndex, clock);
                audioInfo = audio.description;
                audio.start();
            } catch (Exception e) {
                audio = null;
                audioInfo = "nem játszható le (" + e.getMessage() + ")";
            }
        }
    }

    private static float readFps(MediaFormat f) {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 0;
        try {
            return f.getInteger(MediaFormat.KEY_FRAME_RATE);
        } catch (ClassCastException e) {
            try { return f.getFloat(MediaFormat.KEY_FRAME_RATE); } catch (Exception ignored) { return 0; }
        }
    }

    /** Hardveres dekódert keres, és megnézi, hivatalosan bírja-e a felbontást/fps-t. */
    private static void pickDecoder(TrackInfo t, int instancesNeeded) {
        MediaFormat probe = MediaFormat.createVideoFormat(t.mime, t.width, t.height);
        MediaCodecInfo best = null;
        boolean bestHw = false;
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (ci.isEncoder() || ci.getName().endsWith(".secure")) continue;
            boolean supportsType = false;
            for (String type : ci.getSupportedTypes()) {
                if (type.equalsIgnoreCase(t.mime)) { supportsType = true; break; }
            }
            if (!supportsType) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(t.mime);
                if (!caps.isFormatSupported(probe)) continue;
            } catch (Exception e) {
                continue;
            }
            boolean hw = isHardware(ci);
            if (best == null || (hw && !bestHw)) {
                best = ci;
                bestHw = hw;
            }
        }
        if (best == null) {
            t.decoder = null;
            t.hardware = false;
            t.rateSupported = false;
            return;
        }
        t.decoder = best.getName();
        t.hardware = bestHw;
        try {
            MediaCodecInfo.CodecCapabilities caps = best.getCapabilitiesForType(t.mime);
            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
            if (vc != null) {
                t.rateSupported = t.fps > 0
                        ? vc.areSizeAndRateSupported(t.width, t.height, t.fps)
                        : vc.isSizeSupported(t.width, t.height);
            }
            t.twoInstances = caps.getMaxSupportedInstances() >= instancesNeeded;
        } catch (Exception ignored) { }
    }

    private static boolean isHardware(MediaCodecInfo ci) {
        if (Build.VERSION.SDK_INT >= 29) return ci.isHardwareAccelerated();
        String n = ci.getName().toLowerCase();
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg"));
    }

    private void doSeek(long us) {
        boolean wasRunning = clock.isRunning();
        clock.pause();
        clock.setPosition(us);
        completed = false;
        CountDownLatch latch = new CountDownLatch(videos.size());
        for (VideoDecoder v : videos) v.requestSeek(us, latch);
        if (audio != null) audio.requestSeek(us);
        try {
            // Megvárjuk, míg minden lencse kész a célkockával – így indul akadás nélkül.
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        if (wasRunning && !released) clock.start();
    }

    void play() {
        ctl.post(() -> {
            if (released) return;
            if (completed) doSeek(0);
            completed = false;
            clock.start();
        });
    }

    void pause() {
        clock.pause();
    }

    void seekTo(long us) {
        pendingSeek.set(Math.max(0, Math.min(us, durationUs)));
        ctl.post(() -> {
            long s = pendingSeek.getAndSet(-1);
            if (s >= 0 && !released) doSeek(s);
        });
    }

    boolean isPlaying() {
        return clock.isRunning();
    }

    long positionUs() {
        long p = clock.nowUs();
        if (durationUs > 0) p = Math.min(p, durationUs);
        return Math.max(0, p);
    }

    int droppedFrames() {
        int d = 0;
        for (VideoDecoder v : videos) d += v.dropped;
        return d;
    }

    @Override
    public synchronized void onVideoEnded(VideoDecoder d) {
        for (VideoDecoder v : videos) if (!v.ended) return;
        if (completed) return;
        if (!clock.isRunning()) return; // tekerés a végére, nem valódi lejátszás vége
        clock.pause();
        completed = true;
        main.post(() -> { if (!released) listener.onCompleted(); });
    }

    @Override
    public void onDecoderError(String what, Throwable t) {
        clock.pause();
        String msg = what + ": " + (t.getMessage() != null ? t.getMessage() : t.toString());
        main.post(() -> { if (!released) listener.onError(msg); });
    }

    /** Szinkron leállítás: a dekóderek elengedik a felületeket, mielőtt új lejátszó jönne. */
    void release() {
        released = true;
        clock.pause();
        ctl.removeCallbacksAndMessages(null);
        ctlThread.quitSafely();
        for (VideoDecoder v : videos) v.quit = true;
        if (audio != null) audio.quit = true;
        try { ctlThread.join(3000); } catch (InterruptedException ignored) { }
        for (VideoDecoder v : videos) v.quit = true;
        if (audio != null) audio.quit = true;
        for (VideoDecoder v : videos) {
            try { v.join(3000); } catch (InterruptedException ignored) { }
        }
        if (audio != null) {
            try { audio.join(2000); } catch (InterruptedException ignored) { }
        }
    }
}
