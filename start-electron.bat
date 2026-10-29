@echo off
setlocal
cd /d "%~dp0"

echo [1/2] Compiling UniVM Java backend...
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo.
    echo [ERROR] Java compilation failed. Electron UI was not started.
    pause
    exit /b 1
)

cd /d "%~dp0ui-electron"
if not exist node_modules\electron\dist\electron.exe (
    echo [SETUP] Installing Electron dependencies...
    if not defined ELECTRON_MIRROR set "ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/"
    call npm install
    if errorlevel 1 (
        echo.
        echo [ERROR] npm install failed.
        pause
        exit /b 1
    )
)

echo [2/2] Starting UniVM Studio...
call npm start
if errorlevel 1 (
    echo.
    echo [ERROR] UniVM Studio exited with an error.
    pause
    exit /b 1
)
endlocal
