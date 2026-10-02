import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// BackendC：Module -> C 源码（gcc/clang 编译为原生 exe）
// 数组映射为 long long*：calloc(sz+1)，长度存于 block[0]，句柄 = block+1，
// 故 ALEN 读 h[-1]。注意：C 没有运行时 GC，生成代码不回收（堆内存随进程释放）。
public class BackendC {

    public static String emit(Module m) {
        boolean needStdio = anyPrint(m);
        boolean needAlloc = anyNode(m, Opcode.ANEW);
        StringBuilder sb = new StringBuilder();
        if (needStdio) sb.append("#include <stdio.h>\n");
        if (needAlloc) sb.append("#include <stdlib.h>\n");
        if (needStdio || needAlloc) sb.append('\n');

        // 原型
        for (Function fn : m.funcs.values()) {
            sb.append("long long f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append("long long");
            }
            sb.append(");\n");
        }
        sb.append('\n');

        // 定义
        for (Function fn : m.funcs.values()) {
            sb.append("long long f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append("long long p_").append(fn.params.get(k));
            }
            sb.append(") {\n");
            Emitter e = new Emitter(sb);
            e.declareLocals(fn.body, fn.params, "    ");
            e.block(fn.body, "    ");
            if (!alwaysReturns(fn.body)) sb.append("    return 0;\n}\n\n");
            else sb.append("}\n\n");
        }

        sb.append("int main(void) {\n");
        Emitter mainE = new Emitter(sb);
        mainE.declareLocals(m.main, new ArrayList<>(), "    ");
        mainE.block(m.main, "    ");
        sb.append("    return 0;\n}\n");
        return sb.toString();
    }

    private static boolean anyPrint(Module m) {
        for (Function fn : m.funcs.values()) if (hasNode(fn.body, Opcode.PRINT_INT)) return true;
        return hasNode(m.main, Opcode.PRINT_INT);
    }

    private static boolean anyNode(Module m, Opcode op) {
        for (Function fn : m.funcs.values()) if (hasNode(fn.body, op)) return true;
        return hasNode(m.main, op);
    }

    private static boolean hasNode(List<BytecodeNode> ns, Opcode want) {
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction && ((Instruction) n).op == want) return true;
            if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                if (hasNode(f.cond, want) || hasNode(f.then, want) || hasNode(f.els, want)) return true;
            }
            if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                if (hasNode(w.cond, want) || hasNode(w.body, want)) return true;
            }
        }
        return false;
    }

    private static boolean alwaysReturns(List<BytecodeNode> ns) {
        if (ns.isEmpty()) return false;
        BytecodeNode last = ns.get(ns.size() - 1);
        if (last instanceof Instruction) return ((Instruction) last).op == Opcode.RET;
        if (last instanceof IfNode) {
            IfNode f = (IfNode) last;
            return alwaysReturns(f.then) && alwaysReturns(f.els);
        }
        return false;
    }

    private static void storeNames(List<BytecodeNode> ns, Set<String> out) {
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction) {
                Instruction i = (Instruction) n;
                if (i.op == Opcode.STORE) out.add(i.str);
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                storeNames(f.cond, out); storeNames(f.then, out); storeNames(f.els, out);
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                storeNames(w.cond, out); storeNames(w.body, out);
            }
        }
    }

    private static String symbol(Opcode op) {
        switch (op) {
            case IADD: return "+"; case ISUB: return "-";
            case IMUL: return "*"; case IDIV: return "/";
            case IEQ:  return "=="; case INE: return "!=";
            case ILT:  return "<";  case IGT: return ">";
            case ILE:  return "<="; case IGE: return ">=";
            default: throw new RuntimeException("非运算指令 " + op);
        }
    }

    private static class Emitter {
        final StringBuilder sb;
        int tempCounter = 0;
        final Deque<Integer> stack = new ArrayDeque<>();
        final Set<String> paramSet = new java.util.HashSet<>();
        final Set<Integer> tempIsArr = new java.util.HashSet<>();
        Set<String> arrLocals = new java.util.HashSet<>();

        Emitter(StringBuilder sb) { this.sb = sb; }

        String ref(String name) { return (paramSet.contains(name) ? "p_" : "v_") + name; }

        private int newTemp(boolean isArr) {
            int t = tempCounter++;
            if (isArr) tempIsArr.add(t);
            return t;
        }

        void declareLocals(List<BytecodeNode> body, List<String> params, String ind) {
            paramSet.addAll(params);
            arrLocals = ArrayTypes.inferArrays(body, params);
            Set<String> names = new LinkedHashSet<>();
            storeNames(body, names);
            for (String p : params) names.remove(p);
            for (String v : names) {
                if (arrLocals.contains(v)) sb.append(ind).append("long long *v_").append(v).append(" = 0;\n");
                else sb.append(ind).append("long long v_").append(v).append(" = 0;\n");
            }
        }

        void block(List<BytecodeNode> ns, String ind) { for (BytecodeNode n : ns) node(n, ind); }

        private String callArgs(int argc) {
            int[] argT = new int[argc];
            for (int k = argc - 1; k >= 0; k--) argT[k] = stack.pop();
            StringBuilder s = new StringBuilder();
            for (int k = 0; k < argc; k++) {
                if (k > 0) s.append(", ");
                s.append("t").append(argT[k]);
            }
            return s.toString();
        }

        void node(BytecodeNode n, String ind) {
            if (n instanceof Instruction) {
                Instruction i = (Instruction) n;
                switch (i.op) {
                    case PUSH: {
                        int t = newTemp(false);
                        sb.append(ind).append("long long t").append(t)
                          .append(" = ").append(i.operand).append("LL;\n");
                        stack.push(t);
                        break;
                    }
                    case LOAD: {
                        boolean arr = arrLocals.contains(i.str) && !paramSet.contains(i.str);
                        int t = newTemp(arr);
                        sb.append(ind).append(arr ? "long long *t" : "long long t").append(t)
                          .append(" = ").append(ref(i.str)).append(";\n");
                        stack.push(t);
                        break;
                    }
                    case STORE: {
                        int a = stack.pop();
                        sb.append(ind).append(ref(i.str))
                          .append(" = t").append(a).append(";\n");
                        break;
                    }
                    case IADD: case ISUB: case IMUL: case IDIV: {
                        int b = stack.pop(), a = stack.pop();
                        sb.append(ind).append("t").append(a).append(" = t").append(a)
                          .append(" ").append(symbol(i.op)).append(" t").append(b).append(";\n");
                        stack.push(a);
                        break;
                    }
                    case INEG: {
                        int a = stack.pop();
                        sb.append(ind).append("t").append(a).append(" = -t").append(a).append(";\n");
                        stack.push(a);
                        break;
                    }
                    case IEQ: case INE: case ILT: case IGT: case ILE: case IGE: {
                        int b = stack.pop(), a = stack.pop();
                        sb.append(ind).append("t").append(a).append(" = (t").append(a)
                          .append(" ").append(symbol(i.op)).append(" t").append(b).append(");\n");
                        stack.push(a);
                        break;
                    }
                    case PRINT_INT: {
                        int a = stack.pop();
                        sb.append(ind).append("printf(\"%lld\\n\", t").append(a).append(");\n");
                        break;
                    }
                    case POP: { stack.pop(); break; }
                    case ANEW: {
                        int s = stack.pop();
                        int base = tempCounter++;
                        sb.append(ind).append("long long *t").append(base)
                          .append(" = (long long*)calloc((size_t)(t").append(s).append(" + 1), sizeof(long long));\n");
                        sb.append(ind).append("t").append(base).append("[0] = t").append(s).append(";\n");
                        int t = newTemp(true);
                        sb.append(ind).append("long long *t").append(t)
                          .append(" = t").append(base).append(" + 1;\n");
                        stack.push(t);
                        break;
                    }
                    case AGET: {
                        int idx = stack.pop(), arr = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("long long t").append(t)
                          .append(" = t").append(arr).append("[t").append(idx).append("];\n");
                        stack.push(t);
                        break;
                    }
                    case ASET: {
                        int val = stack.pop(), idx = stack.pop(), arr = stack.pop();
                        sb.append(ind).append("t").append(arr).append("[t").append(idx)
                          .append("] = t").append(val).append(";\n");
                        break;
                    }
                    case ALEN: {
                        int arr = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("long long t").append(t)
                          .append(" = t").append(arr).append("[-1];\n");
                        stack.push(t);
                        break;
                    }
                    case CALL: {
                        int argc = (int) i.operand;
                        String args = callArgs(argc);
                        int t = newTemp(false);
                        sb.append(ind).append("long long t").append(t)
                          .append(" = f_").append(i.str).append("(").append(args).append(");\n");
                        stack.push(t);
                        break;
                    }
                    case RET: {
                        int a = stack.pop();
                        sb.append(ind).append("return t").append(a).append(";\n");
                        break;
                    }
                }
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                block(f.cond, ind);
                int c = stack.pop();
                sb.append(ind).append("if (t").append(c).append(") {\n");
                block(f.then, ind + "    ");
                sb.append(ind).append("} else {\n");
                block(f.els, ind + "    ");
                sb.append(ind).append("}\n");
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                sb.append(ind).append("while (1) {\n");
                block(w.cond, ind + "    ");
                int c = stack.pop();
                sb.append(ind).append("    if (!(t").append(c).append(")) break;\n");
                block(w.body, ind + "    ");
                sb.append(ind).append("}\n");
            }
        }
    }
}
