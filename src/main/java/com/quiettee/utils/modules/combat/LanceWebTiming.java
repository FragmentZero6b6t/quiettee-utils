package com.quiettee.utils.modules.combat;

final class LanceWebTiming {
    private LanceWebTiming() {}

    static boolean canStart(int now, int lastVolley, int period, boolean couchReady) {
        if (lastVolley < 0) return true;
        long elapsed = (long) now - lastVolley;
        return couchReady && elapsed >= Math.max(10, period);
    }
}
