package com.quiettee.utils.modules.combat;

import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public final class BoatShotGuard {
    private final List<Vec3d> crystals = new ArrayList<>();
    private final Set<BlockPos> reservations = new HashSet<>();
    public void reset() { crystals.clear(); reservations.clear(); }
    public void reserve(BlockPos pos) { reservations.add(pos.toImmutable()); }
    public void release(BlockPos pos) { reservations.remove(pos); }

    public void refresh(ClientWorld world, PlayerEntity self, AbstractBoatEntity boat, Entity target) {
        refresh(world,self,boat,target,true);
    }
    public void refresh(ClientWorld world, PlayerEntity self, AbstractBoatEntity boat, Entity target, boolean protectCrystals) {
        crystals.clear();
        if (!protectCrystals) return;
        Box scan = boat.getBoundingBox().expand(45);
        if(target!=null) scan=scan.union(target.getBoundingBox().expand(14));
        for (Entity entity : world.getOtherEntities(self,scan,e -> e instanceof EndCrystalEntity && !e.isRemoved())) crystals.add(entity.getEntityPos());

        int scanned=0;
        for (PlayerEntity enemy : world.getPlayers()) {
            if (enemy==self || enemy.isSpectator() || !enemy.isAlive() || !Friends.get().shouldAttack(enemy)
                || enemy.squaredDistanceTo(boat)>45*45 && (target==null || enemy.squaredDistanceTo(target)>14*14)) continue;
            if (++scanned>12) { crystals.add(enemy.getEntityPos()); continue; }
            BlockPos center=enemy.getBlockPos();
            for (BlockPos base : BlockPos.iterate(center.add(-6,-3,-6),center.add(6,4,6))) {
                if (!world.getChunkManager().isChunkLoaded(base.getX()>>4,base.getZ()>>4)) continue;
                var state=world.getBlockState(base);
                if (!(state.isOf(Blocks.OBSIDIAN)||state.isOf(Blocks.BEDROCK)) || !world.getBlockState(base.up()).isAir()) continue;
                Vec3d source=Vec3d.ofCenter(base).add(0,.5,0);

                if (enemy.getEyePos().squaredDistanceTo(source)<=49) crystals.add(source);
            }
        }
    }

    public boolean clear(ClientWorld world, AbstractBoatEntity boat, Vec3d step) {
        return clear(world,boat,step,true);
    }
    public boolean clear(ClientWorld world, AbstractBoatEntity boat, Vec3d step, boolean protectCrystals) {
        return clearAt(world,boat,Vec3d.ZERO,step,protectCrystals);
    }
    public boolean clearAt(ClientWorld world, AbstractBoatEntity boat, Vec3d offset, Vec3d step, boolean protectCrystals) {
        if (!Double.isFinite(step.lengthSquared()) || !Double.isFinite(offset.lengthSquared())) return false;
        for (Entity body : boat.streamSelfAndPassengers().toList()) {
            Box current=body.getBoundingBox().offset(offset).expand(.35), path=current.stretch(step);
            if (protectCrystals) for (Vec3d source : crystals) if (!awayOrOutside(current,step,source,13)) return false;
            for (BlockPos pos : reservations) if (!webClear(current,step,new Box(pos))) return false;
            for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(path.minX,path.minY,path.minZ),BlockPos.ofFloored(path.maxX,path.maxY,path.maxZ))) {
                var state=world.getBlockState(pos);
                if (state.isOf(Blocks.COBWEB) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                    || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW)) {
                    if (!webClear(current,step,new Box(pos))) return false;
                }
            }
        }
        return true;
    }
    public static boolean webClear(Box body, Vec3d step, Box web) {
        if (!body.stretch(step).intersects(web)) return true;

        return body.intersects(web) && !body.offset(step).intersects(web)
            && body.getCenter().subtract(web.getCenter()).dotProduct(step)>0;
    }
    public static boolean awayOrOutside(Box body, Vec3d step, Vec3d source, double radius) {
        if (distanceSquared(body.stretch(step),source)>radius*radius) return true;
        double before=distanceSquared(body,source), after=distanceSquared(body.offset(step),source);
        Vec3d nearest=new Vec3d(Math.clamp(source.x,body.minX,body.maxX),Math.clamp(source.y,body.minY,body.maxY),Math.clamp(source.z,body.minZ,body.maxZ));
        return before<=radius*radius && after>before+.001 && nearest.subtract(source).dotProduct(step)>=0;
    }
    public static double distanceSquared(Box box, Vec3d p) {
        double x=Math.max(Math.max(box.minX-p.x,p.x-box.maxX),0), y=Math.max(Math.max(box.minY-p.y,p.y-box.maxY),0), z=Math.max(Math.max(box.minZ-p.z,p.z-box.maxZ),0);
        return x*x+y*y+z*z;
    }
}
