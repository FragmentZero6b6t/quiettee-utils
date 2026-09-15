package com.quiettee.utils.modules.combat;

public final class BoatShotWebTiming {
    private int readyAt;
    public void reset() { readyAt=0; }
    public boolean ready(int tick) { return tick>=readyAt; }
    public void finish(int tick, boolean wasBusy) { if(wasBusy) readyAt=tick+80; }
    public void finish(int tick, boolean wasBusy, int delay) { if(wasBusy)readyAt=tick+Math.max(1,delay); }
    public void rearm() { readyAt=0; }
    public static int settleTicks(int pingMs) { return Math.clamp((int)Math.ceil(Math.max(0,pingMs)/100.0)+2,3,10); }
}
