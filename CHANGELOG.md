# Changelog

所有值得记录的改动都会写在这里。
格式参考 [Keep a Changelog](https://keepachangelog.com/)。

## [1.2.0] - 2026-10-25

### Added
- 现代化 IDE 壳：`JMenuBar`（文件 / 编辑 / 运行 / 帮助）、图标工具栏、实时底部状态栏
- `JTabbedPane` 多文件标签页，支持新建、打开、保存、另存为、全部保存与关闭
- 左侧工作区文件树：懒加载目录、双击打开文件、刷新、右键菜单
- `EditorPane`：`JTextPane`、行号、等宽字体、语法高亮、撤销/重做、智能缩进
- `Ctrl+B` 编译检查 / `Ctrl+G` 生成后端 / `F5` 运行 / `Shift+F5` 停止 / `Ctrl+Shift+F` 格式化
- 块注释折叠与展开（`Ctrl+Alt+C` / `Ctrl+Alt+U`），折叠状态不会污染编译与运行源码
- `Vm.StopToken`、`VmStoppedException` 与解释器/JIT 检查点，支持安全中断死循环
- `CodeFormatter`：`.uni` / `.py` / `.lisp` 缩进格式化
- `Icons` 矢量图标、`FileTreePanel`、`EditorPane` 等独立组件
- 单文件 Windows 安装器 `dist/UniVM-1.2.0-Setup.exe`

### Changed
- `Gui` 从单编辑框升级为完整 IDE 工作流：文件树、标签页、编辑器、控制台、状态栏
- 控制台改用支持中文的字体，中文输出不再显示为方块
- 编译、生成、运行使用独立完成状态：`编译成功` / `生成成功` / `运行成功`
- `release.ps1`、`installer_build.ps1` 与安装器默认版本更新为 `1.2.0`

### Fixed
- 文件树渲染器在组件字体尚未初始化时的空指针异常
- 控制台中文输出因等宽字体缺少 CJK 字形而显示为方块
- 运行中的无限循环无法从 GUI 停止的问题
- 折叠注释后编辑器文本与编译源码不一致的问题

## [1.1.1] - 2026-10-25

### Added
- 独立 C++ 原生前端 `LexerCpp` / `ParserCpp`，支持 `.cpp` / `.cc` / `.cxx`
- 跳过 `#include` 等预处理行与 `using namespace std;`
- `int main()`、类型声明、函数、`if/else`、`while`、`for`、`break` / `continue`
- `cout << ... << endl;` 降级为打印，`cin >> ...` 降级为 `input()` / `toInt()`
- `examples/test_cpp.cpp`，并验证 VM 与生成的原生 C++ 输出一致
- `StmtSeq` AST 节点，用于 C++ 多变量声明、`cin` 链和无作用域语句序列
- 单文件 Windows 安装器 `dist/UniVM-1.1.1-Setup.exe`：安装到 `%LOCALAPPDATA%\UniVM`，创建桌面/开始菜单快捷方式并显示完成提示
- `installer_build.ps1` 与 `installer/`：检测到 WiX 时优先尝试 `jpackage --type exe`，否则自动降级到 IExpress 自解压安装器

### Changed
- `Main` 按扩展名分发 C++ 源码到 `LexerCpp` / `ParserCpp`
- `release.ps1` 默认版本更新为 `1.1.1`，并在结束时打印中文打包完成提示
- 发布流程仍完全本地执行：`javac` → `jpackage` → `Compress-Archive` → SHA-256

### Fixed
- `C++ for` 循环降级为 `while` 时，`continue` 前会补执行迭代表达式
- 本地发布包保留 `src/`、`examples/`、`README.md` 等源码与文档

## [1.1.0] - 2026-10-25

### Added
- `input()` / `input(prompt)`：CLI 读取标准输入，GUI 使用 `JOptionPane` 弹窗
- `toInt(string)`：去除首尾空白后解析 64 位整数，非法输入返回 `0`
- `random(min, max)`：闭区间随机整数，自动处理逆序边界
- 字符串 `+` 拼接、`str(int)`，支持中文源码、Emoji 与 `\n`、`\t`、`\uXXXX` 等转义
- `break` / `continue`：六种语言前端、VM/JIT 与全部六个后端
- `examples/hello_zh.uni`、`break_continue.uni`、`input_test.uni`、`multiplication.uni`、`guess_number.uni`
- WebAssembly 宿主新增 `concat`、`str_int`、`input`、`to_int`、`random` 导入

### Changed
- `Main` 增加可注入 `Vm.InputProvider` 的 `run(args, provider)` 入口
- 源文件、生成源码与 C/C++ 编译统一使用 UTF-8
- 含字符串操作的模块自动回退到解释器，避免 Tier 1 `long[]` 栈无法承载字符串
- 发布版本号更新为 `1.1.0`

### Fixed
- C/C++ 后端补齐字符串临时量、标准库 helper 与 UTF-8 编译参数
- Go/JAR/Python/WASM 后端的 `input`、`toInt`、`random` 行为与 VM 对齐

## [Unreleased]

### Added
- M7 Day1: 数组作为函数参数（语法 `arr: array` → AST → TypeChecker → 四后端）
- `examples/arrparam.uni`、`examples/arrparam.py` 正例
- `examples/arrparam_arity.uni`、`examples/arrparam_bad.uni` 反例
- TypeChecker 调用点按位比对 INT/ARRAY，错误信息含函数名+序号+期望+实际
- `ArrayTypes` 参数种子 + LOAD 守卫放开，支持数组参数穿透到后端

### Changed
- `FuncDecl` 增加 `paramTypes` 字段
- `build.bat` 移除过时的 `--polyglot`，改用 Maven 调用 Polyglot demo

## [0.6.0] - 2026-10-02

### Added
- M1–M6 全部打通：词法/语法/类型检查/字节码生成/解释器
- Tier1 JIT（2.47x）、OSR（1.97x）
- 保守追踪式 GC
- 四种后端：C / C++ / Go / JAR
- Polyglot 互操作最小验证
- `polyglot/` 子项目（Maven），演示 JS 调用 Java 函数、数组跨语言传递

[Unreleased]: https://github.com/liyinqi1917/Uni_vm/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/liyinqi1917/Uni_vm/releases/tag/v1.1.0
[0.6.0]: https://github.com/liyinqi1917/Uni_vm/releases/tag/v0.6.0