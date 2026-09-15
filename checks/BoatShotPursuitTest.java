import com.quiettee.utils.modules.combat.BoatShotPursuit;
import com.quiettee.utils.modules.movement.BoatPhaseAirSteps;

public class BoatShotPursuitTest {
    private static int checks;
    private static void check(boolean value,String reason) { checks++; if(!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        double boat=0,target=12;
        for(int tick=0;tick<200;tick++) {
            var step=BoatShotPursuit.plan(target-boat,0,0,3,0,3.2,3,s->true);
            check(step.length()<=3.200001,"bounded pursuit speed");
            boat+=step.x(); target+=3;
        }
        check(Math.abs(target-boat)<.001,"3.2 pursuit catches and tracks a 3 b/t target");
        var wall=BoatShotPursuit.plan(20,0,0,0,0,3.2,3,s->s.x()==0);
        check(wall.y()>0 && wall.x()==0,"climb before wall");
        var ceiling=BoatShotPursuit.plan(20,0,0,0,0,3.2,3,s->s.y()==0 && Math.abs(s.z())>1);
        check(Math.abs(ceiling.z())>1,"side detour with blocked ceiling");
        check(BoatShotPursuit.plan(20,0,0,0,0,3.2,3,s->false).length()==0,"enclosed means stop");
        check(BoatShotPursuit.plan(Double.NaN,0,0,0,0,3.2,3,s->true).length()==0,"nonfinite means stop");
        check(BoatShotPursuit.plan(Double.NaN,0,0,0,0,3.2,3,s->false).length()==0,"nonfinite cannot trigger climb");
        var nav=new BoatShotPursuit.Navigator();
        var blockedClimb=nav.next(20,5,0,3,0,3.2,3,s->s.y()==0);
        check(blockedClimb.x()>3 && blockedClimb.y()==0,"ceiling retains fast horizontal pursuit");
        var up=nav.next(20,0,0,0,0,3.2,3,s->s.x()==0&&s.y()>0);
        check(up.y()>0,"obstacle climb");
        var after=nav.next(20,-3,0,0,0,3.2,3,s->true);
        check(after.y()==0 && after.x()>3,"retain gained clearance after obstacle");
        nav.reset();
        check(nav.next(20,-3,0,0,0,3.2,3,s->true).y()<0,"new target releases obstacle altitude hold");
        check(BoatPhaseAirSteps.count(3.2)==4,"3.2 uses four substeps");
        check(BoatPhaseAirSteps.count(3.9)==0,"packet budget cannot exceed four");
        check(BoatPhaseAirSteps.count(3.800000001)==4,"world-coordinate rounding cannot add a fifth packet");
        check(BoatPhaseAirSteps.count(Double.NaN)==0,"reject nonfinite substep");
        for(int i=0;i<=380;i++) {
            double distance=i/100.0;
            int n=BoatPhaseAirSteps.count(distance);
            check(n>=1 && n<=4 && distance/n<=.950001,"each actual displacement bounded");
        }
        System.out.println("BoatShotPursuitTest: "+checks+" checks passed");
    }
}
