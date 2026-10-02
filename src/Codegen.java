import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ===== UniVM 字节码（JVM 风格栈式）=====
// M3：产出 Module = 函数表 + main 指令序列。
// 调用约定（对齐 JVM）：
//   参数由调用方从左到右求值压栈；CALL 弹出实参绑定到新帧的局部变量；
//   RET 弹出当前帧栈顶作为返回值，压回调用方栈。
enum Opcode {
    PUSH(1),       // 立即数压栈
    LOAD(1),       // 压入局部变量值
    STORE(1),      // 弹出栈顶存入局部变量
    IADD(0), ISUB(0), IMUL(0), IDIV(0), INEG(0),
    IEQ(0), INE(0), ILT(0), IGT(0), ILE(0), IGE(0),
    PRINT_INT(0),  // 弹出栈顶打印
    POP(0),        // 丢弃栈顶（表达式语句）
    ANEW(0),       // 弹出 size，压入数组引用
    AGET(0),       // 弹出 index、array，压入元素
    ASET(0),       // 弹出 value、index、array
    ALEN(0),       // 弹出 array，压入长度
    CALL(1),       // str=函数名，operand=实参个数
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
        if (str != null) return op + " " + str + (op == Opcode.CALL ? " argc=" + operand : "");
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
    final List<BytecodeNode> body;
    Function(String name, List<String> params, List<BytecodeNode> body) {
        this.name = name;
        this.params = params;
        this.body = body;
    }
}

// 编译模块：函数表 + main
class Module {
    final Map<String, Function> funcs = new LinkedHashMap<>();
    final List<BytecodeNode> main = new ArrayList<>();
}

// ===== Codegen：AST -> UniVM 字节码 =====
public class Codegen {

    public static Module emit(Program prog) {
        Module m = new Module();
        for (Stmt s : prog.stmts) {
            if (s instanceof FuncDecl) {
                FuncDecl f = (FuncDecl) s;
                m.funcs.put(f.name, new Function(f.name, f.params, blockNodes(f.body)));
            } else {
                genStmt(s, m.main);
            }
        }
        return m;
    }

    private static void genStmt(Stmt s, List<BytecodeNode> out) {
        if (s instanceof Let) {
            Let l = (Let) s;
            genExpr(l.value, out);
            out.add(new Instruction(Opcode.STORE, l.name));
        } else if (s instanceof Assign) {
            Assign a = (Assign) s;
            genExpr(a.value, out);
            out.add(new Instruction(Opcode.STORE, a.name));
        } else if (s instanceof ExprStmt) {
            genExpr(((ExprStmt) s).expr, out);
            out.add(new Instruction(Opcode.POP, 0));
        } else if (s instanceof IndexSet) {
            IndexSet a = (IndexSet) s;
            genExpr(a.array, out);
            genExpr(a.index, out);
            genExpr(a.value, out);
            out.add(new Instruction(Opcode.ASET, 0));
        } else if (s instanceof Print) {
            Print p = (Print) s;
            genExpr(p.value, out);
            out.add(new Instruction(Opcode.PRINT_INT, 0));
        } else if (s instanceof Block) {
            out.addAll(blockNodes((Block) s));
        } else if (s instanceof If) {
            If f = (If) s;
            List<BytecodeNode> els = f.els == null ? new ArrayList<>() : stmtNodes(f.els);
            out.add(new IfNode(exprNodes(f.cond), blockNodes(f.thenBlock), els));
        } else if (s instanceof While) {
            While w = (While) s;
            out.add(new WhileNode(exprNodes(w.cond), blockNodes(w.body)));
        } else if (s instanceof Return) {
            Return r = (Return) s;
            if (r.value != null) genExpr(r.value, out);
            else out.add(new Instruction(Opcode.PUSH, 0));
            out.add(new Instruction(Opcode.RET, 0));
        } else if (s instanceof FuncDecl) {
            throw new RuntimeException("函数声明只允许出现在顶层");
        } else {
            throw new RuntimeException("未知语句节点: " + s);
        }
    }

    private static List<BytecodeNode> exprNodes(Expr e) {
        List<BytecodeNode> l = new ArrayList<>();
        genExpr(e, l);
        return l;
    }

    private static List<BytecodeNode> blockNodes(Block b) {
        List<BytecodeNode> l = new ArrayList<>();
        for (Stmt s : b.stmts) genStmt(s, l);
        return l;
    }

    private static List<BytecodeNode> stmtNodes(Stmt s) {
        List<BytecodeNode> l = new ArrayList<>();
        genStmt(s, l);
        return l;
    }

    private static void genExpr(Expr e, List<BytecodeNode> out) {
        if (e instanceof Num) {
            out.add(new Instruction(Opcode.PUSH, ((Num) e).value));
        } else if (e instanceof Call) {
            Call c = (Call) e;
            if (c.name.equals("len") && c.args.size() == 1) { // 内建 len
                genExpr(c.args.get(0), out);
                out.add(new Instruction(Opcode.ALEN, 0));
                return;
            }
            for (Expr arg : c.args) genExpr(arg, out); // 实参从左到右
            out.add(new Instruction(Opcode.CALL, c.name, c.args.size()));
        } else if (e instanceof ArrayNew) {
            genExpr(((ArrayNew) e).size, out);
            out.add(new Instruction(Opcode.ANEW, 0));
        } else if (e instanceof IndexGet) {
            IndexGet g = (IndexGet) e;
            genExpr(g.array, out);
            genExpr(g.index, out);
            out.add(new Instruction(Opcode.AGET, 0));
        } else if (e instanceof Var) {
            out.add(new Instruction(Opcode.LOAD, ((Var) e).name));
        } else if (e instanceof Unary) {
            Unary u = (Unary) e;
            genExpr(u.operand, out);
            if (u.op == '-') out.add(new Instruction(Opcode.INEG, 0));
        } else if (e instanceof Binary) {
            Binary b = (Binary) e;
            genExpr(b.left, out);
            genExpr(b.right, out);
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
