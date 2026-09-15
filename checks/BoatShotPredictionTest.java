import com.quiettee.utils.modules.combat.BoatShotPrediction;
import com.quiettee.utils.modules.combat.BoatShotAim;

public class BoatShotPredictionTest {
    static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("Check "+checks);}
    public static void main(String[] args) {
        BoatShotPrediction p=new BoatShotPrediction();
        for(double speed:new double[]{0,.1,.5,1,3,3.8}) {
            p.reset();BoatShotPrediction.Velocity v=null;
            for(int t=0;t<40;t++)v=p.observe(speed,0,0);
            ok(Math.abs(v.x()-speed)<1e-9);
            double lead=p.updateLead(150);ok(lead==4);

            double x=-speed*2.5, y=-30;
            var shot=BoatShotAim.solve(x,y,0,v.x(),0,0,3,-9.9,.01);
            ok(shot!=null);
            double time=shot.ticks(),a=BoatShotAim.distanceFactor(time);
            double yaw=Math.toRadians(shot.yaw()),pitch=Math.toRadians(shot.pitch());
            double arrowX=-Math.sin(yaw)*Math.cos(pitch)*3*a;
            double arrowY=(-Math.sin(pitch)*3-9.9)*a-BoatShotAim.gravityDrop(time);
            ok(Math.abs(arrowX-(x+speed*time))<1e-4);
            ok(Math.abs(arrowY-y)<1e-4);
        }
        p.reset();for(int t=0;t<40;t++)p.observe(3,0,0);
        p.observe(0,0,0);ok(Math.abs(p.observe(0,0,0).x())<.1);
        p.reset();for(int t=0;t<40;t++)p.observe(3,0,0);
        var turn=p.observe(0,0,3);ok(turn.x()<.5 && turn.z()>2.5);
        ok(p.observe(50,0,0).x()==0);ok(p.observe(Double.NaN,0,0).x()==0);
        p.reset();ok(p.updateLead(-1)==2);p.reset();ok(p.updateLead(5000)==8);
        p.reset();ok(p.updateLead(50)==2);double previous=2;
        for(int i=0;i<100;i++){double lead=p.updateLead(250);ok(lead>=previous&&lead<=6);previous=lead;}
        System.out.println("BoatShotPredictionTest: "+checks+" checks passed");
    }
}
