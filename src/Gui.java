import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Gui {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Gui::createAndShow);
    }

    private static void createAndShow() {
        JFrame frame = new JFrame("UniVM");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 800);

        JTextField sourceField = new JTextField();
        sourceField.setEditable(false);
        JButton browseSource = new JButton("浏览...");

        JPanel sourcePanel = new JPanel(new BorderLayout(5, 5));
        sourcePanel.add(new JLabel("源文件:"), BorderLayout.WEST);
        sourcePanel.add(sourceField, BorderLayout.CENTER);
        sourcePanel.add(browseSource, BorderLayout.EAST);

        JComboBox<String> frontendBox = new JComboBox<>(new String[]{
                "自动识别", ".uni", "C 风格", "C++ 原生", "Python 风格", "Lua 风格", "Rust 风格", "Lisp 风格"
        });
        JComboBox<String> execModeBox = new JComboBox<>(new String[]{"解释器 + JIT", "解释器（--no-jit）"});
        JCheckBox typeCheckBox = new JCheckBox("启用类型检查", true);
        JComboBox<String> outputModeBox = new JComboBox<>(new String[]{"编译并运行", "仅编译", "输出字节码 --ir", "输出 profile --profile"});
        JComboBox<String> backendBox = new JComboBox<>(new String[]{"无", "C", "C++", "Go", "JAR", "Python", "WASM"});

        JTextField backendOutField = new JTextField();
        backendOutField.setEnabled(false);
        JButton browseBackendOut = new JButton("浏览...");
        browseBackendOut.setEnabled(false);

        JPanel configPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        configPanel.add(new JLabel("前端:"), c);
        c.gridx = 1; c.weightx = 1;
        configPanel.add(frontendBox, c);

        row++;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        configPanel.add(new JLabel("执行模式:"), c);
        c.gridx = 1; c.weightx = 1;
        configPanel.add(execModeBox, c);

        row++;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        configPanel.add(new JLabel("类型检查:"), c);
        c.gridx = 1; c.weightx = 1;
        configPanel.add(typeCheckBox, c);

        row++;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        configPanel.add(new JLabel("输出模式:"), c);
        c.gridx = 1; c.weightx = 1;
        configPanel.add(outputModeBox, c);

        row++;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        configPanel.add(new JLabel("后端:"), c);
        c.gridx = 1; c.weightx = 1;
        configPanel.add(backendBox, c);

        row++;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        configPanel.add(new JLabel("后端输出:"), c);
        c.gridx = 1; c.weightx = 1;
        JPanel outRow = new JPanel(new BorderLayout(5, 0));
        outRow.add(backendOutField, BorderLayout.CENTER);
        outRow.add(browseBackendOut, BorderLayout.EAST);
        configPanel.add(outRow, c);

        // 中间主区域：可编辑源码
        JTextArea codeArea = new JTextArea();
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        codeArea.setLineWrap(true);
        codeArea.setWrapStyleWord(true);
        codeArea.setTabSize(4);
        JScrollPane editorScroll = new JScrollPane(codeArea);
        editorScroll.setBorder(BorderFactory.createTitledBorder("代码编辑器"));

        // 底部输出区
        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("输出结果"));

        JButton configureBtn = new JButton("Configure");
        JButton generateBtn = new JButton("Generate");
        JButton runBtn = new JButton("Run");
        JButton clearBtn = new JButton("Clear");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttonPanel.add(clearBtn);
        buttonPanel.add(configureBtn);
        buttonPanel.add(generateBtn);
        buttonPanel.add(runBtn);

        browseSource.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(".");
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Uni/C/C++/Python/Lua/Rust/Lisp 源文件", "uni", "c", "cpp", "cc", "cxx", "py", "lua", "rs", "lisp"));
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                java.io.File file = chooser.getSelectedFile();
                sourceField.setText(file.getAbsolutePath());
                try {
                    codeArea.setText(new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    codeArea.setCaretPosition(0);
                } catch (Throwable ex) {
                    outputArea.setText("读取源文件失败：\n" + ex.getMessage());
                }
            }
        });

        backendBox.addActionListener(e -> {
            boolean enabled = backendBox.getSelectedIndex() > 0;
            backendOutField.setEnabled(enabled);
            browseBackendOut.setEnabled(enabled);
        });

        browseBackendOut.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(".");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                backendOutField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        clearBtn.addActionListener(e -> outputArea.setText(""));

        configureBtn.addActionListener(e -> {
            String src = sourceField.getText();
            if (src.isEmpty()) {
                outputArea.setText("请先选择源文件（用于确定语言后缀）");
                return;
            }

            java.util.List<String> args = new java.util.ArrayList<>();
            if (execModeBox.getSelectedIndex() == 1) args.add("--no-jit");
            if (!typeCheckBox.isSelected()) args.add("--no-check");

            String outMode = (String) outputModeBox.getSelectedItem();
            if ("输出字节码 --ir".equals(outMode)) args.add("--ir");
            else if ("输出 profile --profile".equals(outMode)) {
                args.add("--profile");
                args.add("--run");
            } else if ("编译并运行".equals(outMode)) args.add("--run");

            runInBackground(frame, src, codeArea.getText(), args, outputArea);
        });

        generateBtn.addActionListener(e -> {
            String src = sourceField.getText();
            if (src.isEmpty()) {
                outputArea.setText("请先选择源文件（用于确定语言后缀）");
                return;
            }

            int backendIdx = backendBox.getSelectedIndex();
            if (backendIdx == 0) { outputArea.setText("请先选择一个后端"); return; }

            String outPath = backendOutField.getText();
            if (outPath.isEmpty()) { outputArea.setText("请先选择后端输出路径"); return; }

            java.util.List<String> args = new java.util.ArrayList<>();
            switch (backendIdx) {
                case 1: args.add("--c");   args.add(outPath + "\\out"); break;
                case 2: args.add("--cpp"); args.add(outPath + "\\out"); break;
                case 3: args.add("--go");  args.add(outPath + "\\out.exe"); break;
                case 4: args.add("--jar"); args.add(outPath + "\\out.jar"); break;
                case 5: args.add("--py");  args.add(outPath + "\\out"); break;
                case 6: args.add("--wasm"); args.add(outPath + "\\out"); break;
            }

            runInBackground(frame, src, codeArea.getText(), args, outputArea);
        });

        runBtn.addActionListener(e -> {
            String src = sourceField.getText();
            if (src.isEmpty()) {
                outputArea.setText("请先选择源文件（用于确定语言后缀）");
                return;
            }

            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("--run");
            if (execModeBox.getSelectedIndex() == 1) args.add("--no-jit");
            if (!typeCheckBox.isSelected()) args.add("--no-check");

            runInBackground(frame, src, codeArea.getText(), args, outputArea);
        });

        JPanel top = new JPanel(new BorderLayout(5, 5));
        top.add(sourcePanel, BorderLayout.NORTH);
        top.add(configPanel, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        bottom.add(buttonPanel, BorderLayout.NORTH);
        bottom.add(outputScroll, BorderLayout.CENTER);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScroll, bottom);
        split.setResizeWeight(0.75);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setBorder(null);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        // 等窗口完成布局后再按比例定位，避免初始高度为 0 把编辑区压扁
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.72));
    }

    // 源码从编辑器取；写入带原后缀的临时文件后交给 Main，finally 必定删除。
    // input() 通过 JOptionPane 弹窗读取，结果同步回 VM。
    private static void runInBackground(JFrame parent, String sourcePath, String sourceText,
                                        java.util.List<String> args, JTextArea outputArea) {
        outputArea.setText("");
        new Thread(() -> {
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream ps = null;
            Path temp = null;

            try {
                ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
                System.setOut(ps);
                System.setErr(ps);

                temp = Files.createTempFile("univm_run_", sourceSuffix(sourcePath));
                Files.write(temp, sourceText.getBytes(StandardCharsets.UTF_8));

                java.util.List<String> fullArgs = new java.util.ArrayList<>();
                fullArgs.add(temp.toString());
                fullArgs.addAll(args);
                Main.run(fullArgs.toArray(new String[0]), prompt -> askInput(parent, prompt));

                int wasmArg = args.indexOf("--wasm");
                if (wasmArg >= 0 && wasmArg + 1 < args.size()) {
                    String base = args.get(wasmArg + 1);
                    if (base.toLowerCase().endsWith(".wat")) {
                        base = base.substring(0, base.length() - 4);
                    }
                    openInBrowser(Paths.get(base + ".html"));
                }

            } catch (Throwable t) {
                // Main.run 的编译/解析/类型错误统一进入下方输出区
                t.printStackTrace();
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Throwable delErr) {
                        System.err.println("[警告] 临时文件删除失败: " + temp);
                        delErr.printStackTrace();
                    }
                }

                if (ps != null) {
                    try { ps.flush(); } catch (Throwable ignored) { }
                }
                System.setOut(oldOut);
                System.setErr(oldErr);
                if (ps != null) {
                    try { ps.close(); } catch (Throwable ignored) { }
                }

                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() -> outputArea.setText(text));
            }
        }).start();
    }

    // 在 EDT 上弹出输入框；用户取消时返回空字符串，让 toInt() 得到 0。
    private static String askInput(JFrame parent, String prompt) {
        String message = (prompt == null || prompt.isEmpty()) ? "请输入：" : prompt;
        final String[] answer = {""};
        Runnable task = () -> {
            String value = JOptionPane.showInputDialog(
                    parent, message, "UniVM 输入", JOptionPane.QUESTION_MESSAGE);
            answer[0] = value == null ? "" : value;
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) task.run();
            else SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            System.err.println("[警告] GUI 输入弹窗失败: " + ex);
        }
        return answer[0];
    }

    // WASM 生成后自动打开配套 HTML；浏览器不能直接执行 .wat。
    private static void openInBrowser(Path html) {
        Path abs = html.toAbsolutePath();
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(abs.toUri());
                System.out.println("已在浏览器打开: " + abs);
                return;
            }
        } catch (Throwable ignored) {
            // 继续尝试平台命令
        }

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", abs.toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", abs.toString());
            } else {
                pb = new ProcessBuilder("xdg-open", abs.toString());
            }
            pb.start();
            System.out.println("已在浏览器打开: " + abs);
        } catch (Throwable t) {
            System.out.println("[警告] 自动打开浏览器失败，请手动打开: " + abs);
        }
    }

    // 保留原文件后缀（.lua/.rs/.lisp/.py/.c/.uni），供 Main 选择前端
    private static String sourceSuffix(String sourcePath) {
        String name = Paths.get(sourcePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot).toLowerCase();
        }
        return ".uni";
    }
}
