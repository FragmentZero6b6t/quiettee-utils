import com.quiettee.utils.modules.combat.BoatShotWebTiming;

public class BoatShotWebTimingTest {
    public static void main(String[] args) {
        BoatShotWebTiming t=new BoatShotWebTiming();int checks=0;
        for(int tick=0;tick<500;tick++){t.finish(tick,false);if(!t.ready(tick))throw new AssertionError("Idle paused capture forever");checks++;}
        t.finish(500,true);
        for(int tick=501;tick<580;tick++){t.finish(tick,false);if(t.ready(tick))throw new AssertionError("Cooldown skipped");checks++;}
        if(!t.ready(580))throw new AssertionError("Idle pauses prolonged cooldown");checks++;
        if(BoatShotWebTiming.settleTicks(-1)!=3||BoatShotWebTiming.settleTicks(250)!=5||BoatShotWebTiming.settleTicks(5000)!=10)throw new AssertionError("Unbounded placement settle time");checks+=3;
        System.out.println("BoatShotWebTimingTest: "+checks+" checks passed");
    }
}
