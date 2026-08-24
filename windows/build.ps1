<#
.SYNOPSIS
    Builds Charger Greetings for Windows.

.DESCRIPTION
    Compiles the tray app, the behaviour test suite and the per-user installer,
    and stages a ready-to-run output folder.

    The build deliberately targets .NET Framework 4.8 and uses the C# compiler
    that ships inside Windows itself (C:\Windows\Microsoft.NET\Framework64).
    That means:
      * no SDK download is required to build,
      * the resulting .exe runs on any Windows 10/11 machine with no runtime
        install (4.8 is an OS component),
      * the whole app is ~220 KB rather than a ~70 MB self-contained publish.

    A modern .NET build is also supported: see ChargerGreetings.csproj, which
    multi-targets net48 and net8.0-windows from these exact sources. Use that
    path if you have the .NET SDK and want a net8.0 binary.

.PARAMETER Test
    Also build and run the behaviour test suite.

.PARAMETER SkipIcons
    Reuse the icons in assets/branding instead of regenerating them.

.EXAMPLE
    .\build.ps1 -Test
#>
[CmdletBinding()]
param(
    [switch]$Test,
    [switch]$SkipIcons
)

$ErrorActionPreference = 'Stop'

$WindowsRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $WindowsRoot
$SrcDir      = Join-Path $WindowsRoot 'src\ChargerGreetings'
$TestDir     = Join-Path $WindowsRoot 'test'
$InstallerDir= Join-Path $WindowsRoot 'installer'
$AssetsDir   = Join-Path $ProjectRoot 'assets'
$BrandDir    = Join-Path $AssetsDir  'branding'
$OutRoot     = Join-Path $ProjectRoot 'build'
$AppOut      = Join-Path $OutRoot 'app'
$TestOut     = Join-Path $OutRoot 'test'
$DistOut     = Join-Path $ProjectRoot 'dist'

$Framework = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
$Csc       = Join-Path $Framework 'csc.exe'

if (-not (Test-Path $Csc)) {
    throw "The in-box C# compiler was not found at $Csc. This script needs .NET Framework 4.x, which is part of Windows."
}

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-Csc([string[]]$Arguments, [string]$What) {
    & $Csc $Arguments
    if ($LASTEXITCODE -ne 0) { throw "$What failed (csc exit $LASTEXITCODE)." }
}

# ---------------------------------------------------------------- 1. icons
if (-not $SkipIcons) {
    Write-Step 'Generating application icons'
    $iconSource = Get-Content -Raw (Join-Path $ProjectRoot 'tools\IconGen\Program.cs')
    # Compiled in-memory: the generator is a build step, not a shipped binary.
    # A fresh namespace each run so re-running in the same session cannot
    # collide with an already-loaded copy of the generator.
    $ns = "IconGenBuild$([Guid]::NewGuid().ToString('N').Substring(0,8))"
    $iconTypes = Add-Type -TypeDefinition ($iconSource -replace 'namespace IconGen', "namespace $ns") `
                          -ReferencedAssemblies 'System.Drawing','System' -PassThru
    $generator = $iconTypes | Where-Object { $_.Name -eq 'Program' } | Select-Object -First 1
    if (-not $generator) { throw 'Icon generator type was not produced.' }
    # Explicit [object[]] of [string]: PowerShell otherwise hands reflection a
    # PSObject wrapper, which will not bind to the string parameter.
    $generatorArgs = [object[]]@([string]$BrandDir)
    [void]$generator.GetMethod('Generate').Invoke($null, $generatorArgs)
} else {
    Write-Step 'Reusing existing icons'
}

foreach ($required in @('app.ico','app-off.ico')) {
    if (-not (Test-Path (Join-Path $BrandDir $required))) {
        throw "Missing icon: $required. Run without -SkipIcons."
    }
}

# ------------------------------------------------------------------ 2. app
Write-Step 'Compiling ChargerGreetings.exe'
New-Item -ItemType Directory -Force -Path $AppOut | Out-Null

$references = @('System.dll','System.Core.dll','System.Windows.Forms.dll','System.Drawing.dll')

$appArgs = New-Object System.Collections.ArrayList
foreach ($a in @('/nologo','/target:winexe','/platform:anycpu','/optimize+','/warn:4','/nowarn:1591')) {
    [void]$appArgs.Add($a)
}
[void]$appArgs.Add("/out:$AppOut\ChargerGreetings.exe")
[void]$appArgs.Add("/win32icon:$BrandDir\app.ico")
[void]$appArgs.Add("/resource:$BrandDir\app.ico,ChargerGreetings.app.ico")
[void]$appArgs.Add("/resource:$BrandDir\app-off.ico,ChargerGreetings.app-off.ico")
foreach ($r in $references) { [void]$appArgs.Add("/r:$Framework\$r") }
Get-ChildItem $SrcDir -Filter *.cs | ForEach-Object { [void]$appArgs.Add($_.FullName) }

Invoke-Csc $appArgs.ToArray() 'Application build'

# --------------------------------------------------------------- 3. assets
Write-Step 'Staging audio assets'
$audioOut = Join-Path $AppOut 'audio'
New-Item -ItemType Directory -Force -Path $audioOut | Out-Null
foreach ($clip in @('power_connected.wav','power_disconnected.wav')) {
    $source = Join-Path $AssetsDir $clip
    if (-not (Test-Path $source)) {
        throw "Missing audio asset: $clip. Run tools/AudioPrep first (see assets/AUDIO-REPORT.md)."
    }
    Copy-Item $source $audioOut -Force
}
Write-Host ("    staged {0} clips" -f (Get-ChildItem $audioOut -Filter *.wav).Count)

# ---------------------------------------------------------------- 4. tests
if ($Test) {
    Write-Step 'Running behaviour tests'
    New-Item -ItemType Directory -Force -Path $TestOut | Out-Null

    # Tests resolve audio relative to the staged folder passed in --appdir.
    $testAudio = Join-Path $TestOut 'audio'
    New-Item -ItemType Directory -Force -Path $testAudio | Out-Null
    Copy-Item (Join-Path $audioOut '*.wav') $testAudio -Force

    # The suite is compiled in-memory and invoked directly, rather than built
    # into a test .exe. A freshly built unsigned executable has no reputation,
    # and Defender's "block at first sight" quarantines such files on some
    # machines -- which would fail the build for reasons unrelated to the code.
    # Never writing a test binary to disk avoids that class of flake entirely.
    $testSources = @()
    $testSources += (Get-ChildItem $SrcDir  -Filter *.cs | ForEach-Object { $_.FullName })
    $testSources += (Get-ChildItem $TestDir -Filter *.cs | ForEach-Object { $_.FullName })

    $refAssemblies = @('System.dll','System.Core.dll','System.Windows.Forms.dll','System.Drawing.dll')
    $testTypes = Add-Type -Path $testSources -ReferencedAssemblies $refAssemblies -PassThru
    $runner = $testTypes | Where-Object { $_.FullName -eq 'ChargerGreetings.Tests.TestRunner' } |
              Select-Object -First 1
    if (-not $runner) { throw 'Test runner type was not produced.' }

    $resultsFile = Join-Path $TestOut 'test-results.txt'
    if (Test-Path $resultsFile) { Remove-Item $resultsFile -Force }

    $runnerArgs = [object[]]@(, [string[]]@('--quiet', '--appdir', [string]$TestOut))
    $testExit = $runner.GetMethod('Run').Invoke($null, $runnerArgs)

    if (Test-Path $resultsFile) { Get-Content $resultsFile | ForEach-Object { Write-Host $_ } }
    else { Write-Host '    (no transcript produced)' -ForegroundColor Yellow }

    if ($testExit -ne 0) { throw "Test suite reported failures (exit $testExit)." }
}

# ------------------------------------------------------------ 5. installer
Write-Step 'Compiling the installer'
$installerSources = Get-ChildItem $InstallerDir -Filter *.cs -ErrorAction SilentlyContinue
if ($installerSources) {
    New-Item -ItemType Directory -Force -Path $DistOut | Out-Null

    # The installer carries the staged app folder as a compressed payload.
    $payloadZip = Join-Path $OutRoot 'payload.zip'
    if (Test-Path $payloadZip) { Remove-Item $payloadZip -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($AppOut, $payloadZip)
    Write-Host ("    payload: {0:N0} KB" -f ((Get-Item $payloadZip).Length / 1KB))

    $setupArgs = New-Object System.Collections.ArrayList
    foreach ($a in @('/nologo','/target:winexe','/platform:anycpu','/optimize+','/warn:4')) {
        [void]$setupArgs.Add($a)
    }
    [void]$setupArgs.Add("/out:$DistOut\ChargerGreetings-Setup.exe")
    [void]$setupArgs.Add("/win32icon:$BrandDir\app.ico")
    [void]$setupArgs.Add("/resource:$payloadZip,ChargerGreetings.payload.zip")
    foreach ($r in @('System.dll','System.Core.dll','System.Windows.Forms.dll',
                     'System.Drawing.dll','System.IO.Compression.dll',
                     'System.IO.Compression.FileSystem.dll')) {
        [void]$setupArgs.Add("/r:$Framework\$r")
    }
    $installerSources | ForEach-Object { [void]$setupArgs.Add($_.FullName) }

    Invoke-Csc $setupArgs.ToArray() 'Installer build'

    # Also ship a plain zip for users who would rather not run an installer.
    $portableZip = Join-Path $DistOut 'ChargerGreetings-portable.zip'
    if (Test-Path $portableZip) { Remove-Item $portableZip -Force }
    [System.IO.Compression.ZipFile]::CreateFromDirectory($AppOut, $portableZip)
} else {
    Write-Host '    (no installer sources found; skipped)' -ForegroundColor Yellow
}

# ----------------------------------------------------------------- summary
Write-Step 'Build complete'
Write-Host ''
Write-Host '  Application : ' -NoNewline; Write-Host (Join-Path $AppOut 'ChargerGreetings.exe')
if (Test-Path (Join-Path $DistOut 'ChargerGreetings-Setup.exe')) {
    Write-Host '  Installer   : ' -NoNewline; Write-Host (Join-Path $DistOut 'ChargerGreetings-Setup.exe')
    Write-Host '  Portable    : ' -NoNewline; Write-Host (Join-Path $DistOut 'ChargerGreetings-portable.zip')
}
Write-Host ''
Get-ChildItem $AppOut -Recurse -File |
    Select-Object @{n='File';e={$_.FullName.Substring($AppOut.Length + 1)}},
                  @{n='KB';e={[math]::Round($_.Length / 1KB, 1)}} |
    Format-Table -AutoSize
