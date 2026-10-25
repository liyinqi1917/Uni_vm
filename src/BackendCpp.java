import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// BackendCpp：Module -> C++（分离 .h 头文件 + .cpp 实现）
// 数组映射为 std::vector<int64_t>*（new 创建，零填充）；注意 C++ 端生成
// 代码不做追踪 GC（与 C 同），可用智能指针/RAII 改造，此处保持与 C 一致的裸指针模型。
public class BackendCpp {

    private static final String READ_HELPER = """
            static std::string uni_read_file(const std::string &path) {
                std::ifstream in(path, std::ios::binary);
                if (!in) return "";
                std::ostringstream ss;
                ss << in.rdbuf();
                return ss.str();
            }

            """;

    private static final String WRITE_HELPER = """
            static std::int64_t uni_write_file(const std::string &path,
                                               const std::string &content) {
                std::ofstream out(path, std::ios::binary);
                if (!out) return -1;
                out << content;
                out.flush();
                return out.good() ? 0 : -1;
            }

            """;

    private static final String TOSTR_HELPER = """
            static std::string uni_str_int(std::int64_t v) {
                return std::to_string(v);
            }

            """;

    private static final String INPUT_HELPER = """
            static std::string uni_input(const std::string &prompt) {
                if (!prompt.empty()) {
                    std::cout << prompt;
                    std::cout.flush();
                }
                std::string line;
                if (!std::getline(std::cin, line)) return "";
                if (!line.empty() && line.back() == '\\r') line.pop_back();
                return line;
            }

            """;

    private static final String TOINT_HELPER = """
            static std::int64_t uni_to_int(const std::string &s) {
                try {
                    std::size_t pos = 0;
                    long long v = std::stoll(s, &pos, 10);
                    if (pos == 0) return 0;
                    while (pos < s.size() && (s[pos] == ' ' || s[pos] == '\\t' ||
                           s[pos] == '\\r' || s[pos] == '\\n' || s[pos] == '\\f' ||
                           s[pos] == '\\v')) pos++;
                    return pos == s.size() ? (std::int64_t)v : 0;
                } catch (...) {
                    return 0;
                }
            }

            """;

    private static final String RANDOM_HELPER = """
            static std::int64_t uni_random(std::int64_t a, std::int64_t b) {
                static bool seeded = false;
                if (!seeded) { std::srand((unsigned)std::time(nullptr)); seeded = true; }
                if (a > b) std::swap(a, b);
                if (a == b) return a;
                unsigned long long span = (unsigned long long)b - (unsigned long long)a + 1;
                unsigned long long r = ((unsigned long long)std::rand() << 31)
                                     ^ (unsigned long long)std::rand();
                if (span == 0) return (std::int64_t)r;
                return (std::int64_t)((unsigned long long)a + (r % span));
            }

            """;

    public static class Output {
        public final String header, source;
        Output(String header, String source) { this.header = header; this.source = source; }
    }

    public static Output emit(Module m, String headerName) {
        boolean needStdio = anyPrint(m);
        boolean needVector = anyNode(m, Opcode.ANEW);
        boolean needRead = m.hasSysCall("readFile");
        boolean needWrite = m.hasSysCall("writeFile");
        boolean needTime = m.hasSysCall("time");
        boolean needInput = m.hasSysCall("input");
        boolean needToInt = m.hasSysCall("toInt");
        boolean needRandom = m.hasSysCall("random");
        boolean needConcat = anyNode(m, Opcode.SCONCAT);
        boolean needToStr = anyNode(m, Opcode.TOSTR);
        boolean needString = anyNode(m, Opcode.SCONST) || needRead || needWrite
                || needInput || needConcat || needToStr || needToInt;
        boolean needIostream = needInput;
        boolean needCstdlib = needRandom;
        boolean needCtime = needTime || needRandom;
        boolean needUtility = needRandom;

        // ===== 头文件 =====
        StringBuilder h = new StringBuilder();
        h.append("#pragma once\n\n");
        h.append("#include <cstdint>\n");
        if (needVector || anyArrayParam(m)) h.append("#include <vector>\n"); // 数组参数签名引用 std::vector
        h.append('\n');
        for (Function fn : m.funcs.values()) {
            h.append("std::int64_t f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) h.append(", ");
                h.append(fn.paramTypes.get(k) == TypeChecker.T.ARRAY
                        ? "std::vector<std::int64_t>* p_" : "std::int64_t p_").append(fn.params.get(k));
            }
            h.append(");\n");
        }

        // ===== 实现文件 =====
        StringBuilder sb = new StringBuilder();
        sb.append("#include \"").append(headerName).append(".h\"\n");
        if (needStdio) sb.append("#include <cstdio>\n");
        if (needVector) sb.append("#include <vector>\n");
        if (needString) sb.append("#include <string>\n");
        if (needIostream) sb.append("#include <iostream>\n");
        if (needRead || needWrite) sb.append("#include <fstream>\n");
        if (needRead) sb.append("#include <sstream>\n");
        if (needCtime) sb.append("#include <ctime>\n");
        if (needCstdlib) sb.append("#include <cstdlib>\n");
        if (needUtility) sb.append("#include <utility>\n");
        sb.append('\n');

        if (needRead) sb.append(READ_HELPER);
        if (needWrite) sb.append(WRITE_HELPER);
        if (needToStr) sb.append(TOSTR_HELPER);
        if (needInput) sb.append(INPUT_HELPER);
        if (needToInt) sb.append(TOINT_HELPER);
        if (needRandom) sb.append(RANDOM_HELPER);

        for (Function fn : m.funcs.values()) {
            sb.append("std::int64_t f_").append(fn.name).append("(");
            for (int k = 0; k < fn.params.size(); k++) {
                if (k > 0) sb.append(", ");
                sb.append(fn.paramTypes.get(k) == TypeChecker.T.ARRAY
                        ? "std::vector<std::int64_t>* p_" : "std::int64_t p_").append(fn.params.get(k));
            }
            sb.append(") {\n");
            Emitter e = new Emitter(sb, m);
            e.declareLocals(fn.body, fn.params, fn.paramTypes, "    ");
            e.block(fn.body, "    ");
            if (!alwaysReturns(fn.body)) sb.append("    return 0;\n}\n\n");
            else sb.append("}\n\n");
        }

        sb.append("int main() {\n");
        Emitter mainE = new Emitter(sb, m);
        mainE.declareLocals(m.main, new ArrayList<>(), new ArrayList<>(), "    ");
        mainE.block(m.main, "    ");
        sb.append("    return 0;\n}\n");

        return new Output(h.toString(), sb.toString());
    }

    // 是否有函数带数组参数（头文件据此决定是否 include <vector>）
    private static boolean anyArrayParam(Module m) {
        for (Function fn : m.funcs.values())
            for (TypeChecker.T t : fn.paramTypes) if (t == TypeChecker.T.ARRAY) return true;
        return false;
    }

    private static boolean anyPrint(Module m) {
        return anyNode(m, Opcode.PRINT_INT) || anyNode(m, Opcode.PRINT_STR);
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

    private static String cppLiteral(String s) {
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
        final Set<String> paramSet = new java.util.HashSet<>();
        final Set<Integer> tempIsArr = new java.util.HashSet<>();
        Set<String> arrLocals = new java.util.HashSet<>();
        Set<String> strLocals = new java.util.HashSet<>();

        Emitter(StringBuilder sb, Module module) {
            this.sb = sb;
            this.module = module;
        }

        String ref(String name) { return (paramSet.contains(name) ? "p_" : "v_") + name; }

        private int newTemp(boolean isArr) {
            int t = tempCounter++;
            if (isArr) tempIsArr.add(t);
            return t;
        }

        void declareLocals(List<BytecodeNode> body, List<String> params,
                           List<TypeChecker.T> paramTypes, String ind) {
            paramSet.addAll(params);
            arrLocals = ArrayTypes.inferArrays(body, params, paramTypes);
            strLocals = StringTypes.inferStrings(body, params, paramTypes);
            Set<String> names = new LinkedHashSet<>();
            storeNames(body, names);
            for (String p : params) names.remove(p);
            for (String v : names) {
                if (arrLocals.contains(v))
                    sb.append(ind).append("std::vector<std::int64_t>* v_").append(v).append(" = nullptr;\n");
                else if (strLocals.contains(v))
                    sb.append(ind).append("std::string v_").append(v).append(";\n");
                else
                    sb.append(ind).append("std::int64_t v_").append(v).append(" = 0;\n");
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
                        sb.append(ind).append("std::int64_t t").append(t)
                          .append(" = ").append(i.operand).append(";\n");
                        stack.push(t);
                        break;
                    }
                    case SCONST: {
                        int t = newTemp(false);
                        sb.append(ind).append("std::string t").append(t).append(" = ")
                          .append(cppLiteral(module.stringPool.get((int) i.operand))).append(";\n");
                        stack.push(t);
                        break;
                    }
                    case LOAD: {
                        boolean arr = arrLocals.contains(i.str); // 数组参数也走数组路径
                        boolean str = strLocals.contains(i.str);
                        int t = newTemp(arr);
                        sb.append(ind).append(arr ? "std::vector<std::int64_t>* t"
                                : (str ? "std::string t" : "std::int64_t t"))
                          .append(t).append(" = ").append(ref(i.str)).append(";\n");
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
                        sb.append(ind).append("std::printf(\"%lld\\n\", static_cast<long long>(t")
                          .append(a).append("));\n");
                        break;
                    }
                    case PRINT_STR: {
                        int a = stack.pop();
                        sb.append(ind).append("std::printf(\"%s\\n\", t").append(a).append(".c_str());\n");
                        break;
                    }
                    case SLEN: {
                        int a = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("std::int64_t t").append(t)
                          .append(" = static_cast<std::int64_t>(t").append(a).append(".size());\n");
                        stack.push(t);
                        break;
                    }
                    case SCONCAT: {
                        int b = stack.pop(), a = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("std::string t").append(t)
                          .append(" = t").append(a).append(" + t").append(b).append(";\n");
                        stack.push(t);
                        break;
                    }
                    case TOSTR: {
                        int a = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("std::string t").append(t)
                          .append(" = uni_str_int(t").append(a).append(");\n");
                        stack.push(t);
                        break;
                    }
                    case BREAK: sb.append(ind).append("break;\n"); break;
                    case CONTINUE: sb.append(ind).append("continue;\n"); break;
                    case POP: { stack.pop(); break; }
                    case ANEW: {
                        int s = stack.pop();
                        int t = newTemp(true);
                        sb.append(ind).append("std::vector<std::int64_t>* t").append(t)
                          .append(" = new std::vector<std::int64_t>(t").append(s).append(", 0);\n");
                        stack.push(t);
                        break;
                    }
                    case AGET: {
                        int idx = stack.pop(), arr = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("std::int64_t t").append(t)
                          .append(" = (*t").append(arr).append(")[t").append(idx).append("];\n");
                        stack.push(t);
                        break;
                    }
                    case ASET: {
                        int val = stack.pop(), idx = stack.pop(), arr = stack.pop();
                        sb.append(ind).append("(*t").append(arr).append(")[t").append(idx)
                          .append("] = t").append(val).append(";\n");
                        break;
                    }
                    case ALEN: {
                        int arr = stack.pop();
                        int t = newTemp(false);
                        sb.append(ind).append("std::int64_t t").append(t)
                          .append(" = static_cast<std::int64_t>(t").append(arr).append("->size());\n");
                        stack.push(t);
                        break;
                    }
                    case CALL: {
                        int argc = (int) i.operand;
                        String args = callArgs(argc);
                        int t = newTemp(false);
                        sb.append(ind).append("std::int64_t t").append(t)
                          .append(" = f_").append(i.str).append("(").append(args).append(");\n");
                        stack.push(t);
                        break;
                    }
                    case SYS_CALL: {
                        int argc = (int) i.operand;
                        if (i.str.equals("time")) {
                            if (argc != 0) throw new RuntimeException("time 参数个数错误: " + argc);
                            int t = newTemp(false);
                            sb.append(ind).append("std::int64_t t").append(t)
                              .append(" = static_cast<std::int64_t>(std::time(nullptr));\n");
                            stack.push(t);
                        } else if (i.str.equals("readFile")) {
                            if (argc != 1) throw new RuntimeException("readFile 参数个数错误: " + argc);
                            int path = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("std::string t").append(t)
                              .append(" = uni_read_file(t").append(path).append(");\n");
                            stack.push(t);
                        } else if (i.str.equals("writeFile")) {
                            if (argc != 2) throw new RuntimeException("writeFile 参数个数错误: " + argc);
                            int content = stack.pop(), path = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("std::int64_t t").append(t)
                              .append(" = uni_write_file(t").append(path).append(", t")
                              .append(content).append(");\n");
                            stack.push(t);
                        } else if (i.str.equals("input")) {
                            if (argc != 1) throw new RuntimeException("input 参数个数错误: " + argc);
                            int prompt = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("std::string t").append(t)
                              .append(" = uni_input(t").append(prompt).append(");\n");
                            stack.push(t);
                        } else if (i.str.equals("toInt")) {
                            if (argc != 1) throw new RuntimeException("toInt 参数个数错误: " + argc);
                            int s = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("std::int64_t t").append(t)
                              .append(" = uni_to_int(t").append(s).append(");\n");
                            stack.push(t);
                        } else if (i.str.equals("random")) {
                            if (argc != 2) throw new RuntimeException("random 参数个数错误: " + argc);
                            int hi = stack.pop(), lo = stack.pop();
                            int t = newTemp(false);
                            sb.append(ind).append("std::int64_t t").append(t)
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
