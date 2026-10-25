import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * UniVM 1.2.0 IDE shell.
 *
 * <p>Menus, toolbar, workspace tree, multi-tab editors, console, status bar,
 * build/run/stop controls and code-folding are wired here. The actual language
 * toolchain stays in {@link Main}, {@link Vm} and the backend classes.</p>
 */
public class Gui {

    public static final String VERSION = "1.2.0";

    private static final Color ACCENT = new Color(0x2F80ED);
    private static final Color SUCCESS = new Color(0x1E8449);
    private static final Color WARNING = new Color(0xD68910);
    private static final Color ERROR = new Color(0xC0392B);
    private static final Color MUTED = new Color(0x5D6D7E);
    private static final Color TOP_BG = new Color(0xF6F8FA);
    private static final Color BORDER = new Color(0xD6DCE3);
    private static final Color CONSOLE_BG = new Color(0x1E1E1E);
    private static final Color CONSOLE_HEADER = new Color(0x252526);
    private static final Color CONSOLE_FG = new Color(0xD4D4D4);

    private final JFrame frame;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<Path, EditorPane> openFiles = new LinkedHashMap<>();
    private final Map<EditorPane, JLabel> tabLabels = new HashMap<>();
    private final Map<EditorPane, JPanel> tabHeaders = new HashMap<>();

    private final FileTreePanel fileTree;
    private final JTextArea outputArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("就绪");
    private final JLabel positionLabel = new JLabel("行 1, 列 1");
    private final JLabel languageLabel = new JLabel("Uni");
    private final JProgressBar busyBar = new JProgressBar();

    private final JComboBox<String> frontendBox;
    private final JComboBox<String> execModeBox;
    private final JComboBox<String> backendBox;
    private final JCheckBox typeCheckBox = new JCheckBox("类型检查", true);
    private final JTextField backendOutField = new JTextField(14);

    private Action newAction;
    private Action openAction;
    private Action saveAction;
    private Action saveAsAction;
    private Action saveAllAction;
    private Action closeTabAction;
    private Action exitAction;
    private Action undoAction;
    private Action redoAction;
    private Action cutAction;
    private Action copyAction;
    private Action pasteAction;
    private Action selectAllAction;
    private Action formatAction;
    private Action foldAction;
    private Action unfoldAction;
    private Action compileAction;
    private Action generateAction;
    private Action runAction;
    private Action stopAction;
    private Action profileAction;
    private Action refreshAction;
    private Action clearOutputAction;
    private Action copyOutputAction;
    private Action shortcutsAction;
    private Action aboutAction;

    private JSplitPane mainSplit;
    private JSplitPane workSplit;
    private javax.swing.Timer elapsedTimer;
    private volatile Thread workerThread;
    private volatile Vm.StopToken stopToken;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private long taskStart;
    private String taskBusyLabel = "";
    private Path lastOpenDir;
    private final Path initialFile;

    public static void main(String[] args) {
        installLookAndFeel();
        Path initial = null;
        if (args.length > 0) {
            try {
                initial = Paths.get(args[0]).toAbsolutePath().normalize();
            } catch (Exception ignored) {
            }
        }
        final Path startup = initial;
        SwingUtilities.invokeLater(() -> new Gui(startup).show());
    }

    private Gui(Path initialFile) {
        this.initialFile = initialFile;
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        lastOpenDir = cwd;

        frontendBox = new JComboBox<>(new String[]{
                "自动识别", "UniVM (.uni)", "C", "C++", "Python", "Lua", "Rust", "Lisp"});
        execModeBox = new JComboBox<>(new String[]{"Tier 0 + JIT", "仅解释器"});
        backendBox = new JComboBox<>(new String[]{
                "不生成后端", "C", "C++", "Go", "Java JAR", "Python", "WASM"});
        backendOutField.setText(cwd.resolve("out").toString());
        backendOutField.setToolTipText("Generate 的输出目录");

        frame = new JFrame("UniVM " + VERSION);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(Icons.appImage(64));
        frame.setMinimumSize(new Dimension(1024, 680));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(Math.max(1024, Math.min(1380, screen.width - 100)),
                Math.max(680, Math.min(920, screen.height - 100)));
        frame.setLocationRelativeTo(null);

        fileTree = new FileTreePanel(cwd, this::openFile);
        configureConsole();
        createActions();
        buildUi();
        installWindowHandler();
        openStartupFile();
        updateActionStates();
    }

    private void show() {
        frame.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            if (mainSplit != null) mainSplit.setDividerLocation(245);
            if (workSplit != null) workSplit.setDividerLocation(0.70);
        });
    }

    private static void installLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ex) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }
        UIManager.put("nimbusBase", new Color(0x2F80ED));
        UIManager.put("nimbusBlueGrey", new Color(0xD6DCE3));
        UIManager.put("nimbusLightBackground", Color.WHITE);
        UIManager.put("control", new Color(0xF6F8FA));
        UIManager.put("text", new Color(0x2C3E50));
        UIManager.put("ToolTip.background", new Color(0x2C3E50));
        UIManager.put("ToolTip.foreground", Color.WHITE);
        UIManager.put("ToolTip.font", new Font("SansSerif", Font.PLAIN, 12));
    }

    private void configureConsole() {
        outputArea.setEditable(false);
        outputArea.setFont(consoleFont(13));
        outputArea.setBackground(CONSOLE_BG);
        outputArea.setForeground(CONSOLE_FG);
        outputArea.setCaretColor(Color.WHITE);
        outputArea.setSelectionColor(new Color(0x264F78));
        outputArea.setSelectedTextColor(Color.WHITE);
        outputArea.setMargin(new Insets(8, 10, 8, 10));
        outputArea.setTabSize(4);
        outputArea.setLineWrap(false);
    }

    private void buildUi() {
        frame.setJMenuBar(buildMenuBar());

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolBar(), BorderLayout.NORTH);
        top.add(buildConfigBar(), BorderLayout.CENTER);
        frame.add(top, BorderLayout.NORTH);

        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        tabs.addChangeListener(e -> {
            updateWindowTitle();
            updatePosition();
            updateActionStates();
        });

        JPanel outputPanel = buildOutputPanel();
        workSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, outputPanel);
        workSplit.setOneTouchExpandable(true);
        workSplit.setContinuousLayout(true);
        workSplit.setResizeWeight(0.70);
        workSplit.setDividerSize(7);
        workSplit.setBorder(BorderFactory.createEmptyBorder());

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileTree, workSplit);
        mainSplit.setOneTouchExpandable(true);
        mainSplit.setContinuousLayout(true);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setDividerSize(7);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        frame.add(mainSplit, BorderLayout.CENTER);
        frame.add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("文件");
        addMenuItem(file, newAction);
        addMenuItem(file, openAction);
        file.addSeparator();
        addMenuItem(file, saveAction);
        addMenuItem(file, saveAsAction);
        addMenuItem(file, saveAllAction);
        file.addSeparator();
        addMenuItem(file, closeTabAction);
        addMenuItem(file, exitAction);
        bar.add(file);

        JMenu edit = new JMenu("编辑");
        addMenuItem(edit, undoAction);
        addMenuItem(edit, redoAction);
        edit.addSeparator();
        addMenuItem(edit, cutAction);
        addMenuItem(edit, copyAction);
        addMenuItem(edit, pasteAction);
        addMenuItem(edit, selectAllAction);
        edit.addSeparator();
        addMenuItem(edit, formatAction);
        addMenuItem(edit, foldAction);
        addMenuItem(edit, unfoldAction);
        bar.add(edit);

        JMenu run = new JMenu("运行");
        addMenuItem(run, compileAction);
        addMenuItem(run, generateAction);
        addMenuItem(run, runAction);
        addMenuItem(run, stopAction);
        run.addSeparator();
        addMenuItem(run, profileAction);
        run.addSeparator();
        addMenuItem(run, refreshAction);
        addMenuItem(run, clearOutputAction);
        bar.add(run);

        JMenu help = new JMenu("帮助");
        addMenuItem(help, shortcutsAction);
        addMenuItem(help, aboutAction);
        bar.add(help);
        return bar;
    }

    private void addMenuItem(JMenu menu, Action action) {
        JMenuItem item = new JMenuItem(action);
        Object description = action.getValue(Action.SHORT_DESCRIPTION);
        if (description != null) item.setToolTipText(description.toString());
        menu.add(item);
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        bar.setBackground(TOP_BG);
        JLabel brand = new JLabel(" UniVM ");
        brand.setFont(new Font("SansSerif", Font.BOLD, 15));
        brand.setForeground(new Color(0x1F5FBF));
        tinyGap(bar);
        bar.add(brand);
        tinyGap(bar);
        bar.add(toolbarButton(newAction));
        bar.add(toolbarButton(openAction));
        bar.add(toolbarButton(saveAction));
        bar.addSeparator(new Dimension(8, 0));
        bar.add(toolbarButton(compileAction));
        bar.add(toolbarButton(generateAction));
        bar.add(toolbarButton(runAction));
        bar.add(toolbarButton(stopAction));
        bar.addSeparator(new Dimension(8, 0));
        bar.add(toolbarButton(formatAction));
        bar.add(toolbarButton(foldAction));
        bar.add(toolbarButton(unfoldAction));
        bar.addSeparator(new Dimension(8, 0));
        bar.add(toolbarButton(refreshAction));
        bar.add(Box.createHorizontalGlue());
        JLabel version = new JLabel("v" + VERSION + "  ");
        version.setFont(version.getFont().deriveFont(11f));
        version.setForeground(MUTED);
        bar.add(version);
        return bar;
    }

    private void tinyGap(JToolBar bar) {
        bar.add(Box.createHorizontalStrut(5));
    }

    private JButton toolbarButton(Action action) {
        JButton button = new JButton(action);
        button.setHideActionText(true);
        button.setFocusable(false);
        button.setPreferredSize(new Dimension(32, 30));
        button.setMaximumSize(new Dimension(36, 30));
        button.setMargin(new Insets(2, 4, 2, 4));
        Object description = action.getValue(Action.SHORT_DESCRIPTION);
        if (description != null) button.setToolTipText(description.toString());
        return button;
    }

    private JPanel buildConfigBar() {
        JPanel config = new JPanel(new BorderLayout(8, 0));
        config.setBackground(new Color(0xFAFBFC));
        config.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        frontendBox.setPreferredSize(new Dimension(120, 25));
        frontendBox.setToolTipText("源语言前端；自动识别会根据文件扩展名选择");
        execModeBox.setPreferredSize(new Dimension(132, 25));
        execModeBox.setToolTipText("关闭 JIT 可复现解释器路径");
        backendBox.setPreferredSize(new Dimension(126, 25));
        backendBox.setToolTipText("Generate 生成的后端目标");
        typeCheckBox.setOpaque(false);
        typeCheckBox.setToolTipText("关闭后向编译/运行命令传入 --no-check");

        left.add(fieldLabel("前端"));
        left.add(frontendBox);
        left.add(fieldLabel("执行"));
        left.add(execModeBox);
        left.add(typeCheckBox);
        left.add(fieldLabel("后端"));
        left.add(backendBox);
        left.add(fieldLabel("输出"));
        left.add(backendOutField);
        JButton outBrowse = new JButton("...");
        outBrowse.setFocusable(false);
        outBrowse.setMargin(new Insets(0, 5, 0, 5));
        outBrowse.setToolTipText("选择 Generate 输出目录");
        outBrowse.addActionListener(e -> chooseBackendDirectory());
        left.add(outBrowse);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(primaryButton(compileAction, "Configure"));
        right.add(primaryButton(generateAction, "Generate"));
        JButton run = primaryButton(runAction, "Run");
        run.setFont(run.getFont().deriveFont(Font.BOLD));
        right.add(run);
        right.add(primaryButton(stopAction, "Stop"));

        config.add(left, BorderLayout.CENTER);
        config.add(right, BorderLayout.EAST);
        return config;
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(11f));
        label.setForeground(MUTED);
        return label;
    }

    private JButton primaryButton(Action action, String text) {
        JButton button = new JButton(action);
        button.setText(text);
        button.setFocusable(false);
        button.setMargin(new Insets(4, 10, 4, 10));
        Object description = action.getValue(Action.SHORT_DESCRIPTION);
        if (description != null) button.setToolTipText(description.toString());
        return button;
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(CONSOLE_HEADER);
        header.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 6));
        JLabel title = new JLabel("控制台 / OUTPUT");
        title.setForeground(new Color(0xC8C8C8));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        header.add(title, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        buttons.setOpaque(false);
        buttons.add(darkButton("复制", this::copyOutput));
        buttons.add(darkButton("清空", this::clearOutput));
        header.add(buttons, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(outputArea);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(CONSOLE_BG);
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(0, 110));
        return panel;
    }

    private JButton darkButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setForeground(new Color(0xC8C8C8));
        button.setFont(button.getFont().deriveFont(11f));
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> action.run());
        return button;
    }

    private JPanel buildStatusBar() {
        JPanel status = new JPanel(new BorderLayout(8, 0));
        status.setBackground(new Color(0xF1F4F7));
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(3, 9, 3, 9)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setForeground(MUTED);
        busyBar.setIndeterminate(true);
        busyBar.setVisible(false);
        busyBar.setPreferredSize(new Dimension(90, 11));
        left.add(statusLabel);
        left.add(busyBar);
        status.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        right.setOpaque(false);
        positionLabel.setFont(positionLabel.getFont().deriveFont(11f));
        languageLabel.setFont(languageLabel.getFont().deriveFont(11f));
        positionLabel.setForeground(MUTED);
        languageLabel.setForeground(MUTED);
        JLabel encoding = new JLabel("UTF-8");
        encoding.setFont(encoding.getFont().deriveFont(11f));
        encoding.setForeground(MUTED);
        right.add(positionLabel);
        right.add(separatorLabel());
        right.add(languageLabel);
        right.add(separatorLabel());
        right.add(encoding);
        status.add(right, BorderLayout.EAST);
        return status;
    }

    private JLabel separatorLabel() {
        JLabel label = new JLabel("|");
        label.setForeground(new Color(0xC4CDD6));
        return label;
    }

    private void createActions() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        newAction = action("新建", Icons.newFile(), "新建文件 (Ctrl+N)",
                KeyEvent.VK_N, menuMask, this::newFile);
        openAction = action("打开...", Icons.open(), "打开源文件 (Ctrl+O)",
                KeyEvent.VK_O, menuMask, this::openFileDialog);
        saveAction = action("保存", Icons.save(), "保存当前文件 (Ctrl+S)",
                KeyEvent.VK_S, menuMask, this::saveCurrent);
        saveAsAction = action("另存为...", Icons.save(), "另存为 (Ctrl+Shift+S)",
                KeyEvent.VK_S, menuMask | InputEvent.SHIFT_DOWN_MASK, this::saveCurrentAs);
        saveAllAction = action("全部保存", null, "保存全部标签页 (Ctrl+Alt+S)",
                KeyEvent.VK_S, menuMask | InputEvent.ALT_DOWN_MASK, this::saveAll);
        closeTabAction = action("关闭标签页", null, "关闭当前标签页 (Ctrl+W)",
                KeyEvent.VK_W, menuMask, this::closeCurrentTab);
        exitAction = action("退出", null, "保存提示后退出 UniVM", 0, 0,
                this::exitApplication);

        undoAction = action("撤销", null, "撤销 (Ctrl+Z)",
                KeyEvent.VK_Z, menuMask, () -> {
                    EditorPane editor = currentEditor();
                    if (editor != null) editor.undo();
                });
        redoAction = action("重做", null, "重做 (Ctrl+Y)",
                KeyEvent.VK_Y, menuMask, () -> {
                    EditorPane editor = currentEditor();
                    if (editor != null) editor.redo();
                });
        cutAction = action("剪切", null, "剪切 (Ctrl+X)",
                KeyEvent.VK_X, menuMask, () -> activeTextComponent().cut());
        copyAction = action("复制", null, "复制 (Ctrl+C)",
                KeyEvent.VK_C, menuMask, () -> activeTextComponent().copy());
        pasteAction = action("粘贴", null, "粘贴 (Ctrl+V)",
                KeyEvent.VK_V, menuMask, () -> activeTextComponent().paste());
        selectAllAction = action("全选", null, "全选 (Ctrl+A)",
                KeyEvent.VK_A, menuMask, () -> activeTextComponent().selectAll());

        formatAction = action("格式化代码", Icons.format(),
                "一键格式化缩进 (Ctrl+Shift+F)",
                KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                this::formatCurrent);
        foldAction = action("折叠注释", Icons.fold(),
                "折叠 /* ... */ 注释 (Ctrl+Alt+C)",
                KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK,
                this::foldCurrent);
        unfoldAction = action("展开注释", Icons.unfold(),
                "展开已折叠的注释 (Ctrl+Alt+U)",
                KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK,
                this::unfoldCurrent);

        compileAction = action("编译检查", Icons.compile(),
                "Configure (Ctrl+B)：编译并输出 UniVM 字节码",
                KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK, this::compileCurrent);
        generateAction = action("生成后端", Icons.generate(),
                "Generate (Ctrl+G)：生成所选后端产物",
                KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK, this::generateCurrent);
        runAction = action("运行", Icons.run(),
                "Run (F5)：在 UniVM 中执行当前文件",
                KeyEvent.VK_F5, 0, this::runCurrent);
        stopAction = action("停止", Icons.stop(),
                "Stop (Shift+F5)：中断运行中的 VM",
                KeyEvent.VK_F5, InputEvent.SHIFT_DOWN_MASK, this::stopExecution);
        stopAction.setEnabled(false);
        profileAction = action("性能分析运行", null,
                "带 JIT/OSR/堆统计运行 (Ctrl+Shift+P)",
                KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                this::profileCurrent);

        refreshAction = action("刷新文件树", Icons.refresh(),
                "刷新工作区文件树 (F6)",
                KeyEvent.VK_F6, 0, () -> {
                    fileTree.refresh();
                    setStatus("文件树已刷新", MUTED);
                });
        clearOutputAction = action("清空控制台", null, "清空控制台输出", 0, 0,
                this::clearOutput);
        copyOutputAction = action("复制控制台内容", null, "复制控制台全部文本", 0, 0,
                this::copyOutput);
        shortcutsAction = action("快捷键", null, "查看快捷键列表 (F1)",
                KeyEvent.VK_F1, 0, this::showShortcuts);
        aboutAction = action("关于 UniVM", null, "关于 UniVM 1.2.0", 0, 0,
                this::showAbout);
    }

    private Action action(String name, Icon icon, String description,
                          int keyCode, int modifiers, Runnable body) {
        AbstractAction action = new AbstractAction(name, icon) {
            @Override public void actionPerformed(ActionEvent e) {
                body.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        if (keyCode != 0) {
            action.putValue(Action.ACCELERATOR_KEY,
                    KeyStroke.getKeyStroke(keyCode, modifiers));
        }
        return action;
    }

    private JTextComponent activeTextComponent() {
        EditorPane editor = currentEditor();
        return editor == null ? new JTextPane() : editor.getTextPane();
    }

    private EditorPane currentEditor() {
        Component component = tabs.getSelectedComponent();
        return component instanceof EditorPane ? (EditorPane) component : null;
    }

    private List<EditorPane> allEditors() {
        List<EditorPane> result = new ArrayList<>();
        for (Component component : tabs.getComponents()) {
            if (component instanceof EditorPane) result.add((EditorPane) component);
        }
        return result;
    }

    private void newFile() {
        EditorPane editor = new EditorPane(null, "");
        addEditor(editor);
        setStatus("已新建未命名文件", MUTED);
        SwingUtilities.invokeLater(editor.getTextPane()::requestFocusInWindow);
    }

    private void openFileDialog() {
        JFileChooser chooser = new JFileChooser(baseDirectory().toFile());
        chooser.setDialogTitle("打开源文件");
        chooser.setFileFilter(sourceFilter());
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("所有文件", "*"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    private Path baseDirectory() {
        EditorPane editor = currentEditor();
        if (editor != null && editor.getFile() != null && editor.getFile().getParent() != null) {
            return editor.getFile().getParent();
        }
        return lastOpenDir == null ? Paths.get("").toAbsolutePath() : lastOpenDir;
    }

    private FileNameExtensionFilter sourceFilter() {
        return new FileNameExtensionFilter(
                "UniVM 源文件 (*.uni;*.py;*.c;*.cpp;*.lua;*.rs;*.lisp)",
                "uni", "py", "c", "h", "cpp", "cc", "cxx", "hpp", "lua", "rs", "lisp");
    }

    private void openFile(Path path) {
        if (path == null) return;
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            setStatus("无法打开：文件不存在", ERROR);
            return;
        }
        EditorPane already = openFiles.get(normalized);
        if (already != null) {
            tabs.setSelectedComponent(already);
            setStatus("已切换到 " + already.getDisplayName(), MUTED);
            return;
        }
        try {
            String text = Files.readString(normalized, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            EditorPane editor = new EditorPane(normalized, text);
            openFiles.put(normalized, editor);
            addEditor(editor);
            lastOpenDir = normalized.getParent();
            setStatus("已打开 " + normalized.getFileName(), MUTED);
            SwingUtilities.invokeLater(editor.getTextPane()::requestFocusInWindow);
        } catch (IOException ex) {
            setStatus("打开失败：" + ex.getMessage(), ERROR);
            JOptionPane.showMessageDialog(frame, "无法读取文件：\n" + normalized + "\n\n" + ex,
                    "打开失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addEditor(EditorPane editor) {
        editor.setDirtyListener(() -> updateTabHeader(editor));
        editor.setCaretListener(() -> {
            if (currentEditor() == editor) {
                updatePosition();
                updateActionStates();
            }
        });
        JPanel header = createTabHeader(editor);
        tabHeaders.put(editor, header);
        tabs.addTab(editor.getDisplayName(), editor);
        int index = tabs.indexOfComponent(editor);
        if (index >= 0) tabs.setTabComponentAt(index, header);
        tabs.setSelectedComponent(editor);
        updateTabHeader(editor);
        updateActionStates();
    }

    private JPanel createTabHeader(EditorPane editor) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(editor.getDisplayName());
        label.setFont(label.getFont().deriveFont(12f));
        tabLabels.put(editor, label);
        header.add(label);

        JButton close = new JButton(Icons.close());
        close.setToolTipText("关闭标签页");
        close.setFocusable(false);
        close.setPreferredSize(new Dimension(16, 16));
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setBorder(BorderFactory.createEmptyBorder());
        close.setContentAreaFilled(false);
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addActionListener(e -> closeEditor(editor));
        header.add(close);
        return header;
    }

    private void updateTabHeader(EditorPane editor) {
        JLabel label = tabLabels.get(editor);
        String suffix = editor.isDirty() ? " *" : "";
        if (label != null) label.setText(editor.getDisplayName() + suffix);
        int index = tabs.indexOfComponent(editor);
        if (index >= 0) {
            tabs.setTitleAt(index, editor.getDisplayName() + suffix);
            Path file = editor.getFile();
            tabs.setToolTipTextAt(index, file == null ? "未保存文件" : file.toString());
        }
        updateWindowTitle();
        updateActionStates();
    }

    private void updateWindowTitle() {
        EditorPane editor = currentEditor();
        if (editor == null) {
            frame.setTitle("UniVM " + VERSION);
            return;
        }
        String path = editor.getFile() == null
                ? editor.getDisplayName() : editor.getFile().toString();
        frame.setTitle("UniVM " + VERSION + " — " + path
                + (editor.isDirty() ? " *" : ""));
    }

    private void updatePosition() {
        EditorPane editor = currentEditor();
        if (editor == null) {
            positionLabel.setText("行 -, 列 -");
            languageLabel.setText("-");
            return;
        }
        int[] lineColumn = editor.getCaretLineColumn();
        positionLabel.setText("行 " + lineColumn[0] + ", 列 " + lineColumn[1]);
        languageLabel.setText(editor.getLanguageLabel());
    }

    private void saveCurrent() {
        EditorPane editor = currentEditor();
        if (editor != null) saveEditor(editor);
    }

    private void saveCurrentAs() {
        EditorPane editor = currentEditor();
        if (editor != null) saveEditorAs(editor);
    }

    private void saveAll() {
        for (EditorPane editor : allEditors()) {
            if (!saveEditor(editor)) return;
        }
        setStatus("全部文件已保存", SUCCESS);
    }

    private boolean saveEditor(EditorPane editor) {
        if (editor.getFile() == null) return saveEditorAs(editor);
        Path file = editor.getFile();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, editor.getSourceText(), StandardCharsets.UTF_8);
            editor.markSaved();
            updateTabHeader(editor);
            setStatus("已保存 " + file.getFileName(), SUCCESS);
            return true;
        } catch (IOException ex) {
            setStatus("保存失败：" + ex.getMessage(), ERROR);
            JOptionPane.showMessageDialog(frame, "无法保存文件：\n" + file + "\n\n" + ex,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean saveEditorAs(EditorPane editor) {
        JFileChooser chooser = new JFileChooser(baseDirectory().toFile());
        chooser.setDialogTitle("另存为");
        chooser.setFileFilter(sourceFilter());
        chooser.setSelectedFile(new File(baseDirectory().toFile(), editor.getDisplayName()));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return false;

        Path target = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        String name = target.getFileName().toString();
        if (!name.contains(".")) {
            target = target.resolveSibling(name + editor.currentSuffix());
        }
        EditorPane existing = openFiles.get(target);
        if (existing != null && existing != editor) {
            setStatus("该文件已在另一个标签页中打开", ERROR);
            JOptionPane.showMessageDialog(frame, "该文件已在另一个标签页中打开：\n" + target,
                    "无法另存为", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, editor.getSourceText(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            setStatus("保存失败：" + ex.getMessage(), ERROR);
            JOptionPane.showMessageDialog(frame, "无法保存文件：\n" + target + "\n\n" + ex,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        Path old = editor.getFile();
        if (old != null) openFiles.remove(old);
        editor.setFile(target);
        openFiles.put(target, editor);
        editor.markSaved();
        lastOpenDir = target.getParent();
        updateTabHeader(editor);
        fileTree.refresh();
        setStatus("已另存为 " + target.getFileName(), SUCCESS);
        return true;
    }

    private boolean confirmClose(EditorPane editor) {
        if (!editor.isDirty()) return true;
        String name = editor.getFile() == null
                ? editor.getDisplayName() : editor.getFile().toString();
        Object[] options = {"保存", "不保存", "取消"};
        int choice = JOptionPane.showOptionDialog(frame,
                "文件已修改，是否保存？\n\n" + name,
                "未保存的更改", JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) return saveEditor(editor);
        return choice == 1;
    }

    private void closeCurrentTab() {
        EditorPane editor = currentEditor();
        if (editor != null) closeEditor(editor);
    }

    private void closeEditor(EditorPane editor) {
        if (!confirmClose(editor)) return;
        Path file = editor.getFile();
        if (file != null) openFiles.remove(file);
        tabs.remove(editor);
        tabLabels.remove(editor);
        tabHeaders.remove(editor);
        editor.dispose();
        if (tabs.getTabCount() == 0) newFile();
        updateWindowTitle();
        updatePosition();
        updateActionStates();
    }

    private void formatCurrent() {
        if (running) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;
        boolean changed = editor.formatCode();
        updateTabHeader(editor);
        updateActionStates();
        setStatus(changed ? "已格式化代码" : "代码格式无变化",
                changed ? SUCCESS : MUTED);
    }

    private void foldCurrent() {
        if (running) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;
        boolean folded = editor.foldComments();
        updateTabHeader(editor);
        updateActionStates();
        if (folded) {
            setStatus("注释已折叠（只读）· 双击编辑区或点击展开恢复", SUCCESS);
        } else {
            setStatus("当前文件没有可折叠的 /* ... */ 注释", MUTED);
        }
    }

    private void unfoldCurrent() {
        if (running) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;
        if (editor.unfoldComments()) {
            updateTabHeader(editor);
            updateActionStates();
            setStatus("注释已展开", SUCCESS);
        }
    }

    private void compileCurrent() {
        if (!ensureIdle()) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;
        List<String> args = new ArrayList<>();
        args.add("--ir");
        addTypeCheckFlag(args);
        startTask("Configure · 编译检查", "编译中", args, null);
    }

    private void generateCurrent() {
        if (!ensureIdle()) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;
        int backend = backendBox.getSelectedIndex();
        if (backend <= 0) {
            setStatus("请先选择后端", WARNING);
            JOptionPane.showMessageDialog(frame,
                    "请先在顶部“后端”下拉框中选择一个目标（C/C++/Go/Java/Python/WASM）。",
                    "尚未选择后端", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Path outDir;
        String outText = backendOutField.getText().trim();
        if (outText.isEmpty()) {
            outDir = Paths.get("").toAbsolutePath().resolve("out");
        } else {
            outDir = Paths.get(outText).toAbsolutePath().normalize();
        }
        try {
            Files.createDirectories(outDir);
        } catch (IOException ex) {
            setStatus("无法创建输出目录", ERROR);
            JOptionPane.showMessageDialog(frame, "无法创建输出目录：\n" + outDir + "\n\n" + ex,
                    "生成失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Path base = outDir.resolve("out");
        List<String> args = new ArrayList<>();
        Path wasmHtml = null;
        switch (backend) {
            case 1:
                args.add("--c");
                args.add(base.toString());
                break;
            case 2:
                args.add("--cpp");
                args.add(base.toString());
                break;
            case 3:
                args.add("--go");
                args.add(base + ".exe");
                break;
            case 4:
                args.add("--jar");
                args.add(base + ".jar");
                break;
            case 5:
                args.add("--py");
                args.add(base.toString());
                break;
            case 6:
                args.add("--wasm");
                args.add(base.toString());
                wasmHtml = Paths.get(base + ".html");
                break;
            default:
                return;
        }
        addTypeCheckFlag(args);
        startTask("Generate · " + backendBox.getSelectedItem(), "生成中", args, wasmHtml);
    }

    private void runCurrent() {
        runCurrent(false);
    }

    private void profileCurrent() {
        runCurrent(true);
    }

    private void runCurrent(boolean profile) {
        if (!ensureIdle()) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;
        List<String> args = new ArrayList<>();
        args.add("--run");
        if (profile) args.add("--profile");
        if (execModeBox.getSelectedIndex() == 1) args.add("--no-jit");
        addTypeCheckFlag(args);
        startTask(profile ? "Run · 性能分析" : "Run · 执行",
                profile ? "性能分析中" : "运行中", args, null);
    }

    private void addTypeCheckFlag(List<String> args) {
        if (!typeCheckBox.isSelected()) args.add("--no-check");
    }

    private boolean ensureIdle() {
        if (running) {
            setStatus("当前已有任务在运行，请先停止", WARNING);
            return false;
        }
        return true;
    }

    private void startTask(String taskName, String busyLabel,
                           List<String> extraArgs, Path wasmHtml) {
        if (running) return;
        EditorPane editor = currentEditor();
        if (editor == null) return;

        String suffix = selectedSuffix(editor);
        Path temp;
        try {
            temp = Files.createTempFile("univm-gui-", suffix);
            Files.writeString(temp, editor.getSourceText(), StandardCharsets.UTF_8);
            temp.toFile().deleteOnExit();
        } catch (IOException ex) {
            setStatus("无法创建临时源文件", ERROR);
            JOptionPane.showMessageDialog(frame, "无法创建临时源文件：\n" + ex,
                    "任务启动失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(temp.toString());
        command.addAll(extraArgs);
        final List<String> argv = Collections.unmodifiableList(command);
        final Path tempFile = temp;
        final Path wasmPage = wasmHtml;
        final Vm.StopToken token = new Vm.StopToken();
        stopToken = token;
        stopRequested = false;

        outputArea.setText(">> " + taskName + "\n"
                + "源: " + editor.getDisplayName() + "\n\n");
        beginBusy(busyLabel);
        Thread worker = new Thread(() -> executeTask(taskName, argv, token, tempFile, wasmPage),
                "UniVM-GUI-Task");
        worker.setDaemon(true);
        workerThread = worker;
        worker.start();
    }

    private String selectedSuffix(EditorPane editor) {
        int index = frontendBox.getSelectedIndex();
        String[] suffixes = {null, ".uni", ".c", ".cpp", ".py", ".lua", ".rs", ".lisp"};
        if (index > 0 && index < suffixes.length) return suffixes[index];
        String suffix = editor.currentSuffix();
        return suffix == null || suffix.isEmpty() ? ".uni" : suffix;
    }

    private void executeTask(String taskName, List<String> argv, Vm.StopToken token,
                             Path tempFile, Path wasmHtml) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        Throwable failure = null;
        boolean stopped = false;
        try {
            System.setOut(capture);
            System.setErr(capture);
            Main.run(argv.toArray(new String[0]), this::askInput, token);
        } catch (Vm.VmStoppedException ex) {
            stopped = true;
        } catch (InterruptedException ex) {
            stopped = true;
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            failure = ex;
            ex.printStackTrace(capture);
        } finally {
            if (token.isStopped()) stopped = true;
            capture.flush();
            System.setOut(oldOut);
            System.setErr(oldErr);
            capture.close();
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        final Throwable taskFailure = failure;
        final boolean taskStopped = stopped;
        SwingUtilities.invokeLater(() -> completeTask(taskName, output,
                taskFailure, taskStopped, wasmHtml));
    }

    private void completeTask(String taskName, String output, Throwable failure,
                              boolean stopped, Path wasmHtml) {
        if (output == null || output.isBlank()) {
            outputArea.append("(无输出)\n");
        } else {
            outputArea.append(output);
            if (!output.endsWith("\n")) outputArea.append("\n");
        }
        double seconds = (System.nanoTime() - taskStart) / 1_000_000_000.0;
        String elapsed = String.format(Locale.ROOT, "%.2f s", seconds);
        if (failure != null) {
            outputArea.append("\n[失败] " + failure + "\n");
            setStatus("执行失败 · " + elapsed, ERROR);
        } else if (stopped) {
            outputArea.append("\n[已停止] 用户请求终止执行\n");
            setStatus("已停止 · " + elapsed, WARNING);
        } else {
            outputArea.append("\n[完成] " + taskName + " · " + elapsed + "\n");
            setStatus(successStatus(taskName) + " · " + elapsed, SUCCESS);
            if (wasmHtml != null && Files.exists(wasmHtml)) {
                openBrowser(wasmHtml);
                setStatus("生成成功，已打开浏览器测试页 · " + elapsed, SUCCESS);
            }
        }
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
        endBusy();
    }

    private String successStatus(String taskName) {
        if (taskName.startsWith("Configure")) return "编译成功";
        if (taskName.startsWith("Generate")) return "生成成功";
        if (taskName.startsWith("Run")) return "运行成功";
        return "执行完成";
    }

    private String askInput(String prompt) {
        if (!frame.isDisplayable()) return "";
        final String[] answer = {""};
        try {
            SwingUtilities.invokeAndWait(() -> {
                String value = JOptionPane.showInputDialog(frame, prompt, "UniVM 输入",
                        JOptionPane.QUESTION_MESSAGE);
                answer[0] = value == null ? "" : value;
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (InvocationTargetException ex) {
            return "";
        }
        return answer[0];
    }

    private void beginBusy(String busyLabel) {
        running = true;
        stopRequested = false;
        taskBusyLabel = busyLabel;
        taskStart = System.nanoTime();
        busyBar.setVisible(true);
        busyBar.setIndeterminate(true);
        setStatus(busyLabel + "…", ACCENT);
        updateActionStates();
        if (elapsedTimer != null) elapsedTimer.stop();
        elapsedTimer = new javax.swing.Timer(250, e -> {
            if (running && !stopRequested) {
                setStatus(taskBusyLabel + "… " + elapsedText(), ACCENT);
            }
        });
        elapsedTimer.start();
    }

    private String elapsedText() {
        long ms = (System.nanoTime() - taskStart) / 1_000_000;
        if (ms < 1000) return ms + " ms";
        return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private void endBusy() {
        running = false;
        stopRequested = false;
        stopToken = null;
        workerThread = null;
        if (elapsedTimer != null) {
            elapsedTimer.stop();
            elapsedTimer = null;
        }
        busyBar.setVisible(false);
        updateActionStates();
    }

    private void stopExecution() {
        if (!running || stopRequested) return;
        stopRequested = true;
        Vm.StopToken token = stopToken;
        if (token != null) token.stop();
        Thread worker = workerThread;
        if (worker != null) worker.interrupt();
        outputArea.append("\n[停止] 已发送终止信号，正在等待 VM 退出…\n");
        setStatus("正在停止…", WARNING);
        updateActionStates();
    }

    private void updateActionStates() {
        EditorPane editor = currentEditor();
        boolean hasEditor = editor != null;
        boolean busy = running;

        compileAction.setEnabled(!busy && hasEditor);
        generateAction.setEnabled(!busy && hasEditor);
        runAction.setEnabled(!busy && hasEditor);
        profileAction.setEnabled(!busy && hasEditor);
        formatAction.setEnabled(!busy && hasEditor);
        foldAction.setEnabled(!busy && hasEditor && !editor.isFolded());
        unfoldAction.setEnabled(!busy && hasEditor && editor.isFolded());
        stopAction.setEnabled(busy);
        saveAction.setEnabled(hasEditor);
        saveAsAction.setEnabled(hasEditor);
        saveAllAction.setEnabled(hasEditor);
        closeTabAction.setEnabled(hasEditor);
        undoAction.setEnabled(!busy && hasEditor && editor.canUndo());
        redoAction.setEnabled(!busy && hasEditor && editor.canRedo());
    }

    private void updateActionStatesSafely() {
        if (SwingUtilities.isEventDispatchThread()) updateActionStates();
        else SwingUtilities.invokeLater(this::updateActionStates);
    }

    private void chooseBackendDirectory() {
        JFileChooser chooser = new JFileChooser(backendOutField.getText().trim());
        chooser.setDialogTitle("选择 Generate 输出目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            backendOutField.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
        }
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void clearOutput() {
        outputArea.setText("");
        setStatus("控制台已清空", MUTED);
    }

    private void copyOutput() {
        if (outputArea.getText().isEmpty()) return;
        outputArea.selectAll();
        outputArea.copy();
        outputArea.setCaretPosition(0);
        setStatus("控制台内容已复制到剪贴板", MUTED);
    }

    private void openBrowser(Path html) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(html.toUri());
            } else {
                Runtime.getRuntime().exec(new String[]{
                        "rundll32", "url.dll,FileProtocolHandler", html.toUri().toString()});
            }
        } catch (Exception ex) {
            setStatus("浏览器打开失败：" + ex.getMessage(), WARNING);
        }
    }

    private void showShortcuts() {
        String text = "文件\n"
                + "  Ctrl+N        新建文件\n"
                + "  Ctrl+O        打开文件\n"
                + "  Ctrl+S        保存\n"
                + "  Ctrl+Shift+S  另存为\n"
                + "  Ctrl+W        关闭标签页\n\n"
                + "编辑\n"
                + "  Ctrl+Z / Ctrl+Y          撤销 / 重做\n"
                + "  Ctrl+Shift+F             一键格式化\n"
                + "  Ctrl+Alt+C               折叠 /* ... */ 注释\n"
                + "  Ctrl+Alt+U               展开注释\n\n"
                + "运行\n"
                + "  Ctrl+B        Configure：编译并输出字节码\n"
                + "  Ctrl+G        Generate：生成后端产物\n"
                + "  F5            Run：在 UniVM 中执行\n"
                + "  Shift+F5      Stop：强制停止运行中的 VM\n"
                + "  Ctrl+Shift+P  性能分析运行\n\n"
                + "提示：折叠注释期间编辑器只读，双击编辑区或按 Ctrl+Alt+U 恢复。";
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(consoleFont(13));
        area.setBackground(new Color(0xFAFBFC));
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(500, 470));
        JOptionPane.showMessageDialog(frame, scroll, "UniVM 快捷键", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAbout() {
        String text = "<html><div style='width: 380px;'>"
                + "<h2 style='margin-bottom:2px;'>UniVM " + VERSION + "</h2>"
                + "<p style='color:#5D6D7E; margin-top:0;'>多语言虚拟机 · 商业级 IDE 工作台</p>"
                + "<p>本版本新增：现代化菜单/工具栏、实时状态栏、行号与语法高亮、"
                + "工作区文件树、多标签编辑、注释折叠、一键格式化、"
                + "Configure/Generate/Run/Stop 完整执行链与强制停止。</p>"
                + "<p style='color:#5D6D7E;'>快捷键：F1 查看完整列表。</p>"
                + "</div></html>";
        JOptionPane.showMessageDialog(frame, text, "关于 UniVM",
                JOptionPane.INFORMATION_MESSAGE,
                new ImageIcon(Icons.appImage(64)));
    }

    private void openStartupFile() {
        if (initialFile != null && Files.isRegularFile(initialFile)) {
            openFile(initialFile);
            return;
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path examples = cwd.resolve("examples");
        String[] preferred = {"hello_zh.uni", "test.uni", "control.uni", "jit.uni"};
        for (String name : preferred) {
            Path candidate = examples.resolve(name);
            if (Files.isRegularFile(candidate)) {
                openFile(candidate);
                return;
            }
        }
        if (Files.isDirectory(examples)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(examples, "*.uni")) {
                for (Path candidate : stream) {
                    openFile(candidate);
                    return;
                }
            } catch (IOException ignored) {
            }
        }
        newFile();
    }

    private void installWindowHandler() {
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });
    }

    private void exitApplication() {
        if (running) stopExecution();
        for (EditorPane editor : allEditors()) {
            if (!confirmClose(editor)) return;
        }
        frame.dispose();
        System.exit(0);
    }

    private static Font monoFont(float size) {
        String[] preferred = {"JetBrains Mono", "Cascadia Code", "Consolas",
                "Fira Code", "Source Code Pro", "DejaVu Sans Mono", "Monospaced"};
        Set<String> available = new HashSet<>();
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            available.add(name.toLowerCase(Locale.ROOT));
        }
        for (String name : preferred) {
            if (name.equals("Monospaced") || available.contains(name.toLowerCase(Locale.ROOT))) {
                return new Font(name, Font.PLAIN, Math.round(size));
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size));
    }

    private static Font consoleFont(float size) {
        String[] preferred = {"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Dialog"};
        Set<String> available = new HashSet<>();
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            available.add(name.toLowerCase(Locale.ROOT));
        }
        for (String name : preferred) {
            if (available.contains(name.toLowerCase(Locale.ROOT))) {
                return new Font(name, Font.PLAIN, Math.round(size));
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, Math.round(size));
    }
}
