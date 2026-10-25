#Requires -Version 5.1
<#
.SYNOPSIS
    Build a self-contained Windows x64 release package for UniVM.

.DESCRIPTION
    Compiles the project, creates UniVM.jar, builds a jpackage app-image with a
    bundled Java runtime, adds source/examples/docs and a CLI launcher, then
    produces a distributable zip plus a SHA-256 checksum.

.PARAMETER Version
    Release version, in major.minor.patch form. Defaults to 1.1.1.

.EXAMPLE
    .\release.ps1

.EXAMPLE
    .\release.ps1 -Version 1.0.1
#>
param(
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = "1.1.1"
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $root) {
    $root = Split-Path -Parent $MyInvocation.MyCommand.Path
}

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==== $Message ===="
}

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH. Install JDK 21+ and ensure its bin directory is on PATH."
    }
}

if ($env:OS -ne "Windows_NT") {
    throw "release.ps1 targets Windows x64. Use jpackage --type app-image directly on other platforms."
}

Assert-Command javac
Assert-Command jar
Assert-Command jpackage

Set-Location $root

$distDir    = Join-Path $root "dist"
$buildDir   = Join-Path $distDir "build"
$classesDir = Join-Path $buildDir "classes"
$inputDir   = Join-Path $buildDir "input"
$manifest   = Join-Path $buildDir "Manifest.txt"
$jarPath    = Join-Path $inputDir "UniVM.jar"
$appImage   = Join-Path $distDir "UniVM"
$zipPath    = Join-Path $distDir "UniVM-$Version-windows-x64.zip"
$hashPath   = "$zipPath.sha256"

Write-Step "[1/6] Preparing dist/"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
foreach ($stale in @($buildDir, $appImage, $zipPath, $hashPath)) {
    if (Test-Path $stale) {
        Remove-Item -Recurse -Force $stale
    }
}
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null

Write-Step "[2/6] Compiling Java sources"
$sources = @(
    Get-ChildItem -Path (Join-Path $root "src") -Filter "*.java" -File |
        Sort-Object FullName |
        ForEach-Object { $_.FullName }
)
if ($sources.Count -eq 0) {
    throw "No Java sources found under src/."
}
& javac -encoding UTF-8 -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE."
}

Write-Step "[3/6] Building UniVM.jar"
$manifestText = "Manifest-Version: 1.0`r`nMain-Class: Gui`r`n`r`n"
[System.IO.File]::WriteAllText($manifest, $manifestText, [System.Text.Encoding]::ASCII)
& jar cfm $jarPath $manifest -C $classesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE."
}

Write-Step "[4/6] Creating self-contained app-image with jpackage"
& jpackage `
    --type app-image `
    --name UniVM `
    --app-version $Version `
    --vendor "UniVM Project" `
    --description "UniVM universal bytecode multi-target compiler" `
    --input $inputDir `
    --main-jar "UniVM.jar" `
    --main-class Gui `
    --dest $distDir `
    --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE."
}
if (-not (Test-Path (Join-Path $appImage "UniVM.exe"))) {
    throw "jpackage did not produce $appImage\UniVM.exe."
}

# jpackage's app-image runtime omits the java launcher. Copy it from the JDK
# used for this build so univm-cli.bat can run without an external JDK.
$javaExe = Join-Path (Split-Path -Parent (Get-Command javac).Source) "java.exe"
if (-not (Test-Path $javaExe)) {
    throw "Could not find java.exe next to javac. Ensure a full JDK 21+ is on PATH."
}
Copy-Item -Force $javaExe (Join-Path $appImage "runtime\bin\java.exe")

Write-Step "[5/6] Adding sources, examples, docs, and CLI launcher"
Copy-Item -Recurse -Force (Join-Path $root "src") (Join-Path $appImage "src")
Copy-Item -Recurse -Force (Join-Path $root "examples") (Join-Path $appImage "examples")

$docs = @("README.md", "ARCHITECTURE.md", "CHANGELOG.md", "SECURITY.md", "CODE_OF_CONDUCT.md")
foreach ($doc in $docs) {
    $source = Join-Path $root $doc
    if (Test-Path $source) {
        Copy-Item -Force $source (Join-Path $appImage $doc)
    }
}

$cliLauncher = @"
@echo off
setlocal
chcp 65001 >nul <nul
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%~dp0app\UniVM.jar" Main %*
endlocal
"@
$cliLauncher = $cliLauncher.Replace("`r`n", "`n").Replace("`n", "`r`n")
[System.IO.File]::WriteAllText(
    (Join-Path $appImage "univm-cli.bat"),
    $cliLauncher,
    [System.Text.Encoding]::ASCII
)

$releaseNotes = @"
UniVM $Version - Windows x64 portable release
=============================================

Start the GUI:
  UniVM.exe

Run the command-line compiler with the bundled Java runtime:
  univm-cli.bat examples\test.uni --run
  univm-cli.bat examples\test_cpp.cpp --run

Package contents:
  UniVM.exe        GUI launcher (bundled runtime)
  univm-cli.bat    CLI launcher (bundled runtime)
  app\             UniVM.jar
  runtime\         Java runtime
  src\             Java sources
  examples\        Sample programs
  README.md        Project documentation

The GUI and CLI do not require a separate JDK installation.
"@
[System.IO.File]::WriteAllText(
    (Join-Path $appImage "README-RELEASE.txt"),
    $releaseNotes,
    [System.Text.UTF8Encoding]::new($false)
)

[System.IO.File]::WriteAllText(
    (Join-Path $appImage "VERSION.txt"),
    "UniVM $Version`r`n",
    [System.Text.Encoding]::ASCII
)

Write-Step "[6/6] Creating zip and SHA-256 checksum"
if (Test-Path $zipPath) {
    Remove-Item -Force $zipPath
}
Compress-Archive -Path $appImage -DestinationPath $zipPath -CompressionLevel Optimal

$hash = (Get-FileHash -Algorithm SHA256 -Path $zipPath).Hash
$hashLine = "$hash  $(Split-Path -Leaf $zipPath)`r`n"
[System.IO.File]::WriteAllText($hashPath, $hashLine, [System.Text.Encoding]::ASCII)

Remove-Item -Recurse -Force $buildDir

$sizeMb = (Get-Item $zipPath).Length / 1MB
$zipRelative = "dist\$([System.IO.Path]::GetFileName($zipPath))"
Write-Host ""
Write-Host "打包完成！文件位于 $zipRelative，你可以直接把这个文件发给别人。"
Write-Host ("大小: {0:N1} MB" -f $sizeMb)
Write-Host "SHA-256 文件: $hashPath"
Write-Host "SHA-256: $hash"
Write-Host "解压后的程序目录: $appImage"
