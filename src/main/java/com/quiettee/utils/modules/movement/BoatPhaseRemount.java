package com.quiettee.utils.modules.movement;

public final class BoatPhaseRemount {
    private int boatId = -1;
    private int armedAt;
    public void reset() { boatId = -1; armedAt = 0; }
    public boolean intent(int id, int tick, boolean grounded, boolean deliberate, boolean fallback, boolean enabled) {
        if (!enabled) { reset(); return false; }
        if (!grounded || !deliberate || !fallback || id < 0) return false;
        boatId = id; armedAt = tick;
        return true;
    }
    public boolean board(int id, int tick, boolean driver, boolean enabled) {
        if (!enabled || tick < armedAt || tick - armedAt > 200 || id != boatId) { reset(); return false; }

        if (!driver) return false;
        boolean retry = boatId != -1;
        reset();
        return retry;
    }
}
