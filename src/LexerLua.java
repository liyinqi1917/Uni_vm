import java.util.ArrayList;
import java.util.List;

// Lua 风格前端词法分析器（.lua）
// 只负责把 Lua 关键字规范化为现有 Token 类型，AST/字节码/后端完全复用：
//   local -> LET, function -> FUNC, print -> PRINT, end -> RBRACE
// 支持 -- 行注释、--[[ ... ]] 块注释、单/双引号字符串。
public class LexerLua {

    private final String src;
    private int pos;

    public LexerLua(String src) {
        this.src = src.startsWith("\uFEFF") ? src.substring(1) : src;
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                pos++;
                continue;
            }
            if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                pos += 2;
                if (pos + 1 < src.length() && src.charAt(pos) == '[' && src.charAt(pos + 1) == '[') {
                    pos += 2;
                    int end = src.indexOf("]]", pos);
                    if (end < 0) throw new RuntimeException("Lua 块注释缺少 ]]");
                    pos = end + 2;
                } else {
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                }
                continue;
            }
            break;
        }
    }

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) return new Token(Token.Type.EOF, "");

        char ch = src.charAt(pos);

        // 字符串字面量：支持 Lua 的单引号与双引号
        if (ch == '"' || ch == '\'') {
            char quote = ch;
            pos++;
            int start = pos;
            while (pos < src.length() && src.charAt(pos) != quote) pos++;
            if (pos >= src.length())
                throw new RuntimeException("Lua 字符串缺少结束引号 (位置 " + start + ")");
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
            switch (word) {
                case "local":    return new Token(Token.Type.LET, word);
                case "function": return new Token(Token.Type.FUNC, word);
                case "end":      return new Token(Token.Type.RBRACE, word);
                case "print":    return new Token(Token.Type.PRINT, word);
                case "return":   return new Token(Token.Type.RETURN, word);
                case "if":       return new Token(Token.Type.IF, word);
                case "else":     return new Token(Token.Type.ELSE, word);
                case "while":    return new Token(Token.Type.WHILE, word);
                case "break":    return new Token(Token.Type.BREAK, word);
                case "continue": return new Token(Token.Type.CONTINUE, word);
                default:         return new Token(Token.Type.IDENT, word);
            }
        }

        // 双字符运算符
        if (ch == '=') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.EQ, "=="); }
            return new Token(Token.Type.EQUALS, "=");
        }
        if (ch == '~') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') { pos++; return new Token(Token.Type.NE, "~="); }
            throw new RuntimeException("Lua 未知字符: '~' (位置 " + (pos - 1) + ")");
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
            case ';': pos++; return new Token(Token.Type.SEMICOLON, ";");
            case ':': pos++; return new Token(Token.Type.COLON, ":");
        }

        throw new RuntimeException("Lua 未知字符: '" + ch + "' (位置 " + pos + ")");
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
