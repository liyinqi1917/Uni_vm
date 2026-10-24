import java.util.ArrayList;
import java.util.List;

// Rust 风格前端词法分析器（.rs）
// 关键词/宏规范化为现有 Token：
//   let -> LET, fn -> FUNC, println! -> PRINT, return -> RETURN
// mut 作为普通 IDENT 交给 Parser 跳过；-> 保留为 MINUS + GT 两个 token。
public class LexerRs {

    private final String src;
    private int pos;

    public LexerRs(String src) {
        this.src = src.startsWith("\uFEFF") ? src.substring(1) : src;
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                pos++;
                continue;
            }
            if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                continue;
            }
            if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                int end = src.indexOf("*/", pos);
                if (end < 0) throw new RuntimeException("Rust 块注释缺少 */");
                pos = end + 2;
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
                throw new RuntimeException("Rust 字符串缺少结束引号 (位置 " + start + ")");
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
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            String word = src.substring(start, pos);

            // println!(...) / print!(...) 宏：吞掉 '!'，对 Parser 暴露为 PRINT
            if (word.equals("println") || word.equals("print")) {
                if (pos < src.length() && src.charAt(pos) == '!') pos++;
                return new Token(Token.Type.PRINT, word);
            }

            switch (word) {
                case "let":    return new Token(Token.Type.LET, word);
                case "fn":     return new Token(Token.Type.FUNC, word);
                case "return": return new Token(Token.Type.RETURN, word);
                case "if":     return new Token(Token.Type.IF, word);
                case "else":   return new Token(Token.Type.ELSE, word);
                case "while":  return new Token(Token.Type.WHILE, word);
                default:       return new Token(Token.Type.IDENT, word);
            }
        }

        if (ch == '=') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.EQ, "=="); }
            return new Token(Token.Type.EQUALS, "=");
        }
        if (ch == '!') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.NE, "!="); }
            throw new RuntimeException("Rust 未知字符: '!' (位置 " + (pos - 1) + ")");
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
            case '{': pos++; return new Token(Token.Type.LBRACE, "{");
            case '}': pos++; return new Token(Token.Type.RBRACE, "}");
            case '[': pos++; return new Token(Token.Type.LBRACKET, "[");
            case ']': pos++; return new Token(Token.Type.RBRACKET, "]");
            case ',': pos++; return new Token(Token.Type.COMMA, ",");
            case ';': pos++; return new Token(Token.Type.SEMICOLON, ";");
            case ':': pos++; return new Token(Token.Type.COLON, ":");
        }

        throw new RuntimeException("Rust 未知字符: '" + ch + "' (位置 " + pos + ")");
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
