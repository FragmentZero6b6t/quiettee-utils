import com.quiettee.utils.modules.combat.*;
import com.quiettee.utils.modules.movement.*;
public final class BoatShotV13Test {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        var budget=new BoatShotUseBudget();
        Object bow=new Object(); budget.sent(bow,1); budget.sent(bow,2);
        check(budget.available(3)==8,"duplicate event must not consume another credit");
        for(int i=0;i<8;i++)budget.sent(new Object(),4+i);
        check(budget.available(300_000_000)==0,"bow and webs share a window");
        check(budget.available(310_000_001)==9,"new window available");
        var lock=new BoatShotDrawLock();
        check(lock.update(true,true,true,false,true,false),"first deliberate draw selects");
        for(int i=0;i<30;i++){
            lock.update(false,false,true,false,true,true);
            check(!lock.update(true,true,true,false,true,true),"new manual draws retain persistent lock");
        }
        lock.update(false,false,true,false,true,false);
        check(!lock.update(true,false,true,false,true,false),"automatic draw never replaces lost target");
        lock.update(false,false,true,false,true,false);
        check(lock.update(true,true,true,false,true,false),"deliberate redraw can choose after target loss");
        var naked=new BoatShotDamage.Defense(20,0,0,0,0);
        var protectedTarget=new BoatShotDamage.Defense(40,20,12,16,1);
        check(BoatShotDamage.damage(20,9.9,5,naked,false)>BoatShotDamage.damage(20,9.9,0,naked,false),"Power changes base damage before speed multiplication");
        check(BoatShotDamage.damage(20,9.9,5,protectedTarget,false)<BoatShotDamage.damage(20,9.9,5,naked,false),"armor, protection and resistance matter");
        check(BoatShotDamage.drawTicks(9.9,0,new BoatShotDamage.Defense(2,0,0,0,0))==3,"quick low-health finisher");
        check(BoatShotDamage.clientTicks(10,10)==20,"damage immunity uses server ticks");
        for(int health=1;health<=60;health++)for(int power=0;power<=5;power++) {
            var defense=new BoatShotDamage.Defense(health,20,12,16,1);
            int draw=BoatShotDamage.drawTicks(9.9,power,defense);
            check(draw>=3 && draw<=20,"finite bounded automatic draw");
            check(BoatShotDamage.damage(draw,9.9,power,defense,true)>=0,"nonnegative damage");
        }
        for(double vx:new double[]{-2,0,2})for(double vz:new double[]{-2,0,2}) {
            var cells=BoatShotWebShape.fastCells(vx,vz);
            check(cells.size()==27 && new java.util.HashSet<>(cells).size()==27,"complete distinct cube");
            for(int i=0;i<27;i++)check(cells.get(i).y()==i/9,"nine stopping cells before body and roof");
        }
        var prediction=new BoatShotPrediction();
        prediction.observePosition(0,0,0,0,3);
        prediction.observePosition(1,1,0,0,3);
        check(prediction.observePosition(5,1,0,0,3).x()==0,"braking clears stale moving velocity");
        check(Math.abs(prediction.updateLead(100,20,0)-2)<1e-9,"observed 100ms ping needs two ticks, not six");

        for(double speed:new double[]{.3,.7,1,1.7,2.5})for(double burst:new double[]{9.9,19.5}) {
            double height=36-burst, base=-speed*3;
            var aim=BoatShotAim.solveDiscrete(base+speed*2,-height,0,speed,0,0,3,-burst,.01);
            if(aim==null)continue;
            double yaw=Math.toRadians(aim.yaw()),pitch=Math.toRadians(aim.pitch());
            double ax=0,ay=0,vx=-Math.sin(yaw)*Math.cos(pitch)*3,vy=-Math.sin(pitch)*3-burst;
            for(int n=0;n<Math.ceil(aim.ticks());n++) {
                double portion=Math.min(1,aim.ticks()-n);ax+=vx*portion;ay+=vy*portion;vx*=.99;vy=vy*.99-.05;
            }
            check(Math.abs(ax-(base+speed*(2+Math.ceil(aim.ticks()))))<.001 && Math.abs(ay+height)<.001,"adaptive-delay intercept matches independently advanced target");
            check(speed*4>.4,"legacy six-tick lead error exceeds player half-width at these speeds");
        }
        for(double v=0;v<=99;v+=.1) {
            int count=BoatPhaseVertical.count(v);
            check(count>0&&count<=5,"five updates maximum");
            check(v/count<=19.800001,"larger vertical update remains bounded");
            if(v<=45)check(v/count<=9.000001,"retain proven 45 pattern");
        }
        check(BoatPhaseVertical.count(99.1)==0,"reject oversize vertical plan");
        System.out.println("BoatShotV13Test: "+checks+" lock, cadence, damage, placement and vertical checks passed");
    }
}
