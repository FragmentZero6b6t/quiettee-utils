package com.quiettee.utils.modules.combat;

public final class LanceCouchRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        LanceCouchTiming timing = new LanceCouchTiming();
        long first = timing.start(704);
        for (int tick = 704; tick <= 710; tick++) check(!timing.retryDue(tick, 0), "reported zero ping must tolerate the logged 6t response");
        check(timing.observe(first, true, 710), "fresh ON belongs to this press");
        check(timing.confirmed(), "6t response confirms instead of entering the logged 6t retry loop");
        check(timing.latencyTicks(0) >= 6, "arming and lead must not trust zero tab ping over a measured 6t response");
        for (int tick = 711; tick < 741; tick++) check(!timing.retryDue(tick, 0), "confirmed use must remain alive");

        timing.stop();
        long next = timing.start(1910);
        check(!timing.confirmed(), "old ON cannot confirm the web re-press");
        check(!timing.observe(first, true, 1911), "queued previous-press ON is rejected");
        check(!timing.observe(first, false, 1912), "queued previous-press OFF is rejected");
        check(timing.observe(next, false, 1913), "post-press stale release echo is an observation, not confirmation");
        check(!timing.retryDue(1919, 193), "the logged 9t DEAD deadline must not kill an unconfirmed press");
        timing.observe(next, true, 1920);
        check(timing.confirmed(), "a delayed fresh ON recovers without sending another release");
        timing.observe(next, false, 1921);
        check(!timing.confirmed(), "fresh OFF suspends strikes immediately");
        check(!timing.retryDue(1925, 193), "brief OFF churn does not immediately recouch");
        check(timing.retryDue(1926, 193), "a persistent confirmed interruption recovers within a bounded grace");
        timing.observe(next, true, 1926);
        check(!timing.retryDue(1927, 193), "ON cancels an interruption grace");

        timing.reset();
        int tick = 0;
        int previousBudget = 0;
        for (int retry = 0; retry < 10; retry++) {
            timing.start(tick);
            int budget = timing.responseBudget(0);
            check(budget >= 12 && budget <= 100 && budget >= previousBudget, "repeated missing replies back off within bounds");
            check(!timing.retryDue(tick + budget - 1, 0), "retry never precedes its response budget");
            check(timing.retryDue(tick + budget, 0), "missing replies never strand the module indefinitely");
            timing.failed();
            timing.stop();
            tick += budget + 2;
            previousBudget = budget;
        }
        for (int ping : new int[] {-100, 0, 50, 193, 600, 5000, Integer.MAX_VALUE}) {
            timing.reset();
            long attempt = timing.start(0);
            int budget = timing.responseBudget(ping);
            check(budget >= 12 && budget <= 100, "all ping values have bounded deadlines");
            check(timing.predictionTicks(ping) >= 1 && timing.predictionTicks(ping) <= 20, "strike and web share a bounded prediction horizon");
            check(!timing.confirmed(), "time alone does not establish confirmation");
            timing.stop();
            check(!timing.observe(attempt, true, budget), "release invalidates in-flight echoes");
        }
        postWebReassertion();
        System.out.println("Lance couch regression: " + checks + " assertions passed.");
    }

    private static void postWebReassertion() {
        LanceCouchTiming timing = new LanceCouchTiming();
        timing.allowNextPostWebRetry();
        long generation = timing.start(100);
        for (int tick = 100; tick < 106; tick++) {
            check(!timing.reassertDue(tick, 0), "post-web retry leaves the logged six-tick reply opportunity intact");
        }
        timing.observe(generation, true, 106);
        check(timing.confirmed() && !timing.reassert(106, 0), "a received six-tick ON wins before any use-only retry");

        timing.reset();
        timing.allowNextPostWebRetry();
        generation = timing.start(200);
        int originalBudget = timing.responseBudget(103);
        check(timing.reassertDue(206, 103), "missing post-web response permits a single six-tick use reassertion");
        check(timing.reassert(206, 103), "one eligible use reassertion is consumed");
        check(!timing.confirmed(), "sending another use is not server confirmation");
        check(timing.earliestUseAge(207) == 7, "expiry retains the older possible server couch age after reassertion");
        for (int tick = 207; tick < 230; tick++) check(!timing.reassert(tick, 103), "the same attempt never sends another use-only retry");
        check(!timing.retryDue(200 + originalBudget - 1, 103), "original full retry deadline is not shortened");
        check(timing.retryDue(200 + originalBudget, 103), "reassertion does not restart or extend the full retry deadline");
        check(timing.observe(generation, true, 209), "a delayed first or second ON retains its original generation");
        check(timing.confirmed(), "fresh ON after reassertion permits eventual conservative arming");
        check(timing.latencyTicks(103) == 3, "ambiguous post-reassert ON does not inflate the measured round-trip latency");

        timing.reset();
        timing.allowNextPostWebRetry();
        timing.start(300);
        check(!timing.reassertDue(321, 1000), "high-latency connections wait for their actual response opportunity");
        check(timing.reassertDue(322, 1000), "high-latency reassertion waits measured latency plus two ticks");
        check(timing.reassert(322, 1000), "one high-latency retry can run before the original full deadline");
        check(timing.retryDue(326, 1000), "high-latency reassertion preserves the original twenty-six-tick failure deadline");
        timing.reset();
        timing.allowNextPostWebRetry();
        timing.start(400);
        check(!timing.reassertDue(500, 5000) && timing.retryDue(500, 5000),
            "reassertion never supersedes an already-due bounded full retry");

        timing.reset();
        generation = timing.start(500);
        timing.observe(generation, true, 508);
        timing.stop();
        timing.allowNextPostWebRetry();
        timing.start(600);
        check(!timing.reassertDue(609, 0), "observed eight-tick latency overrides a falsely zero tab ping");
        check(timing.reassertDue(610, 0), "reassertion follows observed latency plus two ticks");

        timing.reset();
        timing.start(700);
        check(!timing.reassertDue(706, 0), "ordinary startup without a restored web excursion gets no extra use");
        timing.stop();
        timing.allowNextPostWebRetry();
        timing.cancelPostWebRetry();
        timing.start(800);
        check(!timing.reassertDue(806, 0), "target or manual cancellation discards an unconsumed restoration token");
        timing.stop();
        timing.allowNextPostWebRetry();
        generation = timing.start(900);
        timing.cancelPostWebRetry();
        check(!timing.reassertDue(906, 0), "manual takeover also cancels an already-started post-web attempt");
        timing.stop();
        check(!timing.observe(generation, true, 907), "release/body replacement still invalidates delayed observations");
        timing.start(908);
        check(!timing.reassertDue(914, 0), "release does not transfer retry eligibility into another attempt");
    }
}
