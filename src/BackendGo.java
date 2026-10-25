import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// BackendGo：Module -> Go 源码（go build 编译为原生 exe）
public class BackendGo {

    public static String emit(Module m) {
        boolean needInput = m.hasSysCall("input");
        boolean needToInt = m.hasSysCall("toInt");
        boolean needRandom = m.hasSysCall("random");
        boolean needToStr = anyNode(m, Opcode.TOSTR);
        boolean needFmt = anyPrint(m) || needInput;
        boolean needOS = m.hasSysCall("readFile") || m.hasSysCall("writeFile") || needInput;
        boolean needTime = m.hasSysCall("time");
        boolean needBufio = needInput;
        boolean needStrings = needInput || needToInt;
        boolean needStrconv = needToStr || needToInt;
        StringBuilder sb = new StringBuilder();
        sb.append("package main\n\n");
        if (needFmt || needOS || needTime || needBufio || needStrings || needStrconv || needRandom) {
            sb.append("import (\n");
            if (needFmt)     sb.append("\t\"fmt\"\n");
            if (needOS)      sb.append("\t\"os\"\n");
            if (needTime)    sb.append("\t\"time\"\n");
            if (needBufio)   sb.append("\t\"bufio\"\n");
            if (needStrings) sb.append("\t\"strings\"\n");
            if (needStrconv) sb.append("\t\"strconv\"\n");
            if (needRandom)  sb.append("\t\"math/rand\"\n");
            sb.append(")\n\n");
        }

        if (needInput) {
            sb.append("var uniStdin = bufio.NewReader(os.Stdin)\n\n");
            sb.append("func uni_input(prompt string) string {\n");
            sb.append("\tif prompt != \"\" { fmt.Print(prompt) }\n");
            sb.append("\ts, err := uniStdin.ReadString('\\n')\n");
            sb.append("\tif err != nil && len(s) == 0 { return \"\" }\n");
            sb.append("\treturn strings.TrimRight(s, \"\\r\\n\")\n");
            sb.append("}\n\n");
        }
        if (needToInt) {
            sb.append("func uni_to_int(s string) int64 {\n");
            sb.append("\tv, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)\n");
            sb.append("\tif err != nil { return 0 }\n");
            sb.append("\treturn v\n");
            sb.append("}\n\n");
        }
        if (needRandom) {
            sb.append("func uni_random(a, b int64) int64 {\n");
            sb.append("\tif a > b { a, b = b, a }\n");
            sb.append("\tif a == b { return a }\n");
            sb.append("\tspan := b - a + 1\n");
            sb.append("\tif span <= 0 { return rand.Int63() }\n");
            sb.append("\treturn a + rand.Int63n(span)\n");
            sb.append("}\n\n");
        }

        for (Function fn : m.funcs.values()) {
            sb.append("func f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append("p_").append(fn.params.get(k))
                  .append(fn.paramTypes.get(k) == TypeChecker.T.ARRAY ? " []int64" : " int64");
            }
            sb.append(") int64 {\n");
            Emitter e = new Emitter(sb, m);
            e.declareLocals(fn.body, fn.params, fn.paramTypes, "\t");
            e.block(fn.body, "\t");
            if (!alwaysReturns(fn.body)) sb.append("\treturn 0\n}\n\n");
            else sb.append("}\n\n");
        }

        sb.append("func main() {\n");
        Emitter mainE = new Emitter(sb, m);
        mainE.declareLocals(m.main, new ArrayList<>(), new ArrayList<>(), "\t");
        mainE.block(m.main, "\t");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean anyNode(Module m, Opcode target) {
        for (Function fn : m.funcs.values()) if (hasNode(fn.body, target)) return true;
        return hasNode(m.main, target);
    }

    private static boolean hasNode(List<BytecodeNode> ns, Opcode target) {
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction && ((Instruction) n).op == target) return true;
            if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                if (hasNode(f.cond, target) || hasNode(f.then, target) || hasNode(f.els, target)) return true;
            }
            if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                if (hasNode(w.cond, target) || hasNode(w.body, target)) return true;
            }
        }
        return false;
    }

    private static boolean anyPrint(Module m) {
        for (Function fn : m.funcs.values()) if (hasPrint(fn.body)) return true;
        return hasPrint(m.main);
    }

    private static boolean hasPrint(List<BytecodeNode> ns) {
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction) {
                Opcode op = ((Instruction) n).op;
                if (op == Opcode.PRINT_INT || op == Opcode.PRINT_STR) return true;
            }
            if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                if (hasPrint(f.cond) || hasPrint(f.then) || hasPrint(f.els)) return true;
            }
            if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                if (hasPrint(w.cond) || hasPrint(w.body)) return true;
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

    private static String goLiteral(String s) {
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
                    if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
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
        final java.util.Set<String> paramSet = new java.util.HashSet<>();

        Emitter(StringBuilder sb, Module module) {
            this.sb = sb;
            this.module = module;
        }

        String ref(String name) { return (paramSet.contains(name) ? "p_" : "v_") + name; }

        void declareLocals(List<BytecodeNode> body, List<String> params,
                           List<TypeChecker.T> paramTypes, String ind) {
            paramSet.addAll(params);
            Set<String> arrLocals = ArrayTypes.inferArrays(body, params, paramTypes);
            Set<String> strLocals = StringTypes.inferStrings(body, params, paramTypes);
            Set<String> names = new LinkedHashSet<>();
            storeNames(body, names);
            for (String p : params) names.remove(p);
            for (String v : names) {
                if (arrLocals.contains(v)) sb.append(ind).append("var v_").append(v).append(" []int64\n");
                else if (strLocals.contains(v)) sb.append(ind).append("var v_").append(v).append(" string\n");
                else sb.append(ind).append("var v_").append(v).append(" int64 = 0\n");
            }
        }

        void block(List<BytecodeNode> ns, String ind) {
            for (BytecodeNode n : ns) node(n, ind);
        }

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
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := int64(").append(i.operand).append(")\n");
                        stack.push(t);
                        break;
                    }
                    case SCONST: {
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t).append(" := ")
                          .append(goLiteral(module.stringPool.get((int) i.operand))).append("\n");
                        stack.push(t);
                        break;
                    }
                    case LOAD: {
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := ").append(ref(i.str)).append("\n");
                        stack.push(t);
                        break;
                    }
                    case STORE: {
                        int a = stack.pop();
                        sb.append(ind).append(ref(i.str))
                          .append(" = t").append(a).append("\n");
                        break;
                    }
                    case IADD: case ISUB: case IMUL: case IDIV: {
                        int b = stack.pop(), a = stack.pop();
                        sb.append(ind).append("t").append(a).append(" = t").append(a)
                          .append(" ").append(symbol(i.op)).append(" t").append(b).append("\n");
                        stack.push(a);
                        break;
                    }
                    case INEG: {
                        int a = stack.pop();
                        sb.append(ind).append("t").append(a).append(" = -t").append(a).append("\n");
                        stack.push(a);
                        break;
                    }
                    case IEQ: case INE: case ILT: case IGT: case ILE: case IGE: {
                        int b = stack.pop(), a = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t).append(" := int64(0)\n");
                        sb.append(ind).append("if t").append(a).append(" ")
                          .append(symbol(i.op)).append(" t").append(b).append(" {\n");
                        sb.append(ind).append("\tt").append(t).append(" = 1\n");
                        sb.append(ind).append("} else {\n");
                        sb.append(ind).append("\tt").append(t).append(" = 0\n");
                        sb.append(ind).append("}\n");
                        stack.push(t);
                        break;
                    }
                    case PRINT_INT: {
                        int a = stack.pop();
                        sb.append(ind).append("fmt.Println(t").append(a).append(")\n");
                        break;
                    }
                    case PRINT_STR: {
                        int a = stack.pop();
                        sb.append(ind).append("fmt.Println(t").append(a).append(")\n");
                        break;
                    }
                    case SLEN: {
                        int a = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := int64(len(t").append(a).append("))\n");
                        stack.push(t);
                        break;
                    }
                    case SCONCAT: {
                        int b = stack.pop(), a = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := t").append(a).append(" + t").append(b).append("\n");
                        stack.push(t);
                        break;
                    }
                    case TOSTR: {
                        int a = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := strconv.FormatInt(t").append(a).append(", 10)\n");
                        stack.push(t);
                        break;
                    }
                    case BREAK: sb.append(ind).append("break\n"); break;
                    case CONTINUE: sb.append(ind).append("continue\n"); break;
                    case POP: {
                        int a = stack.pop();
                        sb.append(ind).append("_ = t").append(a).append("\n");
                        break;
                    }
                    case ANEW: {
                        int s = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := make([]int64, t").append(s).append(")\n");
                        stack.push(t);
                        break;
                    }
                    case AGET: {
                        int idx = stack.pop(), arr = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := t").append(arr).append("[t").append(idx).append("]\n");
                        stack.push(t);
                        break;
                    }
                    case ASET: {
                        int val = stack.pop(), idx = stack.pop(), arr = stack.pop();
                        sb.append(ind).append("t").append(arr).append("[t").append(idx)
                          .append("] = t").append(val).append("\n");
                        break;
                    }
                    case ALEN: {
                        int arr = stack.pop();
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := int64(len(t").append(arr).append("))\n");
                        stack.push(t);
                        break;
                    }
                    case CALL: {
                        int argc = (int) i.operand;
                        String args = callArgs(argc);
                        int t = tempCounter++;
                        sb.append(ind).append("t").append(t)
                          .append(" := f_").append(i.str).append("(").append(args).append(")\n");
                        stack.push(t);
                        break;
                    }
                    case SYS_CALL: {
                        int argc = (int) i.operand;
                        if (i.str.equals("time")) {
                            if (argc != 0) throw new RuntimeException("time 参数个数错误: " + argc);
                            int t = tempCounter++;
                            sb.append(ind).append("t").append(t)
                              .append(" := time.Now().UnixMilli()\n");
                            stack.push(t);
                        } else if (i.str.equals("readFile")) {
                            if (argc != 1) throw new RuntimeException("readFile 参数个数错误: " + argc);
                            int path = stack.pop();
                            int data = tempCounter++;
                            int err = tempCounter++;
                            int t = tempCounter++;
                            sb.append(ind).append("t").append(data).append(", t").append(err)
                              .append(" := os.ReadFile(t").append(path).append(")\n");
                            sb.append(ind).append("t").append(t).append(" := \"\"\n");
                            sb.append(ind).append("if t").append(err).append(" == nil {\n");
                            sb.append(ind).append("\tt").append(t).append(" = string(t").append(data).append(")\n");
                            sb.append(ind).append("}\n");
                            stack.push(t);
                        } else if (i.str.equals("writeFile")) {
                            if (argc != 2) throw new RuntimeException("writeFile 参数个数错误: " + argc);
                            int content = stack.pop(), path = stack.pop();
                            int err = tempCounter++;
                            int t = tempCounter++;
                            sb.append(ind).append("t").append(err)
                              .append(" := os.WriteFile(t").append(path).append(", []byte(t")
                              .append(content).append("), 0644)\n");
                            sb.append(ind).append("t").append(t).append(" := int64(0)\n");
                            sb.append(ind).append("if t").append(err).append(" != nil {\n");
                            sb.append(ind).append("\tt").append(t).append(" = -1\n");
                            sb.append(ind).append("}\n");
                            stack.push(t);
                        } else if (i.str.equals("input")) {
                            if (argc != 1) throw new RuntimeException("input 参数个数错误: " + argc);
                            int prompt = stack.pop();
                            int t = tempCounter++;
                            sb.append(ind).append("t").append(t)
                              .append(" := uni_input(t").append(prompt).append(")\n");
                            stack.push(t);
                        } else if (i.str.equals("toInt")) {
                            if (argc != 1) throw new RuntimeException("toInt 参数个数错误: " + argc);
                            int s = stack.pop();
                            int t = tempCounter++;
                            sb.append(ind).append("t").append(t)
                              .append(" := uni_to_int(t").append(s).append(")\n");
                            stack.push(t);
                        } else if (i.str.equals("random")) {
                            if (argc != 2) throw new RuntimeException("random 参数个数错误: " + argc);
                            int hi = stack.pop(), lo = stack.pop();
                            int t = tempCounter++;
                            sb.append(ind).append("t").append(t)
                              .append(" := uni_random(t").append(lo).append(", t").append(hi).append(")\n");
                            stack.push(t);
                        } else {
                            throw new RuntimeException("未知标准库函数: " + i.str);
                        }
                        break;
                    }
                    case RET: {
                        int a = stack.pop();
                        sb.append(ind).append("return t").append(a).append("\n");
                        break;
                    }
                }
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                block(f.cond, ind);
                int c = stack.pop();
                sb.append(ind).append("if t").append(c).append(" != 0 {\n");
                block(f.then, ind + "\t");
                sb.append(ind).append("} else {\n");
                block(f.els, ind + "\t");
                sb.append(ind).append("}\n");
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                sb.append(ind).append("for {\n");
                block(w.cond, ind + "\t");
                int c = stack.pop();
                sb.append(ind).append("\tif t").append(c).append(" == 0 {\n");
                sb.append(ind).append("\t\tbreak\n");
                sb.append(ind).append("\t}\n");
                block(w.body, ind + "\t");
                sb.append(ind).append("}\n");
            }
        }
    }
}
