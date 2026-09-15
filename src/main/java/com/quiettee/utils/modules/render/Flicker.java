package com.quiettee.utils.modules.render;

import com.quiettee.utils.QuietteeUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.util.Arm;

import java.util.Random;

public class Flicker extends Module {
    private static final int CAPE = 1, JACKET = 2, LEFT_SLEEVE = 4, RIGHT_SLEEVE = 8,
                             LEFT_PANTS = 16, RIGHT_PANTS = 32, HAT = 64;
    private static final int ALL = CAPE | JACKET | LEFT_SLEEVE | RIGHT_SLEEVE | LEFT_PANTS | RIGHT_PANTS | HAT;

    private static final int[] ORDER = {CAPE, JACKET, LEFT_SLEEVE, RIGHT_SLEEVE, LEFT_PANTS, RIGHT_PANTS, HAT};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final SettingGroup sgHands = settings.createGroup("Hands");

    private final SettingGroup sgHeld = settings.createGroup("Held Item");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Strobe, Peel, Chase, Static or Bare.")
        .defaultValue(Mode.Peel)
        .build()
    );

    private final Setting<Integer> rate = sgGeneral.add(new IntSetting.Builder()
        .name("rate")
        .description("Ticks between steps.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> keepCape = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-cape")
        .description("Always show the cape.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> keepHat = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-hat")
        .description("Always show the hat layer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapHands = sgHands.add(new BoolSetting.Builder()
        .name("swap-hands")
        .description("Flip your main arm on a timer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> handRate = sgHands.add(new IntSetting.Builder()
        .name("hand-rate")
        .description("Ticks between hand swaps.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 60)
        .visible(swapHands::get)
        .build()
    );

    private final Setting<Boolean> itemStrobe = sgHeld.add(new BoolSetting.Builder()
        .name("item-strobe")
        .description("Cycle the hotbar so your item blurs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> itemRate = sgHeld.add(new IntSetting.Builder()
        .name("item-rate")
        .description("Ticks between hotbar steps.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 20)
        .visible(itemStrobe::get)
        .build()
    );

    private final Random random = new Random();
    private int timer;
    private int handTimer;
    private int step;
    private boolean rightArm = true;
    private int itemTimer;
    private int originalSlot = -1;
    private SyncedClientOptions original;

    public Flicker() {
        super(QuietteeUtils.CATEGORY, "flicker", "Animate your skin on other players' screens.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        handTimer = 0;
        step = 0;
        original = mc.options.getSyncedOptions();
        rightArm = original.mainArm() == Arm.RIGHT;
        itemTimer = 0;
        originalSlot = mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    @Override
    public void onDeactivate() {

        if (original != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new ClientOptionsC2SPacket(original));
        }
        original = null;

        if (originalSlot >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(originalSlot);
        originalSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null || original == null) return;

        boolean dirty = false;

        if (--timer <= 0) {
            timer = rate.get();
            step++;
            dirty = true;
        }

        if (swapHands.get() && --handTimer <= 0) {
            handTimer = handRate.get();
            rightArm = !rightArm;
            dirty = true;
        }

        if (dirty) send(mask(), rightArm ? Arm.RIGHT : Arm.LEFT);

        if (itemStrobe.get() && --itemTimer <= 0) {
            itemTimer = itemRate.get();
            mc.player.getInventory().setSelectedSlot((mc.player.getInventory().getSelectedSlot() + 1) % 9);
        }
    }

    private int mask() {
        int locked = (keepCape.get() ? CAPE : 0) | (keepHat.get() ? HAT : 0);
        int free = ALL & ~locked;

        int m = switch (mode.get()) {
            case Strobe -> (step % 2 == 0) ? free : 0;

            case Peel -> {
                int n = ORDER.length + 1;
                int phase = step % (n * 2);
                int shown = phase < n ? ORDER.length - phase : phase - n;
                int bits = 0;
                for (int i = 0; i < Math.min(shown, ORDER.length); i++) bits |= ORDER[i];
                yield bits & free;
            }

            case Chase -> ORDER[step % ORDER.length] & free;

            case Static -> random.nextInt(ALL + 1) & free;

            case Bare -> 0;
        };

        return m | locked;
    }

    private void send(int parts, Arm arm) {
        mc.getNetworkHandler().sendPacket(new ClientOptionsC2SPacket(new SyncedClientOptions(
            original.language(),
            original.viewDistance(),
            original.chatVisibility(),
            original.chatColorsEnabled(),
            parts,
            arm,
            original.filtersText(),
            original.allowsServerListing(),
            original.particleStatus()
        )));
    }

    @Override
    public String getInfoString() {
        return mode.get().toString().toLowerCase();
    }

    public enum Mode {
        Strobe,
        Peel,
        Chase,
        Static,
        Bare
    }
}
