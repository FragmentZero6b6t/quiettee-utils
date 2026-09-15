import com.quiettee.utils.modules.movement.*;
import com.quiettee.utils.modules.combat.BoatShotDrawLock;
import static com.quiettee.utils.modules.movement.BoatPhaseTravel.Mode.*;

public final class BoatShotV12Test {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var travel = new BoatPhaseTravel();
        var remount = new BoatPhaseRemount();
        for (var rejected : new BoatPhaseTravel.Mode[] {EXTENDED, SUBSTEPS, DIRECT}) {
            travel.retry(); travel.reject(HASH); travel.reject(rejected); travel.newRide();
            check(travel.rejected(rejected), "ejection/seat update alone keeps fallback");
            check(!remount.intent(17, 100, true, false, travel.flightRejected(), true), "ejection alone cannot arm retry");
            check(remount.intent(17, 101, true, true, travel.flightRejected(), true), "deliberate airborne retry eligibility arms");
            check(!remount.board(17, 102, false, true), "wait for driver seat");
            check(remount.board(17, 103, true, true), "driver seat consumes intent");
            travel.retryFlight();
            check(!travel.flightRejected() && travel.rejected(HASH), "retry all flight modes while preserving hash rejection");
            check(travel.select(false, false, true, false, true) == EXTENDED, "configured extension restored, no forced 3.8");
            check(travel.corrected(1) == LIMITED, "old rejection evidence cannot contaminate new ride");
            check(!remount.board(17, 104, true, true), "one-shot intent");
        }
        var lock = new BoatShotDrawLock();
        check(lock.update(true,true,false,false), "first manual draw selects");
        for (int i=0;i<30;i++) check(!lock.update(true,true,false,false), "looking around while drawing keeps lock");
        check(!lock.update(false,false,false,false), "release retains lock policy until next draw");
        check(lock.update(true,true,false,false), "next manual draw replaces stale follow lock");
        lock.reset();
        check(lock.update(true,true,true,false), "first automatic draw selects");
        for(int i=0;i<20;i++) {
            check(!lock.update(false,true,true,false), "automatic release retains held intent");
            check(!lock.update(true,true,true,false), "held automatic redraw never switches target");
        }
        lock.update(false,false,true,false);
        check(lock.update(true,true,true,false), "release and repress use selects again");
        lock.reset();
        check(!lock.update(true,true,false,true), "active shot/web defers new lock");
        check(lock.update(true,true,false,false), "deferred new lock applies once safe");
        check(!lock.update(true,true,false,false), "deferred lock consumed exactly once");

        var motion = new BoatPhaseMotion();
        for(double sign : new double[]{-1,1}) for(double v : new double[]{3,9,10,29,45,90,900}) for(int yaw=0;yaw<360;yaw+=5) {
            var origin = new BoatPhaseMotion.Point(0,128,0);
            var plan = motion.plan(origin,1,1,sign>0,sign<0,yaw,9,v,-62,317,false);
            var step = BoatPhaseMotion.airStep(plan,plan,1,1,false,true,190);
            int packets = Math.max(BoatPhaseVertical.count(step.y()), BoatPhaseAirSteps.count(Math.hypot(step.x(),step.z()),10));
            check(packets>=1 && packets<=10, "bounded shared budget for both axes");
            check(step.length()/packets < 20, "each diagonal update stays below 9.9");
            check(Math.abs(step.y())<=99 && Math.hypot(step.x(),step.z())<=9.000000001, "independent axis caps");
            if(v>9) {
                check(Math.abs(step.y())==Math.min(v,99), "high vertical speed survives planning and acceleration");
                check(packets<=5 && Math.hypot(step.x(),step.z())<=4.750000001,"vertical experiment stays within a shared five-update budget");
            }
            var phased=BoatPhaseMotion.phaseStep(step,.24);
            check(Math.abs(phased.y())==Math.abs(step.y()) && Math.hypot(phased.x(),phased.z())<=.24000001, "phase preserves vertical boost and horizontal tolerance");
        }
        for(double speed : new double[]{10,29,45,90}) {
            double y=-62; int ticks=0;
            motion.reset(); motion.rise(379);
            while(motion.riseRemaining()>1e-7 && ticks++<50) {
                motion.nextTick();
                var from=new BoatPhaseMotion.Point(0,y,0);
                var step=motion.plan(from,0,0,false,false,0,9,speed,-62,317,false);
                var to=new BoatPhaseMotion.Point(0,y+step.y(),0);
                check(step.y()>0 && to.y()<=317,"full-height climb progresses without overshoot");
                motion.sent(from,to);y=to.y();
            }
            check(y==317 && motion.riseRemaining()==0,"full-height travel completes");
            if(speed>=45)check(ticks==(int)Math.ceil(379/speed),"fast travel covers the height at configured speed");
        }
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,99.1,-99.1})
            check(BoatPhaseVertical.count(bad)==0,"bad vertical batch suppressed");
        System.out.println("BoatShotV12Test: "+checks+" recovery, draw-lock and vertical checks passed");
    }
}
