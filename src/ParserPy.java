import java.util.ArrayList;
import java.util.List;

// Python 风格前端语法分析器（M5）
// 消费 INDENT/DEDENT/NEWLINE/COLON，构建与 .uni 完全相同的 Ast.Program，
// 因此 Codegen / Vm / Jit / 全部后端直接复用 —— 这就是"多前端、单字节码"。
//
// 语法：
//   简单语句：赋值 / print(e) / return [e] / 调用语句，均以 NEWLINE 结束
//   复合语句头以 ':' 结束，块为 NEWLINE INDENT 语句+ DEDENT
public class ParserPy {

    private final List<Token> tokens;
    private int pos = 0;

    public ParserPy(List<Token> tokens) { this.tokens = tokens; }

    private Token peek() { return tokens.get(pos); }
    private Token next() { return tokens.get(pos++); }
    private boolean check(Token.Type t) { return peek().type == t; }
    private Token expect(Token.Type t) {
        if (!check(t)) throw new RuntimeException("语法错误：期望 " + t + "，实际 " + peek());
        return next();
    }
    private void skipNewlines() { while (check(Token.Type.NEWLINE)) next(); }

    public Program parseProgram() {
        Program prog = new Program();
        skipNewlines();
        while (!check(Token.Type.EOF)) {
            prog.stmts.add(parseStmt());
            skipNewlines();
        }
        return prog;
    }

    private Stmt parseStmt() {
        if (check(Token.Type.PRINT)) {
            next();
            expect(Token.Type.LPAREN);
            Expr e = parseExpr();
            expect(Token.Type.RPAREN);
            expect(Token.Type.NEWLINE);
            return new Print(e);
        }
        if (check(Token.Type.RETURN)) {
            next();
            Expr e = check(Token.Type.NEWLINE) ? null : parseExpr();
            expect(Token.Type.NEWLINE);
            return new Return(e);
        }
        if (check(Token.Type.IF)) return parseIf();
        if (check(Token.Type.WHILE)) return parseWhile();
        if (check(Token.Type.DEF)) return parseDef();

        // 简单语句：先解析表达式，再看是否 '=' 赋值
        Expr left = parseExpr();
        if (check(Token.Type.EQUALS)) {
            next();
            Expr rhs = parseExpr();
            expect(Token.Type.NEWLINE);
            if (left instanceof Var) return new Assign(((Var) left).name, rhs);
            if (left instanceof IndexGet) {
                IndexGet ig = (IndexGet) left;
                return new IndexSet(ig.array, ig.index, rhs);
            }
            throw new RuntimeException("赋值目标非法: " + left);
        }
        expect(Token.Type.NEWLINE);
        return new ExprStmt(left);
    }

    private Stmt parseIf() {
        next(); // if
        Expr cond = parseExpr();
        expect(Token.Type.COLON);
        Block thenBlock = parseBlock();

        Stmt els = null;
        if (check(Token.Type.ELIF)) {
            next();
            Expr ec = parseExpr();
            expect(Token.Type.COLON);
            Block eb = parseBlock();
            // 递归处理后续 elif / else
            Stmt tail = buildIfTail();
            els = new If(ec, eb, tail);
        } else if (check(Token.Type.ELSE)) {
            next();
            expect(Token.Type.COLON);
            els = parseBlock();
        }
        return new If(cond, thenBlock, els);
    }

    // 已消费 elif/else 判定后，构造 else 分支（供 elif 链尾部）
    private Stmt buildIfTail() {
        if (check(Token.Type.ELIF)) {
            next();
            Expr ec = parseExpr();
            expect(Token.Type.COLON);
            Block eb = parseBlock();
            return new If(ec, eb, buildIfTail());
        }
        if (check(Token.Type.ELSE)) {
            next();
            expect(Token.Type.COLON);
            return parseBlock();
        }
        return null;
    }

    private Stmt parseWhile() {
        next();
        Expr cond = parseExpr();
        expect(Token.Type.COLON);
        Block body = parseBlock();
        return new While(cond, body);
    }

    private Stmt parseDef() {
        next(); // def
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.LPAREN);
        List<String> params = new ArrayList<>();
        if (!check(Token.Type.RPAREN)) {
            params.add(expect(Token.Type.IDENT).text);
            while (check(Token.Type.COMMA)) { next(); params.add(expect(Token.Type.IDENT).text); }
        }
        expect(Token.Type.RPAREN);
        expect(Token.Type.COLON);
        Block body = parseBlock();
        return new FuncDecl(name.text, params, body);
    }

    // ':' NEWLINE INDENT stmt+ DEDENT
    private Block parseBlock() {
        expect(Token.Type.NEWLINE);
        expect(Token.Type.INDENT);
        Block b = new Block();
        skipNewlines();
        while (!check(Token.Type.DEDENT) && !check(Token.Type.EOF)) {
            b.stmts.add(parseStmt());
            skipNewlines();
        }
        expect(Token.Type.DEDENT);
        return b;
    }

    // ===== 表达式（优先级与 .uni 一致）=====
    private Expr parseExpr() { return parseComparison(); }

    private Expr parseComparison() {
        Expr left = parseAddSub();
        while (true) {
            String op;
            switch (peek().type) {
                case EQ: op = "=="; break;
                case NE: op = "!="; break;
                case LT: op = "<"; break;
                case GT: op = ">"; break;
                case LE: op = "<="; break;
                case GE: op = ">="; break;
                default: return left;
            }
            next();
            Expr right = parseAddSub();
            left = new Binary(op, left, right);
        }
    }

    private Expr parseAddSub() {
        Expr left = parseMulDiv();
        while (check(Token.Type.PLUS) || check(Token.Type.MINUS)) {
            String op = next().text;
            Expr right = parseMulDiv();
            left = new Binary(op, left, right);
        }
        return left;
    }

    private Expr parseMulDiv() {
        Expr left = parseUnary();
        while (check(Token.Type.MUL) || check(Token.Type.DIV)) {
            String op = next().text;
            Expr right = parseUnary();
            left = new Binary(op, left, right);
        }
        return left;
    }

    private Expr parseUnary() {
        if (check(Token.Type.MINUS)) { next(); return new Unary('-', parseUnary()); }
        if (check(Token.Type.PLUS))  { next(); return new Unary('+', parseUnary()); }
        return parsePostfix();
    }

    // 后缀：下标 [index]
    private Expr parsePostfix() {
        Expr e = parseFactor();
        while (check(Token.Type.LBRACKET)) {
            next();
            Expr idx = parseExpr();
            expect(Token.Type.RBRACKET);
            e = new IndexGet(e, idx);
        }
        return e;
    }

    private Expr parseFactor() {
        // 数组分配 [size]
        if (check(Token.Type.LBRACKET)) {
            next();
            Expr size = parseExpr();
            expect(Token.Type.RBRACKET);
            return new ArrayNew(size);
        }
        if (check(Token.Type.NUMBER)) return new Num(Long.parseLong(next().text));
        if (check(Token.Type.IDENT)) {
            Token name = next();
            if (check(Token.Type.LPAREN)) {
                next();
                List<Expr> args = new ArrayList<>();
                if (!check(Token.Type.RPAREN)) {
                    args.add(parseExpr());
                    while (check(Token.Type.COMMA)) { next(); args.add(parseExpr()); }
                }
                expect(Token.Type.RPAREN);
                return new Call(name.text, args);
            }
            return new Var(name.text);
        }
        if (check(Token.Type.LPAREN)) {
            next();
            Expr e = parseExpr();
            expect(Token.Type.RPAREN);
            return e;
        }
        throw new RuntimeException("语法错误：意外的 token " + peek());
    }
}
