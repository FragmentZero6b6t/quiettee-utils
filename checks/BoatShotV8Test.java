import com.quiettee.utils.modules.movement.*;
import com.quiettee.utils.modules.movement.BoatPhaseTravel.Mode;
import com.quiettee.utils.modules.combat.*;
import com.quiettee.utils.util.LanceWebMath;
public class BoatShotV8Test {
    static int checks;
    static void check(boolean b) { checks++; if(!b)throw new AssertionError("check "+checks); }
    public static void main(String[] args) {
        for(int i=0;i<=4750;i++) {
            double h=i/1000.;int n=BoatPhaseAirSteps.count(h,5);
            check(n>=1 && n<=5 && h/n<=.95000001);
        }
        check(BoatPhaseAirSteps.count(4.75)==0);
        check(BoatPhaseAirSteps.count(4.751,5)==0);
        check(BoatPhaseAirSteps.count(Double.NaN,5)==0);
        check(BoatPhaseAirSteps.count(5.7,6)==6);
        var p=new BoatPhaseTravel();
        check(p.select(false,false,true,false,true)==Mode.EXTENDED);
        p.sent(Mode.EXTENDED,80,4.75,.99);
        check(p.corrected(81)==Mode.EXTENDED);
        check(p.corrected(81)==Mode.LIMITED);
        check(p.select(false,false,true,false,true)==Mode.SUBSTEPS);
        p.newRide();check(p.select(false,false,true,false,true)==Mode.SUBSTEPS);
        p.sent(Mode.SUBSTEPS,30,3.8,.99);check(p.corrected(31)==Mode.SUBSTEPS);
        check(p.select(false,false,true,false,true)==Mode.LIMITED);
        p.retry(Mode.SUBSTEPS);check(p.select(false,false,true,false,true)==Mode.SUBSTEPS);
        var cells=BoatShotWebShape.cells();
        check(cells.size()==27 && new java.util.HashSet<>(cells).size()==27);
        for(var c:cells) {
            check(c.x()>=-1 && c.x()<=1 && c.z()>=-1 && c.z()<=1 && c.y()>=0 && c.y()<3);
            check(c.y()+1 < BoatShotWebShape.HOVER-.6);
            var click=BoatShotWebShape.click(new LanceWebMath.Cell(c.x(),c.y(),c.z()),
                .5,BoatShotWebShape.HOVER+1.2075000190734863,.5,4.5,true,s->false);
            check(click!=null);
        }
        for(var stage:BoatShotCycle.Stage.values()) {
            check(BoatShotCadence.mayDraw(stage,false)==(stage==BoatShotCycle.Stage.IDLE));
            check(BoatShotCadence.mayDraw(stage,true)==(stage==BoatShotCycle.Stage.IDLE
                || stage==BoatShotCycle.Stage.WAIT_CLEAR || stage==BoatShotCycle.Stage.RETURNING));
        }
        check(BoatShotCadence.minimumDraw(true,8,23)==8);
        check(BoatShotCadence.minimumDraw(false,8,23)==23);
        check(BoatShotCadence.minimumDraw(true,0,23)==3);
        for(var stage:BoatShotCycle.Stage.values()) for(int elapsed=0;elapsed<25;elapsed++)
            check(BoatShotCadence.mayRelease(stage,true,elapsed)==(stage==BoatShotCycle.Stage.IDLE&&elapsed>=10));
        System.out.println("BoatShotV8Test: "+checks+" checks passed");
    }
}
