package com.zcinema.client;

public final class ClientClock {
    private static volatile long offsetMs = 0L;
    private static volatile long bestRttMs = Long.MAX_VALUE;

    private ClientClock() {}

    public static void reset() {
        offsetMs = 0L;
        bestRttMs = Long.MAX_VALUE;
    }

    public static void onPong(long clientSendMs, long serverTimeMs) {
        long now = System.currentTimeMillis();
        long rtt = Math.max(0L, now - clientSendMs);
        if (rtt <= bestRttMs) {
            bestRttMs = rtt;
            offsetMs = (serverTimeMs + rtt / 2L) - now;
        }
    }

    public static long now() {
        return System.currentTimeMillis() + offsetMs;
    }
}
