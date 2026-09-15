import com.quiettee.utils.modules.combat.*;
import com.quiettee.utils.modules.movement.*;
public class BoatShotV9Test {
    private static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    public static void main(String[] args) {
        for(int i=0;i<=9000;i++) {
            double speed=i/1000.;int count=BoatPhaseAirSteps.count(speed,10);
            check(count>=1&&count<=10&&speed/count<=.95000001);
        }
        check(BoatPhaseAirSteps.count(10,10)==0);
        check(BoatPhaseAirSteps.count(1,11)==0);
        var p=new BoatShotPrediction();

        for(int t=0;t<60;t++) {
            var v=p.observePosition(t,3*Math.floor(t/3.)*1.7,64,0);
            if(t>25)check(Math.abs(v.x()-1.7)<.0001);
        }
        for(int t=60;t<70;t++)p.observePosition(t,96.9,64,0);
        check(p.observePosition(70,96.9,64,0).x()==0);
        p.reset();check(p.observePosition(0,10,0,0).x()==0);
        p.observePosition(1,11,0,0);check(p.sampleAge(3)==2);
        check(BoatShotPrediction.serverTicksPerClientTick(10)==.5);
        p.reset();check(p.updateLead(0,20,0)==0);
        check(p.updateLead(0,20,3)==3);check(p.updateLead(0,20,0)==0);
        check(BoatShotPrediction.collisionLead(2.2)==2&&BoatShotPrediction.collisionLead(2.8)==3);

        int solved=0;
        for(double speed:new double[]{.1,.3,1,2,3})for(double height:new double[]{24,36,55}) {
            double x=-speed*3.1,y=-height;
            var aim=BoatShotAim.solveDiscrete(x,y,0,speed,0,0,3,-9.9,.01);
            if(aim==null)continue;solved++;
            double yaw=Math.toRadians(aim.yaw()),pitch=Math.toRadians(aim.pitch());
            double vx=-Math.sin(yaw)*Math.cos(pitch)*3,vy=-Math.sin(pitch)*3-9.9,vz=Math.cos(yaw)*Math.cos(pitch)*3;
            double ax=0,ay=0,az=0,t=aim.ticks();
            for(int n=0;n<Math.ceil(t);n++) {double f=Math.min(1,t-n);ax+=vx*f;ay+=vy*f;az+=vz*f;vx*=.99;vy=vy*.99-.05;vz*=.99;}
            check(Math.abs(ax-(x+speed*Math.ceil(t)))<.001&&Math.abs(ay-y)<.001&&Math.abs(az)<.001);
        }
        check(solved>=10);
        for(double speed:new double[]{.3,1,2,3}) {
            var plan=BoatShotWebPlan.intercept(0,64,0,speed,0,0,0,104,0,4.75,3,3,5,1);
            check(plan!=null&&plan.x()>speed*8&&plan.y()==64);
            check(BoatShotWebPlan.canEnter(-12,0,speed,0,50));
            check(!BoatShotWebPlan.canEnter(12,0,speed,0,50));
            var cells=BoatShotWebShape.cells(speed,0);
            check(cells.size()==27&&new java.util.HashSet<>(cells).size()==27&&cells.getFirst().x()==-1&&cells.getFirst().y()==0);
        }

        check(BoatShotWebShape.HOVER-.4125-.6>3.025);
        System.out.println("BoatShotV9Test: "+checks+" server-motion, moving-intercept, web and speed checks passed");
    }
}
