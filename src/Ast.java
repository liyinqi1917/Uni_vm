import java.util.ArrayList;
import java.util.List;

// ===== AST 节点 =====

// 表达式基类
abstract class Expr {}

// 整数常量
class Num extends Expr {
    final long value;
    Num(long value) { this.value = value; }
}

// 变量引用
class Var extends Expr {
    final String name;
    Var(String name) { this.name = name; }
}

// 一元运算（'-' 取反；'+' 为 no-op）
class Unary extends Expr {
    final char op;
    final Expr operand;
    Unary(char op, Expr operand) { this.op = op; this.operand = operand; }
}

// 二元运算：算术 + - * / 与比较 == != < > <= >=（op 统一用 String 以容纳双字符）
class Binary extends Expr {
    final String op;
    final Expr left;
    final Expr right;
    Binary(String op, Expr left, Expr right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }
}

// ===== 语句 =====
abstract class Stmt {}

// let name = expr;
class Let extends Stmt {
    final String name;
    final Expr value;
    Let(String name, Expr value) { this.name = name; this.value = value; }
}

// 赋值 name = expr;（变量已由 let 声明；VM 中变量默认 0）
class Assign extends Stmt {
    final String name;
    final Expr value;
    Assign(String name, Expr value) { this.name = name; this.value = value; }
}

// print expr;
class Print extends Stmt {
    final Expr value;
    Print(Expr value) { this.value = value; }
}

// 表达式语句（调用当语句，丢弃返回值）：f(args);
class ExprStmt extends Stmt {
    final Expr expr;
    ExprStmt(Expr expr) { this.expr = expr; }
}

// 代码块 { stmt* }
class Block extends Stmt {
    final List<Stmt> stmts = new ArrayList<>();
}

// if (cond) thenBlock [else els]
// els 为 Block 或 If（else if 链），无 else 时为 null
class If extends Stmt {
    final Expr cond;
    final Block thenBlock;
    final Stmt els;
    If(Expr cond, Block thenBlock, Stmt els) {
        this.cond = cond;
        this.thenBlock = thenBlock;
        this.els = els;
    }
}

// while (cond) body
class While extends Stmt {
    final Expr cond;
    final Block body;
    While(Expr cond, Block body) {
        this.cond = cond;
        this.body = body;
    }
}

// 函数声明 func name(p0, p1, ...) body（仅顶层）
class FuncDecl extends Stmt {
    final String name;
    final List<String> params;
    final List<TypeChecker.T> paramTypes; // 与 params 等长；未标注参数默认 INT（M7：数组作实参）
    final Block body;
    FuncDecl(String name, List<String> params, List<TypeChecker.T> paramTypes, Block body) {
        this.name = name;
        this.params = params;
        this.paramTypes = paramTypes;
        this.body = body;
    }
}

// return [expr];  expr 为 null 时返回 0
class Return extends Stmt {
    final Expr value;
    Return(Expr value) { this.value = value; }
}

// 函数调用 name(arg0, arg1, ...)
class Call extends Expr {
    final String name;
    final List<Expr> args;
    Call(String name, List<Expr> args) {
        this.name = name;
        this.args = args;
    }
}

// 数组分配 [size]：元素为 int，引用类型（M6）
class ArrayNew extends Expr {
    final Expr size;
    ArrayNew(Expr size) { this.size = size; }
}

// 数组取值 array[index]
class IndexGet extends Expr {
    final Expr array, index;
    IndexGet(Expr array, Expr index) { this.array = array; this.index = index; }
}

// 数组赋值 array[index] = value（语句）
class IndexSet extends Stmt {
    final Expr array, index, value;
    IndexSet(Expr array, Expr index, Expr value) {
        this.array = array; this.index = index; this.value = value;
    }
}

// 程序 = 语句列表
class Program {
    final List<Stmt> stmts = new ArrayList<>();
}
