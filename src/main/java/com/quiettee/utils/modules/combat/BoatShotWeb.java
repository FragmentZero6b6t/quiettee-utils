package com.quiettee.utils.modules.combat;

import com.quiettee.utils.modules.movement.BoatPhase;
import com.quiettee.utils.util.LanceWebMath;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class BoatShotWeb {
    private enum Stage { IDLE, APPROACH, WEAVE, RETREAT }
    private Stage stage=Stage.IDLE;
    private final BoatShotWebTiming timing=new BoatShotWebTiming();
    private final BoatShotGuard guard;
    private final BoatShotUseBudget useBudget;
    private LivingEntity stoppedTarget;
    private Vec3d lastTargetPosition;
    private int stoppedTicks;
    private boolean quickCapture;
    private int retryDelay=80;
    private int replans, shootUntil;
    private boolean shootAfterMiss;
    private BoatShotWebShape.Pattern pattern=BoatShotWebShape.Pattern.Cube, activePattern=BoatShotWebShape.Pattern.Cube;
    private boolean flightIssued;
    private int flightReplyUntil;
    private final Map<BlockPos,Integer> sent=new LinkedHashMap<>();
    private final Map<BlockPos,Integer> retries=new HashMap<>(), retryAt=new HashMap<>();
    private Object lastReply;
    private final Set<BlockPos> confirmed=new HashSet<>();
    private final ConcurrentLinkedQueue<Packet<?>> replies=new ConcurrentLinkedQueue<>();
    private final List<BlockPos> cells=new ArrayList<>();
    private BlockPos center;
    private LivingEntity victim;
    private int deadline, nextVolley, attempts, lastTick, lastWaitTick=-100;
    private String waitReason="waiting for a target", lastWaitReason="";
    private long nextVolleyNanos;
    private double homeY;
    private Vec3d retreatXZ;
    public BoatShotWeb(BoatShotGuard guard) { this(guard,new BoatShotUseBudget()); }
    public BoatShotWeb(BoatShotGuard guard, BoatShotUseBudget budget) { this.guard=guard; this.useBudget=budget; }
    public void configure(boolean quick,int delay) { quickCapture=quick; retryDelay=Math.max(1,delay); }
    public void pattern(BoatShotWebShape.Pattern value) { pattern=Objects.requireNonNull(value); }
    private boolean flightNet() { return activePattern==BoatShotWebShape.Pattern.FlightNet; }
    private boolean acceptsHeldPose(boolean gliding) { return quickCapture && (flightNet() || !gliding); }
    private void updateFlightReplyWait(int tick,Consumer<String> log) {
        if(stage==Stage.WEAVE && flightNet() && flightIssued
            && (confirmedCount()>=cells.size() || tick>=flightReplyUntil)) retreat("flight net volley complete",log);
    }
    public void shootingFallback(boolean enabled) { shootAfterMiss=enabled; }
    public boolean shotWindow(int tick) { return shootAfterMiss && tick<shootUntil; }
    public boolean unavailable() {
        return waitReason.equals("cobwebs needed in hotbar") || waitReason.equals("descent blocked or chunks unavailable")
            || waitReason.equals("no reachable web interception") || waitReason.equals("catch up before web interception")
            || waitReason.equals("crystal or web danger on descent");
    }
    public void rearm() { if(!busy())timing.rearm(); }
    public boolean caught(LivingEntity target) { return target!=null && target==stoppedTarget && stoppedTicks>=4 && inWeb(target); }
    private boolean inWeb(LivingEntity target) {
        var mc=MinecraftClient.getInstance();
        if(mc.world==null)return false;
        Box body=target.getBoundingBox().contract(.001);
        if(target==stoppedTarget && lastTargetPosition!=null)body=body.offset(lastTargetPosition.subtract(target.getEntityPos()));
        for(BlockPos pos:BlockPos.iterate(BlockPos.ofFloored(body.minX,body.minY,body.minZ),BlockPos.ofFloored(body.maxX,body.maxY,body.maxZ)))
            if(mc.world.getBlockState(pos).isOf(Blocks.COBWEB) && (!sent.containsKey(pos)||confirmed.contains(pos)))return true;
        return false;
    }
    public boolean busy() { return stage!=Stage.IDLE; }
    private int confirmedCount() { int count=0; for(BlockPos cell:cells)if(confirmed.contains(cell))count++;return count; }
    public String status() { return "web "+stage.name().toLowerCase(Locale.ROOT)+" ("+confirmedCount()+" replies)"; }
    public String waitReason() { return waitReason; }
    public void reset() { stage=Stage.IDLE; sent.clear(); confirmed.clear(); retries.clear(); retryAt.clear(); lastReply=null; replies.clear(); cells.clear(); victim=null; center=null; timing.reset(); stoppedTarget=null; lastTargetPosition=null; stoppedTicks=0; shootUntil=0; replans=0; flightIssued=false; flightReplyUntil=0; waitReason="waiting for a target"; lastWaitReason=""; lastWaitTick=-100; }
    public void offer(Packet<?> packet) {
        if (packet instanceof BlockUpdateS2CPacket || packet instanceof ChunkDeltaUpdateS2CPacket) {
            if (replies.size()<256) replies.offer(packet);
        }
    }
    private void reply(BlockPos pos, BlockState state,Consumer<String> log) {
        if (!sent.containsKey(pos)) return;
        if(state.isOf(Blocks.COBWEB)) { confirmed.add(pos.toImmutable()); retryAt.remove(pos); }
        else {
            boolean removed=confirmed.remove(pos);
            retryAt.put(pos.toImmutable(),lastTick+Math.max(6,(int)Math.ceil(PlayerUtils.getPing()/50.)+2));
            log.accept("WEB-REJECTED cell="+pos+" removedConfirmed="+removed+" state="+state);
        }
    }
    public void retreat() { if(busy()) stage=Stage.RETREAT; }
    public void cancel() { timing.finish(lastTick,busy(),retryDelay); stage=Stage.IDLE; victim=null; }
    private void waiting(int tick,String reason,Consumer<String> log) {
        waitReason=reason;
        if(!reason.equals(lastWaitReason) || tick-lastWaitTick>=100) {
            log.accept("WEB-WAIT tick="+tick+" reason="+reason);
            lastWaitReason=reason; lastWaitTick=tick;
        }
    }
    private void retreat(String reason,Consumer<String> log) {
        if(stage!=Stage.RETREAT) log.accept("WEB-RETREAT tick="+lastTick+" reason="+reason+" attempts="+attempts+" replies="+confirmedCount());
        if(stage!=Stage.RETREAT && !reason.equals("target stopped in confirmed webs") && !reason.equals("cube server confirmed"))
            shootUntil=lastTick+60;
        retreat();
    }
    public void tick(int tick, AbstractBoatEntity boat, LivingEntity target, Vec3d targetPosition, Vec3d velocity,double latency,
                     boolean enabled, String pauseReason, boolean airPlace, BoatPhase phase,
                     Runnable cancelDraw, Consumer<String> log) {
        lastTick=tick;
        MinecraftClient mc=MinecraftClient.getInstance();
        if(target!=stoppedTarget) { stoppedTarget=target; stoppedTicks=0; lastTargetPosition=null; }
        lastTargetPosition=targetPosition;
        int beforeReplies=confirmedCount();
        Packet<?> packet;
        while((packet=replies.poll())!=null) {
            if(packet==lastReply)continue;lastReply=packet;
            if(packet instanceof BlockUpdateS2CPacket p) reply(p.getPos(),p.getState(),log);
            else if(packet instanceof ChunkDeltaUpdateS2CPacket p) p.visitUpdates((pos,state)->reply(pos,state,log));
        }
        if(confirmedCount()!=beforeReplies) log.accept("WEB-CONFIRMED tick="+tick+" count="+confirmedCount()+" attempts="+attempts);

        stoppedTicks=BoatShotAutomation.stoppedTicks(stoppedTicks,target!=null && targetPosition!=null && inWeb(target),
            velocity==null?Double.POSITIVE_INFINITY:velocity.horizontalLength(),velocity==null?Double.POSITIVE_INFINITY:velocity.y);
        sent.entrySet().removeIf(e -> {
            if(tick-e.getValue()<100) return false;
            guard.release(e.getKey()); confirmed.remove(e.getKey()); retries.remove(e.getKey()); retryAt.remove(e.getKey()); return true;
        });
        if(mc.world==null||mc.player==null||mc.interactionManager==null||boat==null||phase==null) {cancel();return;}
        if(pauseReason!=null) {
            if(busy()) log.accept("WEB-CANCEL tick="+tick+" stage="+stage+" reason="+pauseReason+" attempts="+attempts+" replies="+confirmedCount());
            cancel();
            if(enabled && target instanceof PlayerEntity) waiting(tick,pauseReason,log);
            return;
        }
        if(busy() && (!enabled || target!=victim || !victim.isAlive())) retreat("target lost or capture disabled",log);
        if(busy() && tick>deadline) retreat("approach deadline",log);

        if((stage==Stage.APPROACH || stage==Stage.WEAVE) && !guard.clear(mc.world,boat,Vec3d.ZERO))
            retreat("new crystal or web danger",log);
        if(stage==Stage.WEAVE && acceptsHeldPose(target.isGliding()) && caught(target)) retreat("target stopped in confirmed webs",log);
        updateFlightReplyWait(tick,log);
        if(stage==Stage.WEAVE && !flightNet() && targetPosition!=null && (!BoatShotWebPlan.canEnter(
            targetPosition.x-center.getX()-.5,targetPosition.z-center.getZ()-.5,velocity.x,velocity.z,Math.max(12,latency+8))
            || Math.abs(targetPosition.y-center.getY())>4)) {
            BlockPos replacement=attempts==0 && replans<3 ? anchor(boat,targetPosition,velocity,latency,phase) : null;
            if(replacement!=null) {
                Vec3d move=new Vec3d(replacement.getX()+.5-boat.getX(),replacement.getY()+BoatShotWebShape.HOVER-boat.getY(),replacement.getZ()+.5-boat.getZ());
                if(phase.clearRoute(boat,move)&&guard.clear(mc.world,boat,move)) {
                    center=replacement;cells.clear();stage=Stage.APPROACH;replans++;
                    log.accept("WEB-REPLAN tick="+tick+" count="+replans+" reason=target turned before placement");
                } else retreat("target passed cube or changed course",log);
            } else retreat("target passed cube or changed course",log);
        }
        if(stage==Stage.IDLE) {
            if(!enabled || !(target instanceof PlayerEntity) || targetPosition==null) return;
            if(caught(target)) { waiting(tick,"target stopped in webs",log); return; }
            if(shotWindow(tick)) {waiting(tick,"shooting window after missed capture",log);return;}
            if(!timing.ready(tick)) {waiting(tick,"capture cooldown",log);return;}
            if(velocity.horizontalLength()*tickRatio()>phase.travelSpeed()*.95) {waiting(tick,"catch up before web interception",log);return;}
            var webs=InvUtils.findInHotbar(Items.COBWEB);
            if(!webs.isHotbar()) {waiting(tick,"cobwebs needed in hotbar",log);return;}
            BlockPos anchor=anchor(boat,targetPosition,velocity,latency,phase);
            if(anchor==null) {waiting(tick,"no reachable web interception",log);return;}
            Vec3d low=new Vec3d(anchor.getX()+.5,anchor.getY()+BoatShotWebShape.HOVER,anchor.getZ()+.5).subtract(boat.getEntityPos());

            if(low.y>0) {waiting(tick,"get above the target",log);return;}
            if(low.horizontalLength()>Math.min(Math.abs(low.y)>9?4.75:9,phase.travelSpeed())+.08) {
                waiting(tick,"approaching capture position",log);return;
            }
            if(!phase.clearRoute(boat,low)) {waiting(tick,"descent blocked or chunks unavailable",log);return;}
            if(!guard.clear(mc.world,boat,low)) {waiting(tick,"crystal or web danger on descent",log);return;}
            center=anchor; victim=target; homeY=boat.getY(); retreatXZ=boat.getEntityPos();

            cells.clear(); attempts=0; replans=0; activePattern=pattern; flightIssued=false; flightReplyUntil=0;
            stage=Stage.APPROACH; deadline=tick+(int)Math.ceil(Math.abs(low.y)/phase.captureVerticalLimit())+90; nextVolley=tick; nextVolleyNanos=0;
            cancelDraw.run(); mc.player.clearActiveItem();
            log.accept("WEB-START tick="+tick+" center="+center+" homeY="+homeY+" gliding="+target.isGliding()+" velocity="+velocity+" pattern="+activePattern);
        }

        if(stage==Stage.APPROACH) {
            BlockPos next=targetPosition==null?null:anchor(boat,targetPosition,velocity,latency,phase);
            if(next==null) retreat("interception no longer reachable",log); else center=next;
        }
        if(stage==Stage.APPROACH && Math.abs(boat.getY()-(center.getY()+BoatShotWebShape.HOVER))<.025
            && Math.hypot(boat.getX()-center.getX()-.5,boat.getZ()-center.getZ()-.5)<=phase.travelSpeed()+.08) {
            stage=Stage.WEAVE;

            int settle=BoatShotWebTiming.settleTicks(PlayerUtils.getPing());
            nextVolley=tick+settle;
            nextVolleyNanos=System.nanoTime()+settle*50_000_000L;
            for (var offset : BoatShotWebShape.fastCells(velocity.x,velocity.z)) cells.add(center.add(offset.x(),offset.y(),offset.z()));
            log.accept("WEB-IN-REACH tick="+tick+" center="+center);
        }
        if(stage==Stage.WEAVE && !flightNet() && confirmedCount()>=BoatShotWebShape.CELLS) retreat("cube server confirmed",log);
        if(stage!=Stage.WEAVE || tick<nextVolley || System.nanoTime()<nextVolleyNanos || useBudget.available(System.nanoTime())==0) return;
        if(flightNet() && flightIssued)return;
        if(Math.abs(boat.getY()-center.getY()-BoatShotWebShape.HOVER)>.025
            || Math.hypot(boat.getX()-center.getX()-.5,boat.getZ()-center.getZ()-.5)>.08) return;

        cells.clear();
        var targetBody=target.getBoundingBox();
        var ordered=flightNet()?BoatShotWebShape.flightCells(targetPosition.x+velocity.x*latency-center.getX(),
            targetPosition.y+velocity.y*latency-center.getY(),targetPosition.z+velocity.z*latency-center.getZ(),
            velocity.x,velocity.y,velocity.z,targetBody.getLengthX(),targetBody.getLengthY()):BoatShotWebShape.contactCells(targetPosition.x+velocity.x*latency-center.getX(),
            targetPosition.y+velocity.y*latency-center.getY(),targetPosition.z+velocity.z*latency-center.getZ(),
            velocity.x,velocity.y,velocity.z,targetBody.getLengthX(),targetBody.getLengthY());
        for(var offset:ordered)cells.add(center.add(offset.x(),offset.y(),offset.z()));

        nextVolley=tick+1;
        var webs=InvUtils.findInHotbar(Items.COBWEB);
        if(!webs.isHotbar() || !guard.clear(mc.world,boat,Vec3d.ZERO)) {retreat("inventory or local danger",log);return;}
        int original=mc.player.getInventory().getSelectedSlot(), budget=Math.min(useBudget.available(System.nanoTime()),webs.count());
        int previousAttempts=attempts;
        int existing=0, blocked=0, ownHull=0, unreachable=0;
        if(!InvUtils.swap(webs.slot(),false)) {retreat("hotbar swap failed",log);return;}
        try {
            for(BlockPos cell:cells) {
                if(budget<=0||attempts>=BoatShotWebShape.CELLS*2) break;
                if(!sent.containsKey(cell)&&mc.world.getBlockState(cell).isOf(Blocks.COBWEB)) confirmed.add(cell.toImmutable());
                if(confirmed.contains(cell)) {existing++;continue;}
                if(sent.containsKey(cell) && (retries.getOrDefault(cell,0)>=2 || tick<retryAt.getOrDefault(cell,sent.get(cell)+Math.max(16,PlayerUtils.getPing()/25+6)))) {existing++;continue;}
                if(!BlockUtils.canPlaceBlock(cell,false,Blocks.COBWEB)) {blocked++;continue;}
                Box box=new Box(cell); boolean self=false;
                for(var body:boat.streamSelfAndPassengers().toList()) if(box.intersects(body.getBoundingBox().expand(.6))) {self=true;break;}
                if(self) {ownHull++;continue;}
                Vec3d eye=mc.player.getEyePos();
                var click=BoatShotWebShape.click(new LanceWebMath.Cell(cell.getX(),cell.getY(),cell.getZ()),eye.x,eye.y,eye.z,
                    Math.min(4.5,mc.player.getBlockInteractionRange()),airPlace,s -> {
                        BlockPos pos=new BlockPos(s.x(),s.y(),s.z()); var state=mc.world.getBlockState(pos);
                        return !sent.containsKey(pos)&&!state.isAir()&&!state.isReplaceable()&&!BlockUtils.isClickable(state.getBlock())&&state.getFluidState().isEmpty();
                    });
                if(click==null) {unreachable++;continue;}
                Direction side=click.sideX()<0?Direction.WEST:click.sideX()>0?Direction.EAST:click.sideY()<0?Direction.DOWN:click.sideY()>0?Direction.UP:click.sideZ()<0?Direction.NORTH:Direction.SOUTH;
                BlockHitResult hit=new BlockHitResult(new Vec3d(click.hitX(),click.hitY(),click.hitZ()),side,
                    new BlockPos(click.clicked().x(),click.clicked().y(),click.clicked().z()),false);

                sent.put(cell.toImmutable(),tick); retries.merge(cell.toImmutable(),1,Integer::sum); retryAt.remove(cell); guard.reserve(cell); attempts++; budget--;
                var result=mc.interactionManager.interactBlock(mc.player,Hand.MAIN_HAND,hit);
                log.accept("WEB-ATTEMPT cell="+cell+" localAccepted="+result.isAccepted()+" replies="+confirmedCount());
            }
        } finally { if(mc.player.getInventory().getSelectedSlot()==webs.slot()) InvUtils.swap(original,false); }
        log.accept("WEB-VOLLEY attempts="+(attempts-previousAttempts)+" existing="+existing+" blocked="+blocked
            +" ownHull="+ownHull+" unreachable="+unreachable+" total="+attempts+" replies="+confirmedCount());
        if(flightNet()) {
            if(attempts>previousAttempts) {

                cells.removeIf(c->!confirmed.contains(c)&&!sent.containsKey(c));
                flightIssued=true;
                flightReplyUntil=tick+Math.clamp((int)Math.ceil(Math.max(0,PlayerUtils.getPing())/50.)+4,6,20);
                log.accept("WEB-NET-ISSUED tick="+tick+" cells="+cells.size()+" waitUntil="+flightReplyUntil);
            } else if(confirmedCount()>0) retreat("flight net already present",log);
            else {

                BlockPos replacement=replans<3?anchor(boat,targetPosition,velocity,latency,phase):null;
                Vec3d move=replacement==null?null:new Vec3d(replacement.getX()+.5-boat.getX(),replacement.getY()+BoatShotWebShape.HOVER-boat.getY(),replacement.getZ()+.5-boat.getZ());
                if(replacement!=null&&!replacement.equals(center)&&phase.clearRoute(boat,move)&&guard.clear(mc.world,boat,move)) {
                    center=replacement;cells.clear();stage=Stage.APPROACH;replans++;
                    log.accept("WEB-REPLAN tick="+tick+" count="+replans+" reason=fresh flight net outside reach");
                } else retreat("flight net cells unavailable",log);
            }
            return;
        }

        boolean waitingReply=cells.stream().anyMatch(c->sent.containsKey(c)&&!confirmed.contains(c)
            && retries.getOrDefault(c,0)<2 && tick<retryAt.getOrDefault(c,sent.get(c)+Math.max(16,PlayerUtils.getPing()/25+6)));
        if(confirmedCount()>=BoatShotWebShape.CELLS || attempts>=BoatShotWebShape.CELLS*2
            || attempts==previousAttempts&&!waitingReply || tick>=deadline-4)
            retreat(confirmedCount()>=BoatShotWebShape.CELLS?"cube server confirmed":waitingReply?"capture deadline":"remaining cells unavailable",log);
    }
    private static double tickRatio() {
        return BoatShotPrediction.serverTicksPerClientTick(meteordevelopment.meteorclient.utils.world.TickRate.INSTANCE.getTickRate());
    }
    private static BlockPos anchor(AbstractBoatEntity boat,Vec3d position,Vec3d velocity,double latency,BoatPhase phase) {
        var aim=BoatShotWebPlan.intercept(position.x,position.y,position.z,velocity.x,velocity.y,velocity.z,
            boat.getX(),boat.getY(),boat.getZ(),phase.travelSpeed(),phase.captureVerticalLimit(),latency,
            BoatShotWebTiming.settleTicks(PlayerUtils.getPing()),tickRatio());
        if(aim==null)return null;
        BlockPos cell=BlockPos.ofFloored(aim.x(),aim.y(),aim.z());
        var world=MinecraftClient.getInstance().world;
        return world!=null && BoatShotAutomation.cubeFits(cell.getY(),world.getBottomY(),world.getTopYInclusive())
            && cell.getY()+BoatShotWebShape.HOVER<=phase.captureCeiling(boat)?cell:null;
    }
    public Vec3d interception(AbstractBoatEntity boat,Vec3d position,Vec3d velocity,double latency,BoatPhase phase) {
        if(boat==null||position==null||velocity==null||phase==null)return null;
        BlockPos cell=anchor(boat,position,velocity,latency,phase);
        return cell==null?null:new Vec3d(cell.getX()+.5,cell.getY(),cell.getZ()+.5);
    }
    public Vec3d movement(AbstractBoatEntity boat, double speed, double vertical, BoatPhase phase, Consumer<String> log) {
        if(!busy()) return null;
        MinecraftClient mc=MinecraftClient.getInstance();
        Vec3d goal=stage==Stage.RETREAT ? new Vec3d(boat.getX(),Math.min(phase.captureCeiling(boat),Math.max(homeY,center.getY()+15)),boat.getZ())
            : new Vec3d(center.getX()+.5,center.getY()+BoatShotWebShape.HOVER,center.getZ()+.5);
        if(stage==Stage.RETREAT && boat.getY()>=goal.y-.1) {
            log.accept("WEB-END tick="+lastTick+" attempts="+attempts+" replies="+confirmedCount());
            cancel();return Vec3d.ZERO;
        }
        Vec3d delta=goal.subtract(boat.getEntityPos());
        double dy=Math.clamp(delta.y,-Math.min(45,vertical),Math.min(45,vertical));
        double cap=Math.min(Math.abs(dy)>9?4.75:9,speed);
        double h=delta.horizontalLength(), factor=h>cap?cap/h:1;
        Vec3d step=new Vec3d(delta.x*factor,dy,delta.z*factor);
        if(phase.clearTravel(boat,step)&&guard.clear(mc.world,boat,step)) return step;
        retreat("movement blocked or new danger",log);

        for(Vec3d escape:new Vec3d[]{new Vec3d(0,Math.min(9,vertical),0),retreatXZ.subtract(boat.getEntityPos()).normalize().multiply(Math.min(1,speed))})
            if(phase.clearTravel(boat,escape)&&guard.clear(mc.world,boat,escape)) return escape;
        return Vec3d.ZERO;
    }
}
