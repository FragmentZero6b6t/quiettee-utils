package com.quiettee.utils.modules.combat;

final class LanceBeatTiming {
    private boolean anchored;
    private int lastStrikeTick;

    void reset() {
        anchored = false;
    }

    void strike(int tick) {
        anchored = true;
        lastStrikeTick = tick;
    }

    boolean due(int tick, int period) {
        return remaining(tick, period) == 0;
    }

    int remaining(int tick, int period) {
        if (!anchored) return 0;
        int interval = Math.max(1, period);
        long elapsed = (long) tick - lastStrikeTick;
        if (elapsed < 0) return interval;
        return elapsed >= interval ? 0 : (int) (interval - elapsed);
    }
}
