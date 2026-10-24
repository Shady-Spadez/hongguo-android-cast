package io.github.jackotom.hongguo.android;

import java.io.InterruptedIOException;

/** Bounded quality stages. Each attempt retains the existing single-quality retry policy. */
final class AdaptiveQuality {
    interface Attempt<T> { T prepare(String quality) throws Exception; }
    interface Eligible { boolean test(Exception error); }
    interface Alive { boolean test(); }
    interface Progress { void changed(String quality, int stage, Exception previous); }
    interface Discard<T> { void release(T value); }
    static final class Result<T> {
        final String quality; final T value; final int stages;
        Result(String quality, T value, int stages) { this.quality = quality; this.value = value; this.stages = stages; }
    }
    static String next(String quality) {
        switch (MediaQuality.normalize(quality)) {
            case "1080p": return "720p";
            case "720p": return "auto";
            default: return null;
        }
    }
    private static void check(Alive alive) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || !alive.test()) throw new InterruptedIOException("播放准备已取消");
    }
    static <T> Result<T> prepare(String initial, Attempt<T> attempt, Eligible eligible, Alive alive, Progress progress, Discard<T> discard) throws Exception {
        String quality = MediaQuality.normalize(initial); Exception previous = null;
        for (int stage = 1; stage <= 3; stage++) {
            check(alive); progress.changed(quality, stage, previous); check(alive);
            T value;
            try { value = attempt.prepare(quality); }
            catch (Exception error) {
                check(alive);
                String lower = next(quality);
                if (lower == null || !eligible.test(error)) throw error;
                previous = error; quality = lower; continue;
            }
            try { check(alive); }
            catch (Exception cancelled) { discard.release(value); throw cancelled; }
            return new Result<>(quality, value, stage);
        }
        throw new IllegalStateException("清晰度准备阶段超出上限");
    }
}
