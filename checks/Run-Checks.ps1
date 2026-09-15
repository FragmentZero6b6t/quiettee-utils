[CmdletBinding()]
param([string]$JdkPath = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repoRoot 'checks\out'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$src = Join-Path $repoRoot 'src\main\java\com\quiettee\utils'
$sources = @(
    (Join-Path $src 'modules\combat\BoatShotAutomation.java'),
    (Join-Path $src 'modules\movement\BoatPhaseVertical.java'),
    (Join-Path $src 'modules\combat\BoatShotUseBudget.java'),
    (Join-Path $src 'modules\combat\BoatShotDamage.java'),
    (Join-Path $src 'modules\combat\BoatShotDrawLock.java'),
    (Join-Path $src 'modules\combat\BoatShotWebPlan.java'),
    (Join-Path $src 'modules\combat\BoatShotCadence.java'),
    (Join-Path $src 'modules\combat\BoatShotWebShape.java'),
    (Join-Path $src 'modules\movement\BoatPhaseRemount.java'),
    (Join-Path $src 'modules\combat\BoatShotPrediction.java'),
    (Join-Path $src 'modules\combat\BoatShotWebTiming.java'),
    (Join-Path $src 'modules\movement\BoatPhaseTravel.java'),
    (Join-Path $src 'modules\movement\BoatPhaseMeter.java'),
    (Join-Path $src 'modules\movement\BoatPhaseHash.java'),
    (Join-Path $src 'modules\combat\BoatShotPursuit.java'),
    (Join-Path $src 'modules\movement\BoatPhaseAirSteps.java'),
    (Join-Path $src 'modules\combat\BoatShotAim.java'),
    (Join-Path $src 'modules\combat\BoatShotEvents.java'),
    (Join-Path $src 'modules\combat\BoatShotMount.java'),
    (Join-Path $src 'modules\combat\BoatShotCycle.java'),
    (Join-Path $src 'modules\movement\BoatPhaseMotion.java'),
    (Join-Path $src 'modules\combat\LanceApproachPriority.java'),
    (Join-Path $src 'modules\combat\LanceAttackMath.java'),
    (Join-Path $src 'modules\combat\LanceBeatTiming.java'),
    (Join-Path $src 'modules\combat\LanceCouchTiming.java'),
    (Join-Path $src 'modules\combat\LanceCrystalSafety.java'),
    (Join-Path $src 'modules\combat\LanceLanePolicy.java'),
    (Join-Path $src 'modules\combat\LanceMovementMath.java'),
    (Join-Path $src 'modules\combat\LancePathSafety.java'),
    (Join-Path $src 'modules\combat\LanceWebTiming.java'),
    (Join-Path $src 'util\LanceWebMath.java'),
    (Join-Path $src 'util\MaceMovementMath.java')
) + @(Get-ChildItem -Path $PSScriptRoot -Filter '*.java' | ForEach-Object { $_.FullName })
$javac = if ($JdkPath) { Join-Path $JdkPath 'bin\javac.exe' } else { 'javac' }
$java = if ($JdkPath) { Join-Path $JdkPath 'bin\java.exe' } else { 'java' }
& $javac -encoding UTF-8 -d $outputRoot @sources
if ($LASTEXITCODE -ne 0) { throw 'Checks failed to compile.' }
foreach ($mainClass in @(
    'BoatShotV14Test',
    'BoatShotV13Test',
    'BoatShotV12Test',
    'BoatShotV8Test',
    'BoatShotV9Test',
    'BoatPhaseRemountTest',
    'BoatShotPredictionTest',
    'BoatShotWebTimingTest',
    'BoatPhaseTravelTest',
    'BoatPhaseMeterTest',
    'BoatPhaseHashTest',
    'BoatShotPursuitTest',
    'com.quiettee.utils.modules.combat.BoatShotAimTest',
    'com.quiettee.utils.modules.combat.BoatShotCycleTest',
    'com.quiettee.utils.modules.movement.BoatPhaseMotionTest',
    'LanceCrystalSafetyTest', 'LanceWebMathTest', 'MaceMovementMathTest',
    'com.quiettee.utils.modules.combat.LanceLanePolicyTest',
    'com.quiettee.utils.modules.combat.LanceAttackMathTest',
    'com.quiettee.utils.modules.combat.LanceApproachPriorityTest',
    'com.quiettee.utils.modules.combat.LanceBeatTimingTest',
    'com.quiettee.utils.modules.combat.LanceWebTimingTest',
    'com.quiettee.utils.modules.combat.LanceMovementRegression',
    'com.quiettee.utils.modules.combat.LanceCouchRegression',
    'com.quiettee.utils.modules.combat.LancePathSafetyTest')) {
    & $java -ea -cp $outputRoot $mainClass
    if ($LASTEXITCODE -ne 0) { throw "Check failed: $mainClass" }
}
Write-Output 'all checks passed'
