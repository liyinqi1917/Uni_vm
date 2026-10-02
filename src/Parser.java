import java.util.List;

// Parser 递归下降解析器：Token 流 -> AST
// 文法：
//   program := stmt*
//   stmt    := letStmt | printStmt | ifStmt | whileStmt | block
//   ifStmt  := 'if' '(' expr ')' block ('else' (ifStmt | block))?
//   whileStmt:='while' '(' expr ')' block
//   block   := '{' stmt* '}'
//   expr    := comparison
//   comparison := addsub (('=='|'!='|'<'|'>'|'<='|'>=') addsub)*
//   addsub  := term (('+'|'-') term)*
//   term    := unary (('*'|'/') unary)*
//   unary   := ('+'|'-') unary | factor
//   factor  := NUMBER | IDENT | '(' expr ')'
public class Parser {
    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    private Token peek() { return tokens.get(pos); }
    private Token next() { return tokens.get(pos++); }
    private boolean check(Token.Type t) { return peek().type == t; }

    private Token expect(Token.Type t) {
        if (!check(t)) {
            throw new RuntimeException("语法错误：期望 " + t + "，实际得到 " + peek());
        }
        return next();
    }

    public Program parseProgram() {
        Program prog = new Program();
        while (!check(Token.Type.EOF)) {
            prog.stmts.add(parseStmt());
        }
        return prog;
    }

    private Stmt parseStmt() {
        if (check(Token.Type.LET))   return parseLet();
        if (check(Token.Type.PRINT)) return parsePrint();
        if (check(Token.Type.IF))    return parseIf();
        if (check(Token.Type.WHILE)) return parseWhile();
        if (check(Token.Type.FUNC))  return parseFunc();
        if (check(Token.Type.RETURN)) return parseReturn();
        if (check(Token.Type.LBRACE)) return parseBlock();
        // 表达式语句：IDENT(...) 形式的调用
        if (check(Token.Type.IDENT) && tokens.get(pos + 1).type == Token.Type.LPAREN) {
            Expr e = parseFactor();
            expect(Token.Type.SEMICOLON);
            return new ExprStmt(e);
        }
        // 数组赋值：IDENT[index] = expr;
        if (check(Token.Type.IDENT) && tokens.get(pos + 1).type == Token.Type.LBRACKET) {
            Expr target = parsePostfix();
            expect(Token.Type.EQUALS);
            Expr value = parseExpr();
            expect(Token.Type.SEMICOLON);
            IndexGet ig = (IndexGet) target;
            return new IndexSet(ig.array, ig.index, value);
        }
        // 赋值语句：IDENT '=' expr ';'
        if (check(Token.Type.IDENT) && tokens.get(pos + 1).type == Token.Type.EQUALS) {
            Token name = next();
            next(); // '='
            Expr value = parseExpr();
            expect(Token.Type.SEMICOLON);
            return new Assign(name.text, value);
        }
        throw new RuntimeException("语法错误：意外的 token " + peek());
    }

    private Stmt parseFunc() {
        next(); // func
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.LPAREN);
        List<String> params = new java.util.ArrayList<>();
        if (!check(Token.Type.RPAREN)) {
            params.add(expect(Token.Type.IDENT).text);
            while (check(Token.Type.COMMA)) { next(); params.add(expect(Token.Type.IDENT).text); }
        }
        expect(Token.Type.RPAREN);
        Block body = parseBlock();
        return new FuncDecl(name.text, params, body);
    }

    private Stmt parseReturn() {
        next(); // return
        if (check(Token.Type.SEMICOLON)) { next(); return new Return(null); }
        Expr value = parseExpr();
        expect(Token.Type.SEMICOLON);
        return new Return(value);
    }

    private Stmt parseLet() {
        next(); // let
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.EQUALS);
        Expr value = parseExpr();
        expect(Token.Type.SEMICOLON);
        return new Let(name.text, value);
    }

    private Stmt parsePrint() {
        next(); // print
        Expr value = parseExpr();
        expect(Token.Type.SEMICOLON);
        return new Print(value);
    }

    private Stmt parseIf() {
        next(); // if
        expect(Token.Type.LPAREN);
        Expr cond = parseExpr();
        expect(Token.Type.RPAREN);
        Block thenBlock = parseBlock();
        Stmt els = null;
        if (check(Token.Type.ELSE)) {
            next(); // else
            if (check(Token.Type.IF)) els = parseIf();      // else if 链
            else els = parseBlock();                        // else { ... }
        }
        return new If(cond, thenBlock, els);
    }

    private Stmt parseWhile() {
        next(); // while
        expect(Token.Type.LPAREN);
        Expr cond = parseExpr();
        expect(Token.Type.RPAREN);
        Block body = parseBlock();
        return new While(cond, body);
    }

    private Block parseBlock() {
        expect(Token.Type.LBRACE);
        Block b = new Block();
        while (!check(Token.Type.RBRACE)) {
            if (check(Token.Type.EOF)) throw new RuntimeException("语法错误：缺右花括号 }");
            b.stmts.add(parseStmt());
        }
        expect(Token.Type.RBRACE);
        return b;
    }

    // expr 入口：比较层
    private Expr parseExpr() { return parseComparison(); }

    private Expr parseComparison() {
        Expr left = parseAddSub();
        while (check(Token.Type.EQ) || check(Token.Type.NE) || check(Token.Type.LT)
            || check(Token.Type.GT) || check(Token.Type.LE) || check(Token.Type.GE)) {
            String op = next().text;
            Expr right = parseAddSub();
            left = new Binary(op, left, right);
        }
        return left;
    }

    private Expr parseAddSub() {
        Expr left = parseTerm();
        while (check(Token.Type.PLUS) || check(Token.Type.MINUS)) {
            String op = next().text;
            Expr right = parseTerm();
            left = new Binary(op, left, right);
        }
        return left;
    }

    private Expr parseTerm() {
        Expr left = parseUnary();
        while (check(Token.Type.MUL) || check(Token.Type.DIV)) {
            String op = next().text;
            Expr right = parseUnary();
            left = new Binary(op, left, right);
        }
        return left;
    }

    private Expr parseUnary() {
        if (check(Token.Type.PLUS) || check(Token.Type.MINUS)) {
            char op = next().text.charAt(0);
            Expr operand = parseUnary();
            return new Unary(op, operand);
        }
        return parsePostfix();
    }

    // 后缀：下标 [index]（可链式）
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
            Token t = next();
            if (check(Token.Type.LPAREN)) {
                next();
                List<Expr> args = new java.util.ArrayList<>();
                if (!check(Token.Type.RPAREN)) {
                    args.add(parseExpr());
                    while (check(Token.Type.COMMA)) { next(); args.add(parseExpr()); }
                }
                expect(Token.Type.RPAREN);
                return new Call(t.text, args);
            }
            return new Var(t.text);
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
