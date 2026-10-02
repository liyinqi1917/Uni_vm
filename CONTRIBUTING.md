# Contributing

## 可以接手的任务

### 简单
- 给 `examples/` 下每个 `.uni` 加预期输出注释（见 `examples/` 目录）
- 把 `--c` 后端的 gcc 检测改成更友好的提示（`src/BackendC.java`）

### 中等
- 用真实 `Heap` 替换 `PolyglotDemo.UniHeapStub`（`src/Heap.java` + `polyglot/PolyglotDemo.java`）
- 为 Polyglot 互操作写一个最小测试（`polyglot/` 下加 JUnit）

### 较难
- 把 `Codegen` 输出的 Uni 函数注册为 `Value`，让 guest language 直接调用 `.uni` 文件里的函数
- 字符串迁移到 `TruffleString`（`src/Vm.java`）

## 环境

- 主项目：JDK 17+
- Polyglot 子项目：GraalVM JDK 21 + Maven 3.9+

## 运行

    build.bat
    cd polyglot && mvn compile exec:java
