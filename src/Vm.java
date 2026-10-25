import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

// Vm：UniVM 解释器（Tier 0 / JVM interpreter C0）
// M4：持有 Jit；所有函数调用（解释器与编译码）统一走 callByName，
// 由调用计数决定进入解释帧还是 Tier 1 编译码。
public class Vm {

    // 解释帧（局部变量表 + 操作数栈）
    // 值统一用 Object 承载：整数/数组句柄为 Long，字符串为 String
    static class Frame {
        final Map<String, Object> locals = new HashMap<>();
        final Deque<Object> stack = new ArrayDeque<>();
        Function fn; // 该帧所属函数；main 帧为 null
        final Map<WhileNode, Integer> loopHits = new java.util.IdentityHashMap<>();
    }

    // return 信号
    private static class ReturnSignal extends RuntimeException {
        final long value;
        ReturnSignal(long value) { this.value = value; }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    // break / continue 控制信号：由最内层 WhileNode 捕获
    private static class BreakSignal extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
    private static class ContinueSignal extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    // OSR 已在 main 帧完成剩余执行
    private static class MainDoneSignal extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    // CLI 使用控制台；GUI 注入 JOptionPane 弹窗实现。
    public interface InputProvider {
        String input(String prompt);
    }

    // GUI 的停止按钮通过该令牌通知正在执行的 VM。
    // 令牌只表示“请求停止”，同一个令牌可以跨 Vm 实例重复使用。
    public static final class StopToken {
        private volatile boolean stopped;

        public void stop() { stopped = true; }
        public boolean isStopped() { return stopped; }
        public void reset() { stopped = false; }
    }

    public static class VmStoppedException extends RuntimeException {
        public VmStoppedException() { super("执行已停止"); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    private static final int OSR_THRESHOLD = 10_000; // while 回边热点阈值

    final Module module;
    final Jit jit;
    final Heap heap = new Heap();
    private static final int GC_INTERVAL = 100; // 每 100 次分配触发一次 GC
    private final Deque<Frame> callStack = new ArrayDeque<>();
    private final InputProvider inputProvider;
    private final StopToken stopToken;
    private BufferedReader consoleIn;

    private Vm(Module module, boolean jitEnabled, InputProvider inputProvider, StopToken stopToken) {
        this.module = module;
        this.inputProvider = inputProvider;
        this.stopToken = stopToken;
        // JIT 的槽位/操作数栈是原生 long[]，无法承载 String；
        // 含字符串操作或标准库 SYS_CALL 的模块统一走解释器，
        // 整数/数组模块保持原 JIT 行为。
        this.jit = new Jit(module, jitEnabled
                && module.stringPool.isEmpty()
                && !module.usesStrings
                && !module.hasAnySysCall());
    }

    public static Vm create(Module module, boolean jitEnabled) {
        return new Vm(module, jitEnabled, null, null);
    }

    public static Vm create(Module module, boolean jitEnabled, InputProvider inputProvider) {
        return new Vm(module, jitEnabled, inputProvider, null);
    }

    public static Vm create(Module module, boolean jitEnabled, InputProvider inputProvider, StopToken stopToken) {
        return new Vm(module, jitEnabled, inputProvider, stopToken);
    }

    public Jit jit() { return jit; }

    // 线程中断和显式 StopToken 都能终止死循环；JIT 与解释器共用。
    public void forceStop() {
        if (stopToken != null) stopToken.stop();
    }

    void checkpoint() {
        if ((stopToken != null && stopToken.isStopped()) || Thread.currentThread().isInterrupted()) {
            throw new VmStoppedException();
        }
    }

    public void runMain() {
        checkpoint();
        Frame mf = new Frame();
        callStack.push(mf);
        try {
            exec(module.main);
        } catch (MainDoneSignal d) {
            callStack.pop(); // OSR 已跑完 main
        }
    }

    public static void run(Module module) {
        Vm vm = create(module, true);
        vm.runMain();
    }

    private Frame cur() { return callStack.peek(); }

    private static long asLong(Object v) {
        if (v instanceof Long) return (Long) v;
        throw new RuntimeException("运行时类型错误：期望整数，实际 "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private static String asString(Object v) {
        if (v instanceof String) return (String) v;
        throw new RuntimeException("运行时类型错误：期望字符串，实际 "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private void exec(List<BytecodeNode> ns) {
        for (BytecodeNode n : ns) {
            checkpoint();
            if (n instanceof Instruction) {
                inst((Instruction) n);
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                exec(f.cond);
                long c = asLong(cur().stack.pop());
                exec(c != 0 ? f.then : f.els);
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                Frame fr = cur();
                while (true) {
                    checkpoint();
                    exec(w.cond);
                    long c = asLong(cur().stack.pop());
                    if (c == 0) break;
                    try {
                        exec(w.body);
                    } catch (ContinueSignal cont) {
                        // continue：跳过本轮剩余语句，直接进入条件判断
                    } catch (BreakSignal brk) {
                        break;
                    }
                    // 回边计数 → OSR
                    int hits = fr.loopHits.merge(w, 1, Integer::sum);
                    if (jit.enabled && hits >= OSR_THRESHOLD) doOsr(fr, w);
                }
            }
        }
    }

    private void inst(Instruction ins) {
        Frame f = cur();
        switch (ins.op) {
            case PUSH:   f.stack.push(ins.operand); break;
            case SCONST: f.stack.push(module.stringPool.get((int) ins.operand)); break;
            case LOAD:   f.stack.push(f.locals.getOrDefault(ins.str, 0L)); break;
            case STORE:  f.locals.put(ins.str, f.stack.pop()); break;
            case IADD: { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a + b); break; }
            case ISUB: { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a - b); break; }
            case IMUL: { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a * b); break; }
            case IDIV: { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a / b); break; }
            case INEG: f.stack.push(-asLong(f.stack.pop())); break;
            case IEQ:  { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a == b ? 1L : 0L); break; }
            case INE:  { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a != b ? 1L : 0L); break; }
            case ILT:  { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a < b ? 1L : 0L); break; }
            case IGT:  { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a > b ? 1L : 0L); break; }
            case ILE:  { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a <= b ? 1L : 0L); break; }
            case IGE:  { long b = asLong(f.stack.pop()), a = asLong(f.stack.pop()); f.stack.push(a >= b ? 1L : 0L); break; }
            case PRINT_INT: System.out.println(asLong(f.stack.pop())); break;
            case PRINT_STR: System.out.println(asString(f.stack.pop())); break;
            case SLEN: {
                String s = asString(f.stack.pop());
                f.stack.push((long) s.length());
                break;
            }
            case SCONCAT: {
                String b = asString(f.stack.pop());
                String a = asString(f.stack.pop());
                f.stack.push(a + b);
                break;
            }
            case TOSTR:
                f.stack.push(Long.toString(asLong(f.stack.pop())));
                break;
            case BREAK: throw new BreakSignal();
            case CONTINUE: throw new ContinueSignal();
            case POP: f.stack.pop(); break;
            case ANEW: {
                long size = asLong(f.stack.pop());
                f.stack.push(rtNew((int) size, null));
                break;
            }
            case AGET: {
                long idx = asLong(f.stack.pop()), h = asLong(f.stack.pop());
                f.stack.push(rtGet(h, idx));
                break;
            }
            case ASET: {
                long v = asLong(f.stack.pop()), idx = asLong(f.stack.pop()), h = asLong(f.stack.pop());
                rtSet(h, idx, v);
                break;
            }
            case ALEN: {
                long h = asLong(f.stack.pop());
                f.stack.push(rtLen(h));
                break;
            }
            case RET: throw new ReturnSignal(asLong(f.stack.pop()));
            case CALL: {
                int argc = (int) ins.operand;
                long[] vals = new long[argc];
                for (int k = argc - 1; k >= 0; k--) vals[k] = asLong(f.stack.pop());
                f.stack.push(callByName(ins.str, vals));
                break;
            }
            case SYS_CALL: sysCall(ins, f); break;
        }
    }

    // 标准库内置函数。VM 的字符串本来就是 Java String（不经过 Heap 句柄），
    // 因此 readFile 直接压入 String，writeFile 直接消费 String。
    private void sysCall(Instruction ins, Frame f) {
        checkpoint();
        int argc = (int) ins.operand;
        switch (ins.str) {
            case "time":
                if (argc != 0) throw new RuntimeException("time 参数个数错误: " + argc);
                f.stack.push(System.currentTimeMillis());
                break;

            case "readFile": {
                if (argc != 1) throw new RuntimeException("readFile 参数个数错误: " + argc);
                String path = asString(f.stack.pop());
                try {
                    String content = java.nio.file.Files.readString(java.nio.file.Path.of(path));
                    f.stack.push(content);
                } catch (Exception ex) {
                    // 统一约定：读取失败返回空字符串
                    f.stack.push("");
                }
                break;
            }

            case "writeFile": {
                if (argc != 2) throw new RuntimeException("writeFile 参数个数错误: " + argc);
                String content = asString(f.stack.pop());
                String path = asString(f.stack.pop());
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(path), content);
                    f.stack.push(0L);
                } catch (Exception ex) {
                    f.stack.push(-1L);
                }
                break;
            }

            case "input": {
                if (argc != 1) throw new RuntimeException("input 参数个数错误: " + argc);
                String prompt = asString(f.stack.pop());
                f.stack.push(readInput(prompt));
                break;
            }

            case "toInt": {
                if (argc != 1) throw new RuntimeException("toInt 参数个数错误: " + argc);
                f.stack.push(parseLongSafe(asString(f.stack.pop())));
                break;
            }

            case "random": {
                if (argc != 2) throw new RuntimeException("random 参数个数错误: " + argc);
                long max = asLong(f.stack.pop());
                long min = asLong(f.stack.pop());
                f.stack.push(randomInclusive(min, max));
                break;
            }

            default:
                throw new RuntimeException("未知标准库函数: " + ins.str);
        }
    }

    // 控制台输入：显式 UTF-8，兼容中文；GUI 通过 InputProvider 注入弹窗。
    private String readInput(String prompt) {
        if (inputProvider != null) {
            String value = inputProvider.input(prompt);
            return value == null ? "" : value;
        }
        if (!prompt.isEmpty()) {
            System.out.print(prompt);
            System.out.flush();
        }
        try {
            if (consoleIn == null) {
                consoleIn = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            }
            String line = consoleIn.readLine();
            return line == null ? "" : line;
        } catch (Exception ex) {
            return "";
        }
    }

    // 与各后端约定一致：去除首尾空白，非法输入返回 0。
    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // 闭区间随机数；min/max 顺序可颠倒，并处理极端溢出。
    private static long randomInclusive(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        if (lo == hi) return lo;
        long span = hi - lo + 1;
        if (span > 0) return lo + ThreadLocalRandom.current().nextLong(span);
        return ThreadLocalRandom.current().nextLong();
    }

    // 统一调用入口（解释器 / 编译码共用）：计数 → 分层
    public long callByName(String name, long[] vals) {
        checkpoint();
        Function fn = module.funcs.get(name);
        if (fn == null) throw new RuntimeException("调用了未定义的函数: " + name);
        if (fn.params.size() != vals.length) {
            throw new RuntimeException("函数 " + name + " 参数个数不匹配：期望 " + fn.params.size() + "，实际 " + vals.length);
        }

        long count = jit.bump(name);
        Jit.CompiledFunction cf = jit.tier1(name, count);
        if (cf != null) return Jit.run(cf, vals, this); // Tier 1

        // Tier 0：解释帧
        Frame nf = new Frame();
        nf.fn = fn;
        for (int k = 0; k < vals.length; k++) nf.locals.put(fn.params.get(k), vals[k]);
        callStack.push(nf);
        long result = 0;
        try {
            exec(fn.body);
        } catch (ReturnSignal r) {
            result = r.value;
        } finally {
            callStack.pop();
        }
        return result;
    }

    // OSR：把当前解释帧状态迁移到编译码的循环头，执行函数/main 剩余部分
    private void doOsr(Frame fr, WhileNode w) {
        Jit.CompiledFunction cf = (fr.fn != null)
                ? jit.osrCompile(fr.fn)
                : jit.osrCompileMain();

        Jit.FrameC fc = new Jit.FrameC(cf.slotCount);
        for (Map.Entry<String, Object> e : fr.locals.entrySet()) {
            Integer s = cf.slotIndex.get(e.getKey());
            if (s != null && e.getValue() instanceof Long) fc.slots[s] = (Long) e.getValue();
        }
        Integer header = cf.loopHeader.get(w);
        if (header == null) header = 0;

        long v = Jit.runFrom(cf, fc, header, this);
        if (fr.fn != null) throw new ReturnSignal(v); // 函数：编译码已到 RET
        throw new MainDoneSignal();                  // main：剩余已执行完
    }

    // ===== 数组运行时（解释器与 JIT 共用；集中处理 GC）=====
    public long rtNew(int size, List<long[]> extraRoots) {
        if (heap.totalAlloc > 0 && heap.totalAlloc % GC_INTERVAL == 0) {
            heap.collect(gatherRoots(extraRoots));
        }
        return heap.alloc(size);
    }

    public long rtGet(long h, long idx) {
        Heap.ArrayObj o = heap.get(h);
        if (o == null) throw new RuntimeException("非法数组引用: " + h);
        if (idx < 0 || idx >= o.data.length) throw new RuntimeException("数组下标越界: " + idx + "，长度 " + o.data.length);
        return o.data[(int) idx];
    }

    public void rtSet(long h, long idx, long v) {
        Heap.ArrayObj o = heap.get(h);
        if (o == null) throw new RuntimeException("非法数组引用: " + h);
        if (idx < 0 || idx >= o.data.length) throw new RuntimeException("数组下标越界: " + idx + "，长度 " + o.data.length);
        o.data[(int) idx] = v;
    }

    public long rtLen(long h) {
        Heap.ArrayObj o = heap.get(h);
        if (o == null) throw new RuntimeException("非法数组引用: " + h);
        return o.data.length;
    }

    public String heapStats() {
        return "==== 堆 / GC ====\n"
             + "累计分配 " + heap.totalAlloc + "，GC 次数 " + heap.gcRuns
             + "，上次回收 " + heap.lastFreed + "，当前存活 " + heap.lastLive + "\n";
    }

    // 汇总根：解释器各帧的局部变量 + 操作数栈，外加 JIT 帧（extraRoots）
    private List<long[]> gatherRoots(List<long[]> extraRoots) {
        List<long[]> blocks = new java.util.ArrayList<>();
        for (Frame fr : callStack) {
            blocks.add(longsOf(fr.locals.values()));
            blocks.add(longsOf(fr.stack));
        }
        if (extraRoots != null) blocks.addAll(extraRoots);
        return blocks;
    }

    // GC 只跟踪 Long 型数组句柄；String 等对象不是堆句柄，直接忽略
    private static long[] longsOf(java.util.Collection<Object> values) {
        java.util.ArrayList<Long> tmp = new java.util.ArrayList<>();
        for (Object v : values) if (v instanceof Long) tmp.add((Long) v);
        long[] r = new long[tmp.size()];
        for (int i = 0; i < r.length; i++) r[i] = tmp.get(i);
        return r;
    }
}
