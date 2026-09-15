import com.quiettee.utils.modules.combat.BoatShotAutomation;
import com.quiettee.utils.modules.combat.BoatShotWebTiming;

public class BoatShotV14Test {
    static int checks;
    static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {

        for(int required=3;required<=40;required++) {
            int arrows=0,age=0;
            for(int frame=0;frame<required*4;frame++) {
                age++;
                if(!BoatShotAutomation.retainDraw(true,true,true,false))age=0;
                if(age==required) {
                    check(!BoatShotAutomation.retainDraw(true,true,true,true),"explicit release must reach vanilla");
                    arrows++;age=0;
                }
            }
            check(arrows==4,"mouse up must not clear automatic draw age");
        }
        check(!BoatShotAutomation.retainDraw(false,true,true,false),"ordinary bow input remains vanilla");
        check(!BoatShotAutomation.retainDraw(true,false,true,false),"item changes release input ownership");
        check(!BoatShotAutomation.retainDraw(true,true,false,false),"unlock, manual movement and screens release ownership");

        for(double dx:new double[]{-120,-.5,0,.5,120})for(double dz:new double[]{-60,0,60})for(double dy:new double[]{-160,-.1,0,.1,160}) {
            double x=dx,y=dy,z=dz;
            for(int tick=0;tick<100;tick++) {
                var step=BoatShotAutomation.approach(x,y,z,6.5,45);
                check(Math.abs(step.y())<=45 && Math.hypot(step.x(),step.z())<= (Math.abs(step.y())>9?4.75:6.5)+1e-8,"movement packet bounds");
                x-=step.x();y-=step.y();z-=step.z();
                if(Math.abs(x)+Math.abs(y)+Math.abs(z)<1e-8)break;
            }
            check(Math.abs(x)+Math.abs(y)+Math.abs(z)<1e-8,"approach converges without overshoot");
        }
        var drop=BoatShotAutomation.approach(0,-36,0,6.5,45);
        check(drop.y()==-36,"one-tick clear descent");
        for(double ceiling:new double[]{252,317}) {
            check(BoatShotAutomation.ceilingDanger(ceiling-6,ceiling-2,ceiling-.2,ceiling,25.5),"enemy above at ceiling disengages");
            check(!BoatShotAutomation.ceilingDanger(ceiling,ceiling-6,ceiling-4.2,ceiling,25.5),"enemy below at ceiling stays locked");
            check(!BoatShotAutomation.ceilingDanger(100,110,111.8,ceiling,25.5),"ordinary upward approach remains possible");
        }
        check(BoatShotAutomation.cubeFits(317,-64,319),"top complete cube");
        check(!BoatShotAutomation.cubeFits(318,-64,319),"no cube above build height");
        check(!BoatShotAutomation.cubeFits(-65,-64,319),"no cube below world bottom");
        check(!BoatShotAutomation.cubeFits(Integer.MAX_VALUE,-64,319),"height addition cannot wrap");
        check(BoatShotAutomation.downwardBurst(19.5,317,312)==3,"close target below ceiling keeps downward launch above target");
        check(BoatShotAutomation.downwardBurst(19.5,200,163)==19.5,"normal overhead shot retains power");
        int stopped=0;

        for(int tick=0;tick<9;tick++)stopped=BoatShotAutomation.stoppedTicks(stopped,true,.06/3,0);
        check(stopped>=2,"batched network positions recognize stopped target");
        check(BoatShotAutomation.stoppedTicks(stopped,false,0,0)==0,"client-only predicted web never releases shot");
        check(BoatShotAutomation.stoppedTicks(stopped,true,.3,0)==0,"escape resumes capture");
        check(BoatShotAutomation.stoppedTicks(stopped,true,0,-1)==0,"falling target is not stopped");
        check(BoatShotAutomation.stoppedTicks(stopped,true,Double.NaN,0)==0,"invalid motion never qualifies");
        BoatShotWebTiming timing=new BoatShotWebTiming();
        timing.finish(100,true,4);
        for(int tick=101;tick<104;tick++){timing.finish(tick,false,4);check(!timing.ready(tick),"idle pause does not reset retry");}
        check(timing.ready(104),"recapture ready after four ticks");
        timing.finish(104,true,4);timing.rearm();check(timing.ready(105),"new deliberate lock rearms capture");
        System.out.println("BoatShotV14Test: "+checks+" hands-free draw, approach, capture and ceiling checks passed");
    }
}
