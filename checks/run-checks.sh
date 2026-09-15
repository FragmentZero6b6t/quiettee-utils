#!/usr/bin/env sh
set -e
cd "$(dirname "$0")/.."
OUT=checks/out
mkdir -p "$OUT"
SRC=src/main/java/com/quiettee/utils
javac -encoding UTF-8 -d "$OUT" \
    $SRC/modules/combat/BoatShotAutomation.java \
    $SRC/modules/movement/BoatPhaseVertical.java \
    $SRC/modules/combat/BoatShotUseBudget.java \
    $SRC/modules/combat/BoatShotDamage.java \
    $SRC/modules/combat/BoatShotDrawLock.java \
    $SRC/modules/combat/BoatShotWebPlan.java \
    $SRC/modules/combat/BoatShotCadence.java \
    $SRC/modules/combat/BoatShotWebShape.java \
    $SRC/modules/movement/BoatPhaseRemount.java \
    $SRC/modules/combat/BoatShotPrediction.java \
    $SRC/modules/combat/BoatShotWebTiming.java \
    $SRC/modules/movement/BoatPhaseTravel.java \
    $SRC/modules/movement/BoatPhaseMeter.java \
    $SRC/modules/movement/BoatPhaseHash.java \
    $SRC/modules/combat/BoatShotPursuit.java \
    $SRC/modules/movement/BoatPhaseAirSteps.java \
    $SRC/modules/combat/BoatShotAim.java \
    $SRC/modules/combat/BoatShotEvents.java \
    $SRC/modules/combat/BoatShotMount.java \
    $SRC/modules/combat/BoatShotCycle.java \
    $SRC/modules/movement/BoatPhaseMotion.java \
    $SRC/modules/combat/LanceApproachPriority.java \
    $SRC/modules/combat/LanceAttackMath.java \
    $SRC/modules/combat/LanceBeatTiming.java \
    $SRC/modules/combat/LanceCouchTiming.java \
    $SRC/modules/combat/LanceCrystalSafety.java \
    $SRC/modules/combat/LanceLanePolicy.java \
    $SRC/modules/combat/LanceMovementMath.java \
    $SRC/modules/combat/LancePathSafety.java \
    $SRC/modules/combat/LanceWebTiming.java \
    $SRC/util/LanceWebMath.java \
    $SRC/util/MaceMovementMath.java \
    checks/*.java
for main in \
    BoatShotV14Test \
    BoatShotV13Test \
    BoatShotV12Test \
    BoatShotV8Test \
    BoatShotV9Test \
    BoatPhaseRemountTest \
    BoatShotPredictionTest \
    BoatShotWebTimingTest \
    BoatPhaseTravelTest \
    BoatPhaseMeterTest \
    BoatPhaseHashTest \
    BoatShotPursuitTest \
    com.quiettee.utils.modules.combat.BoatShotAimTest \
    com.quiettee.utils.modules.combat.BoatShotCycleTest \
    com.quiettee.utils.modules.movement.BoatPhaseMotionTest \
    LanceCrystalSafetyTest \
    LanceWebMathTest \
    MaceMovementMathTest \
    com.quiettee.utils.modules.combat.LanceLanePolicyTest \
    com.quiettee.utils.modules.combat.LanceAttackMathTest \
    com.quiettee.utils.modules.combat.LanceApproachPriorityTest \
    com.quiettee.utils.modules.combat.LanceBeatTimingTest \
    com.quiettee.utils.modules.combat.LanceWebTimingTest \
    com.quiettee.utils.modules.combat.LanceMovementRegression \
    com.quiettee.utils.modules.combat.LanceCouchRegression \
    com.quiettee.utils.modules.combat.LancePathSafetyTest \

do
    java -ea -cp "$OUT" "$main"
done
echo "all checks passed"
