package com.djstudio2.app;

/** Közös lejátszási óra: minden dekóder ehhez igazítja a képkockákat. */
final class MediaClock {
    private long basePtsUs = 0;
    private long baseNanos = 0;
    private boolean running = false;

    synchronized long nowUs() {
        if (!running) return basePtsUs;
        return basePtsUs + (System.nanoTime() - baseNanos) / 1000L;
    }

    synchronized boolean isRunning() {
        return running;
    }

    synchronized void start() {
        if (running) return;
        baseNanos = System.nanoTime();
        running = true;
    }

    synchronized void pause() {
        if (!running) return;
        basePtsUs = nowUs();
        running = false;
    }

    synchronized void setPosition(long us) {
        basePtsUs = us;
        baseNanos = System.nanoTime();
    }
}
