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
            if (Character.isWhitespace(ch) || ch == '\uFEFF') {
                pos++;
            } else if (ch == '#') {
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
            } else if (ch == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
            } else if (ch == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
                int start = pos;
                pos += 2;
                int end = input.indexOf("*/", pos);
                if (end < 0) throw new RuntimeException("块注释缺少 */ (位置 " + start + ")");
                pos = end + 2;
            } else {
                break;
            }
        }
    }

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (pos >= input.length()) return new Token(Token.Type.EOF, "");

        char ch = input.charAt(pos);

        // 字符串字面量："..."，支持常见反斜杠转义与 Unicode 四位转义，
        // 双引号内的中文/Emoji 等 Unicode 字符按原样保留。
        if (ch == '"') {
            pos++;
            int start = pos;
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return new Token(Token.Type.STRING, sb.toString());
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= input.length()) {
                    throw new RuntimeException("字符串转义序列不完整 (位置 " + (pos - 1) + ")");
                }
                char esc = input.charAt(pos++);
                switch (esc) {
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case '0':  sb.append('\0'); break;
                    case '\\': sb.append('\\'); break;
                    case '"':  sb.append('"'); break;
                    case '\'': sb.append('\''); break;
                    case 'u': {
                        if (pos + 4 > input.length()) {
                            throw new RuntimeException("\\u 转义需要 4 位十六进制数 (位置 " + (pos - 2) + ")");
                        }
                        int value = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(input.charAt(pos++), 16);
                            if (digit < 0) {
                                throw new RuntimeException("\\u 转义包含非十六进制字符 (位置 " + (pos - 1) + ")");
                            }
                            value = value * 16 + digit;
                        }
                        sb.append((char) value);
                        break;
                    }
                    default:
                        throw new RuntimeException("未知转义序列: \\" + esc + " (位置 " + (pos - 1) + ")");
                }
            }
            throw new RuntimeException("字符串字面量缺少结束引号 (位置 " + start + ")");
        }

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
                case "break": return new Token(Token.Type.BREAK, word);
                case "continue": return new Token(Token.Type.CONTINUE, word);
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
            case ':': pos++; return new Token(Token.Type.COLON, ":");
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
