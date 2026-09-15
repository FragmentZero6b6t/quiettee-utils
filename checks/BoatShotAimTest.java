package com.quiettee.utils.modules.combat;

import com.quiettee.utils.modules.movement.BoatPhaseMotion;

public final class BoatShotAimTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void shot(double x, double y, double z, double vx, double vy, double vz, double burst) {
        BoatShotAim.Solution aim = BoatShotAim.solve(x, y, z, vx, vy, vz, 3, burst, 1);
        check(aim != null, "Reachable target must have an intercept");
        double yaw = Math.toRadians(aim.yaw()), pitch = Math.toRadians(aim.pitch());
        double dx = -3 * Math.sin(yaw) * Math.cos(pitch), dz = 3 * Math.cos(yaw) * Math.cos(pitch);
        double dy = -3 * Math.sin(pitch) + burst, px = 0, py = 0, pz = 0;
        for (int n = 0; n < Math.ceil(aim.ticks()); n++) {
            double f = Math.min(1, aim.ticks() - n);
            px += dx * f; py += dy * f; pz += dz * f;
            dx *= .99; dz *= .99; dy = dy * .99 - .05;
        }
        double error = Math.sqrt(Math.pow(px-x-vx*aim.ticks(),2)+Math.pow(py-y-vy*aim.ticks(),2)+Math.pow(pz-z-vz*aim.ticks(),2));
        check(error < .001, "Independent discrete-flight simulation must hit moving target: " + error);
    }
    public static void main(String[] args) {
        for (double burst : new double[]{-3,-9,-9.9,-20,-29}) {
            for (double y : new double[]{-20,-50,-100,-150}) {
                shot(0,y,0,0,0,0,burst);
                if (Math.abs(burst) <= 10 || y < -50) shot(3,y,2,.05,0,-.03,burst);
            }
        }
        shot(2,60,2,.05,0,0,9);
        shot(10,-100,-8,.2,0,.1,-9);
        check(BoatShotAim.solve(100,-10,0,0,0,0,3,-9,1)==null,"Unreachable horizontal target cannot pretend to be aimed");
        check(BoatShotAim.solve(0,-100,0,Double.NaN,0,0,3,-9,1)==null,"Invalid prediction rejected");
        check(BoatShotAim.solve(0,-100,0,0,0,0,0,-9,1)==null,"Uncharged bow rejected");
        BoatShotEvents events = new BoatShotEvents();
        Object player = new Object();
        check(events.tick(player,1),"First client tick accepted");
        check(!events.tick(player,1),"Duplicate Pre callback ignored");
        check(events.tick(player,2),"Next real tick accepted");
        check(events.tick(new Object(),2),"New player session accepted even at same age");
        Object packet = new Object();
        check(events.vehicle(packet) && !events.vehicle(packet),"Repeated Sent notification ignored");
        BoatPhaseMotion motion = new BoatPhaseMotion();
        BoatPhaseMotion.Point previous = BoatPhaseMotion.Point.ZERO, position = BoatPhaseMotion.Point.ZERO;
        for (int i=0;i<12;i++) {
            motion.nextTick();
            var step=BoatPhaseMotion.airStep(new BoatPhaseMotion.Point(.99,0,0),previous,.1,i,false,false,100);
            var next=new BoatPhaseMotion.Point(position.x()+step.x(),0,0);
            for(int duplicate=0;duplicate<2;duplicate++) {
                if(motion.alreadySent()) continue;
                motion.sent(position,next);previous=step;
            }
            position=next;
        }
        check(Math.abs(previous.x()-.99)<1e-8,"Duplicate Sent events must not leave flight stuck at .1 b/t");
        System.out.println("BoatShotAimTest: " + checks + " intercept and event regression checks passed");
    }
}
