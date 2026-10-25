import java.util.Locale;

/**
 * Lightweight, dependency-free source formatter for the GUI.
 *
 * <p>The formatter intentionally works on a lexical level instead of rebuilding
 * an AST: it preserves every token and only normalizes leading whitespace.
 * Strings and comments are skipped while counting braces, so brace characters
 * inside strings or comments cannot break indentation.</p>
 */
public final class CodeFormatter {

    private static final String INDENT = "    ";

    private CodeFormatter() {
    }

    public static String format(String source, String suffix) {
        if (source == null || source.isEmpty()) {
            return source == null ? "" : source;
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String ext = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        switch (ext) {
            case ".py":
                return formatPython(normalized);
            case ".lisp":
                return formatLisp(normalized);
            case ".lua":
                return formatLua(normalized);
            default:
                return formatBraces(normalized, ext);
        }
    }

    private static String[] linesOf(String source) {
        return source.split("\n", -1);
    }

    private static String trimRight(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) n++;
        }
        return n;
    }

    private static String finish(StringBuilder out, boolean trailingNewline) {
        String text = out.toString();
        return trailingNewline ? text + "\n" : text;
    }

    private static String formatBraces(String source, String ext) {
        String[] lines = linesOf(source);
        boolean trailing = source.endsWith("\n");
        int lineCount = trailing ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder(source.length());
        boolean[] inBlockComment = {false};
        int indent = 0;

        for (int i = 0; i < lineCount; i++) {
            String raw = trimRight(lines[i]);
            String trimmed = raw.trim();
            if (i > 0) out.append('\n');
            if (trimmed.isEmpty()) {
                continue;
            }

            String code = stripCode(trimmed, inBlockComment, ext);
            int leadingClosers = 0;
            int p = 0;
            while (p < code.length()) {
                char ch = code.charAt(p);
                if (ch == '}' || ch == ')' || ch == ']') {
                    if (ch == '}') leadingClosers++;
                    p++;
                } else if (Character.isWhitespace(ch)) {
                    p++;
                } else {
                    break;
                }
            }

            int lineIndent = Math.max(0, indent - leadingClosers);
            out.append(INDENT.repeat(lineIndent)).append(trimmed);

            int opens = count(code, '{');
            int closes = count(code, '}');
            indent = Math.max(0, indent + opens - closes);
        }
        return finish(out, trailing);
    }

    private static String formatPython(String source) {
        String[] lines = linesOf(source);
        boolean trailing = source.endsWith("\n");
        int lineCount = trailing ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder(source.length());
        // Python blocks are defined by indentation. Keep the author's nesting
        // decisions, but normalize every nesting level to four spaces. This is
        // safer than guessing blocks from colons: a formatter must never move a
        // top-level def under a previous function by accident.
        java.util.ArrayDeque<Integer> indentStack = new java.util.ArrayDeque<>();
        indentStack.push(0);

        for (int i = 0; i < lineCount; i++) {
            String raw = trimRight(lines[i]);
            String trimmed = raw.trim();
            if (i > 0) out.append('\n');
            if (trimmed.isEmpty()) {
                continue;
            }

            int originalIndent = leadingIndentWidth(raw);
            while (indentStack.size() > 1 && originalIndent < indentStack.peek()) {
                indentStack.pop();
            }
            if (originalIndent > indentStack.peek()) {
                indentStack.push(originalIndent);
            }

            int level = indentStack.size() - 1;
            out.append(INDENT.repeat(level)).append(trimmed);
        }
        return finish(out, trailing);
    }

    private static int leadingIndentWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == ' ') {
                width++;
            } else if (ch == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    private static String formatLua(String source) {
        String[] lines = linesOf(source);
        boolean trailing = source.endsWith("\n");
        int lineCount = trailing ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder(source.length());
        boolean[] inBlockComment = {false};
        int indent = 0;

        for (int i = 0; i < lineCount; i++) {
            String raw = trimRight(lines[i]);
            String trimmed = raw.trim();
            if (i > 0) out.append('\n');
            if (trimmed.isEmpty()) {
                continue;
            }

            String code = stripCode(trimmed, inBlockComment, ".lua");
            String bare = code.trim();
            boolean closes = startsWithWord(bare, "end") || startsWithWord(bare, "until")
                    || startsWithWord(bare, "else") || startsWithWord(bare, "elseif");
            if (closes) indent = Math.max(0, indent - 1);

            out.append(INDENT.repeat(indent)).append(trimmed);

            int delta = 0;
            if (containsWord(bare, "function") || containsWord(bare, "then")
                    || containsWord(bare, "do") || containsWord(bare, "repeat")) {
                delta++;
            }
            if (containsWord(bare, "end") || startsWithWord(bare, "until")) {
                delta--;
            }
            if (startsWithWord(bare, "else") || startsWithWord(bare, "elseif")) {
                delta++;
            }
            indent = Math.max(0, indent + delta);
        }
        return finish(out, trailing);
    }

    private static String formatLisp(String source) {
        String[] lines = linesOf(source);
        boolean trailing = source.endsWith("\n");
        int lineCount = trailing ? lines.length - 1 : lines.length;
        StringBuilder out = new StringBuilder(source.length());
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < lineCount; i++) {
            String raw = trimRight(lines[i]);
            String trimmed = raw.trim();
            if (i > 0) out.append('\n');
            if (trimmed.isEmpty()) {
                continue;
            }

            int leadingClose = 0;
            int p = 0;
            while (p < trimmed.length() && trimmed.charAt(p) == ')') {
                leadingClose++;
                p++;
            }
            int lineIndent = Math.max(0, depth - leadingClose);
            out.append(INDENT.repeat(lineIndent)).append(trimmed);

            for (int k = 0; k < trimmed.length(); k++) {
                char ch = trimmed.charAt(k);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (ch == '\\') {
                        escaped = true;
                    } else if (ch == '"') {
                        inString = false;
                    }
                } else if (ch == '"') {
                    inString = true;
                } else if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth = Math.max(0, depth - 1);
                }
            }
        }
        return finish(out, trailing);
    }

    private static boolean endsWithBlockColon(String code) {
        int i = code.length() - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
        return i >= 0 && code.charAt(i) == ':';
    }

    private static boolean startsWithWord(String text, String word) {
        if (!text.startsWith(word)) return false;
        return text.length() == word.length()
                || !Character.isLetterOrDigit(text.charAt(word.length()))
                && text.charAt(word.length()) != '_';
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) return false;
            boolean left = at == 0 || !isWordChar(text.charAt(at - 1));
            int end = at + word.length();
            boolean right = end >= text.length() || !isWordChar(text.charAt(end));
            if (left && right) return true;
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Returns only "structural" code characters: comments and string contents
     * are removed so their braces do not affect indentation.
     */
    private static String stripCode(String line, boolean[] inBlockComment, String ext) {
        StringBuilder out = new StringBuilder(line.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        boolean lua = ".lua".equals(ext);

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inBlockComment[0]) {
                if (ch == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    inBlockComment[0] = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inDouble = false;
                }
                continue;
            }

            if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                inBlockComment[0] = true;
                i++;
                continue;
            }
            if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (ch == '#' ) {
                break;
            }
            if (lua && ch == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                break;
            }
            if (ch == '\'') {
                inSingle = true;
                continue;
            }
            if (ch == '"') {
                inDouble = true;
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
