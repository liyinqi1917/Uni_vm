// Token 词元：类型 + 文本
public class Token {
    public enum Type {
        NUMBER, IDENT,
        PLUS, MINUS, MUL, DIV,
        LPAREN, RPAREN, LBRACE, RBRACE, COMMA,
        LBRACKET, RBRACKET,
        EQUALS, SEMICOLON, COLON, NEWLINE, INDENT, DEDENT,
        EQ, NE, LT, GT, LE, GE,   // == != < > <= >=
        LET, PRINT, IF, ELIF, ELSE, WHILE, FUNC, DEF, RETURN, EOF
    }

    public Type type;
    public String text;

    public Token(Type type, String text) {
        this.type = type;
        this.text = text;
    }

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}
