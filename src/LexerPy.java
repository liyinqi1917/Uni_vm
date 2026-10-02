import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// Python 风格前端词法分析器（M5）
// 与 .uni Lexer 的区别：缩进决定块 —— 在行首把前导空格转成
// INDENT / DEDENT，逻辑行末产生 NEWLINE，头部 ':' 产生 COLON。
// 关键字：def / if / elif / else / while / return / print；无 let / func / 花括号 / 分号。
public class LexerPy {

    private final String src;
    private int pos = 0;
    private final List<Token> out = new ArrayList<>();

    public LexerPy(String src) { this.src = src; }

    public List<Token> scanAllTokens() {
        Deque<Integer> indentStack = new ArrayDeque<>();
        indentStack.push(0);

        String[] lines = src.split("\n", -1);
        for (String rawLine : lines) {
            // 去掉 # 行注释
            int hash = rawLine.indexOf('#');
            String line = hash >= 0 ? rawLine.substring(0, hash) : rawLine;

            // 计算缩进（空格计 1，Tab 计 4）
            int indent = 0;
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                if (c == ' ') indent++;
                else if (c == '\t') indent += 4;
                else break;
                i++;
            }
            String content = line.substring(i);
            if (content.trim().isEmpty()) continue; // 空行/纯注释行不影响缩进

            // 缩进变化
            int top = indentStack.peek();
            if (indent > top) {
                indentStack.push(indent);
                out.add(new Token(Token.Type.INDENT, ""));
            } else if (indent < top) {
                while (indentStack.peek() > indent) {
                    indentStack.pop();
                    out.add(new Token(Token.Type.DEDENT, ""));
                }
                if (indentStack.peek() != indent)
                    throw new RuntimeException("缩进错误：不一致的缩进层级");
            }

            // 词法化本行内容
            tokenize(content);
            out.add(new Token(Token.Type.NEWLINE, "\\n"));
        }

        // 文件末尾：闭合所有缩进
        while (indentStack.size() > 1) {
            indentStack.pop();
            out.add(new Token(Token.Type.DEDENT, ""));
        }
        out.add(new Token(Token.Type.EOF, ""));
        return out;
    }

    private void tokenize(String s) {
        int p = 0;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c)) { p++; continue; }

            if (Character.isDigit(c)) {
                int start = p;
                while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                out.add(new Token(Token.Type.NUMBER, s.substring(start, p)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = p;
                while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
                String word = s.substring(start, p);
                Token.Type t = KEYWORDS.getOrDefault(word, Token.Type.IDENT);
                out.add(new Token(t, word));
                continue;
            }

            // 双字符运算符优先
            if (p + 1 < s.length()) {
                String two = s.substring(p, p + 2);
                switch (two) {
                    case "==": out.add(new Token(Token.Type.EQ, two)); p += 2; continue;
                    case "!=": out.add(new Token(Token.Type.NE, two)); p += 2; continue;
                    case "<=": out.add(new Token(Token.Type.LE, two)); p += 2; continue;
                    case ">=": out.add(new Token(Token.Type.GE, two)); p += 2; continue;
                }
            }
            switch (c) {
                case '+': out.add(new Token(Token.Type.PLUS, "+")); break;
                case '-': out.add(new Token(Token.Type.MINUS, "-")); break;
                case '*': out.add(new Token(Token.Type.MUL, "*")); break;
                case '/': out.add(new Token(Token.Type.DIV, "/")); break;
                case '(': out.add(new Token(Token.Type.LPAREN, "(")); break;
                case ')': out.add(new Token(Token.Type.RPAREN, ")")); break;
                case '[': out.add(new Token(Token.Type.LBRACKET, "[")); break;
                case ']': out.add(new Token(Token.Type.RBRACKET, "]")); break;
                case ',': out.add(new Token(Token.Type.COMMA, ",")); break;
                case ':': out.add(new Token(Token.Type.COLON, ":")); break;
                case '=': out.add(new Token(Token.Type.EQUALS, "=")); break;
                case '<': out.add(new Token(Token.Type.LT, "<")); break;
                case '>': out.add(new Token(Token.Type.GT, ">")); break;
                default: throw new RuntimeException("无法识别的字符: " + c);
            }
            p++;
        }
    }

    private static final java.util.Map<String, Token.Type> KEYWORDS = new java.util.HashMap<>();
    static {
        KEYWORDS.put("if", Token.Type.IF);
        KEYWORDS.put("elif", Token.Type.ELIF);
        KEYWORDS.put("else", Token.Type.ELSE);
        KEYWORDS.put("while", Token.Type.WHILE);
        KEYWORDS.put("def", Token.Type.DEF);
        KEYWORDS.put("return", Token.Type.RETURN);
        KEYWORDS.put("print", Token.Type.PRINT);
    }
}
