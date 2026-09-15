import com.quiettee.utils.modules.movement.BoatPhaseHash;
import java.util.Random;

public class BoatPhaseHashTest {
    public static void main(String[] args) {
        Random random=new Random(154);
        int checks=0;
        for(int i=0;i<100000;i++) {
            double x=random.nextDouble(-29000000,29000000), z=random.nextDouble(-29000000,29000000), y=random.nextDouble(-62,318);
            float yaw=random.nextFloat(-180,180), pitch=random.nextFloat(-90,90);
            int hash=random.nextInt();
            double changed=BoatPhaseHash.altitude(x,y,z,yaw,pitch,hash);
            if(!Double.isFinite(changed)||Math.abs(changed-y)>.001||BoatPhaseHash.hash(x,changed,z,yaw,pitch)!=hash)
                throw new AssertionError("hash construction failed at "+x+","+y+","+z);
            checks++;
        }
        for(double y:new double[]{-64,-.0,0,.001,64,128,256,320}) {
            double changed=BoatPhaseHash.altitude(100,y,-100,0,0,123);
            if(!Double.isFinite(changed)||Math.abs(changed-y)>.001) throw new AssertionError("boundary altitude");
        }
        if(!Double.isNaN(BoatPhaseHash.altitude(Double.NaN,100,0,0,0,0))) throw new AssertionError("NaN accepted");
        System.out.println("BoatPhaseHashTest: "+checks+" randomized destinations passed; this is arithmetic, not server acceptance");
    }
}
