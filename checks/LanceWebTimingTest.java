package com.quiettee.utils.modules.combat;

public final class LanceWebTimingTest {
    private static int checks;

    public static void main(String[] args) {
        initialCatchAndRearming();
        continuationOpportunities();
        failedConfirmation();
        boundsAndClockChanges();
        System.out.println("LanceWebTimingTest: " + checks + " checks passed");
    }

    private static void initialCatchAndRearming() {
        int lastVolley = -1000;
        check(LanceWebTiming.canStart(100, lastVolley, 10, false), "initial catch does not wait for the first spear arm");
        lastVolley = 100;

        for (int tick = 100; tick <= 102; tick++) {
            check(!LanceWebTiming.canStart(tick, lastVolley, 10, false), "an existing burst cannot also start a new burst");
        }

        int pressTick = 105;
        int readyTick = pressTick + 8 + 3 + 1;
        for (int tick = 103; tick < readyTick; tick++) {
            check(!LanceWebTiming.canStart(tick, lastVolley, 10, false), "elapsed web spacing must not interrupt recouch or re-arming");
        }
        check(!LanceWebTiming.canStart(110, lastVolley, 10, false), "ten-tick expiry is insufficient during the longer re-arm");
        check(LanceWebTiming.canStart(readyTick, lastVolley, 10, true), "fresh confirmed full readiness permits the next opportunity immediately");
        lastVolley = readyTick;
        for (int tick = readyTick + 1; tick < readyTick + 10; tick++) {
            check(!LanceWebTiming.canStart(tick, lastVolley, 10, true), "a newly started volley gets its own minimum spacing");
        }
    }

    private static void continuationOpportunities() {
        check(LanceWebTiming.canStart(101, -1, 10, true), "the first post-strike continuation can catch");
        check(!LanceWebTiming.canStart(110, 101, 10, true), "a subsequent opportunity at age nine cannot catch again");
        check(LanceWebTiming.canStart(111, 101, 10, true), "a ready continuation stream stays eligible exactly ten ticks apart");
        check(!LanceWebTiming.canStart(121, 111, 10, false), "a continuation opportunity cannot bypass lost readiness");
        check(LanceWebTiming.canStart(130, 111, 10, true), "a delayed continuation becomes eligible without another arbitrary hold");
        check(!LanceWebTiming.canStart(129, 110, 20, true), "a longer configured interval remains authoritative");
        check(LanceWebTiming.canStart(130, 110, 20, true), "a longer configured interval opens at equality");
    }

    private static void failedConfirmation() {
        for (int tick = 1; tick <= 500; tick++) {
            check(!LanceWebTiming.canStart(tick, 0, 10, false), "missing use confirmation never permits recurring web interruptions");
        }
        check(LanceWebTiming.canStart(501, 0, 10, true), "confirmation and full arm restore eligibility immediately");
        check(!LanceWebTiming.canStart(501, 501, 10, false), "a repeated same-tick planner/web callback cannot restart the burst");
    }

    private static void boundsAndClockChanges() {
        for (int period : new int[] {Integer.MIN_VALUE, -1, 0, 1, 9, 10}) {
            check(!LanceWebTiming.canStart(109, 100, period, true), "every configured value preserves the ten-tick minimum");
            check(LanceWebTiming.canStart(110, 100, period, true), "the minimum opens at ten ticks");
        }
        check(!LanceWebTiming.canStart(0, 100, 10, true), "clock rewind cannot masquerade as an elapsed interval");
        check(!LanceWebTiming.canStart(Integer.MIN_VALUE, Integer.MAX_VALUE - 5, 10, true), "signed tick overflow cannot authorize an immediate restart");
        check(LanceWebTiming.canStart(Integer.MAX_VALUE, Integer.MAX_VALUE - 10, 10, true), "near-maximum tick values preserve exact spacing");
        check(!LanceWebTiming.canStart(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, true), "large interval comparison does not overflow");
        check(LanceWebTiming.canStart(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, true), "maximum positive interval opens at equality");
        check(LanceWebTiming.canStart(0, -1000, Integer.MAX_VALUE, false), "explicit lifecycle reset restores the initial-catch exception");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
