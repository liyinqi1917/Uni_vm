# UniVM Windows portable app packaging script
# Output: UniVM.jar + exe\build\UniVM\UniVM.exe
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Step([string]$msg) {
    Write-Host ""
    Write-Host "==== $msg ===="
}

Step "[1/5] Cleaning out/ and exe/build/"
if (Test-Path "out") { Remove-Item -Recurse -Force "out" }
if (Test-Path "exe\build") { Remove-Item -Recurse -Force "exe\build" }
New-Item -ItemType Directory -Path "out" | Out-Null

Step "[2/5] Compiling Java sources"
$sources = @(Get-ChildItem -Path "src" -Filter "*.java" | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -d out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit=$LASTEXITCODE)" }

Step "[3/5] Creating Manifest (Main-Class: Gui)"
$manifest = Join-Path $root "Manifest.txt"
Set-Content -Path $manifest -Value "Main-Class: Gui" -Encoding ASCII

Step "[4/5] Packaging UniVM.jar"
& jar cfm UniVM.jar $manifest -C out .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit=$LASTEXITCODE)" }

Step "[5/5] Creating portable app image with jpackage"
# jpackage requires the main jar to live inside the --input directory
Copy-Item -Force "UniVM.jar" "out\UniVM.jar"
New-Item -ItemType Directory -Force -Path "exe\build" | Out-Null
& jpackage --type app-image --name UniVM --input out --main-jar UniVM.jar --main-class Gui --dest "exe\build"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed (exit=$LASTEXITCODE)" }

Remove-Item -Force $manifest
Write-Host ""
Write-Host "Build complete: exe\build\UniVM\UniVM.exe"
