package com.quiettee.utils.modules.combat;

import java.util.ArrayList;
import java.util.List;

public final class LanceBeatTimingTest {
    private static int checks;

    public static void main(String[] args) {
        strikeAnchors();
        stablePrimaryContacts();
        continuationContacts();
        delayedArmingAndWebRelease();
        passiveContactCaveat();
        lifecycleAndBounds();
        System.out.println("LanceBeatTimingTest: " + checks + " checks passed");
    }

    private static void strikeAnchors() {
        LanceBeatTiming timing = new LanceBeatTiming();
        check(timing.due(100, 10), "new target has no inherited strike delay");
        timing.strike(100);
        check(timing.remaining(100, 10) == 10, "strike sets exactly ten ticks of spacing");
        check(timing.remaining(101, 10) == 9, "continuation does not restart the beat at ten");
        for (int elapsed = 0; elapsed < 10; elapsed++) {
            check(!timing.due(100 + elapsed, 10), "strike cannot run before its tenth tick");
            check(timing.remaining(100 + elapsed, 10) == 10 - elapsed, "display countdown matches actual due gate");
        }
        check(timing.due(110, 10), "next strike is due at ten, not eleven");
        check(timing.due(111, 10), "a held attempt remains due without a catch-up counter");
    }

    private static void stablePrimaryContacts() {
        LanceBeatTiming timing = new LanceBeatTiming();
        ContactGate server = new ContactGate();
        List<Integer> hits = new ArrayList<>();
        int continuation = -1;
        for (int tick = 0; tick <= 100; tick++) {
            if (timing.due(tick, 10)) {
                timing.strike(tick);
                continuation = tick + 1;
                if (server.contact(tick, true)) hits.add(tick);
            } else if (tick == continuation) {
                check(!server.contact(tick, true), "successful primary contact makes the next-tick continuation harmless");
            }
        }
        check(hits.size() == 11, "steady primary hits occur at zero through one hundred inclusive");
        for (int i = 1; i < hits.size(); i++) check(hits.get(i) - hits.get(i - 1) == 10, "primary contacts are exactly ten world ticks apart");
    }

    private static void continuationContacts() {
        LanceBeatTiming timing = new LanceBeatTiming();
        ContactGate server = new ContactGate();
        List<Integer> hits = new ArrayList<>();
        int continuation = -1;
        for (int tick = 0; tick <= 101; tick++) {
            if (timing.due(tick, 10)) {
                timing.strike(tick);
                continuation = tick + 1;

                if (tick != 0) check(!server.contact(tick, true), "primary at age nine cannot refresh the contact map");
            } else if (tick == continuation && server.contact(tick, true)) hits.add(tick);
        }
        check(hits.size() == 11 && hits.getFirst() == 1, "a missed first primary shifts contacts onto the continuation stream");
        for (int i = 1; i < hits.size(); i++) check(hits.get(i) - hits.get(i - 1) == 10, "continuation contacts also remain ten ticks apart");
    }

    private static void delayedArmingAndWebRelease() {
        LanceBeatTiming timing = new LanceBeatTiming();
        timing.strike(100);

        for (int tick = 101; tick < 126; tick++) {
            boolean armed = false;
            check(!(armed && timing.due(tick, 10)), "elapsed beat never authorizes a strike before re-arming");
        }
        check(timing.due(126, 10), "re-armed strike is available immediately after the old beat has expired");
        timing.strike(126);
        check(!timing.due(127, 10) && timing.remaining(127, 10) == 9, "delayed strike does not cause a queued flurry");
        check(timing.due(136, 10), "the new beat is ten ticks after the actual delayed strike");
        check(timing.due(145, 10), "blocked geometry leaves the overdue strike ready");
        timing.strike(145);
        check(!timing.due(146, 10) && timing.due(155, 10), "terrain delay also restarts only from the actual strike");
    }

    private static void passiveContactCaveat() {
        ContactGate server = new ContactGate();
        check(!server.contact(9, false), "a zero-damage ray graze does not damage the victim");
        check(!server.contact(10, true), "but its contact timestamp blocks the scheduled primary");
        check(!server.contact(11, true), "the continuation is also blocked by that passive graze");
        check(server.contact(19, true), "the same contact map opens exactly at age ten");
        server.newUse();
        check(server.contact(20, true), "a new use resets the per-use contact map, independently of victim invulnerability");
    }

    private static void lifecycleAndBounds() {
        LanceBeatTiming timing = new LanceBeatTiming();
        timing.strike(500);
        check(!timing.due(0, 10), "unexpected clock rewind cannot create a strike");
        timing.reset();
        check(timing.due(0, 10), "explicit body/target reset permits fresh scheduling");
        for (int period = 1; period <= 40; period++) {
            timing.strike(1000);
            check(!timing.due(1000 + period - 1, period), "all configured periods keep their full interval");
            check(timing.due(1000 + period, period), "all configured periods become due at equality");
        }
        timing.strike(Integer.MAX_VALUE - 20);
        check(timing.due(Integer.MAX_VALUE - 10, 10), "large absolute tick values preserve ten-tick spacing");
        timing.strike(2);
        check(!timing.due(2, 0) && timing.due(3, 0), "invalid zero period still cannot repeat in one tick");
    }

    private static final class ContactGate {
        private Integer lastContact;

        boolean contact(int worldTick, boolean damageConditions) {
            if (lastContact != null && worldTick - lastContact < 10) return false;
            lastContact = worldTick;
            return damageConditions;
        }

        void newUse() {
            lastContact = null;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
