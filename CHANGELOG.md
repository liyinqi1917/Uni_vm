# Changelog

所有值得记录的改动都会写在这里。
格式参考 [Keep a Changelog](https://keepachangelog.com/)。

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

[Unreleased]: https://github.com/liyinqi1917/Uni_vm/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/liyinqi1917/Uni_vm/releases/tag/v0.6.0