import java.util.ArrayList;
import java.util.List;

// Lisp 风格前端语法分析器（.lisp）
// S-表达式递归转换为现有 AST：
//   (let x 10)                 -> Let
//   (print (+ x 1))            -> Print
//   (if cond then [else])      -> If
//   (while cond body...)       -> While
//   (set x expr)               -> Assign
//   (do form...)               -> Block
//   (defun add (a b) (+ a b))  -> FuncDecl（最后一个表达式隐式 return）
public class ParserLisp {

    private final List<Token> tokens;
    private int pos;

    public ParserLisp(List<Token> tokens) { this.tokens = tokens; }

    private Token peek() { return tokens.get(pos); }
    private Token at(int offset) {
        int i = pos + offset;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }
    private Token next() { return tokens.get(pos++); }
    private boolean check(Token.Type t) { return peek().type == t; }

    private Token expect(Token.Type t) {
        if (!check(t)) {
            throw new RuntimeException("Lisp 语法错误：期望 " + t + "，实际得到 " + peek());
        }
        return next();
    }

    private boolean headIs(Token.Type t) {
        return check(Token.Type.LPAREN) && at(1).type == t;
    }

    private boolean headIsIdent(String name) {
        return check(Token.Type.LPAREN) && at(1).type == Token.Type.IDENT
                && at(1).text.equals(name);
    }

    private boolean isStmtHead(Token.Type t) {
        return t == Token.Type.LET || t == Token.Type.PRINT || t == Token.Type.IF
                || t == Token.Type.WHILE || t == Token.Type.FUNC || t == Token.Type.RETURN;
    }

    public Program parseProgram() {
        Program prog = new Program();
        while (!check(Token.Type.EOF)) {
            Object form = parseAnyForm();
            if (form instanceof Stmt) prog.stmts.add((Stmt) form);
            else prog.stmts.add(new ExprStmt((Expr) form));
        }
        return prog;
    }

    // 根据列表头判断是语句还是表达式
    private Object parseAnyForm() {
        if (check(Token.Type.LPAREN)) {
            Token.Type head = at(1).type;
            if (isStmtHead(head) || headIsIdent("set") || headIsIdent("do")) {
                return parseStmt();
            }
        }
        return parseExpr();
    }

    private Stmt parseStmt() {
        if (!check(Token.Type.LPAREN)) return new ExprStmt(parseExpr());

        Token.Type head = at(1).type;
        if (head == Token.Type.LET)    return parseLet();
        if (head == Token.Type.PRINT)  return parsePrint();
        if (head == Token.Type.IF)     return parseIf();
        if (head == Token.Type.WHILE)  return parseWhile();
        if (head == Token.Type.FUNC)   return parseDefun();
        if (head == Token.Type.RETURN) return parseReturn();
        if (headIsIdent("set"))        return parseSet();
        if (headIsIdent("do"))         return parseDo();

        return new ExprStmt(parseExpr());
    }

    private Stmt parseLet() {
        expect(Token.Type.LPAREN);
        expect(Token.Type.LET);
        Token name = expect(Token.Type.IDENT);
        Expr value = parseExpr();
        expect(Token.Type.RPAREN);
        return new Let(name.text, value);
    }

    private Stmt parsePrint() {
        expect(Token.Type.LPAREN);
        expect(Token.Type.PRINT);
        Expr value = parseExpr();
        expect(Token.Type.RPAREN);
        return new Print(value);
    }

    private Stmt parseSet() {
        expect(Token.Type.LPAREN);
        next(); // set
        Token name = expect(Token.Type.IDENT);
        Expr value = parseExpr();
        expect(Token.Type.RPAREN);
        return new Assign(name.text, value);
    }

    private Stmt parseDo() {
        expect(Token.Type.LPAREN);
        next(); // do
        return parseBlockUntilRParen();
    }

    private Stmt parseIf() {
        expect(Token.Type.LPAREN);
        expect(Token.Type.IF);
        Expr cond = parseExpr();
        Block thenBlock = asBlock(parseAnyForm());

        Stmt els = null;
        if (!check(Token.Type.RPAREN)) els = asBlock(parseAnyForm());
        expect(Token.Type.RPAREN);
        return new If(cond, thenBlock, els);
    }

    private Stmt parseWhile() {
        expect(Token.Type.LPAREN);
        expect(Token.Type.WHILE);
        Expr cond = parseExpr();
        Block body = parseBlockUntilRParen();
        return new While(cond, body);
    }

    private Stmt parseDefun() {
        expect(Token.Type.LPAREN);
        expect(Token.Type.FUNC);
        Token name = expect(Token.Type.IDENT);
        expect(Token.Type.LPAREN);

        List<String> params = new ArrayList<>();
        List<TypeChecker.T> types = new ArrayList<>();
        while (!check(Token.Type.RPAREN)) {
            params.add(expect(Token.Type.IDENT).text);
            types.add(TypeChecker.T.INT);
            if (check(Token.Type.COMMA)) next();
        }
        expect(Token.Type.RPAREN);

        // 函数体：最后一个表达式形态隐式作为 return
        List<Object> forms = new ArrayList<>();
        while (!check(Token.Type.RPAREN) && !check(Token.Type.EOF)) {
            forms.add(parseAnyForm());
        }
        expect(Token.Type.RPAREN);

        Block body = new Block();
        for (int i = 0; i < forms.size(); i++) {
            Object f = forms.get(i);
            if (f instanceof Stmt) {
                body.stmts.add((Stmt) f);
            } else if (i == forms.size() - 1) {
                body.stmts.add(new Return((Expr) f));
            } else {
                body.stmts.add(new ExprStmt((Expr) f));
            }
        }
        return new FuncDecl(name.text, params, types, body);
    }

    private Stmt parseReturn() {
        expect(Token.Type.LPAREN);
        expect(Token.Type.RETURN);
        Expr value = check(Token.Type.RPAREN) ? null : parseExpr();
        expect(Token.Type.RPAREN);
        return new Return(value);
    }

    private Block parseBlockUntilRParen() {
        Block b = new Block();
        while (!check(Token.Type.RPAREN) && !check(Token.Type.EOF)) {
            Object f = parseAnyForm();
            if (f instanceof Stmt) b.stmts.add((Stmt) f);
            else b.stmts.add(new ExprStmt((Expr) f));
        }
        expect(Token.Type.RPAREN);
        return b;
    }

    private Block asBlock(Object form) {
        if (form instanceof Block) return (Block) form;
        Block b = new Block();
        if (form instanceof Stmt) b.stmts.add((Stmt) form);
        else b.stmts.add(new ExprStmt((Expr) form));
        return b;
    }

    // ===== 表达式 =====
    private Expr parseExpr() {
        if (check(Token.Type.NUMBER)) return new Num(Long.parseLong(next().text));
        if (check(Token.Type.STRING)) return new StrLit(next().text);
        if (check(Token.Type.IDENT)) return new Var(next().text);

        if (check(Token.Type.LPAREN)) {
            next();
            if (check(Token.Type.EOF)) throw new RuntimeException("Lisp 语法错误：表达式缺少 )");

            if (isOperator(peek().type)) {
                Token op = next();
                List<Expr> args = new ArrayList<>();
                while (!check(Token.Type.RPAREN)) {
                    if (check(Token.Type.EOF))
                        throw new RuntimeException("Lisp 语法错误：运算符表达式缺少 )");
                    args.add(parseExpr());
                }
                expect(Token.Type.RPAREN);
                return buildOperator(op, args);
            }

            if (check(Token.Type.IDENT)) {
                Token name = next();
                List<Expr> args = new ArrayList<>();
                while (!check(Token.Type.RPAREN)) {
                    if (check(Token.Type.EOF))
                        throw new RuntimeException("Lisp 语法错误：函数调用缺少 )");
                    args.add(parseExpr());
                }
                expect(Token.Type.RPAREN);
                return new Call(name.text, args);
            }

            throw new RuntimeException("Lisp 语法错误：列表头必须是函数名或运算符，实际 " + peek());
        }

        throw new RuntimeException("Lisp 语法错误：意外的 token " + peek());
    }

    private boolean isOperator(Token.Type t) {
        return t == Token.Type.PLUS || t == Token.Type.MINUS || t == Token.Type.MUL
                || t == Token.Type.DIV || t == Token.Type.EQ || t == Token.Type.NE
                || t == Token.Type.LT || t == Token.Type.GT
                || t == Token.Type.LE || t == Token.Type.GE;
    }

    private Expr buildOperator(Token raw, List<Expr> args) {
        if (args.isEmpty())
            throw new RuntimeException("Lisp 运算符 " + raw.text + " 缺少参数");

        String op = raw.text.equals("=") ? "==" : raw.text;
        if (args.size() == 1) {
            if (op.equals("-")) return new Unary('-', args.get(0));
            if (op.equals("+")) return args.get(0);
            throw new RuntimeException("Lisp 运算符 " + op + " 需要至少两个参数");
        }

        Expr e = args.get(0);
        for (int i = 1; i < args.size(); i++) {
            e = new Binary(op, e, args.get(i));
        }
        return e;
    }
}
