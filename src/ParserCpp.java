import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// C++ 原生语法前端。
//
// 支持当前 UniVM 能表达的 C++ 子集：
//   - #include / #define 等预处理行（由 LexerCpp 跳过）
//   - using namespace std;
//   - int main() / 普通函数 / 局部与全局变量
//   - cout << a << b << endl;  -> Print(...)
//   - cin >> x >> y;          -> input() + toInt() 赋值
//   - if / else / while / for / break / continue / return
//   - std::cout / std::cin / std::endl / std::string
//
// 类型模型仍沿用 UniVM 的 INT / ARRAY / STRING；C++ 的 int/long/bool/char
// 等整数家族统一映射为 INT，string 映射为 STRING。
public class ParserCpp {

    private static final Set<String> TYPE_WORDS = Set.of(
            "int", "long", "short", "char", "bool", "byte",
            "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t",
            "uint8_t", "uint16_t", "uint32_t", "uint64_t",
            "unsigned", "signed", "const", "constexpr", "static", "mutable",
            "volatile", "auto", "void", "string"
    );

    private static final Set<String> UNSUPPORTED_TYPES = Set.of("float", "double", "long double");

    private final List<Token> tokens;
    private int pos;
    private boolean inMain;
    private boolean sawMain;
    private final Deque<Map<String, TypeChecker.T>> typeScopes = new ArrayDeque<>();

    public ParserCpp(List<Token> tokens) {
        this.tokens = tokens;
    }

    private Token peek() { return tokens.get(pos); }
    private Token at(int offset) {
        int i = pos + offset;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }
    private Token next() { return tokens.get(pos++); }
    private boolean check(Token.Type t) { return peek().type == t; }
    private boolean match(Token.Type t) {
        if (!check(t)) return false;
        pos++;
        return true;
    }
    private Token expect(Token.Type t) {
        if (!check(t)) {
            throw new RuntimeException("C++ 语法错误：期望 " + t + "，实际得到 " + peek());
        }
        return next();
    }

    private void pushTypeScope() { typeScopes.push(new HashMap<>()); }
    private void popTypeScope() { typeScopes.pop(); }

    private void declareType(String name, TypeChecker.T type) {
        if (typeScopes.isEmpty()) pushTypeScope();
        typeScopes.peek().put(name, type);
    }

    private TypeChecker.T lookupType(String name) {
        for (Map<String, TypeChecker.T> scope : typeScopes) {
            TypeChecker.T t = scope.get(name);
            if (t != null) return t;
        }
        return TypeChecker.T.INT;
    }

    public Program parseProgram() {
        Program program = new Program();
        pushTypeScope();

        while (!check(Token.Type.EOF)) {
            if (match(Token.Type.SEMICOLON)) continue;
            if (isUsingDirective()) {
                skipUsingDirective();
                continue;
            }

            Stmt stmt;
            if (isTypeStart()) {
                stmt = parseTypedDeclOrFunc();
            } else {
                stmt = parseStmt();
            }
            addTopLevel(program, stmt);
        }

        popTypeScope();
        return program;
    }

    private void addTopLevel(Program program, Stmt stmt) {
        if (stmt instanceof FuncDecl) {
            FuncDecl f = (FuncDecl) stmt;
            if (f.name.equals("main")) {
                if (sawMain) throw new RuntimeException("C++ 前端只允许一个 main()");
                sawMain = true;
                if (!f.params.isEmpty())
                    throw new RuntimeException("C++ main() 暂不支持参数");
                program.stmts.addAll(f.body.stmts);
                return;
            }
        }
        program.stmts.add(stmt);
    }

    private boolean isUsingDirective() {
        return check(Token.Type.IDENT) && peek().text.equals("using");
    }

    private void skipUsingDirective() {
        while (!check(Token.Type.SEMICOLON) && !check(Token.Type.EOF)) next();
        expect(Token.Type.SEMICOLON);
    }

    private boolean isTypeStart() {
        if (!check(Token.Type.IDENT)) return false;
        String word = peek().text;
        if (UNSUPPORTED_TYPES.contains(word)) return true;
        if (TYPE_WORDS.contains(word)) return true;
        return word.equals("std")
                && at(1).type == Token.Type.SCOPE
                && at(2).type == Token.Type.IDENT
                && (at(2).text.equals("string") || UNSUPPORTED_TYPES.contains(at(2).text));
    }

    private TypeChecker.T parseType() {
        TypeChecker.T type = TypeChecker.T.INT;
        boolean any = false;

        while (true) {
            if (check(Token.Type.IDENT) && peek().text.equals("std")
                    && at(1).type == Token.Type.SCOPE
                    && at(2).type == Token.Type.IDENT) {
                next(); // std
                next(); // ::
                Token base = expect(Token.Type.IDENT);
                any = true;
                if (base.text.equals("string")) {
                    type = TypeChecker.T.STRING;
                } else if (UNSUPPORTED_TYPES.contains(base.text)) {
                    throw new RuntimeException("C++ 前端暂不支持浮点类型: " + base.text);
                } else {
                    type = TypeChecker.T.INT;
                }
                continue;
            }

            if (!check(Token.Type.IDENT)) break;
            String word = peek().text;
            if (UNSUPPORTED_TYPES.contains(word))
                throw new RuntimeException("C++ 前端暂不支持浮点类型: " + word);
            if (!TYPE_WORDS.contains(word)) break;

            next();
            any = true;
            if (word.equals("string")) type = TypeChecker.T.STRING;
            else if (word.equals("void")) type = TypeChecker.T.INT;
            else type = TypeChecker.T.INT;
        }

        if (!any) throw new RuntimeException("C++ 语法错误：这里需要类型声明，实际得到 " + peek());
        return type;
    }

    private Stmt parseTypedDeclOrFunc() {
        TypeChecker.T type = parseType();
        Token name = expect(Token.Type.IDENT);

        if (check(Token.Type.LPAREN)) {
            return parseFunction(name, type);
        }
        return parseVariableDecl(type, name);
    }

    private Stmt parseVariableDecl(TypeChecker.T type, Token firstName) {
        StmtSeq seq = new StmtSeq();
        Token name = firstName;

        while (true) {
            if (check(Token.Type.LBRACKET)) {
                throw new RuntimeException("C++ 前端暂不支持数组声明: " + name.text);
            }

            Expr init;
            if (match(Token.Type.EQUALS)) {
                init = parseExpr();
            } else {
                init = type == TypeChecker.T.STRING ? new StrLit("") : new Num(0);
            }

            declareType(name.text, type);
            seq.stmts.add(new Let(name.text, init));

            if (!match(Token.Type.COMMA)) break;
            name = expect(Token.Type.IDENT);
        }

        expect(Token.Type.SEMICOLON);
        return seq.stmts.size() == 1 ? seq.stmts.get(0) : seq;
    }

    private Stmt parseFunction(Token name, TypeChecker.T returnType) {
        expect(Token.Type.LPAREN);

        List<String> params = new ArrayList<>();
        List<TypeChecker.T> paramTypes = new ArrayList<>();

        if (!check(Token.Type.RPAREN)) {
            if (check(Token.Type.IDENT) && peek().text.equals("void")
                    && at(1).type == Token.Type.RPAREN) {
                next();
            } else {
                parseParam(params, paramTypes);
                while (match(Token.Type.COMMA)) parseParam(params, paramTypes);
            }
        }
        expect(Token.Type.RPAREN);

        // 函数原型：只登记签名，不生成函数体。
        if (match(Token.Type.SEMICOLON)) return new StmtSeq();

        if (returnType == TypeChecker.T.STRING)
            throw new RuntimeException("C++ 前端暂不支持 string 返回值: " + name.text);

        pushTypeScope();
        for (int i = 0; i < params.size(); i++) declareType(params.get(i), paramTypes.get(i));

        boolean oldInMain = inMain;
        if (name.text.equals("main")) inMain = true;
        Block body = parseBlock();
        inMain = oldInMain;

        popTypeScope();
        return new FuncDecl(name.text, params, paramTypes, body);
    }

    private void parseParam(List<String> params, List<TypeChecker.T> types) {
        TypeChecker.T type = parseType();
        if (type == TypeChecker.T.STRING)
            throw new RuntimeException("C++ 前端暂不支持 string 参数（UniVM 函数参数目前只支持整数/数组）");

        if (check(Token.Type.LBRACKET)) {
            next();
            expect(Token.Type.RBRACKET);
            type = TypeChecker.T.ARRAY;
        }

        Token name = expect(Token.Type.IDENT);
        params.add(name.text);
        types.add(type);
    }

    private Stmt parseStmt() {
        if (check(Token.Type.LBRACE)) return parseBlock();
        if (isUsingDirective()) {
            skipUsingDirective();
            return new StmtSeq();
        }
        if (isTypeStart()) return parseTypedDeclOrFunc();

        if (check(Token.Type.IF)) return parseIf();
        if (check(Token.Type.WHILE)) return parseWhile();
        if (check(Token.Type.FOR)) return parseFor();
        if (check(Token.Type.RETURN)) return parseReturn();
        if (check(Token.Type.BREAK)) {
            next();
            expect(Token.Type.SEMICOLON);
            return new Break();
        }
        if (check(Token.Type.CONTINUE)) {
            next();
            expect(Token.Type.SEMICOLON);
            return new Continue();
        }

        if (isQualifiedName("cout")) return parseCout();
        if (isQualifiedName("cin")) return parseCin();

        if (check(Token.Type.INC) || check(Token.Type.DEC)
                || (check(Token.Type.IDENT) && isAssignOp(at(1).type))) {
            return parseAssignLike(true);
        }

        Expr expr = parseExpr();
        expect(Token.Type.SEMICOLON);
        return new ExprStmt(expr);
    }

    private Block parseBlock() {
        expect(Token.Type.LBRACE);
        pushTypeScope();
        Block block = new Block();
        while (!check(Token.Type.RBRACE) && !check(Token.Type.EOF)) {
            if (match(Token.Type.SEMICOLON)) continue;
            Stmt stmt = parseStmt();
            if (stmt instanceof StmtSeq && ((StmtSeq) stmt).stmts.isEmpty()) continue;
            block.stmts.add(stmt);
        }
        expect(Token.Type.RBRACE);
        popTypeScope();
        return block;
    }

    private Block parseBody() {
        if (check(Token.Type.LBRACE)) return parseBlock();
        Block block = new Block();
        Stmt stmt = parseStmt();
        if (!(stmt instanceof StmtSeq) || !((StmtSeq) stmt).stmts.isEmpty()) block.stmts.add(stmt);
        return block;
    }

    private Stmt parseIf() {
        next();
        expect(Token.Type.LPAREN);
        Expr cond = parseExpr();
        expect(Token.Type.RPAREN);
        Block thenBlock = parseBody();

        Stmt els = null;
        if (match(Token.Type.ELSE)) {
            if (check(Token.Type.IF)) els = parseIf();
            else els = parseBody();
        }
        return new If(cond, thenBlock, els);
    }

    private Stmt parseWhile() {
        next();
        expect(Token.Type.LPAREN);
        Expr cond = parseExpr();
        expect(Token.Type.RPAREN);
        return new While(cond, parseBody());
    }

    private Stmt parseFor() {
        next();
        expect(Token.Type.LPAREN);

        Stmt init = null;
        if (!check(Token.Type.SEMICOLON)) {
            if (isTypeStart()) {
                init = parseTypedDeclOrFunc();
                if (init instanceof FuncDecl)
                    throw new RuntimeException("for 初始化部分不能是函数声明");
            } else {
                init = parseAssignLike(false);
                expect(Token.Type.SEMICOLON);
            }
        } else {
            next();
        }

        Expr cond = check(Token.Type.SEMICOLON) ? new Num(1) : parseExpr();
        expect(Token.Type.SEMICOLON);

        Stmt update = null;
        if (!check(Token.Type.RPAREN)) {
            update = parseAssignLike(false);
        }
        expect(Token.Type.RPAREN);

        Block body = parseBody();
        if (update != null) {
            injectUpdateBeforeContinue(body, update);
            body.stmts.add(update);
        }

        Block outer = new Block();
        if (init instanceof StmtSeq) outer.stmts.addAll(((StmtSeq) init).stmts);
        else if (init != null) outer.stmts.add(init);
        outer.stmts.add(new While(cond, body));
        return outer;
    }

    // for 循环被降级为 while；为了保持 C++ 的 continue 语义，
    // 在循环体中的 continue 前插入 update 语句。嵌套循环不递归。
    private void injectUpdateBeforeContinue(Block block, Stmt update) {
        for (int i = 0; i < block.stmts.size(); i++) {
            block.stmts.set(i, injectUpdateBeforeContinue(block.stmts.get(i), update));
        }
    }

    private Stmt injectUpdateBeforeContinue(Stmt stmt, Stmt update) {
        if (stmt instanceof Continue) {
            StmtSeq seq = new StmtSeq();
            seq.stmts.add(update);
            seq.stmts.add(stmt);
            return seq;
        }
        if (stmt instanceof Block) {
            injectUpdateBeforeContinue((Block) stmt, update);
            return stmt;
        }
        if (stmt instanceof StmtSeq) {
            StmtSeq seq = (StmtSeq) stmt;
            for (int i = 0; i < seq.stmts.size(); i++) {
                seq.stmts.set(i, injectUpdateBeforeContinue(seq.stmts.get(i), update));
            }
            return seq;
        }
        if (stmt instanceof If) {
            If branch = (If) stmt;
            injectUpdateBeforeContinue(branch.thenBlock, update);
            Stmt newElse = branch.els == null ? null : injectUpdateBeforeContinue(branch.els, update);
            if (newElse == branch.els) return stmt;
            return new If(branch.cond, branch.thenBlock, newElse);
        }
        // Continue 在嵌套 while/for 中属于内层循环，不在这里注入。
        return stmt;
    }

    private Stmt parseReturn() {
        next();
        if (inMain) {
            if (!check(Token.Type.SEMICOLON) && !check(Token.Type.RBRACE)) parseExpr();
            expect(Token.Type.SEMICOLON);
            return new StmtSeq();
        }
        if (match(Token.Type.SEMICOLON)) return new Return(null);
        Expr value = parseExpr();
        expect(Token.Type.SEMICOLON);
        return new Return(value);
    }

    private boolean isAssignOp(Token.Type t) {
        return t == Token.Type.EQUALS || t == Token.Type.PLUSEQ
                || t == Token.Type.MINUSEQ || t == Token.Type.MULEQ
                || t == Token.Type.DIVEQ || t == Token.Type.INC
                || t == Token.Type.DEC;
    }

    // requireSemi=false 供 for 的初始化/更新部分复用。
    private Stmt parseAssignLike(boolean requireSemi) {
        if (check(Token.Type.INC) || check(Token.Type.DEC)) {
            boolean inc = check(Token.Type.INC);
            next();
            Token name = expect(Token.Type.IDENT);
            Expr value = new Binary(inc ? "+" : "-", new Var(name.text), new Num(1));
            if (requireSemi) expect(Token.Type.SEMICOLON);
            return new Assign(name.text, value);
        }

        if (check(Token.Type.IDENT) && isAssignOp(at(1).type)) {
            Token name = next();
            Token.Type op = next().type;

            if (op == Token.Type.INC || op == Token.Type.DEC) {
                Expr value = new Binary(op == Token.Type.INC ? "+" : "-",
                        new Var(name.text), new Num(1));
                if (requireSemi) expect(Token.Type.SEMICOLON);
                return new Assign(name.text, value);
            }

            Expr rhs = parseExpr();
            Expr value;
            switch (op) {
                case EQUALS: value = rhs; break;
                case PLUSEQ: value = new Binary("+", new Var(name.text), rhs); break;
                case MINUSEQ: value = new Binary("-", new Var(name.text), rhs); break;
                case MULEQ: value = new Binary("*", new Var(name.text), rhs); break;
                case DIVEQ: value = new Binary("/", new Var(name.text), rhs); break;
                default: throw new RuntimeException("不支持的 C++ 赋值运算符");
            }
            if (requireSemi) expect(Token.Type.SEMICOLON);
            return new Assign(name.text, value);
        }

        Expr expr = parseExpr();
        if (requireSemi) expect(Token.Type.SEMICOLON);
        return new ExprStmt(expr);
    }

    private boolean isQualifiedName(String name) {
        if (check(Token.Type.IDENT) && peek().text.equals(name)) return true;
        return check(Token.Type.IDENT) && peek().text.equals("std")
                && at(1).type == Token.Type.SCOPE
                && at(2).type == Token.Type.IDENT
                && at(2).text.equals(name);
    }

    private void consumeQualifiedName(String name) {
        if (check(Token.Type.IDENT) && peek().text.equals("std")
                && at(1).type == Token.Type.SCOPE) {
            next();
            next();
        }
        Token token = expect(Token.Type.IDENT);
        if (!token.text.equals(name))
            throw new RuntimeException("C++ 语法错误：期望 " + name + "，实际得到 " + token.text);
    }

    private Stmt parseCout() {
        consumeQualifiedName("cout");
        expect(Token.Type.SHL);

        StmtSeq seq = new StmtSeq();
        Expr acc = new StrLit("");
        boolean hasValue = false;

        while (true) {
            if (isQualifiedName("endl")) {
                consumeQualifiedName("endl");
                seq.stmts.add(new Print(acc));
                acc = new StrLit("");
                hasValue = false;
            } else {
                Expr value = parseExpr();
                acc = new Binary("+", acc, value);
                hasValue = true;
            }
            if (!match(Token.Type.SHL)) break;
        }
        expect(Token.Type.SEMICOLON);

        if (hasValue || seq.stmts.isEmpty()) seq.stmts.add(new Print(acc));
        if (seq.stmts.size() == 1) return seq.stmts.get(0);
        return seq;
    }

    private Stmt parseCin() {
        consumeQualifiedName("cin");
        expect(Token.Type.SHR);

        StmtSeq seq = new StmtSeq();
        while (true) {
            Token target = expect(Token.Type.IDENT);
            TypeChecker.T type = lookupType(target.text);
            if (type == TypeChecker.T.ARRAY)
                throw new RuntimeException("cin 暂不支持数组变量: " + target.text);

            Expr input = new Call("input", List.of(new StrLit("")));
            if (type != TypeChecker.T.STRING) {
                input = new Call("toInt", List.of(input));
            }
            seq.stmts.add(new Assign(target.text, input));

            if (!match(Token.Type.SHR)) break;
        }
        expect(Token.Type.SEMICOLON);
        if (seq.stmts.size() == 1) return seq.stmts.get(0);
        return seq;
    }

    // ===== 表达式：与 UniVM 现有 AST 对齐 =====
    private Expr parseExpr() { return parseLogicalOr(); }

    private Expr parseLogicalOr() {
        Expr left = parseLogicalAnd();
        while (match(Token.Type.OROR)) {
            left = new Binary("!=",
                    new Binary("+", toBool(left), toBool(parseLogicalAnd())),
                    new Num(0));
        }
        return left;
    }

    private Expr parseLogicalAnd() {
        Expr left = parseComparison();
        while (match(Token.Type.ANDAND)) {
            left = new Binary("*", toBool(left), toBool(parseComparison()));
        }
        return left;
    }

    private Expr toBool(Expr e) {
        return new Binary("!=", e, new Num(0));
    }

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
        if (check(Token.Type.NOT)) {
            next();
            return new Binary("==", parseUnary(), new Num(0));
        }
        if (check(Token.Type.PLUS) || check(Token.Type.MINUS)) {
            char op = next().text.charAt(0);
            return new Unary(op, parseUnary());
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr expr = parsePrimary();
        while (match(Token.Type.LBRACKET)) {
            Expr index = parseExpr();
            expect(Token.Type.RBRACKET);
            expr = new IndexGet(expr, index);
        }
        return expr;
    }

    private Expr parsePrimary() {
        if (match(Token.Type.NUMBER)) {
            return new Num(Long.parseLong(tokens.get(pos - 1).text));
        }
        if (match(Token.Type.STRING)) {
            return new StrLit(tokens.get(pos - 1).text);
        }
        if (match(Token.Type.LPAREN)) {
            Expr expr = parseExpr();
            expect(Token.Type.RPAREN);
            return expr;
        }
        if (check(Token.Type.IDENT)) {
            Token name = next();
            if (name.text.equals("true")) return new Num(1);
            if (name.text.equals("false") || name.text.equals("nullptr")) return new Num(0);
            if (match(Token.Type.LPAREN)) {
                List<Expr> args = new ArrayList<>();
                if (!check(Token.Type.RPAREN)) {
                    args.add(parseExpr());
                    while (match(Token.Type.COMMA)) args.add(parseExpr());
                }
                expect(Token.Type.RPAREN);
                return new Call(name.text, args);
            }
            return new Var(name.text);
        }
        throw new RuntimeException("C++ 语法错误：无法解析表达式，实际得到 " + peek());
    }
}
