import java.util.ArrayList;
import java.util.List;

// Lua 风格前端语法分析器（.lua）
// 生成与 .uni/.py 完全相同的 AST，因此 Codegen / Vm / 后端无需任何改动。
//
// 支持：
//   local x = expr;
//   print(expr);
//   function name(a, b) ... end
//   if cond then ... [else ...] end
//   while cond do ... end
//   return expr;
// 分号可选；块结束统一由 end（词法层映射为 RBRACE）表示。
public class ParserLua {

    private final List<Token> tokens;
    private int pos;

    public ParserLua(List<Token> tokens) { this.tokens = tokens; }

    private Token peek() { return tokens.get(pos); }
    private Token at(int offset) {
        int i = pos + offset;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }
    private Token next() { return tokens.get(pos++); }
    private boolean check(Token.Type t) { return peek().type == t; }

    private Token expect(Token.Type t) {
        if (!check(t)) {
            throw new RuntimeException("Lua 语法错误：期望 " + t + "，实际得到 " + peek());
        }
        return next();
    }

    private void expectWord(String word) {
        if (!check(Token.Type.IDENT) || !peek().text.equals(word)) {
            throw new RuntimeException("Lua 语法错误：期望 " + word + "，实际得到 " + peek());
        }
        next();
    }

    private void expectEnd() {
        if (!check(Token.Type.RBRACE)) {
            throw new RuntimeException("Lua 语法错误：期望 end，实际得到 " + peek());
        }
        next();
    }

    private void optionalSemicolon() {
        if (check(Token.Type.SEMICOLON)) next();
    }

    public Program parseProgram() {
        Program prog = new Program();
        while (!check(Token.Type.EOF)) {
            if (check(Token.Type.SEMICOLON)) { next(); continue; }
            prog.stmts.add(parseStmt());
        }
        return prog;
    }

    private Stmt parseStmt() {
        if (check(Token.Type.LET))    return parseLet();
        if (check(Token.Type.PRINT))  return parsePrint();
        if (check(Token.Type.FUNC))   return parseFunc();
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

        if (check(Token.Type.IDENT)) {
            if (at(1).type == Token.Type.EQUALS) return parseAssign();
            Expr e = parseExpr();
            optionalSemicolon();
            return new ExprStmt(e);
        }

        throw new RuntimeException("Lua 语法错误：意外的 token " + peek());
    }

    private Stmt parseLet() {
        next(); // local
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.EQUALS);
        Expr value = parseExpr();
        optionalSemicolon();
        return new Let(name.text, value);
    }

    private Stmt parsePrint() {
        next(); // print
        Expr value;
        if (check(Token.Type.LPAREN)) {
            next();
            value = parseExpr();
            expect(Token.Type.RPAREN);
        } else {
            value = parseExpr();
        }
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
        next(); // function
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.LPAREN);
        List<String> params = new ArrayList<>();
        List<TypeChecker.T> types = new ArrayList<>();
        if (!check(Token.Type.RPAREN)) {
            parseParam(params, types);
            while (check(Token.Type.COMMA)) { next(); parseParam(params, types); }
        }
        expect(Token.Type.RPAREN);
        Block body = parseEndBlock();
        optionalSemicolon();
        return new FuncDecl(name.text, params, types, body);
    }

    private void parseParam(List<String> names, List<TypeChecker.T> types) {
        names.add(expect(Token.Type.IDENT).text);
        types.add(TypeChecker.T.INT);
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
        Expr cond = parseExpr();
        expectWord("then");
        Block thenBlock = parseStmtListUntilElse();

        Stmt els = null;
        if (check(Token.Type.ELSE)) {
            next();
            els = parseEndBlock();
        } else {
            expectEnd();
        }
        return new If(cond, thenBlock, els);
    }

    private Stmt parseWhile() {
        next(); // while
        Expr cond = parseExpr();
        expectWord("do");
        Block body = parseEndBlock();
        return new While(cond, body);
    }

    // then 分支：停在 else 或 end，不消费它们
    private Block parseStmtListUntilElse() {
        Block b = new Block();
        while (!check(Token.Type.EOF) && !check(Token.Type.RBRACE) && !check(Token.Type.ELSE)) {
            if (check(Token.Type.SEMICOLON)) { next(); continue; }
            b.stmts.add(parseStmt());
        }
        return b;
    }

    // 普通块：消费结尾的 end
    private Block parseEndBlock() {
        Block b = new Block();
        while (!check(Token.Type.EOF) && !check(Token.Type.RBRACE)) {
            if (check(Token.Type.SEMICOLON)) { next(); continue; }
            b.stmts.add(parseStmt());
        }
        expectEnd();
        return b;
    }

    // ===== 表达式（优先级与 .uni 一致）=====
    private Expr parseExpr() { return parseComparison(); }

    private Expr parseComparison() {
        Expr left = parseAddSub();
        while (check(Token.Type.EQ) || check(Token.Type.NE) || check(Token.Type.LT)
                || check(Token.Type.GT) || check(Token.Type.LE) || check(Token.Type.GE)) {
            String op = next().text;
            if (op.equals("~=")) op = "!=";
            Expr right = parseAddSub();
            left = new Binary(op, left, right);
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
        throw new RuntimeException("Lua 语法错误：意外的 token " + peek());
    }
}
