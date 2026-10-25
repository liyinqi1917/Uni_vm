import java.util.ArrayList;
import java.util.List;

// C++ 原生语法前端词法分析器。
//
// 支持：
//   - 跳过 #include / #define 等预处理指令行
//   - // 与 /* ... */ 注释
//   - UTF-8 字符串、常见转义与 Unicode 转义
//   - << >> :: ++ -- += -= *= /= && || 等多字符运算符
//   - 常见 C++ 关键字（if/else/while/for/return/break/continue）
public class LexerCpp {

    private final String input;
    private int pos;

    public LexerCpp(String input) {
        this.input = input.startsWith("\uFEFF") ? input.substring(1) : input;
        this.pos = 0;
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char ch = input.charAt(pos);
            if (Character.isWhitespace(ch) || ch == '\uFEFF') {
                pos++;
            } else if (ch == '#' ) {
                // C++ 预处理指令对 UniVM 没有意义，整行跳过。
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
            } else if (ch == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
            } else if (ch == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
                int start = pos;
                pos += 2;
                int end = input.indexOf("*/", pos);
                if (end < 0) throw new RuntimeException("C++ 块注释缺少 */ (位置 " + start + ")");
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

        if (ch == '"') {
            pos++;
            int start = pos;
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return new Token(Token.Type.STRING, sb.toString());
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= input.length())
                    throw new RuntimeException("C++ 字符串转义序列不完整 (位置 " + (pos - 1) + ")");
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
                        if (pos + 4 > input.length())
                            throw new RuntimeException("\\u 转义需要 4 位十六进制数 (位置 " + (pos - 2) + ")");
                        int value = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(input.charAt(pos++), 16);
                            if (digit < 0)
                                throw new RuntimeException("\\u 转义包含非十六进制字符 (位置 " + (pos - 1) + ")");
                            value = value * 16 + digit;
                        }
                        sb.append((char) value);
                        break;
                    }
                    default:
                        throw new RuntimeException("C++ 未知转义序列: \\" + esc + " (位置 " + (pos - 1) + ")");
                }
            }
            throw new RuntimeException("C++ 字符串字面量缺少结束引号 (位置 " + start + ")");
        }

        if (Character.isDigit(ch)) {
            int start = pos;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            return new Token(Token.Type.NUMBER, input.substring(start, pos));
        }

        if (Character.isLetter(ch) || ch == '_') {
            int start = pos;
            while (pos < input.length()
                    && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }
            String word = input.substring(start, pos);
            switch (word) {
                case "if":       return new Token(Token.Type.IF, word);
                case "else":     return new Token(Token.Type.ELSE, word);
                case "while":    return new Token(Token.Type.WHILE, word);
                case "for":      return new Token(Token.Type.FOR, word);
                case "return":   return new Token(Token.Type.RETURN, word);
                case "break":    return new Token(Token.Type.BREAK, word);
                case "continue": return new Token(Token.Type.CONTINUE, word);
                default:         return new Token(Token.Type.IDENT, word);
            }
        }

        // 三字符优先级最高（当前前端暂未使用，但保留完整扫描入口）
        if (pos + 2 <= input.length()) {
            String three = input.substring(pos, pos + 2);
            if (three.equals("<<")) { pos += 2; return new Token(Token.Type.SHL, "<<"); }
            if (three.equals(">>")) { pos += 2; return new Token(Token.Type.SHR, ">>"); }
        }

        if (pos + 1 < input.length()) {
            String two = input.substring(pos, pos + 2);
            switch (two) {
                case "::": pos += 2; return new Token(Token.Type.SCOPE, "::");
                case "++": pos += 2; return new Token(Token.Type.INC, "++");
                case "--": pos += 2; return new Token(Token.Type.DEC, "--");
                case "+=": pos += 2; return new Token(Token.Type.PLUSEQ, "+=");
                case "-=": pos += 2; return new Token(Token.Type.MINUSEQ, "-=");
                case "*=": pos += 2; return new Token(Token.Type.MULEQ, "*=");
                case "/=": pos += 2; return new Token(Token.Type.DIVEQ, "/=");
                case "&&": pos += 2; return new Token(Token.Type.ANDAND, "&&");
                case "||": pos += 2; return new Token(Token.Type.OROR, "||");
                case "==": pos += 2; return new Token(Token.Type.EQ, "==");
                case "!=": pos += 2; return new Token(Token.Type.NE, "!=");
                case "<=": pos += 2; return new Token(Token.Type.LE, "<=");
                case ">=": pos += 2; return new Token(Token.Type.GE, ">=");
            }
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
            case '=': pos++; return new Token(Token.Type.EQUALS, "=");
            case '<': pos++; return new Token(Token.Type.LT, "<");
            case '>': pos++; return new Token(Token.Type.GT, ">");
            case '!': pos++; return new Token(Token.Type.NOT, "!");
        }

        throw new RuntimeException("C++ 无法识别的字符: '" + ch + "' (位置 " + pos + ")");
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
