import java.util.ArrayList;
import java.util.List;

// Rust 风格前端语法分析器（.rs）
// 生成现有 AST 节点（Let / Print / FuncDecl / ...）。
// 为了支持脚本式测试，若定义了无参 fn main()，会在 Program 末尾追加一次 main() 调用。
public class ParserRs {

    private final List<Token> tokens;
    private int pos;

    public ParserRs(List<Token> tokens) { this.tokens = tokens; }

    private Token peek() { return tokens.get(pos); }
    private Token at(int offset) {
        int i = pos + offset;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }
    private Token next() { return tokens.get(pos++); }
    private boolean check(Token.Type t) { return peek().type == t; }

    private Token expect(Token.Type t) {
        if (!check(t)) {
            throw new RuntimeException("Rust 语法错误：期望 " + t + "，实际得到 " + peek());
        }
        return next();
    }

    private void optionalSemicolon() {
        if (check(Token.Type.SEMICOLON)) next();
    }

    public Program parseProgram() {
        Program prog = new Program();
        boolean hasMain = false;

        while (!check(Token.Type.EOF)) {
            if (check(Token.Type.SEMICOLON)) { next(); continue; }
            Stmt s = parseStmt();
            prog.stmts.add(s);
            if (s instanceof FuncDecl) {
                FuncDecl f = (FuncDecl) s;
                if (f.name.equals("main") && f.params.isEmpty()) hasMain = true;
            }
        }

        // 脚本模式：fn main() 本身仍作为 FuncDecl 保留，额外生成入口调用。
        if (hasMain) {
            prog.stmts.add(new ExprStmt(new Call("main", new ArrayList<>())));
        }
        return prog;
    }

    private Stmt parseStmt() {
        if (check(Token.Type.LET))    return parseLet();
        if (check(Token.Type.FUNC))   return parseFunc();
        if (check(Token.Type.PRINT))  return parsePrint();
        if (check(Token.Type.RETURN)) return parseReturn();
        if (check(Token.Type.IF))     return parseIf();
        if (check(Token.Type.WHILE))  return parseWhile();
        if (check(Token.Type.BREAK)) {
            next();
            optionalSemicolon();
            return new Break();
        }
        if (check(Token.Type.CONTINUE)) {
            next();
            optionalSemicolon();
            return new Continue();
        }

        if (check(Token.Type.IDENT) && at(1).type == Token.Type.EQUALS) {
            return parseAssign();
        }

        Expr e = parseExpr();
        optionalSemicolon();
        return new ExprStmt(e);
    }

    private Stmt parseLet() {
        next(); // let
        if (check(Token.Type.IDENT) && peek().text.equals("mut")) next();
        Token name = expect(Token.Type.IDENT);
        if (check(Token.Type.COLON)) {
            next();
            parseTypeName(); // 类型信息由 TypeChecker 从初始化表达式推断
        }
        expect(Token.Type.EQUALS);
        Expr value = parseExpr();
        optionalSemicolon();
        return new Let(name.text, value);
    }

    private Stmt parsePrint() {
        next(); // println! / print!
        expect(Token.Type.LPAREN);
        Expr value = parseExpr();
        expect(Token.Type.RPAREN);
        optionalSemicolon();
        return new Print(value);
    }

    private Stmt parseAssign() {
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.EQUALS);
        Expr value = parseExpr();
        optionalSemicolon();
        return new Assign(name.text, value);
    }

    private Stmt parseFunc() {
        next(); // fn
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.LPAREN);

        List<String> params = new ArrayList<>();
        List<TypeChecker.T> types = new ArrayList<>();
        if (!check(Token.Type.RPAREN)) {
            parseParam(params, types);
            while (check(Token.Type.COMMA)) { next(); parseParam(params, types); }
        }
        expect(Token.Type.RPAREN);

        // -> int / -> i32 ...
        if (check(Token.Type.MINUS) && at(1).type == Token.Type.GT) {
            next();
            next();
            parseTypeName();
        }

        Block body = parseBraceBlock();
        implicitReturn(body);
        return new FuncDecl(name.text, params, types, body);
    }

    private void parseParam(List<String> names, List<TypeChecker.T> types) {
        names.add(expect(Token.Type.IDENT).text);
        if (check(Token.Type.COLON)) {
            next();
            types.add(parseTypeName());
        } else {
            types.add(TypeChecker.T.INT);
        }
    }

    private TypeChecker.T parseTypeName() {
        Token tn = expect(Token.Type.IDENT);
        TypeChecker.T t = TypeChecker.typeFromName(tn.text);
        // Rust 常见整数类型统一映射到 INT
        return t == null ? TypeChecker.T.INT : t;
    }

    // Rust 尾表达式隐式返回：函数体最后一条 ExprStmt 转为 Return
    private void implicitReturn(Block body) {
        if (body.stmts.isEmpty()) return;
        int last = body.stmts.size() - 1;
        Stmt s = body.stmts.get(last);
        if (s instanceof ExprStmt) {
            body.stmts.set(last, new Return(((ExprStmt) s).expr));
        }
    }

    private Stmt parseReturn() {
        next(); // return
        if (check(Token.Type.SEMICOLON) || check(Token.Type.RBRACE) || check(Token.Type.EOF)) {
            optionalSemicolon();
            return new Return(null);
        }
        Expr value = parseExpr();
        optionalSemicolon();
        return new Return(value);
    }

    private Stmt parseIf() {
        next(); // if
        Expr cond;
        if (check(Token.Type.LPAREN)) {
            next();
            cond = parseExpr();
            expect(Token.Type.RPAREN);
        } else {
            cond = parseExpr();
        }
        Block thenBlock = parseBraceBlock();

        Stmt els = null;
        if (check(Token.Type.ELSE)) {
            next();
            if (check(Token.Type.IF)) els = parseIf();
            else els = parseBraceBlock();
        }
        return new If(cond, thenBlock, els);
    }

    private Stmt parseWhile() {
        next(); // while
        Expr cond;
        if (check(Token.Type.LPAREN)) {
            next();
            cond = parseExpr();
            expect(Token.Type.RPAREN);
        } else {
            cond = parseExpr();
        }
        Block body = parseBraceBlock();
        return new While(cond, body);
    }

    private Block parseBraceBlock() {
        expect(Token.Type.LBRACE);
        Block b = new Block();
        while (!check(Token.Type.RBRACE) && !check(Token.Type.EOF)) {
            if (check(Token.Type.SEMICOLON)) { next(); continue; }
            b.stmts.add(parseStmt());
        }
        expect(Token.Type.RBRACE);
        return b;
    }

    // ===== 表达式（优先级与 .uni 一致）=====
    private Expr parseExpr() { return parseComparison(); }

    private Expr parseComparison() {
        Expr left = parseAddSub();
        while (check(Token.Type.EQ) || check(Token.Type.NE) || check(Token.Type.LT)
                || check(Token.Type.GT) || check(Token.Type.LE) || check(Token.Type.GE)) {
            String op = next().text;
            left = new Binary(op, left, parseAddSub());
        }
        return left;
    }

    private Expr parseAddSub() {
        Expr left = parseTerm();
        while (check(Token.Type.PLUS) || check(Token.Type.MINUS)) {
            String op = next().text;
            left = new Binary(op, left, parseTerm());
        }
        return left;
    }

    private Expr parseTerm() {
        Expr left = parseUnary();
        while (check(Token.Type.MUL) || check(Token.Type.DIV)) {
            String op = next().text;
            left = new Binary(op, left, parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        if (check(Token.Type.PLUS) || check(Token.Type.MINUS)) {
            char op = next().text.charAt(0);
            return new Unary(op, parseUnary());
        }
        return parsePostfix();
    }

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
        if (check(Token.Type.NUMBER)) return new Num(Long.parseLong(next().text));
        if (check(Token.Type.STRING)) return new StrLit(next().text);
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
        throw new RuntimeException("Rust 语法错误：意外的 token " + peek());
    }
}
