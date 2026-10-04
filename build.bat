@echo off
setlocal
cd /d "%~dp0"

if not exist out mkdir out

echo ============================================
echo  [1/3] Compiling UniVM
echo ============================================
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo Build failed
    exit /b 1
)

echo.
echo ============================================
echo  [2/3] Running Uni example
echo ============================================
java -cp out Main example.uni

echo.
echo ============================================
echo  [3/3] Polyglot demo optional
echo ============================================
java -version 2>&1 | findstr /C:"GraalVM" >nul
if errorlevel 1 (
    echo Skipped: GraalVM not detected
) else (
    if exist polyglot\pom.xml (
        pushd polyglot
        call mvn -q compile exec:java
        popd
    ) else (
        echo Skipped: polyglot/pom.xml not found
    )
)

echo.
echo Done.
echo.
echo Available commands:
echo   java -cp out Main example.uni              compile only
echo   java -cp out Main example.uni --run        compile and run
echo   java -cp out Main example.uni --ir         dump IR
echo   java -cp out Main example.uni --go out.exe Go backend
echo   java -cp out Main example.uni --jar out.jar JAR backend
echo   java -cp out Main example.uni --c out.exe C backend
echo.
echo Polyglot demo (requires GraalVM + Maven):
echo   cd polyglot ^&^& mvn compile exec:java
echo.
endlocal