import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Vm：UniVM 解释器（Tier 0 / JVM interpreter C0）
// M4：持有 Jit；所有函数调用（解释器与编译码）统一走 callByName，
// 由调用计数决定进入解释帧还是 Tier 1 编译码。
public class Vm {

    // 解释帧（局部变量表 + 操作数栈）
    static class Frame {
        final Map<String, Long> locals = new HashMap<>();
        final Deque<Long> stack = new ArrayDeque<>();
        Function fn; // 该帧所属函数；main 帧为 null
        final Map<WhileNode, Integer> loopHits = new java.util.IdentityHashMap<>();
    }

    // return 信号
    private static class ReturnSignal extends RuntimeException {
        final long value;
        ReturnSignal(long value) { this.value = value; }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    // OSR 已在 main 帧完成剩余执行
    private static class MainDoneSignal extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    private static final int OSR_THRESHOLD = 10_000; // while 回边热点阈值

    final Module module;
    final Jit jit;
    final Heap heap = new Heap();
    private static final int GC_INTERVAL = 100; // 每 100 次分配触发一次 GC
    private final Deque<Frame> callStack = new ArrayDeque<>();

    private Vm(Module module, boolean jitEnabled) {
        this.module = module;
        this.jit = new Jit(module, jitEnabled);
    }

    public static Vm create(Module module, boolean jitEnabled) { return new Vm(module, jitEnabled); }

    public Jit jit() { return jit; }

    public void runMain() {
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

    private void exec(List<BytecodeNode> ns) {
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction) {
                inst((Instruction) n);
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                exec(f.cond);
                long c = cur().stack.pop();
                exec(c != 0 ? f.then : f.els);
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                Frame fr = cur();
                while (true) {
                    exec(w.cond);
                    long c = cur().stack.pop();
                    if (c == 0) break;
                    exec(w.body);
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
            case PUSH:  f.stack.push(ins.operand); break;
            case LOAD:  f.stack.push(f.locals.getOrDefault(ins.str, 0L)); break;
            case STORE: f.locals.put(ins.str, f.stack.pop()); break;
            case IADD: { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a + b); break; }
            case ISUB: { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a - b); break; }
            case IMUL: { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a * b); break; }
            case IDIV: { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a / b); break; }
            case INEG: f.stack.push(-f.stack.pop()); break;
            case IEQ:  { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a == b ? 1L : 0L); break; }
            case INE:  { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a != b ? 1L : 0L); break; }
            case ILT:  { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a < b ? 1L : 0L); break; }
            case IGT:  { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a > b ? 1L : 0L); break; }
            case ILE:  { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a <= b ? 1L : 0L); break; }
            case IGE:  { long b = f.stack.pop(), a = f.stack.pop(); f.stack.push(a >= b ? 1L : 0L); break; }
            case PRINT_INT: System.out.println(f.stack.pop()); break;
            case POP: f.stack.pop(); break;
            case ANEW: {
                long size = f.stack.pop();
                f.stack.push(rtNew((int) size, null));
                break;
            }
            case AGET: {
                long idx = f.stack.pop(), h = f.stack.pop();
                f.stack.push(rtGet(h, idx));
                break;
            }
            case ASET: {
                long v = f.stack.pop(), idx = f.stack.pop(), h = f.stack.pop();
                rtSet(h, idx, v);
                break;
            }
            case ALEN: {
                long h = f.stack.pop();
                f.stack.push(rtLen(h));
                break;
            }
            case RET: throw new ReturnSignal(f.stack.pop());
            case CALL: {
                int argc = (int) ins.operand;
                long[] vals = new long[argc];
                for (int k = argc - 1; k >= 0; k--) vals[k] = f.stack.pop();
                f.stack.push(callByName(ins.str, vals));
                break;
            }
        }
    }

    // 统一调用入口（解释器 / 编译码共用）：计数 → 分层
    public long callByName(String name, long[] vals) {
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
        for (Map.Entry<String, Long> e : fr.locals.entrySet()) {
            Integer s = cf.slotIndex.get(e.getKey());
            if (s != null) fc.slots[s] = e.getValue();
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
            long[] lv = new long[fr.locals.size()];
            int k = 0;
            for (long v : fr.locals.values()) lv[k++] = v;
            blocks.add(lv);
            long[] st = new long[fr.stack.size()];
            k = 0;
            for (long v : fr.stack) st[k++] = v;
            blocks.add(st);
        }
        if (extraRoots != null) blocks.addAll(extraRoots);
        return blocks;
    }
}
