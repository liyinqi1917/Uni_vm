# UniVM — 通用字节码多目标编译器

> 借鉴 JVM / GraalVM 模型：**每种语言写一个前端 → 全部落到同一套栈式字节码 → 一个 VM（解释器 + JIT）→ 多个后端输出**。
> 不存在"一个编译器编译所有语言"；JVM 能跑 Java/Kotlin/Scala 靠的就是"多前端 + 单字节码 + 单 VM"。

## 当前状态（2026-10-02 已验证）

M1–M6 全部打通并多端验证一致：

| 链路 | 文件 | 状态 |
| --- | --- | --- |
| .uni 前端 | `src/Lexer.java` `src/Parser.java` | ✅ 递归下降，let/赋值/print/if-else/while/func/return/表达式语句/数组 |
| Python 前端（M5） | `src/LexerPy.java` `src/ParserPy.java` | ✅ 缩进块/`def`/`elif`，产出同一 AST |
| AST | `src/Ast.java` | ✅ |
| 类型检查（M6） | `src/TypeChecker.java` | ✅ 作用域符号表，INT/ARRAY，未定义变量/实参/操作数校验 |
| 字节码生成 | `src/Codegen.java` | ✅ Module，栈式指令 + 结构化控制节点 |
| 解释器 | `src/Vm.java` | ✅ Tier 0，帧式调用 |
| 分层 JIT | `src/Jit.java` | ✅ Tier 1（2.47x）+ OSR（1.97x）；Tier2 能力探测 |
| 堆 + GC（M6） | `src/Heap.java` `src/ArrayTypes.java` | ✅ 保守追踪式 GC，根=各帧局部变量+操作数栈 |
| 后端 C | `src/BackendC.java` | ✅ .c（数组→`long long*`，无 GC；需 gcc） |
| 后端 C++ | `src/BackendCpp.java` | ✅ 分离 .h/.cpp（数组→`vector*`；需 g++） |
| 后端 Go | `src/BackendGo.java` | ✅ exe（数组→`[]int64`，Go 自带 GC） |
| 后端 Java | `src/BackendJava.java` | ✅ jar（数组→`long[]`，JVM 自带 GC） |

验证记录：

- M1 `test.uni`：`50 48 196` ✅
- M2 `control.uni`：`10 100 2 1 1 0` ✅
- M3 `funcs.uni`：解释器/jar/Go 一致 `7 120 3628800 55` ✅
- M4 `jit.uni`：work 调用 3001 次第 1000 次编译，1179ms vs 2914ms，**2.47x** ✅
- M5 `py_demo.py`：Python 源码 → 解释器/jar/Go 一致 `7 120 3628800 55 3 2 1 5050` ✅
- M6 `arrays.uni`：`30 499 10`；502 次分配、5 次 GC、每次回收约 100；jar/Go 一致 ✅
- M4b `osr.uni`（函数调用 1 次、回边 200 万）：结果 `1999999000000`，165ms vs 325ms，**OSR 1.97x**；`osr_main.uni` 验证 main 帧路径 ✅
- Tier2：本机为普通 OpenJDK（无 GraalVM JVMCI），`--profile` 如实报告不可用。

## 用法

```
java Main <input.uni|input.py> [--run] [--ir] [--profile] [--no-jit] [--no-check]
                              [--c out] [--cpp base] [--go out.exe] [--jar out.jar]
```

- 前端按扩展名选择：`.py` → Python 前端，其余 → .uni；二者落到同一 Module。
- `--cpp base` 生成 `base.h`+`base.cpp` 并尝试 `g++ -O2 -std=c++17`。
- `--profile` 打印调用计数、C1/OSR 编译、Tier2 状态与堆/GC 统计。
- `--no-jit` 关 Tier1/OSR；`--no-check` 关类型检查。

## 语言与运行时

- 语句：`let`、赋值/下标赋值、`print`、`if/elif/else`、`while`、`func/def`、`return`、调用语句；`#` 注释；仅 int64。
- 数组：`[n]` 分配、`a[i]`、`a[i]=v`、`len(a)`；数组为引用类型。
- GC：根=解释器与编译码各帧的局部变量+操作数栈；保守标记（数据恰等于句柄只会多留，不会误释放），每 100 次分配回收一次。

## 路线图

- [x] **M1–M3** 变量/控制流/函数栈帧 + 多后端
- [x] **M4** 分层 JIT（C1 编译码，2.47x）
- [x] **M4b** OSR 栈上替换（1.97x）；Tier2 能力探测（需 GraalVM，当前不可用）
- [x] **M5** 多语言前端（Python 风格，复用全部后端）
- [x] **M6** 类型系统（INT/ARRAY 检查）+ 追踪式 GC
- [ ] **M7** 更多类型（字符串/对象）、数组作为函数参数、跨语言互操作
- [ ] **Tier2** 换 GraalVM 后启用 JVMCI 机器码（C2）

## 本机工具链

- JDK 21 ✅  Go 1.26 ✅  gcc ✗（装 MinGW 后 `--c`）  g++ ✗（装 MinGW 后 `--cpp`）
