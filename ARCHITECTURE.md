# 架构

## 总体模型

借鉴 JVM / GraalVM：

    多前端 → 统一字节码 → 一个 VM（解释器 + JIT）→ 多后端

不存在"一个编译器编译所有语言"。JVM 能跑 Java/Kotlin/Scala 靠的就是"多前端 + 单字节码 + 单 VM"。

## 数据流

    .uni / .py 源码
        ↓  Lexer + Parser（按扩展名选前端）
       AST
        ↓  TypeChecker（INT / ARRAY / 后续 STRING / OBJECT）
      Module
        ↓  Codegen（栈式指令 + 结构化控制节点）
      字节码
        ↓
    解释器 (Vm)  ←→  分层 JIT (Jit)  ←→  Heap + GC
        ↓
    C / C++ / Go / JAR 四种后端

## 模块职责

| 模块 | 文件 | 职责 |
| --- | --- | --- |
| 前端 | `Lexer.java` `Parser.java` | .uni 语法，递归下降 |
| 前端 | `LexerPy.java` `ParserPy.java` | Python 风格语法，产出同一 AST |
| AST | `Ast.java` | 节点定义，含 `FuncDecl.paramTypes` |
| 类型检查 | `TypeChecker.java` | 作用域符号表，签名表 `funParams` |
| 字节码 | `Codegen.java` | Module 生成，栈式指令 |
| 解释器 | `Vm.java` | Tier 0，帧式调用 |
| JIT | `Jit.java` | Tier 1 编译 + OSR |
| 堆 | `Heap.java` `ArrayTypes.java` | 对象分配 + 保守追踪式 GC |
| 后端 | `BackendC.java` `BackendCpp.java` `BackendGo.java` `BackendJava.java` | 目标语言代码生成 |

## 关键设计决策

### 为什么数组参数不需要改帧结构

数组在 VM 里就是 handle（long）。参数已占槽位 0..p-1，本来就在 GC 根扫描范围内。
所以 Day1 的改动集中在"类型"和"后端声明"，运行时帧结构零改动。

### 为什么 GC 是保守追踪式

根 = 解释器与编译码各帧的局部变量 + 操作数栈。
保守扫描：数据恰好等于句柄只会多留，不会误释放。
代价是可能有浮动垃圾，但对教学向编译器是合理权衡。

M7 引入对象类型后，GC 需要升级为精确追踪——对象字段会持有引用，
必须按对象类型枚举引用槽位。

### 为什么 JIT 分两层

Tier 0 解释器逐条执行。Tier 1 在函数被调用 1000 次后编译成扁平化表示。
OSR 处理"函数只调用一次但循环回边很多"的情况。
Tier 2（JVMCI 机器码）暂缓，先测量 GraalVM 宿主下 Tier1 的实际收益。

## 扩展点

想参与的话，看 `CONTRIBUTING.md`。当前最活跃的扩展点是 M7：
字符串类型、对象类型、Polyglot 完整化。