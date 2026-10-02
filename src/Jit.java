import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ===== Jit：分层 JIT（HotSpot 思路）=====
// Tier 0：Vm 结构化解释器
// Tier 1（本文件）：函数调用计数超阈值后，把结构化字节码在运行时
//   扁平化为线性指令（变量→固定槽位，控制流→JMP/JZ），由数组+PC
//   循环执行 —— 等价 C1：无寄存器分配的快速编译码。
// 不做 OSR：触发编译时当前激活继续解释，下一次调用进入编译码。
// Tier 2（未实现）：机器码级优化，需 GraalVM JVMCI。
public class Jit {

    public static final int COMPILE_THRESHOLD = 1000; // 调用次数阈值

    // 扁平化指令
    public enum JitOp {
        PUSH, LOAD, STORE,
        IADD, ISUB, IMUL, IDIV, INEG,
        IEQ, INE, ILT, IGT, ILE, IGE,
        JMP, JZ, PRINT, POP, ANEW, AGET, ASET, ALEN, CALL, RET
    }

    // 编译后的函数：并行数组
    public static class CompiledFunction {
        final String name;
        final int paramCount, slotCount;
        final int[] op, arg;
        final String[] ref;
        final long[] imm;
        final Map<String, Integer> slotIndex;       // 变量名 -> 槽位
        final Map<WhileNode, Integer> loopHeader;   // while 节点 -> 条件处 pc（OSR 入口）
        CompiledFunction(String name, int paramCount, int slotCount,
                         List<Integer> opL, List<Integer> argL,
                         List<String> refL, List<Long> immL,
                         Map<String, Integer> slotIndex,
                         Map<WhileNode, Integer> loopHeader) {
            this.name = name;
            this.paramCount = paramCount;
            this.slotCount = slotCount;
            this.op = opL.stream().mapToInt(Integer::intValue).toArray();
            this.arg = argL.stream().mapToInt(Integer::intValue).toArray();
            this.ref = refL.toArray(new String[0]);
            this.imm = immL.stream().mapToLong(Long::longValue).toArray();
            this.slotIndex = slotIndex;
            this.loopHeader = loopHeader;
        }
    }

    // 缓存 values()：枚举 values() 每次调用都克隆数组，热循环里是巨大开销
    static final JitOp[] ALL = JitOp.values();

    final Module module;
    final boolean enabled;
    final Map<String, Long> invokeCount = new LinkedHashMap<>();
    final Map<String, CompiledFunction> compiled = new LinkedHashMap<>();

    public Jit(Module module, boolean enabled) {
        this.module = module;
        this.enabled = enabled;
    }

    public boolean isCompiled(String name) { return compiled.containsKey(name); }

    // 调用计数 +1，返回最新值
    long bump(String name) {
        long v = invokeCount.getOrDefault(name, 0L) + 1;
        invokeCount.put(name, v);
        return v;
    }

    // 阈值触发时编译并返回，否则 null
    CompiledFunction tier1(String name, long count) {
        if (!enabled || count < COMPILE_THRESHOLD) return null;
        CompiledFunction cf = compiled.get(name);
        if (cf != null) return cf;
        Function fn = module.funcs.get(name);
        if (fn == null) return null;
        cf = Flattener.flatten(fn);
        compiled.put(name, cf);
        return cf;
    }

    CompiledFunction getCompiled(String name) { return compiled.get(name); }

    // OSR 编译：不看调用计数，立即把函数（或合成的 $main）扁平化为 C1 码
    CompiledFunction osrCompile(Function fn) {
        CompiledFunction cf = Flattener.flatten(fn);
        compiled.put(fn.name, cf);
        return cf;
    }

    CompiledFunction osrCompileMain() {
        return osrCompile(new Function("$main", new ArrayList<>(), module.main));
    }

    // Tier 2（机器码 C2）能力探测：需要 GraalVM / JVMCI
    public static String tier2Status() {
        boolean jvmci;
        try {
            Class.forName("jdk.vm.ci.runtime.JVMCI");
            jvmci = true;
        } catch (Throwable t) {
            jvmci = false;
        }
        boolean graal = System.getProperty("java.home", "").toLowerCase().contains("graal");
        if (jvmci && graal) return "Tier 2 可用（检测到 GraalVM JVMCI）";
        return "Tier 2 不可用（未检测到 GraalVM JVMCI；当前为普通 "
             + System.getProperty("java.vm.name", "JVM") + "，仅 Tier 0/1）";
    }

    // 画像（--profile）
    public String profile() {
        StringBuilder sb = new StringBuilder();
        sb.append("==== JIT 画像（阈值 ").append(COMPILE_THRESHOLD).append(" 次调用）====\n");
        for (Map.Entry<String, Long> e : invokeCount.entrySet()) {
            String mark = compiled.containsKey(e.getKey()) ? " [已编译 C1]" : "";
            sb.append("函数 ").append(e.getKey()).append("：调用 ")
              .append(e.getValue()).append(" 次").append(mark).append('\n');
        }
        sb.append(tier2Status()).append('\n');
        return sb.toString();
    }

    // ===== 扁平化器 =====
    static class Flattener {
        final List<Integer> op = new ArrayList<>();
        final List<Integer> arg = new ArrayList<>();
        final List<String> ref = new ArrayList<>();
        final List<Long> imm = new ArrayList<>();
        final Map<String, Integer> slots = new LinkedHashMap<>();
        final Map<WhileNode, Integer> loopHeader = new java.util.IdentityHashMap<>();

        static CompiledFunction flatten(Function fn) {
            Flattener f = new Flattener();
            // 参数占 0..p-1
            for (String p : fn.params) f.slots.put(p, f.slots.size());
            // 收集局部变量（STORE 目标，排除参数）
            collectLocals(fn.body, f.slots);
            f.flattenList(fn.body);
            return new CompiledFunction(fn.name, fn.params.size(), f.slots.size(),
                    f.op, f.arg, f.ref, f.imm,
                    new LinkedHashMap<>(f.slots), f.loopHeader);
        }

        private static void collectLocals(List<BytecodeNode> ns, Map<String, Integer> slots) {
            for (BytecodeNode n : ns) {
                if (n instanceof Instruction) {
                    Instruction i = (Instruction) n;
                    if (i.op == Opcode.STORE && !slots.containsKey(i.str)) {
                        slots.put(i.str, slots.size());
                    }
                } else if (n instanceof IfNode) {
                    IfNode c = (IfNode) n;
                    collectLocals(c.cond, slots); collectLocals(c.then, slots); collectLocals(c.els, slots);
                } else if (n instanceof WhileNode) {
                    WhileNode w = (WhileNode) n;
                    collectLocals(w.cond, slots); collectLocals(w.body, slots);
                }
            }
        }

        private int emit(JitOp o, int a, String r, long v) {
            int idx = op.size();
            op.add(o.ordinal()); arg.add(a); ref.add(r); imm.add(v);
            return idx;
        }

        private int slot(String name) { return slots.getOrDefault(name, 0); }

        private void flattenList(List<BytecodeNode> ns) {
            for (BytecodeNode n : ns) {
                if (n instanceof Instruction) flatInst((Instruction) n);
                else if (n instanceof IfNode) {
                    IfNode c = (IfNode) n;
                    flattenList(c.cond);
                    int jz = emit(JitOp.JZ, 0, null, 0);
                    flattenList(c.then);
                    int jmp = emit(JitOp.JMP, 0, null, 0);
                    int elseAt = op.size();
                    arg.set(jz, elseAt);
                    flattenList(c.els);
                    int endAt = op.size();
                    arg.set(jmp, endAt);
                } else if (n instanceof WhileNode) {
                    WhileNode w = (WhileNode) n;
                    int start = op.size();
                    loopHeader.put(w, start); // OSR 入口：条件处 pc
                    flattenList(w.cond);
                    int jz = emit(JitOp.JZ, 0, null, 0);
                    flattenList(w.body);
                    emit(JitOp.JMP, start, null, 0);
                    int endAt = op.size();
                    arg.set(jz, endAt);
                }
            }
        }

        private void flatInst(Instruction i) {
            switch (i.op) {
                case PUSH:  emit(JitOp.PUSH, 0, null, i.operand); break;
                case LOAD:  emit(JitOp.LOAD, slot(i.str), null, 0); break;
                case STORE: emit(JitOp.STORE, slot(i.str), null, 0); break;
                case IADD: emit(JitOp.IADD, 0, null, 0); break;
                case ISUB: emit(JitOp.ISUB, 0, null, 0); break;
                case IMUL: emit(JitOp.IMUL, 0, null, 0); break;
                case IDIV: emit(JitOp.IDIV, 0, null, 0); break;
                case INEG: emit(JitOp.INEG, 0, null, 0); break;
                case IEQ: emit(JitOp.IEQ, 0, null, 0); break;
                case INE: emit(JitOp.INE, 0, null, 0); break;
                case ILT: emit(JitOp.ILT, 0, null, 0); break;
                case IGT: emit(JitOp.IGT, 0, null, 0); break;
                case ILE: emit(JitOp.ILE, 0, null, 0); break;
                case IGE: emit(JitOp.IGE, 0, null, 0); break;
                case PRINT_INT: emit(JitOp.PRINT, 0, null, 0); break;
                case POP: emit(JitOp.POP, 0, null, 0); break;
                case ANEW: emit(JitOp.ANEW, 0, null, 0); break;
                case AGET: emit(JitOp.AGET, 0, null, 0); break;
                case ASET: emit(JitOp.ASET, 0, null, 0); break;
                case ALEN: emit(JitOp.ALEN, 0, null, 0); break;
                case CALL: emit(JitOp.CALL, (int) i.operand, i.str, 0); break;
                case RET:  emit(JitOp.RET, 0, null, 0); break;
            }
        }
    }

    // ===== 编译码执行器（Tier 1）=====
    // 关键优化：操作数栈用原生 long[]（无装箱、无 Deque 分配），
    // 这是相对解释器（Deque<Long> 每步装箱）的主要提速来源。
    static class FrameC {
        final long[] slots;
        long[] stack;
        int sp;
        FrameC(int slotCount) {
            this.slots = new long[slotCount];
            this.stack = new long[Math.max(64, slotCount * 2)];
        }
        void push(long v) {
            if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
            stack[sp++] = v;
        }
        long pop() { return stack[--sp]; }
    }

    public static long run(CompiledFunction cf, long[] vals, Vm vm) {
        FrameC first = new FrameC(cf.slotCount);
        for (int k = 0; k < vals.length && k < cf.paramCount; k++) first.slots[k] = vals[k];
        return runFrom(cf, first, 0, vm);
    }

    // OSR：从指定编译帧、指定 pc（某 while 条件处）继续执行函数剩余部分
    public static long runFrom(CompiledFunction cf, FrameC first, int startPc, Vm vm) {
        Deque<FrameC> frames = new ArrayDeque<>();
        frames.push(first);

        int pc = startPc;
        while (pc < cf.op.length) {
            JitOp op = ALL[cf.op[pc]];
            FrameC cur = frames.peek();
            switch (op) {
                case PUSH: cur.push(cf.imm[pc]); pc++; break;
                case LOAD: cur.push(cur.slots[cf.arg[pc]]); pc++; break;
                case STORE: cur.slots[cf.arg[pc]] = cur.pop(); pc++; break;
                case IADD: { long b = cur.pop(), a = cur.pop(); cur.push(a + b); pc++; break; }
                case ISUB: { long b = cur.pop(), a = cur.pop(); cur.push(a - b); pc++; break; }
                case IMUL: { long b = cur.pop(), a = cur.pop(); cur.push(a * b); pc++; break; }
                case IDIV: { long b = cur.pop(), a = cur.pop(); cur.push(a / b); pc++; break; }
                case INEG: cur.push(-cur.pop()); pc++; break;
                case IEQ: { long b = cur.pop(), a = cur.pop(); cur.push(a == b ? 1L : 0L); pc++; break; }
                case INE: { long b = cur.pop(), a = cur.pop(); cur.push(a != b ? 1L : 0L); pc++; break; }
                case ILT: { long b = cur.pop(), a = cur.pop(); cur.push(a < b ? 1L : 0L); pc++; break; }
                case IGT: { long b = cur.pop(), a = cur.pop(); cur.push(a > b ? 1L : 0L); pc++; break; }
                case ILE: { long b = cur.pop(), a = cur.pop(); cur.push(a <= b ? 1L : 0L); pc++; break; }
                case IGE: { long b = cur.pop(), a = cur.pop(); cur.push(a >= b ? 1L : 0L); pc++; break; }
                case JMP: pc = cf.arg[pc]; break;
                case JZ: { long v = cur.pop(); pc = (v == 0) ? cf.arg[pc] : pc + 1; break; }
                case PRINT: System.out.println(cur.pop()); pc++; break;
                case POP: cur.pop(); pc++; break;
                case ANEW: {
                    long size = cur.pop();
                    long h = vm.rtNew((int) size, jitRoots(frames));
                    cur.push(h);
                    pc++;
                    break;
                }
                case AGET: {
                    long idx = cur.pop(), h = cur.pop();
                    cur.push(vm.rtGet(h, idx));
                    pc++;
                    break;
                }
                case ASET: {
                    long v = cur.pop(), idx = cur.pop(), h = cur.pop();
                    vm.rtSet(h, idx, v);
                    pc++;
                    break;
                }
                case ALEN: {
                    long h = cur.pop();
                    cur.push(vm.rtLen(h));
                    pc++;
                    break;
                }
                case RET: return cur.pop();
                case CALL: {
                    int argc = cf.arg[pc];
                    long[] callVals = new long[argc];
                    for (int k = argc - 1; k >= 0; k--) callVals[k] = cur.pop();
                    long r = vm.callByName(cf.ref[pc], callVals); // 跨层：统一入口
                    cur.push(r);
                    pc++;
                    break;
                }
            }
        }
        return 0;
    }

    // 编译帧的 GC 根：每帧槽位 + 操作数栈（精确到 sp）
    private static List<long[]> jitRoots(Deque<FrameC> frames) {
        List<long[]> b = new ArrayList<>();
        for (FrameC fc : frames) {
            b.add(fc.slots);
            long[] st = new long[fc.sp];
            System.arraycopy(fc.stack, 0, st, 0, fc.sp);
            b.add(st);
        }
        return b;
    }
}
