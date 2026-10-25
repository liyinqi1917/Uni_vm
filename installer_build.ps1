#Requires -Version 5.1
<#
.SYNOPSIS
    Builds a single-file Windows installer for UniVM.

.DESCRIPTION
    Runs release.ps1 (unless -SkipRelease), then:
      1. Looks for WiX Toolset (candle.exe / light.exe).
      2. If WiX exists, tries a native jpackage --type exe installer.
      3. Otherwise, or if jpackage fails, falls back to an IExpress
         self-extracting Setup.exe that runs installer/install.ps1.

    The fallback installer extracts UniVM to %LOCALAPPDATA%\UniVM,
    creates a desktop shortcut, and shows a completion message.

.PARAMETER Version
    Release version, in major.minor.patch form. Defaults to 1.1.1.

.PARAMETER SkipRelease
    Reuse an existing dist\UniVM-<version>-windows-x64.zip instead of
    re-running release.ps1.

.PARAMETER ForceIExpress
    Use the IExpress fallback even when WiX Toolset is installed.
#>
param(
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = "1.1.1",

    [switch]$SkipRelease,

    [switch]$ForceIExpress
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $root) {
    $root = Split-Path -Parent $MyInvocation.MyCommand.Path
}
Set-Location $root

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==== $Message ===="
}

function Assert-File([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description does not exist: $Path"
    }
}

function Get-CscPath {
    $candidates = @(
        (Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"),
        (Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw "C# compiler csc.exe was not found. A Windows .NET Framework 4.x installation is required."
}

function Find-WixToolset {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if ($candle -and $light) {
        return [pscustomobject]@{
            Candle = $candle.Source
            Light  = $light.Source
        }
    }

    $roots = @()
    if ($env:WIX) { $roots += $env:WIX }
    $roots += @(
        (Join-Path $env:ProgramFiles "WiX Toolset v3.14\bin"),
        (Join-Path $env:ProgramFiles "WiX Toolset v3.11\bin"),
        (Join-Path ${env:ProgramFiles(x86)} "WiX Toolset v3.14\bin"),
        (Join-Path ${env:ProgramFiles(x86)} "WiX Toolset v3.11\bin")
    )

    foreach ($dir in $roots) {
        if ([string]::IsNullOrWhiteSpace($dir)) { continue }
        $candlePath = Join-Path $dir "candle.exe"
        $lightPath = Join-Path $dir "light.exe"
        if ((Test-Path -LiteralPath $candlePath) -and (Test-Path -LiteralPath $lightPath)) {
            return [pscustomobject]@{
                Candle = $candlePath
                Light  = $lightPath
            }
        }
    }
    return $null
}

function Write-SetupChecksum([string]$Path) {
    $hash = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash
    $hashPath = "$Path.sha256"
    $line = "$hash  $(Split-Path -Leaf $Path)`r`n"
    [System.IO.File]::WriteAllText($hashPath, $line, [System.Text.Encoding]::ASCII)
    return [pscustomobject]@{ Hash = $hash; HashPath = $hashPath }
}

function Show-SetupResult([string]$Path, [string]$Hash) {
    $sizeMb = (Get-Item -LiteralPath $Path).Length / 1MB
    $relative = "dist\$([System.IO.Path]::GetFileName($Path))"
    Write-Host ""
    Write-Host "安装包生成完成！"
    Write-Host "文件位于：$relative"
    Write-Host ("大小: {0:N1} MB" -f $sizeMb)
    Write-Host "SHA-256: $Hash"
    Write-Host "双击 Setup.exe 即可安装到 %LOCALAPPDATA%\UniVM，并创建桌面快捷方式。"
}

function Try-BuildJpackageInstaller([string]$AppImage, [string]$Destination, [string]$OutputPath) {
    Assert-File (Join-Path $AppImage "UniVM.exe") "jpackage app image launcher"

    $candidate = Join-Path $Destination "UniVM-$Version.exe"
    if (Test-Path -LiteralPath $candidate) {
        Remove-Item -LiteralPath $candidate -Force
    }

    Write-Host "Running jpackage --type exe ..."
    & jpackage `
        --type exe `
        --name UniVM `
        --app-version $Version `
        --vendor "UniVM Project" `
        --description "UniVM universal bytecode multi-target compiler" `
        --app-image $AppImage `
        --dest $Destination `
        --win-shortcut `
        --win-menu `
        --win-dir-chooser
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage --type exe failed with exit code $LASTEXITCODE."
    }

    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        $found = Get-ChildItem -LiteralPath $Destination -Filter "UniVM-$Version*.exe" -File |
            Where-Object { $_.FullName -ne $OutputPath } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $found) {
            throw "jpackage finished but no EXE installer was found in $Destination."
        }
        $candidate = $found.FullName
    }

    if (Test-Path -LiteralPath $OutputPath) {
        Remove-Item -LiteralPath $OutputPath -Force
    }
    Move-Item -LiteralPath $candidate -Destination $OutputPath -Force
    Assert-File $OutputPath "generated jpackage installer"
    return $true
}

$releaseScript = Join-Path $root "release.ps1"
$launcherSource = Join-Path $root "installer\InstallerLauncher.cs"
$installScript = Join-Path $root "installer\install.ps1"
$distDir = Join-Path $root "dist"
$zipPath = Join-Path $distDir "UniVM-$Version-windows-x64.zip"
$setupPath = Join-Path $distDir "UniVM-$Version-Setup.exe"
$buildDir = Join-Path $distDir "installer-build"
$stageDir = Join-Path $buildDir "stage"
$sedPath = Join-Path $buildDir "UniVM-Setup.sed"

Assert-File $releaseScript "release.ps1"
Assert-File $launcherSource "installer launcher source"
Assert-File $installScript "installer script"
Assert-File (Join-Path $env:WINDIR "System32\iexpress.exe") "IExpress"

if (-not $SkipRelease) {
    Write-Step "[1/6] Running release.ps1"
    & $releaseScript -Version $Version
}

Assert-File $zipPath "release zip (run without -SkipRelease to create it)"

Write-Step "[2/6] Checking WiX Toolset"
$wix = Find-WixToolset
if ($wix) {
    Write-Host "WiX found: $($wix.Candle)"
    Write-Host "WiX found: $($wix.Light)"
} else {
    Write-Host "candle.exe / light.exe not found. Falling back to IExpress."
}

if ($wix -and -not $ForceIExpress) {
    Write-Step "[3/6] Trying native jpackage installer"
    try {
        $appImage = Join-Path $distDir "UniVM"
        if (Try-BuildJpackageInstaller -AppImage $appImage -Destination $distDir -OutputPath $setupPath) {
            $checksum = Write-SetupChecksum $setupPath
            Write-Step "[4/6] Done"
            Show-SetupResult -Path $setupPath -Hash $checksum.Hash
            return
        }
    } catch {
        Write-Warning "Native jpackage installer failed: $($_.Exception.Message)"
        Write-Host "Falling back to IExpress."
    }
}

Write-Step "[3/6] Staging IExpress payload"
if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
Copy-Item -LiteralPath $zipPath -Destination $stageDir -Force
Copy-Item -LiteralPath $installScript -Destination $stageDir -Force

Write-Step "[4/6] Compiling installer launcher"
$csc = Get-CscPath
$launcherExe = Join-Path $stageDir "install_launcher.exe"
& $csc `
    /nologo `
    /target:winexe `
    /optimize+ `
    /codepage:65001 `
    /out:$launcherExe `
    /r:System.Windows.Forms.dll `
    $launcherSource
if ($LASTEXITCODE -ne 0) {
    throw "csc failed with exit code $LASTEXITCODE."
}
Assert-File $launcherExe "installer launcher"

Write-Step "[5/6] Generating IExpress package description"
$fileNames = @(
    [System.IO.Path]::GetFileName($zipPath),
    "install.ps1",
    "install_launcher.exe"
)
$stringLines = for ($i = 0; $i -lt $fileNames.Count; $i++) {
    "FILE$i=`"$($fileNames[$i])`""
}
$sourceLines = for ($i = 0; $i -lt $fileNames.Count; $i++) {
    "%FILE$i%="
}
$sourceDir = $stageDir.TrimEnd('\') + '\'
$sedText = @"
[Version]
Class=IEXPRESS
SEDVersion=3

[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$setupPath
FriendlyName=UniVM $Version Setup
AppLaunched=install_launcher.exe
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
SourceFiles=SourceFiles

[Strings]
$($stringLines -join "`r`n")

[SourceFiles]
SourceFiles0=$sourceDir
[SourceFiles0]
$($sourceLines -join "`r`n")
"@
$sedText = $sedText.Replace("`r`n", "`n").Replace("`n", "`r`n")
[System.IO.File]::WriteAllText($sedPath, $sedText, [System.Text.Encoding]::ASCII)

if (Test-Path -LiteralPath $setupPath) {
    Remove-Item -LiteralPath $setupPath -Force
}

Write-Step "[6/6] Building single-file Setup.exe with IExpress"
$iexpress = Join-Path $env:WINDIR "System32\iexpress.exe"
& (Join-Path $env:WINDIR "System32\cmd.exe") /d /c "$iexpress /N /Q $sedPath"
if ($LASTEXITCODE -ne 0) {
    throw "IExpress failed with exit code $LASTEXITCODE."
}

for ($i = 0; $i -lt 30 -and -not (Test-Path -LiteralPath $setupPath); $i++) {
    Start-Sleep -Milliseconds 500
}
Assert-File $setupPath "IExpress Setup.exe"
if ((Get-Item -LiteralPath $setupPath).Length -lt 1MB) {
    throw "IExpress produced an unexpectedly small Setup.exe."
}

$checksum = Write-SetupChecksum $setupPath
Remove-Item -LiteralPath $buildDir -Recurse -Force -ErrorAction SilentlyContinue
Show-SetupResult -Path $setupPath -Hash $checksum.Hash
