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
        boolean needInput = m.hasSysCall("input");
        boolean needToInt = m.hasSysCall("toInt");
        boolean needRandom = m.hasSysCall("random");
        StringBuilder sb = new StringBuilder();
        sb.append("public class Gen {\n");
        if (needInput) {
            sb.append("    private static final java.io.BufferedReader UNI_IN =\n");
            sb.append("        new java.io.BufferedReader(new java.io.InputStreamReader(\n");
            sb.append("            System.in, java.nio.charset.StandardCharsets.UTF_8));\n");
            sb.append("    private static String uni_input(String prompt) {\n");
            sb.append("        if (prompt != null && !prompt.isEmpty()) { System.out.print(prompt); System.out.flush(); }\n");
            sb.append("        try { String s = UNI_IN.readLine(); return s == null ? \"\" : s; }\n");
            sb.append("        catch (Exception e) { return \"\"; }\n");
            sb.append("    }\n");
        }
        if (needToInt) {
            sb.append("    private static long uni_to_int(String s) {\n");
            sb.append("        try { return Long.parseLong(s.trim()); }\n");
            sb.append("        catch (Exception e) { return 0L; }\n");
            sb.append("    }\n");
        }
        if (needRandom) {
            sb.append("    private static long uni_random(long a, long b) {\n");
            sb.append("        long lo = Math.min(a, b), hi = Math.max(a, b);\n");
            sb.append("        if (lo == hi) return lo;\n");
            sb.append("        long span = hi - lo + 1;\n");
            sb.append("        if (span > 0) return lo + java.util.concurrent.ThreadLocalRandom.current().nextLong(span);\n");
            sb.append("        return java.util.concurrent.ThreadLocalRandom.current().nextLong();\n");
            sb.append("    }\n");
        }
        if (needInput || needToInt || needRandom) sb.append('\n');

        for (Function fn : m.funcs.values()) {
            sb.append("    static long f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(fn.paramTypes.get(k) == TypeChecker.T.ARRAY ? "long[] p_" : "long p_")
                  .append(fn.params.get(k));
            }
            sb.append(") {\n");
            Emitter e = new Emitter(sb, m);
            e.declareLocals(fn.body, fn.params, fn.paramTypes, "        ");
            e.block(fn.body, "        ");
            if (!alwaysReturns(fn.body)) sb.append("        return 0L;\n    }\n\n");
            else sb.append("    }\n\n");
        }

        sb.append("    public static void main(String[] args) {\n");
        Emitter mainE = new Emitter(sb, m);
        mainE.declareLocals(m.main, new ArrayList<>(), new ArrayList<>(), "        ");
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

    private static String javaLiteral(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"':  b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
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
        final Module module;
        int tempCounter = 0;
        final Deque<Integer> stack = new ArrayDeque<>();
        final Set<String> paramSet = new java.util.HashSet<>();
        final Set<Integer> tempIsArr = new java.util.HashSet<>();
        Set<String> arrLocals = new java.util.HashSet<>();
        Set<String> strLocals = new java.util.HashSet<>();

        Emitter(StringBuilder sb, Module module) {
            this.sb = sb;
            this.module = module;
        }

        String ref(String name) { return (paramSet.contains(name) ? "p_" : "v_") + name; }

        void declareLocals(List<BytecodeNode> body, List<String> params,
                           List<TypeChecker.T> paramTypes, String ind) {
            paramSet.addAll(params);
            arrLocals = ArrayTypes.inferArrays(body, params, paramTypes);
            strLocals = StringTypes.inferStrings(body, params, paramTypes);
            Set<String> names = new LinkedHashSet<>();
            storeNames(body, names);
            for (String p : params) names.remove(p);
            for (String v : names) {
                if (arrLocals.contains(v)) sb.append(ind).append("long[] v_").append(v).append(";\n");
                else if (strLocals.contains(v)) sb.append(ind).append("String v_").append(v).append(" = \"\";\n");
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
                    case SCONST: {
                        int t = newTemp(false);
                        sb.append(ind).append("String t").append(t).append(" = ")
                          .append(javaLiteral(module.stringPool.get((int) i.operand))).append(";\n");
                        stack.push(t);
                        break;
                    }
                    case LOAD: {
                        boolean arr = arrLocals.contains(i.str); // 数组参数也走数组路径
                        boolean str = strLocals.contains(i.str);
                        int t = newTemp(arr);
                        sb.append(ind).append(arr ? "long[] t" : (str ? "String t" : "long t")).append(t)
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
                    case PRINT_STR: {
                        int a = stack.pop();
                        sb.append(ind).append("System.out.println(t").append(a).append(");\n");
                        break;
                    }
                    case SLEN: {
                        int a = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("long t").append(t)
                          .append(" = (long) t").append(a).append(".length();\n");
                        stack.push(t);
                        break;
                    }
                    case SCONCAT: {
                        int b = stack.pop(), a = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("String t").append(t)
                          .append(" = t").append(a).append(" + t").append(b).append(";\n");
                        stack.push(t);
                        break;
                    }
                    case TOSTR: {
                        int a = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("String t").append(t)
                          .append(" = Long.toString(t").append(a).append(");\n");
                        stack.push(t);
                        break;
                    }
                    case BREAK: sb.append(ind).append("break;\n"); break;
                    case CONTINUE: sb.append(ind).append("continue;\n"); break;
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
                    case SYS_CALL: {
                        int argc = (int) i.operand;
                        if (i.str.equals("time")) {
                            if (argc != 0) throw new RuntimeException("time 参数个数错误: " + argc);
                            int t = newTemp(false);
                            sb.append(ind).append("long t").append(t)
                              .append(" = System.currentTimeMillis();\n");
                            stack.push(t);
                        } else if (i.str.equals("readFile")) {
                            if (argc != 1) throw new RuntimeException("readFile 参数个数错误: " + argc);
                            int path = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("String t").append(t).append(";\n");
                            sb.append(ind).append("try {\n");
                            sb.append(ind).append("    t").append(t)
                              .append(" = java.nio.file.Files.readString(java.nio.file.Path.of(t")
                              .append(path).append("));\n");
                            sb.append(ind).append("} catch (Exception e) {\n");
                            sb.append(ind).append("    t").append(t).append(" = \"\";\n");
                            sb.append(ind).append("}\n");
                            stack.push(t);
                        } else if (i.str.equals("writeFile")) {
                            if (argc != 2) throw new RuntimeException("writeFile 参数个数错误: " + argc);
                            int content = stack.pop(), path = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("long t").append(t).append(" = -1L;\n");
                            sb.append(ind).append("try {\n");
                            sb.append(ind).append("    java.nio.file.Files.writeString(java.nio.file.Path.of(t")
                              .append(path).append("), t").append(content).append(");\n");
                            sb.append(ind).append("    t").append(t).append(" = 0L;\n");
                            sb.append(ind).append("} catch (Exception e) {\n");
                            sb.append(ind).append("    t").append(t).append(" = -1L;\n");
                            sb.append(ind).append("}\n");
                            stack.push(t);
                        } else if (i.str.equals("input")) {
                            if (argc != 1) throw new RuntimeException("input 参数个数错误: " + argc);
                            int prompt = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("String t").append(t)
                              .append(" = uni_input(t").append(prompt).append(");\n");
                            stack.push(t);
                        } else if (i.str.equals("toInt")) {
                            if (argc != 1) throw new RuntimeException("toInt 参数个数错误: " + argc);
                            int s = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("long t").append(t)
                              .append(" = uni_to_int(t").append(s).append(");\n");
                            stack.push(t);
                        } else if (i.str.equals("random")) {
                            if (argc != 2) throw new RuntimeException("random 参数个数错误: " + argc);
                            int hi = stack.pop(), lo = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("long t").append(t)
                              .append(" = uni_random(t").append(lo).append(", t").append(hi).append(");\n");
                            stack.push(t);
                        } else {
                            throw new RuntimeException("未知标准库函数: " + i.str);
                        }
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
