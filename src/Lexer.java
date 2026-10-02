import java.util.ArrayList;
import java.util.List;

// Lexer 词法分析器：源码 -> Token 流（UniVM 前端第 1 层）
public class Lexer {
    private String input;
    private int pos;

    public Lexer(String input) {
        this.input = input;
        this.pos = 0;
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char ch = input.charAt(pos);
            if (Character.isWhitespace(ch)) {
                pos++;
            } else if (ch == '#') {
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
            } else {
                break;
            }
        }
    }

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= input.length()) return new Token(Token.Type.EOF, "");

        char ch = input.charAt(pos);

        // 数字
        if (Character.isDigit(ch)) {
            int start = pos;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            return new Token(Token.Type.NUMBER, input.substring(start, pos));
        }

        // 标识符 / 关键字
        if (Character.isLetter(ch) || ch == '_') {
            int start = pos;
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
            String word = input.substring(start, pos);
            switch (word) {
                case "let":   return new Token(Token.Type.LET, word);
                case "print": return new Token(Token.Type.PRINT, word);
                case "if":    return new Token(Token.Type.IF, word);
                case "else":  return new Token(Token.Type.ELSE, word);
                case "while": return new Token(Token.Type.WHILE, word);
                case "func":  return new Token(Token.Type.FUNC, word);
                case "return":return new Token(Token.Type.RETURN, word);
                default:      return new Token(Token.Type.IDENT, word);
            }
        }

        // 双字符运算符（必须在单字符之前判断）
        if (ch == '=') {
            pos++;
            if (pos < input.length() && input.charAt(pos) == '=') { pos++; return new Token(Token.Type.EQ, "=="); }
            return new Token(Token.Type.EQUALS, "=");
        }
        if (ch == '!') {
            pos++;
            if (pos < input.length() && input.charAt(pos) == '=') { pos++; return new Token(Token.Type.NE, "!="); }
            throw new RuntimeException("未知字符: '!' (位置 " + (pos - 1) + ")，是否想写 != ");
        }
        if (ch == '<') {
            pos++;
            if (pos < input.length() && input.charAt(pos) == '=') { pos++; return new Token(Token.Type.LE, "<="); }
            return new Token(Token.Type.LT, "<");
        }
        if (ch == '>') {
            pos++;
            if (pos < input.length() && input.charAt(pos) == '=') { pos++; return new Token(Token.Type.GE, ">="); }
            return new Token(Token.Type.GT, ">");
        }

        // 单字符符号
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
        }

        throw new RuntimeException("未知字符: '" + ch + "' (位置 " + pos + ")");
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
