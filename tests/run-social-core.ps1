param([string]$Tools = (Join-Path $PSScriptRoot '..\.local-tools'))
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskJava = Get-ChildItem -LiteralPath $Tools -Recurse -Filter java.exe | Select-Object -First 1 -ExpandProperty FullName
if (!$taskJava) { throw 'Portable Java is absent; configure JAVA_HOME or supply -Tools with the verification runtime.' }
$taskDependencies = Get-ChildItem -LiteralPath $Tools -Filter '*.jar' | Where-Object { $_.Name -notin @('social-tests.jar', 'syntax-checker.jar') } | ForEach-Object FullName
$taskClassPath = $taskDependencies -join ';'
$taskSources = @(
    "$taskRoot\app\src\main\java\net\atom\dpibypass\social\SocialModels.kt",
    "$taskRoot\app\src\main\java\net\atom\dpibypass\social\ExperimentCoordinator.kt",
    "$taskRoot\app\src\test\java\net\atom\dpibypass\SocialAccessTest.kt",
    "$taskRoot\app\src\test\java\net\atom\dpibypass\SocialCoordinatorTest.kt"
)
& $taskJava -cp $taskClassPath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $taskClassPath -d "$Tools\social-tests.jar" @taskSources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Push-Location "$taskRoot\app"
try { & $taskJava -cp "$Tools\social-tests.jar;$taskClassPath" org.junit.runner.JUnitCore net.atom.dpibypass.SocialAccessTest net.atom.dpibypass.SocialCoordinatorTest }
finally { Pop-Location }
exit $LASTEXITCODE
