import java.util.ArrayList;
import java.util.List;

// Lisp 风格前端词法分析器（.lisp）
// 所有符号复用现有 Token 类型；; 开头为行注释。
// 关键字：let -> LET, print -> PRINT, if -> IF, while -> WHILE,
//         defun -> FUNC, return -> RETURN。
public class LexerLisp {

    private final String src;
    private int pos;

    public LexerLisp(String src) {
        this.src = src.startsWith("\uFEFF") ? src.substring(1) : src;
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                pos++;
                continue;
            }
            if (c == ';') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                continue;
            }
            break;
        }
    }

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) return new Token(Token.Type.EOF, "");

        char ch = src.charAt(pos);

        if (ch == '"') {
            pos++;
            int start = pos;
            while (pos < src.length() && src.charAt(pos) != '"') pos++;
            if (pos >= src.length())
                throw new RuntimeException("Lisp 字符串缺少结束引号 (位置 " + start + ")");
            String text = src.substring(start, pos);
            pos++;
            return new Token(Token.Type.STRING, text);
        }

        if (Character.isDigit(ch)) {
            int start = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            return new Token(Token.Type.NUMBER, src.substring(start, pos));
        }

        if (Character.isLetter(ch) || ch == '_') {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '?' || c == '!') pos++;
                else break;
            }
            String word = src.substring(start, pos);
            switch (word) {
                case "let":    return new Token(Token.Type.LET, word);
                case "print":  return new Token(Token.Type.PRINT, word);
                case "if":     return new Token(Token.Type.IF, word);
                case "while":  return new Token(Token.Type.WHILE, word);
                case "defun":  return new Token(Token.Type.FUNC, word);
                case "return": return new Token(Token.Type.RETURN, word);
                case "break":  return new Token(Token.Type.BREAK, word);
                case "continue": return new Token(Token.Type.CONTINUE, word);
                default:       return new Token(Token.Type.IDENT, word);
            }
        }

        // 双字符运算符
        if (ch == '=') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.EQ, "=="); }
            return new Token(Token.Type.EQ, "=");
        }
        if (ch == '!') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.NE, "!="); }
            throw new RuntimeException("Lisp 未知字符: '!' (位置 " + (pos - 1) + ")");
        }
        if (ch == '<') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.LE, "<="); }
            return new Token(Token.Type.LT, "<");
        }
        if (ch == '>') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.GE, ">="); }
            return new Token(Token.Type.GT, ">");
        }

        switch (ch) {
            case '+': pos++; return new Token(Token.Type.PLUS, "+");
            case '-': pos++; return new Token(Token.Type.MINUS, "-");
            case '*': pos++; return new Token(Token.Type.MUL, "*");
            case '/': pos++; return new Token(Token.Type.DIV, "/");
            case '(': pos++; return new Token(Token.Type.LPAREN, "(");
            case ')': pos++; return new Token(Token.Type.RPAREN, ")");
            case '[': pos++; return new Token(Token.Type.LBRACKET, "[");
            case ']': pos++; return new Token(Token.Type.RBRACKET, "]");
            case ',': pos++; return new Token(Token.Type.COMMA, ",");
        }

        throw new RuntimeException("Lisp 未知字符: '" + ch + "' (位置 " + pos + ")");
    }

    public List<Token> scanAllTokens() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            Token t = nextToken();
            tokens.add(t);
            if (t.type == Token.Type.EOF) break;
        }
        return tokens;
    }
}
