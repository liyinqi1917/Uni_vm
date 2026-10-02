import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// BackendJava：Module -> Java 源码（javac 编译、jar 打包，对接 JVM 生态）
// 数组映射为 long[]（由 JVM 负责 GC）；局部变量/临时变量按 int 或 long[] 声明。
public class BackendJava {

    public static String emit(Module m) {
        StringBuilder sb = new StringBuilder();
        sb.append("public class Gen {\n");

        for (Function fn : m.funcs.values()) {
            sb.append("    static long f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append("long p_").append(fn.params.get(k));
            }
            sb.append(") {\n");
            Emitter e = new Emitter(sb);
            e.declareLocals(fn.body, fn.params, "        ");
            e.block(fn.body, "        ");
            if (!alwaysReturns(fn.body)) sb.append("        return 0L;\n    }\n\n");
            else sb.append("    }\n\n");
        }

        sb.append("    public static void main(String[] args) {\n");
        Emitter mainE = new Emitter(sb);
        mainE.declareLocals(m.main, new ArrayList<>(), "        ");
        mainE.block(m.main, "        ");
        sb.append("    }\n}\n");
        return sb.toString();
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

        void declareLocals(List<BytecodeNode> body, List<String> params, String ind) {
            paramSet.addAll(params);
            arrLocals = ArrayTypes.inferArrays(body, params);
            Set<String> names = new LinkedHashSet<>();
            storeNames(body, names);
            for (String p : params) names.remove(p);
            for (String v : names) {
                if (arrLocals.contains(v)) sb.append(ind).append("long[] v_").append(v).append(";\n");
                else sb.append(ind).append("long v_").append(v).append(" = 0L;\n");
            }
        }

        private int newTemp(boolean isArr) {
            int t = tempCounter++;
            if (isArr) tempIsArr.add(t);
            return t;
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
                        sb.append(ind).append("long t").append(t)
                          .append(" = ").append(i.operand).append("L;\n");
                        stack.push(t);
                        break;
                    }
                    case LOAD: {
                        boolean arr = arrLocals.contains(i.str) && !paramSet.contains(i.str);
                        int t = newTemp(arr);
                        sb.append(ind).append(arr ? "long[] t" : "long t").append(t)
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
                        int t = newTemp(false);
                        sb.append(ind).append("long t").append(t).append(" = (t").append(a)
                          .append(" ").append(symbol(i.op)).append(" t").append(b)
                          .append(") ? 1L : 0L;\n");
                        stack.push(t);
                        break;
                    }
                    case PRINT_INT: {
                        int a = stack.pop();
                        sb.append(ind).append("System.out.println(t").append(a).append(");\n");
                        break;
                    }
                    case POP: { stack.pop(); break; }
                    case ANEW: {
                        int s = stack.pop();
                        int t = newTemp(true);
                        sb.append(ind).append("long[] t").append(t)
                          .append(" = new long[(int) t").append(s).append("];\n");
                        stack.push(t);
                        break;
                    }
                    case AGET: {
                        int idx = stack.pop(), arr = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("long t").append(t)
                          .append(" = t").append(arr).append("[(int) t").append(idx).append("];\n");
                        stack.push(t);
                        break;
                    }
                    case ASET: {
                        int val = stack.pop(), idx = stack.pop(), arr = stack.pop();
                        sb.append(ind).append("t").append(arr).append("[(int) t").append(idx)
                          .append("] = t").append(val).append(";\n");
                        break;
                    }
                    case ALEN: {
                        int arr = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("long t").append(t)
                          .append(" = t").append(arr).append(".length;\n");
                        stack.push(t);
                        break;
                    }
                    case CALL: {
                        int argc = (int) i.operand;
                        String args = callArgs(argc);
                        int t = newTemp(false);
                        sb.append(ind).append("long t").append(t)
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
                sb.append(ind).append("if (t").append(c).append(" != 0) {\n");
                block(f.then, ind + "    ");
                sb.append(ind).append("} else {\n");
                block(f.els, ind + "    ");
                sb.append(ind).append("}\n");
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                sb.append(ind).append("while (true) {\n");
                block(w.cond, ind + "    ");
                int c = stack.pop();
                sb.append(ind).append("    if (t").append(c).append(" == 0) break;\n");
                block(w.body, ind + "    ");
                sb.append(ind).append("}\n");
            }
        }
    }
}
