import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

// UniVM 主入口
//   源码 -> Lexer -> Parser -> AST -> Codegen -> Module
//                                         |-> Vm 解释器(Tier 0) + Jit(Tier 1)
//                                         |-> BackendC   -> gcc   -> exe
//                                         |-> BackendCpp -> g++   -> exe（.h + .cpp）
//                                         |-> BackendGo  -> go    -> exe
//                                         |-> BackendJava-> javac -> jar
//
// 用法:
//   java Main <input.uni> [--run] [--ir] [--profile] [--no-jit]
//                         [--c out] [--cpp base] [--go out.exe] [--jar out.jar]
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("UniVM —— 通用字节码多目标编译器（JVM 架构）");
            System.out.println("用法: java Main <input.uni> [--run] [--ir] [--profile] [--no-jit]");
            System.out.println("                          [--c out] [--cpp base] [--go out.exe] [--jar out.jar]");
            return;
        }

        String source = new String(Files.readAllBytes(Paths.get(args[0])));

        // 前端：按扩展名选择语言（M5：多前端 → 同一 Module）
        boolean isPython = args[0].toLowerCase().endsWith(".py");
        Program prog;
        if (isPython) {
            List<Token> pyTokens = new LexerPy(source).scanAllTokens();
            prog = new ParserPy(pyTokens).parseProgram();
        } else {
            List<Token> tokens = new Lexer(source).scanAllTokens();
            prog = new Parser(tokens).parseProgram();
        }
        Module module = Codegen.emit(prog);

        // M6：静态类型检查（--no-check 关闭）
        boolean noCheck = false;
        for (String a : args) if (a.equals("--no-check")) noCheck = true;
        if (!noCheck) TypeChecker.check(prog);

        boolean jitEnabled = true;
        boolean wantProfile = false;
        for (String a : args) {
            if (a.equals("--no-jit")) jitEnabled = false;
            if (a.equals("--profile")) wantProfile = true;
        }

        Vm vm = Vm.create(module, jitEnabled);
        boolean didSomething = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--run":
                    vm.runMain();
                    didSomething = true;
                    break;

                case "--ir":
                    System.out.println("==== UniVM 字节码 ====");
                    System.out.print(Codegen.pretty(module));
                    didSomething = true;
                    break;

                case "--profile":
                case "--no-jit":
                    break; // 仅作开关，不单独动作

                case "--c": {
                    String out = args[++i];
                    Files.write(Paths.get(out + ".c"), BackendC.emit(module).getBytes());
                    System.out.println("已生成 C 源码: " + out + ".c");
                    try {
                        exec("gcc", "-O2", "-o", out, out + ".c");
                        System.out.println("已编译为 exe: " + out);
                    } catch (Exception e) {
                        System.out.println("[警告] gcc 不可用，仅生成 .c。安装 MinGW 后: gcc -O2 -o " + out + " " + out + ".c");
                    }
                    didSomething = true;
                    break;
                }

                case "--cpp": {
                    String base = args[++i];
                    String headerName = Paths.get(base).getFileName().toString();
                    BackendCpp.Output out = BackendCpp.emit(module, headerName);
                    Files.write(Paths.get(base + ".h"), out.header.getBytes());
                    Files.write(Paths.get(base + ".cpp"), out.source.getBytes());
                    System.out.println("已生成 C++: " + base + ".h / " + base + ".cpp");
                    try {
                        exec("g++", "-O2", "-std=c++17", "-o", base, base + ".cpp");
                        System.out.println("已编译为 exe: " + base);
                    } catch (Exception e) {
                        System.out.println("[警告] g++ 不可用，仅生成 .h/.cpp。安装 MinGW 后: g++ -O2 -std=c++17 -o " + base + " " + base + ".cpp");
                    }
                    didSomething = true;
                    break;
                }

                case "--go": {
                    String out = args[++i];
                    Files.write(Paths.get("gen_main.go"), BackendGo.emit(module).getBytes());
                    exec("go", "build", "-o", out, "gen_main.go");
                    System.out.println("已编译为 exe: " + out);
                    didSomething = true;
                    break;
                }

                case "--jar": {
                    String out = args[++i];
                    Files.write(Paths.get("Gen.java"), BackendJava.emit(module).getBytes());
                    exec("javac", "Gen.java");
                    exec("jar", "cfe", out, "Gen", "Gen.class");
                    System.out.println("已打包为 jar: " + out + "  (运行: java -jar " + out + ")");
                    didSomething = true;
                    break;
                }

                default:
                    System.out.println("未知参数: " + args[i]);
            }
        }

        // 默认：打印字节码 + 执行
        if (!didSomething) {
            System.out.println("==== UniVM 字节码 ====");
            System.out.print(Codegen.pretty(module));
            System.out.println("==== 执行结果（JIT " + (jitEnabled ? "开" : "关") + "）====");
            vm.runMain();
        }

        if (wantProfile) { System.out.print(vm.jit().profile()); System.out.print(vm.heapStats()); }
    }

    private static void exec(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("命令执行失败 (exit=" + code + "): " + String.join(" ", cmd));
        }
    }
}
