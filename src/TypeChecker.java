import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// TypeChecker：M6 静态类型检查（先只有 INT，框架可扩展更多类型）。
// 在 Codegen 之前运行：建立带作用域的符号表，推断每个表达式类型，
// 校验：未定义变量/函数、实参个数、算术/比较/条件的操作数类型、return 类型。
// Python 前端没有 let，首次赋值即声明（Assign 到未声明变量时引入）。
public class TypeChecker {

    public enum T { INT, ARRAY, STRING }

    private final Deque<Map<String, T>> scopes = new ArrayDeque<>();
    private final Map<String, List<T>> funParams = new LinkedHashMap<>();
    private final Map<String, T> funRet = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private int loopDepth = 0; // 当前函数内的循环嵌套深度（break/continue 合法性）

    public static void check(Program p) {
        TypeChecker c = new TypeChecker();
        c.run(p);
        if (!c.errors.isEmpty()) {
            throw new RuntimeException("类型检查失败：\n  " + String.join("\n  ", c.errors));
        }
    }

    // --no-check：仍然做一次类型推断，供 Codegen 选择 PRINT_STR / SLEN 等指令，但不报错
    public static void infer(Program p) {
        new TypeChecker().run(p);
    }

    private void run(Program p) {
        scopes.push(new HashMap<>());
        // 先登记所有函数签名（支持前向引用/递归）
        for (Stmt s : p.stmts) {
            if (s instanceof FuncDecl) {
                FuncDecl f = (FuncDecl) s;
                List<T> ps = new ArrayList<>();
                for (int k = 0; k < f.params.size(); k++) ps.add(f.paramTypes.get(k));
                funParams.put(f.name, ps);
                funRet.put(f.name, T.INT);
            }
        }
        for (Stmt s : p.stmts) stmt(s);
    }

    private void enter() { scopes.push(new HashMap<>()); }
    private void exit() { scopes.pop(); }

    private void declare(String name, T t) { scopes.peek().put(name, t); }

    private T lookup(String name) {
        for (Map<String, T> m : scopes) if (m.containsKey(name)) return m.get(name);
        return null;
    }

    private void requireInt(T t, String what) {
        if (t != T.INT) errors.add(what + " 需要整数类型，实际 " + t);
    }

    private T typed(Expr e, T t) {
        e.type = t;
        return t;
    }

    // 类型名 -> T：前端类型标注与后端共享的唯一映射表（未知名返回 null）
    public static T typeFromName(String s) {
        if (s.equals("array")) return T.ARRAY;
        if (s.equals("int")) return T.INT;
        return null;
    }

    private void stmt(Stmt s) {
        if (s instanceof Let) {
            Let l = (Let) s;
            T t = expr(l.value);
            declare(l.name, t);
        } else if (s instanceof Assign) {
            Assign a = (Assign) s;
            T t = expr(a.value);
            T old = lookup(a.name);
            if (old == null) declare(a.name, t); // Python 风格：首次赋值即声明
            else if (old != t) errors.add("变量 " + a.name + " 赋值类型不匹配：期望 " + old + "，实际 " + t);
        } else if (s instanceof Print) {
            expr(((Print) s).value);
        } else if (s instanceof ExprStmt) {
            expr(((ExprStmt) s).expr);
        } else if (s instanceof Block) {
            enter();
            for (Stmt b : ((Block) s).stmts) stmt(b);
            exit();
        } else if (s instanceof If) {
            If f = (If) s;
            requireInt(expr(f.cond), "if 条件");
            enter(); for (Stmt b : f.thenBlock.stmts) stmt(b); exit();
            if (f.els != null) stmt(f.els);
        } else if (s instanceof While) {
            While w = (While) s;
            requireInt(expr(w.cond), "while 条件");
            enter();
            loopDepth++;
            for (Stmt b : w.body.stmts) stmt(b);
            loopDepth--;
            exit();
        } else if (s instanceof Break) {
            if (loopDepth == 0) errors.add("break 只能出现在循环体内");
        } else if (s instanceof Continue) {
            if (loopDepth == 0) errors.add("continue 只能出现在循环体内");
        } else if (s instanceof FuncDecl) {
            FuncDecl f = (FuncDecl) s;
            int savedLoopDepth = loopDepth;
            loopDepth = 0; // break/continue 不能跨函数绑定到调用者的循环
            enter();
            for (int i = 0; i < f.params.size(); i++) declare(f.params.get(i), f.paramTypes.get(i));
            for (Stmt b : f.body.stmts) stmt(b);
            exit();
            loopDepth = savedLoopDepth;
        } else if (s instanceof IndexSet) {
            IndexSet a = (IndexSet) s;
            T at = expr(a.array);
            T it = expr(a.index);
            T vt = expr(a.value);
            if (at != T.ARRAY) errors.add("下标赋值目标必须是数组，实际 " + at);
            requireInt(it, "下标");
            requireInt(vt, "存入值");
        } else if (s instanceof Return) {
            Return r = (Return) s;
            if (r.value != null) requireInt(expr(r.value), "return 值");
        }
    }

    private T expr(Expr e) {
        if (e instanceof Num) return typed(e, T.INT);
        if (e instanceof StrLit) return typed(e, T.STRING);
        if (e instanceof Var) {
            T t = lookup(((Var) e).name);
            if (t == null) errors.add("未定义的变量: " + ((Var) e).name);
            return typed(e, t == null ? T.INT : t);
        }
        if (e instanceof Unary) {
            T t = expr(((Unary) e).operand);
            requireInt(t, "一元运算");
            return typed(e, T.INT);
        }
        if (e instanceof Binary) {
            Binary b = (Binary) e;
            T l = expr(b.left), r = expr(b.right);
            // + 只要有一侧是 STRING 就执行拼接；另一侧允许 STRING 或 INT（自动转字符串）
            if (b.op.equals("+") && (l == T.STRING || r == T.STRING)) {
                if (l != T.STRING && l != T.INT)
                    errors.add("字符串拼接左值必须是 STRING 或 INT，实际 " + l);
                if (r != T.STRING && r != T.INT)
                    errors.add("字符串拼接右值必须是 STRING 或 INT，实际 " + r);
                return typed(e, T.STRING);
            }
            requireInt(l, "运算左值"); requireInt(r, "运算右值");
            return typed(e, T.INT); // 算术与比较都产生 INT（比较为 0/1）
        }
        if (e instanceof Call) {
            Call c = (Call) e;
            if (isBuiltin(c.name)) {
                return typed(e, builtinCall(c));
            }
            if (c.name.equals("len") && c.args.size() == 1) { // 内建 len：数组或字符串
                T at = expr(c.args.get(0));
                if (at != T.ARRAY && at != T.STRING)
                    errors.add("len 的参数必须是数组或字符串，实际 " + at);
                return typed(e, T.INT);
            }
            List<T> ps = funParams.get(c.name);
            if (ps == null) {
                errors.add("调用了未定义的函数: " + c.name);
                for (Expr a : c.args) expr(a);
                return typed(e, T.INT);
            }
            if (ps.size() != c.args.size()) {
                errors.add("函数 " + c.name + " 参数个数不匹配：期望 " + ps.size() + "，实际 " + c.args.size());
            }
            for (int i = 0; i < c.args.size() && i < ps.size(); i++) {
                T at = expr(c.args.get(i));
                if (at != ps.get(i))
                    errors.add("函数 " + c.name + " 第 " + (i + 1) + " 个实参类型不匹配：期望 "
                            + ps.get(i) + "，实际 " + at);
            }
            T rt = funRet.get(c.name);
            return typed(e, rt == null ? T.INT : rt);
        }
        if (e instanceof ArrayNew) {
            requireInt(expr(((ArrayNew) e).size), "数组大小");
            return typed(e, T.ARRAY);
        }
        if (e instanceof IndexGet) {
            IndexGet g = (IndexGet) e;
            T at = expr(g.array);
            T it = expr(g.index);
            if (at != T.ARRAY) errors.add("下标取值目标必须是数组，实际 " + at);
            requireInt(it, "下标");
            return typed(e, T.INT);
        }
        errors.add("未知表达式节点: " + e);
        return typed(e, T.INT);
    }

    private static boolean isBuiltin(String name) {
        return name.equals("time") || name.equals("readFile") || name.equals("writeFile")
                || name.equals("input") || name.equals("toInt") || name.equals("random")
                || name.equals("str");
    }

    // 标准库内置函数签名检查
    private T builtinCall(Call c) {
        if (c.name.equals("time")) {
            if (c.args.size() != 0)
                errors.add("time 需要 0 个参数，实际 " + c.args.size());
            for (Expr a : c.args) expr(a);
            return T.INT;
        }

        if (c.name.equals("readFile")) {
            if (c.args.size() != 1)
                errors.add("readFile 需要 1 个参数，实际 " + c.args.size());
            for (int i = 0; i < c.args.size(); i++) {
                T at = expr(c.args.get(i));
                if (i == 0 && at != T.STRING)
                    errors.add("readFile 第 1 个参数需要 STRING，实际 " + at);
            }
            return T.STRING;
        }

        if (c.name.equals("writeFile")) {
            if (c.args.size() != 2)
                errors.add("writeFile 需要 2 个参数，实际 " + c.args.size());
            for (int i = 0; i < c.args.size(); i++) {
                T at = expr(c.args.get(i));
                if (i < 2 && at != T.STRING)
                    errors.add("writeFile 第 " + (i + 1) + " 个参数需要 STRING，实际 " + at);
            }
            return T.INT;
        }

        // input() / input(prompt: STRING) -> STRING
        if (c.name.equals("input")) {
            if (c.args.size() > 1)
                errors.add("input 最多接受 1 个提示字符串参数，实际 " + c.args.size());
            for (int i = 0; i < c.args.size(); i++) {
                T at = expr(c.args.get(i));
                if (i == 0 && at != T.STRING)
                    errors.add("input 的提示参数需要 STRING，实际 " + at);
            }
            return T.STRING;
        }

        // toInt(value: STRING) -> INT
        if (c.name.equals("toInt")) {
            if (c.args.size() != 1)
                errors.add("toInt 需要 1 个参数，实际 " + c.args.size());
            for (int i = 0; i < c.args.size(); i++) {
                T at = expr(c.args.get(i));
                if (i == 0 && at != T.STRING)
                    errors.add("toInt 的参数需要 STRING，实际 " + at);
            }
            return T.INT;
        }

        // random(min: INT, max: INT) -> INT（闭区间）
        if (c.name.equals("random")) {
            if (c.args.size() != 2)
                errors.add("random 需要 2 个参数，实际 " + c.args.size());
            for (int i = 0; i < c.args.size(); i++) {
                T at = expr(c.args.get(i));
                if (i < 2 && at != T.INT)
                    errors.add("random 第 " + (i + 1) + " 个参数需要 INT，实际 " + at);
            }
            return T.INT;
        }

        // str(value: INT | STRING) -> STRING
        if (c.name.equals("str")) {
            if (c.args.size() != 1)
                errors.add("str 需要 1 个参数，实际 " + c.args.size());
            for (int i = 0; i < c.args.size(); i++) {
                T at = expr(c.args.get(i));
                if (i == 0 && at != T.INT && at != T.STRING)
                    errors.add("str 的参数需要 INT 或 STRING，实际 " + at);
            }
            return T.STRING;
        }

        return T.INT;
    }
}
