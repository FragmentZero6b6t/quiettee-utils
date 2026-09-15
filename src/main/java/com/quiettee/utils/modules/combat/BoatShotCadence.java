package com.quiettee.utils.modules.combat;

public final class BoatShotCadence {
    public static boolean mayDraw(BoatShotCycle.Stage stage, boolean rapid) {
        return stage == BoatShotCycle.Stage.IDLE || rapid && (stage == BoatShotCycle.Stage.WAIT_CLEAR
            || stage == BoatShotCycle.Stage.RETURNING);
    }
    public static int minimumDraw(boolean rapid, int shortDraw, int fullDraw) {
        return rapid ? Math.clamp(shortDraw, 3, 20) : Math.max(20, fullDraw);
    }
    public static boolean mayRelease(BoatShotCycle.Stage stage, boolean rapid, int sinceRelease) {
        return stage == BoatShotCycle.Stage.IDLE && (!rapid || sinceRelease >= 10);
    }
}
