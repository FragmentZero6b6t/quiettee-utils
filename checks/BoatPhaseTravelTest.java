import com.quiettee.utils.modules.movement.BoatPhaseTravel;
import com.quiettee.utils.modules.movement.BoatPhaseTravel.Mode;

public final class BoatPhaseTravelTest {
    private static int checks;
    private static void eq(Object actual, Object expected) {
        checks++; if (!actual.equals(expected)) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        BoatPhaseTravel p = new BoatPhaseTravel();

        for (int t=30;t<=419;t++) p.sent(Mode.SUBSTEPS,t,3.2,.99);
        for (int t=629;t<=632;t++) p.sent(Mode.HASH,t,1+(t-629)*.1,.99);
        eq(p.corrected(632),Mode.HASH);
        eq(p.corrected(632),Mode.LIMITED);
        eq(p.select(true,true,true,true),Mode.SUBSTEPS);
        for(int ride=0;ride<6;ride++) {
            p.newRide();
            eq(p.select(false,false,true,true),Mode.SUBSTEPS);
            eq(p.rejected(Mode.HASH),true);
            eq(p.corrected(1),Mode.LIMITED);
        }
        p.sent(Mode.SUBSTEPS,100,3.2,.99);
        eq(p.corrected(102),Mode.SUBSTEPS);
        p.newRide();
        eq(p.select(false,false,true,true),Mode.LIMITED);
        eq(p.select(false,false,false,true),Mode.DIRECT);
        p.retry();
        eq(p.select(false,false,true,false),Mode.SUBSTEPS);
        eq(p.select(true,false,true,false),Mode.LIMITED);
        eq(p.select(true,true,true,false),Mode.HASH);
        p.sent(Mode.HASH,20,1.3,.99);
        eq(p.corrected(41),Mode.LIMITED);
        eq(p.corrected(19),Mode.LIMITED);
        p.retry();
        p.sent(Mode.SUBSTEPS,10,3.2,.99);

        p.newRide();
        eq(p.select(false,false,true,false),Mode.SUBSTEPS);
        p.sent(Mode.DIRECT,20,Double.NaN,.99);
        eq(p.corrected(21),Mode.LIMITED);
        System.out.println("BoatPhaseTravelTest: "+checks+" checks passed");
    }
}
