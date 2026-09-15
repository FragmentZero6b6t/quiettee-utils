import com.quiettee.utils.modules.movement.BoatPhaseMeter;

public final class BoatPhaseMeterTest {
    private static int checks;
    private static final long STEP = 50_000_000L;
    private static void near(double actual, double expected) {
        checks++; if(Math.abs(actual-expected)>1e-6 || !Double.isFinite(actual)) throw new AssertionError(actual+" != "+expected);
    }
    private static void ok(boolean value) {checks++;if(!value)throw new AssertionError();}
    private static void send(BoatPhaseMeter m,int tick,double speed,int parts) {
        m.tick(tick,tick*STEP);
        for(int i=0;i<parts;i++) m.sent(tick,tick*STEP,(tick-1)*speed+i*speed/parts,0,
            (tick-1)*speed+(i+1)*speed/parts,170,0);
    }
    public static void main(String[] args) {
        BoatPhaseMeter m=new BoatPhaseMeter();
        for(int t=1;t<=100;t++) {
            send(m,t,3.2,4);

            if(t>3 && t%3==0) {
                int wireTick=t-(t%2==0?2:3);
                ok(m.echo(t,t*STEP,wireTick*3.2,170,0));
                ok(!m.echo(t,t*STEP,wireTick*3.2,170,0));
                ok(!m.echo(t,t*STEP,wireTick*3.2,170,0));
            }
        }
        near(m.sentBpt(),3.2);
        near(m.sentBps(),64);
        near(m.echoBpt(100*STEP),3.2);
        near(m.echoBps(100*STEP),64);
        near(m.packetsLastTick(),4);
        ok(!m.echo(100,100*STEP,1_000_000,170,0));
        for(int t=101;t<=126;t++) m.tick(t,t*STEP);
        near(m.sentBpt(),0);near(m.sentBps(),0);near(m.packetsLastTick(),0);
        ok(Double.isNaN(m.echoBpt(126*STEP)));
        m.reset();
        near(m.sentBpt(),0);ok(Double.isNaN(m.echoBpt(127*STEP)));
        for(int t=1;t<=240;t++) {
            send(m,t,.99,1);
            if(t>3 && t%3==0) m.echo(t,t*STEP,(t-2)*.99,170,0);
        }
        near(m.sentBpt(),.99);near(m.sentBps(),19.8);near(m.echoBpt(240*STEP),.99);
        ok(Double.isNaN(m.echoBpt(300*STEP)));
        m.tick(1,301*STEP);near(m.sentBpt(),0);
        m.reset();
        for(int t=1;t<=30;t++) {
            m.tick(t,t*STEP);m.sent(t,t*STEP,0,0,0,170+t*10,0);
        }
        near(m.sentBpt(),0);near(m.sentBps(),0);
        System.out.println("BoatPhaseMeterTest: "+checks+" checks passed");
    }
}
