import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ===== UniVM 字节码（JVM 风格栈式）=====
// M3：产出 Module = 函数表 + main 指令序列。
// 调用约定（对齐 JVM）：
//   参数由调用方从左到右求值压栈；CALL 弹出实参绑定到新帧的局部变量；
//   RET 弹出当前帧栈顶作为返回值，压回调用方栈。
enum Opcode {
    PUSH(1),       // 立即数压栈
    SCONST(1),     // 压入字符串池中下标为 operand 的字符串
    LOAD(1),       // 压入局部变量值
    STORE(1),      // 弹出栈顶存入局部变量
    IADD(0), ISUB(0), IMUL(0), IDIV(0), INEG(0),
    IEQ(0), INE(0), ILT(0), IGT(0), ILE(0), IGE(0),
    PRINT_INT(0),  // 弹出栈顶整数打印
    PRINT_STR(0),  // 弹出栈顶字符串打印
    SLEN(0),       // 弹出字符串，压入长度
    SCONCAT(0),    // 弹出两个字符串，压入拼接结果
    TOSTR(0),      // 弹出整数，压入十进制字符串
    BREAK(0),      // 跳出最内层循环（VM/JIT 控制信号，后端生成 break）
    CONTINUE(0),   // 继续最内层循环（VM/JIT 控制信号，后端生成 continue）
    POP(0),        // 丢弃栈顶（表达式语句）
    ANEW(0),       // 弹出 size，压入数组引用
    AGET(0),       // 弹出 index、array，压入元素
    ASET(0),       // 弹出 value、index、array
    ALEN(0),       // 弹出 array，压入长度
    CALL(1),       // str=函数名，operand=实参个数
    SYS_CALL(1),   // str=内置函数名，operand=实参个数
    RET(0)         // 弹出栈顶返回
    ;

    final int operandCount;
    Opcode(int n) { this.operandCount = n; }
}

abstract class BytecodeNode {}

class Instruction extends BytecodeNode {
    final Opcode op;
    final long operand;
    final String str;

    Instruction(Opcode op, long operand) {
        this.op = op;
        this.operand = operand;
        this.str = null;
    }

    Instruction(Opcode op, String str) {
        this.op = op;
        this.operand = 0;
        this.str = str;
    }

    // CALL 用：同时带函数名与实参个数
    Instruction(Opcode op, String str, long operand) {
        this.op = op;
        this.str = str;
        this.operand = operand;
    }

    @Override
    public String toString() {
        if (str != null) {
            boolean hasArgc = op == Opcode.CALL || op == Opcode.SYS_CALL;
            return op + " " + str + (hasArgc ? " argc=" + operand : "");
        }
        return op.operandCount > 0 ? op + " " + operand : op.toString();
    }
}

class IfNode extends BytecodeNode {
    final List<BytecodeNode> cond, then, els;
    IfNode(List<BytecodeNode> cond, List<BytecodeNode> then, List<BytecodeNode> els) {
        this.cond = cond; this.then = then; this.els = els;
    }
}

class WhileNode extends BytecodeNode {
    final List<BytecodeNode> cond, body;
    WhileNode(List<BytecodeNode> cond, List<BytecodeNode> body) {
        this.cond = cond; this.body = body;
    }
}

// 编译后的函数
class Function {
    final String name;
    final List<String> params;
    final List<TypeChecker.T> paramTypes; // 与 params 等长；后端据此选择参数声明类型
    final List<BytecodeNode> body;
    Function(String name, List<String> params, List<TypeChecker.T> paramTypes, List<BytecodeNode> body) {
        this.name = name;
        this.params = params;
        this.paramTypes = paramTypes;
        this.body = body;
    }
}

// 编译模块：函数表 + main + 字符串常量池
class Module {
    final Map<String, Function> funcs = new LinkedHashMap<>();
    final List<BytecodeNode> main = new ArrayList<>();
    final List<String> stringPool = new ArrayList<>();
    final Set<String> sysCalls = new LinkedHashSet<>();
    boolean usesStrings = false; // JIT 的 long[] 无法承载字符串；含字符串操作时回退解释器

    // 字符串去重：相同字面量复用同一个池下标
    int internString(String s) {
        int idx = stringPool.indexOf(s);
        if (idx >= 0) return idx;
        stringPool.add(s);
        return stringPool.size() - 1;
    }

    boolean hasSysCall(String name) { return sysCalls.contains(name); }
    boolean hasAnySysCall() { return !sysCalls.isEmpty(); }
}

// ===== Codegen：AST -> UniVM 字节码 =====
public class Codegen {

    public static Module emit(Program prog) {
        Module m = new Module();
        for (Stmt s : prog.stmts) {
            if (s instanceof FuncDecl) {
                FuncDecl f = (FuncDecl) s;
                m.funcs.put(f.name, new Function(f.name, f.params, f.paramTypes, blockNodes(f.body, m)));
            } else {
                genStmt(s, m.main, m);
            }
        }
        return m;
    }

    private static boolean isString(Expr e) {
        return e instanceof StrLit || e.type == TypeChecker.T.STRING;
    }

    private static boolean isBuiltin(String name) {
        return name.equals("time") || name.equals("readFile") || name.equals("writeFile")
                || name.equals("input") || name.equals("toInt") || name.equals("random");
    }

    private static void genStmt(Stmt s, List<BytecodeNode> out, Module m) {
        if (s instanceof Let) {
            Let l = (Let) s;
            genExpr(l.value, out, m);
            out.add(new Instruction(Opcode.STORE, l.name));
        } else if (s instanceof Assign) {
            Assign a = (Assign) s;
            genExpr(a.value, out, m);
            out.add(new Instruction(Opcode.STORE, a.name));
        } else if (s instanceof ExprStmt) {
            genExpr(((ExprStmt) s).expr, out, m);
            out.add(new Instruction(Opcode.POP, 0));
        } else if (s instanceof IndexSet) {
            IndexSet a = (IndexSet) s;
            genExpr(a.array, out, m);
            genExpr(a.index, out, m);
            genExpr(a.value, out, m);
            out.add(new Instruction(Opcode.ASET, 0));
        } else if (s instanceof Print) {
            Print p = (Print) s;
            genExpr(p.value, out, m);
            boolean str = isString(p.value);
            if (str) m.usesStrings = true;
            out.add(new Instruction(str ? Opcode.PRINT_STR : Opcode.PRINT_INT, 0));
        } else if (s instanceof Block) {
            out.addAll(blockNodes((Block) s, m));
        } else if (s instanceof If) {
            If f = (If) s;
            List<BytecodeNode> els = f.els == null ? new ArrayList<>() : stmtNodes(f.els, m);
            out.add(new IfNode(exprNodes(f.cond, m), blockNodes(f.thenBlock, m), els));
        } else if (s instanceof While) {
            While w = (While) s;
            out.add(new WhileNode(exprNodes(w.cond, m), blockNodes(w.body, m)));
        } else if (s instanceof Return) {
            Return r = (Return) s;
            if (r.value != null) genExpr(r.value, out, m);
            else out.add(new Instruction(Opcode.PUSH, 0));
            out.add(new Instruction(Opcode.RET, 0));
        } else if (s instanceof Break) {
            out.add(new Instruction(Opcode.BREAK, 0));
        } else if (s instanceof Continue) {
            out.add(new Instruction(Opcode.CONTINUE, 0));
        } else if (s instanceof FuncDecl) {
            throw new RuntimeException("函数声明只允许出现在顶层");
        } else {
            throw new RuntimeException("未知语句节点: " + s);
        }
    }

    private static List<BytecodeNode> exprNodes(Expr e, Module m) {
        List<BytecodeNode> l = new ArrayList<>();
        genExpr(e, l, m);
        return l;
    }

    private static List<BytecodeNode> blockNodes(Block b, Module m) {
        List<BytecodeNode> l = new ArrayList<>();
        for (Stmt s : b.stmts) genStmt(s, l, m);
        return l;
    }

    private static List<BytecodeNode> stmtNodes(Stmt s, Module m) {
        List<BytecodeNode> l = new ArrayList<>();
        genStmt(s, l, m);
        return l;
    }

    private static void genExpr(Expr e, List<BytecodeNode> out, Module m) {
        if (e instanceof Num) {
            out.add(new Instruction(Opcode.PUSH, ((Num) e).value));
        } else if (e instanceof StrLit) {
            int idx = m.internString(((StrLit) e).value);
            m.usesStrings = true;
            out.add(new Instruction(Opcode.SCONST, idx));
        } else if (e instanceof Call) {
            Call c = (Call) e;
            // str(x)：字符串原样返回，整数转换为 TOSTR；不进入后端 SYS_CALL。
            if (c.name.equals("str")) {
                if (c.args.size() != 1)
                    throw new RuntimeException("str 需要 1 个参数，实际 " + c.args.size());
                Expr arg = c.args.get(0);
                genExpr(arg, out, m);
                if (!isString(arg)) out.add(new Instruction(Opcode.TOSTR, 0));
                m.usesStrings = true;
                return;
            }
            // input() / input(prompt)：统一规范化为 1 个提示字符串参数，
            // 这样 VM、CLI、GUI 和所有后端都只需处理一种调用形状。
            if (c.name.equals("input")) {
                if (c.args.size() > 1)
                    throw new RuntimeException("input 最多接受 1 个参数，实际 " + c.args.size());
                if (c.args.isEmpty()) {
                    m.usesStrings = true;
                    out.add(new Instruction(Opcode.SCONST, m.internString("")));
                } else {
                    genExpr(c.args.get(0), out, m);
                }
                m.usesStrings = true;
                m.sysCalls.add("input");
                out.add(new Instruction(Opcode.SYS_CALL, "input", 1));
                return;
            }
            if (isBuiltin(c.name)) {
                // 标准库内置函数：实参照常从左到右压栈，调用点标记为 SYS_CALL
                for (Expr arg : c.args) genExpr(arg, out, m);
                m.sysCalls.add(c.name);
                out.add(new Instruction(Opcode.SYS_CALL, c.name, c.args.size()));
                return;
            }
            if (c.name.equals("len") && c.args.size() == 1) { // 内建 len：数组或字符串
                Expr arg = c.args.get(0);
                genExpr(arg, out, m);
                boolean str = isString(arg);
                if (str) m.usesStrings = true;
                out.add(new Instruction(str ? Opcode.SLEN : Opcode.ALEN, 0));
                return;
            }
            for (Expr arg : c.args) genExpr(arg, out, m); // 实参从左到右
            out.add(new Instruction(Opcode.CALL, c.name, c.args.size()));
        } else if (e instanceof ArrayNew) {
            genExpr(((ArrayNew) e).size, out, m);
            out.add(new Instruction(Opcode.ANEW, 0));
        } else if (e instanceof IndexGet) {
            IndexGet g = (IndexGet) e;
            genExpr(g.array, out, m);
            genExpr(g.index, out, m);
            out.add(new Instruction(Opcode.AGET, 0));
        } else if (e instanceof Var) {
            out.add(new Instruction(Opcode.LOAD, ((Var) e).name));
        } else if (e instanceof Unary) {
            Unary u = (Unary) e;
            genExpr(u.operand, out, m);
            if (u.op == '-') out.add(new Instruction(Opcode.INEG, 0));
        } else if (e instanceof Binary) {
            Binary b = (Binary) e;
            // 字符串拼接：+ 任一侧为 STRING 时，把整数侧 TOSTR 后交给 SCONCAT。
            if (b.op.equals("+") && (isString(b.left) || isString(b.right))) {
                genExpr(b.left, out, m);
                if (!isString(b.left)) out.add(new Instruction(Opcode.TOSTR, 0));
                genExpr(b.right, out, m);
                if (!isString(b.right)) out.add(new Instruction(Opcode.TOSTR, 0));
                out.add(new Instruction(Opcode.SCONCAT, 0));
                m.usesStrings = true;
                return;
            }
            genExpr(b.left, out, m);
            genExpr(b.right, out, m);
            switch (b.op) {
                case "+":  out.add(new Instruction(Opcode.IADD, 0)); break;
                case "-":  out.add(new Instruction(Opcode.ISUB, 0)); break;
                case "*":  out.add(new Instruction(Opcode.IMUL, 0)); break;
                case "/":  out.add(new Instruction(Opcode.IDIV, 0)); break;
                case "==": out.add(new Instruction(Opcode.IEQ, 0)); break;
                case "!=": out.add(new Instruction(Opcode.INE, 0)); break;
                case "<":  out.add(new Instruction(Opcode.ILT, 0)); break;
                case ">":  out.add(new Instruction(Opcode.IGT, 0)); break;
                case "<=": out.add(new Instruction(Opcode.ILE, 0)); break;
                case ">=": out.add(new Instruction(Opcode.IGE, 0)); break;
                default: throw new RuntimeException("未知运算符 " + b.op);
            }
        } else {
            throw new RuntimeException("未知 AST 节点 " + e);
        }
    }

    // ===== 结构化打印（--ir）=====
    public static String pretty(Module m) {
        StringBuilder sb = new StringBuilder();
        for (Function f : m.funcs.values()) {
            sb.append("FUNC ").append(f.name).append('(').append(String.join(", ", f.params)).append("):\n");
            sb.append(pp(f.body, 1));
        }
        sb.append("MAIN:\n").append(pp(m.main, 1));
        return sb.toString();
    }

    private static String pp(List<BytecodeNode> ns, int depth) {
        StringBuilder sb = new StringBuilder();
        String ind = "  ".repeat(depth);
        for (BytecodeNode n : ns) {
            if (n instanceof Instruction) {
                sb.append(ind).append(n).append('\n');
            } else if (n instanceof IfNode) {
                IfNode f = (IfNode) n;
                sb.append(ind).append("IF cond:\n").append(pp(f.cond, depth + 1));
                sb.append(ind).append("THEN:\n").append(pp(f.then, depth + 1));
                sb.append(ind).append("ELSE:\n").append(pp(f.els, depth + 1));
                sb.append(ind).append("END IF\n");
            } else if (n instanceof WhileNode) {
                WhileNode w = (WhileNode) n;
                sb.append(ind).append("WHILE cond:\n").append(pp(w.cond, depth + 1));
                sb.append(ind).append("BODY:\n").append(pp(w.body, depth + 1));
                sb.append(ind).append("END WHILE\n");
            }
        }
        return sb.toString();
    }
}
