@echo off
REM UniVM 构建脚本
setlocal
cd /d "%~dp0"

if not exist out mkdir out

echo ============================================
echo  [1/3] 编译 UniVM 前端与后端
echo ============================================
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo 编译失败
    exit /b 1
)

echo.
echo ============================================
echo  [2/3] 运行 Uni 语言示例
echo ============================================
java -cp out Main example.uni

echo.
echo ============================================
echo  [3/3] Polyglot 互操作演示 (可选)
echo ============================================
if exist polyglot\PolyglotDemo.java (
    echo 检测到 Polyglot 演示，尝试运行...
    java -cp "out;polyglot" PolyglotDemo
) else (
    echo 未找到 polyglot\PolyglotDemo.java，跳过。
    echo 想尝试跨语言互操作，请先阅读 README 的 Polyglot 章节。
)

echo.
echo ============================================
echo  完成。可用命令:
echo ============================================
echo   java -cp out Main example.uni              仅编译
echo   java -cp out Main example.uni --run        编译并运行
echo   java -cp out Main example.uni --ir         输出中间表示
echo   java -cp out Main example.uni --go out.exe 生成 Go 后端
echo   java -cp out Main example.uni --jar out.jar 打包 JAR
echo   java -cp out Main example.uni --c  out.exe 生成 C 后端
echo.
echo   Polyglot 演示（需 GraalVM）:
echo   java --polyglot -cp "out;polyglot" PolyglotDemo
echo.
endlocal