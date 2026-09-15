package com.quiettee.utils.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class PlayerInfoConcurrencyMixin {
    @Shadow @Final @Mutable private Map<UUID, PlayerListEntry> playerListEntries;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void quiettee$concurrentPlayerInfo(CallbackInfo ci) {
        playerListEntries = new ConcurrentHashMap<>(playerListEntries);
    }
}
