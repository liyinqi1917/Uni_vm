import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * A commercial-style source editor tab:
 * dark editor, line numbers, syntax highlighting, current-line marker,
 * undo/redo, smart indentation, code formatting and block-comment folding.
 */
public class EditorPane extends JPanel {

    private static final Color EDITOR_BG = new Color(0x1E1E1E);
    private static final Color EDITOR_FG = new Color(0xD4D4D4);
    private static final Color LINE_BG = new Color(0x252526);
    private static final Color LINE_FG = new Color(0x858585);
    private static final Color LINE_CURRENT = new Color(0xC6C6C6);
    private static final Color SELECTION = new Color(0x264F78);
    private static final Color KEYWORD = new Color(0x569CD6);
    private static final Color STRING = new Color(0x98C379);
    private static final Color COMMENT = new Color(0x7F8C8D);
    private static final Color NUMBER = new Color(0xB5CEA8);
    private static final Color CARET = new Color(0xFFFFFF);
    private static final Color CURRENT_LINE = new Color(0x2A2D2E);
    private static final String INDENT = "    ";
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    private static final Font EDITOR_FONT = chooseEditorFont();
    private static int untitledCounter;

    private final CodeTextPane textPane;
    private final DefaultStyledDocument document;
    private final JScrollPane scrollPane;
    private final LineNumberView lineNumbers;
    private final UndoManager undo = new UndoManager();
    private final javax.swing.Timer highlightTimer;
    private final SyntaxHighlighter highlighter;
    private final Highlighter.HighlightPainter currentLinePainter =
            new DefaultHighlighter.DefaultHighlightPainter(CURRENT_LINE);

    private Path file;
    private String displayName;
    private boolean dirty;
    private boolean loading;
    private boolean applyingStyles;
    private boolean folded;
    private String foldedSnapshot;
    private Object currentLineHighlight;
    private int lastCaretLine = -1;
    private Runnable dirtyListener = () -> { };
    private Runnable caretListener = () -> { };

    public EditorPane(Path file, String initialText) {
        super(new BorderLayout());
        setOpaque(false);
        this.file = file == null ? null : file.toAbsolutePath().normalize();
        this.displayName = this.file == null
                ? "未命名-" + (++untitledCounter) + ".uni"
                : this.file.getFileName().toString();

        document = new DefaultStyledDocument();
        textPane = new CodeTextPane(document);
        textPane.setFont(EDITOR_FONT);
        textPane.setBackground(EDITOR_BG);
        textPane.setForeground(EDITOR_FG);
        textPane.setCaretColor(CARET);
        textPane.setSelectionColor(SELECTION);
        textPane.setSelectedTextColor(Color.WHITE);
        textPane.setMargin(new Insets(8, 10, 8, 14));
        textPane.setFocusTraversalKeysEnabled(false);
        textPane.setBorder(BorderFactory.createEmptyBorder());

        lineNumbers = new LineNumberView(textPane);
        scrollPane = new JScrollPane(textPane);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setRowHeaderView(lineNumbers);
        scrollPane.getViewport().setBackground(EDITOR_BG);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(18);
        scrollPane.getViewport().addChangeListener(e -> lineNumbers.repaint());
        add(scrollPane, BorderLayout.CENTER);

        highlighter = new SyntaxHighlighter(textPane, document, this::currentSuffix);
        highlightTimer = new javax.swing.Timer(140, e -> highlighter.apply());
        highlightTimer.setRepeats(false);

        installListeners();
        installKeyBindings();
        installCurrentLine();
        setText(initialText == null ? "" : initialText, false);
        highlighter.apply();
    }

    public void setDirtyListener(Runnable listener) {
        dirtyListener = listener == null ? () -> { } : listener;
    }

    public void setCaretListener(Runnable listener) {
        caretListener = listener == null ? () -> { } : listener;
    }

    public JTextPane getTextPane() {
        return textPane;
    }

    public Path getFile() {
        return file;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isFolded() {
        return folded;
    }

    public String getText() {
        try {
            return document.getText(0, document.getLength());
        } catch (BadLocationException ex) {
            return "";
        }
    }

    /** Text used for compiling/running: folded markers must never reach the VM. */
    public String getSourceText() {
        return folded && foldedSnapshot != null ? foldedSnapshot : getText();
    }

    public String currentSuffix() {
        String name = displayName == null ? "" : displayName;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot).toLowerCase(Locale.ROOT);
        }
        return ".uni";
    }

    public String getLanguageLabel() {
        switch (currentSuffix()) {
            case ".py": return "Python";
            case ".c": return "C";
            case ".cpp":
            case ".cc":
            case ".cxx": return "C++";
            case ".lua": return "Lua";
            case ".rs": return "Rust";
            case ".lisp": return "Lisp";
            default: return "Uni";
        }
    }

    public void setFile(Path newFile) {
        this.file = newFile == null ? null : newFile.toAbsolutePath().normalize();
        this.displayName = this.file == null
                ? "未命名-" + (++untitledCounter) + ".uni"
                : this.file.getFileName().toString();
        highlighter.apply();
        fireDirty();
        fireCaret();
    }

    public void loadText(String text, boolean markDirty) {
        setText(text == null ? "" : text, markDirty);
    }

    public void markSaved() {
        if (dirty) {
            dirty = false;
            fireDirty();
        }
    }

    public void setText(String text, boolean markDirty) {
        loading = true;
        applyingStyles = true;
        try {
            if (document.getLength() > 0) {
                document.remove(0, document.getLength());
            }
            document.insertString(0, text == null ? "" : text, null);
            undo.discardAllEdits();
            folded = false;
            foldedSnapshot = null;
            textPane.setEditable(true);
            textPane.setCaretPosition(0);
        } catch (BadLocationException ex) {
            // Plain document edits cannot throw here for a valid range.
        } finally {
            applyingStyles = false;
            loading = false;
        }
        dirty = markDirty;
        highlighter.apply();
        updateCurrentLine();
        fireDirty();
        fireCaret();
    }

    public void undo() {
        if (folded) return;
        try {
            if (undo.canUndo()) undo.undo();
        } catch (CannotUndoException ignored) {
        }
    }

    public void redo() {
        if (folded) return;
        try {
            if (undo.canRedo()) undo.redo();
        } catch (CannotRedoException ignored) {
        }
    }

    public boolean canUndo() {
        return !folded && undo.canUndo();
    }

    public boolean canRedo() {
        return !folded && undo.canRedo();
    }

    public boolean formatCode() {
        if (folded) unfoldComments();
        String original = getText();
        String formatted = CodeFormatter.format(original, currentSuffix());
        if (formatted.equals(original)) {
            return false;
        }
        int caret = Math.min(textPane.getCaretPosition(), formatted.length());
        try {
            document.remove(0, document.getLength());
            document.insertString(0, formatted, null);
            textPane.setCaretPosition(Math.min(caret, formatted.length()));
        } catch (BadLocationException ex) {
            return false;
        }
        highlighter.apply();
        return true;
    }

    public boolean foldComments() {
        if (folded) return false;
        String text = getText();
        Matcher matcher = BLOCK_COMMENT.matcher(text);
        if (!matcher.find()) {
            return false;
        }

        String snapshot = text;
        StringBuilder foldedText = new StringBuilder(text.length());
        int cursor = 0;
        do {
            foldedText.append(text, cursor, matcher.start());
            String comment = matcher.group();
            int lines = comment.split("\\R", -1).length;
            foldedText.append("/* ... 已折叠 ")
                    .append(lines)
                    .append(" 行 ... */");
            cursor = matcher.end();
        } while (matcher.find());
        foldedText.append(text.substring(cursor));

        boolean wasDirty = dirty;
        loading = true;
        applyingStyles = true;
        try {
            document.remove(0, document.getLength());
            document.insertString(0, foldedText.toString(), null);
            undo.discardAllEdits();
            textPane.setEditable(false);
            textPane.setCaretPosition(0);
            foldedSnapshot = snapshot;
            folded = true;
        } catch (BadLocationException ex) {
            return false;
        } finally {
            applyingStyles = false;
            loading = false;
        }
        dirty = wasDirty;
        highlighter.apply();
        fireCaret();
        return true;
    }

    public boolean unfoldComments() {
        if (!folded || foldedSnapshot == null) return false;
        String snapshot = foldedSnapshot;
        boolean wasDirty = dirty;
        loading = true;
        applyingStyles = true;
        try {
            document.remove(0, document.getLength());
            document.insertString(0, snapshot, null);
            undo.discardAllEdits();
            textPane.setEditable(true);
            textPane.setCaretPosition(0);
            folded = false;
            foldedSnapshot = null;
        } catch (BadLocationException ex) {
            return false;
        } finally {
            applyingStyles = false;
            loading = false;
        }
        dirty = wasDirty;
        highlighter.apply();
        fireCaret();
        return true;
    }

    public int[] getCaretLineColumn() {
        int caret = textPane.getCaretPosition();
        Element root = document.getDefaultRootElement();
        int line = root.getElementIndex(caret);
        Element element = root.getElement(line);
        return new int[]{line + 1, caret - element.getStartOffset() + 1};
    }

    public void dispose() {
        highlightTimer.stop();
    }

    private void installListeners() {
        document.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { contentChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { contentChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        document.addUndoableEditListener(event -> {
            if (loading || applyingStyles) return;
            UndoableEdit edit = event.getEdit();
            if (edit instanceof DocumentEvent
                    && ((DocumentEvent) edit).getType() == DocumentEvent.EventType.CHANGE) {
                return;
            }
            undo.addEdit(edit);
        });
        textPane.addCaretListener(e -> {
            updateCurrentLine();
            fireCaret();
        });
        textPane.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (folded && e.getClickCount() == 2) {
                    unfoldComments();
                }
            }
        });
    }

    private void contentChanged() {
        if (loading || applyingStyles) return;
        if (!folded && !dirty) {
            dirty = true;
            fireDirty();
        }
        highlightTimer.restart();
        lineNumbers.repaint();
    }

    private void installKeyBindings() {
        InputMap im = textPane.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textPane.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "univm-indent");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "univm-outdent");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "univm-newline");
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "univm-undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask), "univm-redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | InputEvent.SHIFT_DOWN_MASK), "univm-redo");

        am.put("univm-indent", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { indentSelection(); }
        });
        am.put("univm-outdent", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { outdentSelection(); }
        });
        am.put("univm-newline", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { insertSmartNewline(); }
        });
        am.put("univm-undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { undo(); }
        });
        am.put("univm-redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { redo(); }
        });
    }

    private void indentSelection() {
        if (folded) return;
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        if (start == end) {
            replaceSelection(INDENT);
            return;
        }
        Element root = document.getDefaultRootElement();
        int first = root.getElementIndex(start);
        int last = root.getElementIndex(Math.max(start, end - 1));
        try {
            for (int line = last; line >= first; line--) {
                document.insertString(root.getElement(line).getStartOffset(), INDENT, null);
            }
        } catch (BadLocationException ignored) {
        }
    }

    private void outdentSelection() {
        if (folded) return;
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        Element root = document.getDefaultRootElement();
        int first = root.getElementIndex(start);
        int last = root.getElementIndex(Math.max(start, end - 1));
        try {
            for (int line = last; line >= first; line--) {
                int lineStart = root.getElement(line).getStartOffset();
                int available = Math.min(4, document.getLength() - lineStart);
                String prefix = document.getText(lineStart, available);
                int remove = 0;
                while (remove < prefix.length() && remove < 4
                        && (prefix.charAt(remove) == ' ' || prefix.charAt(remove) == '\t')) {
                    remove++;
                }
                if (remove > 0) {
                    document.remove(lineStart, remove);
                }
            }
        } catch (BadLocationException ignored) {
        }
    }

    private void insertSmartNewline() {
        if (folded) return;
        try {
            int caret = textPane.getCaretPosition();
            Element root = document.getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(caret));
            String before = document.getText(line.getStartOffset(), caret - line.getStartOffset());
            int i = 0;
            while (i < before.length() && (before.charAt(i) == ' ' || before.charAt(i) == '\t')) {
                i++;
            }
            String leading = before.substring(0, i);
            String trimmed = before.trim();
            String extra = (trimmed.endsWith("{") || trimmed.endsWith(":")
                    || trimmed.endsWith("(") || trimmed.endsWith("["))
                    ? INDENT : "";
            replaceSelection("\n" + leading + extra);
        } catch (BadLocationException ignored) {
        }
    }

    private void replaceSelection(String text) {
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        try {
            if (end > start) {
                document.remove(start, end - start);
            }
            document.insertString(start, text, null);
            textPane.setCaretPosition(start + text.length());
        } catch (BadLocationException ignored) {
        }
    }

    private void installCurrentLine() {
        updateCurrentLine();
    }

    private void updateCurrentLine() {
        Highlighter highlighter = textPane.getHighlighter();
        try {
            if (currentLineHighlight != null) {
                highlighter.removeHighlight(currentLineHighlight);
                currentLineHighlight = null;
            }
            if (document.getLength() == 0) {
                lastCaretLine = -1;
                return;
            }
            int caret = Math.min(textPane.getCaretPosition(), document.getLength() - 1);
            Element root = document.getDefaultRootElement();
            int line = root.getElementIndex(caret);
            Element element = root.getElement(line);
            int start = element.getStartOffset();
            int end = Math.max(start, element.getEndOffset());
            currentLineHighlight = highlighter.addHighlight(start, end, currentLinePainter);
            lastCaretLine = line;
        } catch (BadLocationException ignored) {
        }
    }

    private void fireDirty() {
        if (SwingUtilities.isEventDispatchThread()) dirtyListener.run();
        else SwingUtilities.invokeLater(dirtyListener);
    }

    private void fireCaret() {
        if (SwingUtilities.isEventDispatchThread()) caretListener.run();
        else SwingUtilities.invokeLater(caretListener);
    }

    private static Font chooseEditorFont() {
        String[] preferred = {"JetBrains Mono", "Cascadia Code", "Consolas",
                "Fira Code", "Source Code Pro", "DejaVu Sans Mono", "Monospaced"};
        Set<String> available = new HashSet<>();
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            available.add(name.toLowerCase(Locale.ROOT));
        }
        for (String name : preferred) {
            if (name.equals("Monospaced") || available.contains(name.toLowerCase(Locale.ROOT))) {
                return new Font(name, Font.PLAIN, 14);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 14);
    }

    /** JTextPane that avoids wrapping long source lines when the content is wide. */
    private static final class CodeTextPane extends JTextPane {
        CodeTextPane(StyledDocument document) {
            super(document);
            setOpaque(true);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            Component parent = getParent();
            if (parent == null) return true;
            return getUI().getPreferredSize(this).width <= parent.getSize().width;
        }
    }

    /** Paints logical line numbers and keeps them aligned while scrolling. */
    private final class LineNumberView extends JComponent {
        private final JTextPane pane;
        private final Font font;

        LineNumberView(JTextPane pane) {
            this.pane = pane;
            this.font = EDITOR_FONT.deriveFont(12f);
            setOpaque(true);
            setBackground(LINE_BG);
            pane.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { refresh(); }
                @Override public void removeUpdate(DocumentEvent e) { refresh(); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
            pane.addCaretListener(e -> repaint());
            refresh();
        }

        private void refresh() {
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = Math.max(1, pane.getDocument().getDefaultRootElement().getElementCount());
            int digits = Math.max(2, Integer.toString(lines).length());
            FontMetrics fm = getFontMetrics(font);
            int width = digits * fm.charWidth('9') + 18;
            return new Dimension(width, Math.max(1, pane.getPreferredSize().height));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(LINE_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(0x333333));
                g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());

                Rectangle clip = g.getClipBounds();
                Element root = pane.getDocument().getDefaultRootElement();
                int top = pane.viewToModel2D(new Point(0, Math.max(0, clip.y)));
                int bottom = pane.viewToModel2D(new Point(0, clip.y + Math.max(1, clip.height)));
                int first = Math.max(0, root.getElementIndex(top));
                int last = Math.min(root.getElementCount() - 1, root.getElementIndex(bottom));
                int caretLine = root.getElementIndex(Math.min(
                        pane.getCaretPosition(), Math.max(0, pane.getDocument().getLength() - 1)));

                g.setFont(font);
                FontMetrics fm = g.getFontMetrics();
                for (int line = first; line <= last; line++) {
                    Element el = root.getElement(line);
                    Rectangle2D loc = pane.modelToView2D(el.getStartOffset());
                    if (loc == null) continue;
                    int y = (int) loc.getY() + (int) ((loc.getHeight() - fm.getHeight()) / 2) + fm.getAscent();
                    g.setColor(line == caretLine ? LINE_CURRENT : LINE_FG);
                    g.setFont(line == caretLine ? font.deriveFont(Font.BOLD) : font);
                    String text = Integer.toString(line + 1);
                    g.drawString(text, getWidth() - 8 - fm.stringWidth(text), y);
                }
            } catch (Exception ignored) {
            } finally {
                g.dispose();
            }
        }
    }

    /** Debounced regex highlighter. Attribute updates never enter undo history. */
    private final class SyntaxHighlighter {
        private final JTextPane pane;
        private final DefaultStyledDocument doc;
        private final java.util.function.Supplier<String> suffixSupplier;
        private String cachedSuffix = "";
        private Pattern cachedPattern;

        SyntaxHighlighter(JTextPane pane, DefaultStyledDocument doc,
                          java.util.function.Supplier<String> suffixSupplier) {
            this.pane = pane;
            this.doc = doc;
            this.suffixSupplier = suffixSupplier;
        }

        void apply() {
            if (applyingStyles) return;
            applyingStyles = true;
            try {
                int length = doc.getLength();
                String text = doc.getText(0, length);
                String suffix = suffixSupplier.get();
                Pattern pattern = patternFor(suffix);
                SimpleAttributeSet normal = new SimpleAttributeSet();
                StyleConstants.setForeground(normal, EDITOR_FG);
                doc.setCharacterAttributes(0, length, normal, true);

                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    String token = matcher.group();
                    SimpleAttributeSet style = new SimpleAttributeSet();
                    if (token.startsWith("/*") || token.startsWith("//")
                            || token.startsWith("#") || token.startsWith("--")) {
                        StyleConstants.setForeground(style, COMMENT);
                        StyleConstants.setItalic(style, true);
                    } else if (token.startsWith("\"") || token.startsWith("'")) {
                        StyleConstants.setForeground(style, STRING);
                    } else if (Character.isDigit(token.charAt(0))) {
                        StyleConstants.setForeground(style, NUMBER);
                    } else {
                        StyleConstants.setForeground(style, KEYWORD);
                        StyleConstants.setBold(style, true);
                    }
                    doc.setCharacterAttributes(matcher.start(), token.length(), style, true);
                }
            } catch (BadLocationException ignored) {
            } finally {
                applyingStyles = false;
            }
        }

        private Pattern patternFor(String suffix) {
            if (suffix.equals(cachedSuffix) && cachedPattern != null) {
                return cachedPattern;
            }
            Set<String> keywords = new HashSet<>(Arrays.asList(
                    "let", "func", "function", "fn", "def", "if", "else", "elif",
                    "while", "for", "return", "break", "continue", "true", "false",
                    "and", "or", "not", "nil", "null", "print", "input", "int",
                    "long", "short", "byte", "char", "float", "double", "boolean",
                    "string", "array", "void", "const", "static", "extern", "struct",
                    "class", "new", "public", "private", "protected", "import", "use",
                    "mut", "match", "loop", "in", "end", "then", "do", "local", "self",
                    "sizeof", "switch", "case", "default", "goto", "typedef", "enum",
                    "union", "try", "catch", "finally", "throw", "throws", "package",
                    "extends", "implements", "interface", "instanceof", "this", "super",
                    "synchronized", "volatile", "transient", "native", "abstract", "final",
                    "lambda", "yield", "global", "nonlocal", "assert", "del", "pass",
                    "raise", "with", "as", "from", "except", "repeat", "until"));
            if (".lua".equals(suffix)) {
                keywords.addAll(Arrays.asList("local", "function", "then", "end", "repeat", "until"));
            }
            String kw = String.join("|", keywords);
            String luaComment = ".lua".equals(suffix) ? "|--[^\\n]*" : "";
            String regex = "(?s)/\\*.*?(?:\\*/|$)|//[^\\n]*|#[^\\n]*" + luaComment
                    + "|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'"
                    + "|\\b(?:" + kw + ")\\b|\\b\\d+(?:\\.\\d+)?\\b";
            cachedSuffix = suffix;
            cachedPattern = Pattern.compile(regex);
            return cachedPattern;
        }
    }
}
