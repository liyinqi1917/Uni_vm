import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** Lazy workspace explorer used by the UniVM IDE. */
public class FileTreePanel extends JPanel {

    private static final Set<String> SOURCE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "uni", "py", "c", "h", "cpp", "cc", "cxx", "hpp", "lua", "rs", "lisp",
            "md", "txt", "json", "yml", "yaml", "toml"));
    private static final Set<String> IGNORED_DIRS = new HashSet<>(Arrays.asList(
            "out", "dist", "runtime", "app", "build", "target", "node_modules",
            ".git", ".idea", ".vscode", "exe", "bin", "obj", "logs"));

    private final Consumer<Path> openHandler;
    private final JTree tree;
    private final DefaultTreeModel model;
    private final JLabel workspaceLabel;
    private Path rootPath;

    public FileTreePanel(Path rootPath, Consumer<Path> openHandler) {
        super(new BorderLayout());
        this.openHandler = openHandler;
        this.rootPath = rootPath.toAbsolutePath().normalize();

        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 4));
        workspaceLabel = new JLabel("工作区");
        workspaceLabel.setFont(workspaceLabel.getFont().deriveFont(Font.BOLD, 12f));
        header.add(workspaceLabel, BorderLayout.CENTER);

        JButton refresh = new JButton(Icons.refresh());
        refresh.setToolTipText("刷新文件树");
        refresh.setFocusable(false);
        refresh.setContentAreaFilled(false);
        refresh.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        refresh.addActionListener(e -> refresh());
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        model = new DefaultTreeModel(buildRoot(this.rootPath));
        tree = new JTree(model);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 4));
        tree.setCellRenderer(new WorkspaceRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode) {
                    loadChildren((DefaultMutableTreeNode) last);
                }
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    activate(tree.getPathForLocation(e.getX(), e.getY()));
                }
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    activate(tree.getSelectionPath());
                }
            }
        });
        installPopupMenu();

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(250, 0));

        loadChildren((DefaultMutableTreeNode) model.getRoot());
        tree.expandRow(0);
        expandExamples((DefaultMutableTreeNode) model.getRoot());
    }

    public Path getRootPath() {
        return rootPath;
    }

    public JTree getTree() {
        return tree;
    }

    public void refresh() {
        Path currentRoot = rootPath;
        DefaultMutableTreeNode newRoot = buildRoot(currentRoot);
        model.setRoot(newRoot);
        workspaceLabel.setText(currentRoot.getFileName() == null
                ? currentRoot.toString() : currentRoot.getFileName().toString());
        workspaceLabel.setToolTipText(currentRoot.toString());
        tree.expandRow(0);
        expandExamples(newRoot);
    }

    private DefaultMutableTreeNode buildRoot(Path root) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(new NodeData(root, true));
        addPlaceholder(rootNode);
        workspaceLabel.setText(root.getFileName() == null
                ? root.toString() : root.getFileName().toString());
        workspaceLabel.setToolTipText(root.toString());
        return rootNode;
    }

    private void expandExamples(DefaultMutableTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            NodeData data = nodeData(child);
            if (data != null && data.directory && "examples".equalsIgnoreCase(
                    data.path.getFileName().toString())) {
                TreePath path = new TreePath(child.getPath());
                tree.expandPath(path);
                return;
            }
        }
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        NodeData data = nodeData(node);
        if (data == null || !data.directory) return;
        if (!hasPlaceholder(node)) return;
        node.removeAllChildren();
        try {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(data.path)) {
                for (Path child : stream) {
                    if (isVisible(child)) children.add(child);
                }
            }
            children.sort((a, b) -> {
                boolean ad = Files.isDirectory(a);
                boolean bd = Files.isDirectory(b);
                if (ad != bd) return ad ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(
                        b.getFileName().toString());
            });
            for (Path child : children) {
                DefaultMutableTreeNode childNode =
                        new DefaultMutableTreeNode(new NodeData(child, Files.isDirectory(child)));
                if (Files.isDirectory(child)) addPlaceholder(childNode);
                node.add(childNode);
            }
        } catch (IOException | SecurityException ex) {
            node.add(new DefaultMutableTreeNode("无法读取目录"));
        }
        if (model != null) model.nodeStructureChanged(node);
    }

    private void addPlaceholder(DefaultMutableTreeNode node) {
        node.add(new DefaultMutableTreeNode("加载中..."));
    }

    private boolean hasPlaceholder(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && !(node.getChildAt(0) instanceof DefaultMutableTreeNode
                && ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() instanceof NodeData);
    }

    private boolean isVisible(Path path) {
        Path namePath = path.getFileName();
        if (namePath == null) return false;
        String name = namePath.toString();
        if (name.startsWith(".")) return false;
        if (Files.isDirectory(path)) {
            return !IGNORED_DIRS.contains(name.toLowerCase(Locale.ROOT));
        }
        if ("README".equalsIgnoreCase(name) || "LICENSE".equalsIgnoreCase(name)) return true;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return SOURCE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void activate(TreePath path) {
        if (path == null) return;
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode)) return;
        NodeData data = nodeData((DefaultMutableTreeNode) last);
        if (data == null) return;
        if (data.directory) {
            if (tree.isExpanded(path)) tree.collapsePath(path);
            else tree.expandPath(path);
        } else {
            openHandler.accept(data.path);
        }
    }

    private void installPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem("打开");
        open.addActionListener(e -> activate(tree.getSelectionPath()));
        JMenuItem refresh = new JMenuItem("刷新");
        refresh.addActionListener(e -> refresh());
        menu.add(open);
        menu.addSeparator();
        menu.add(refresh);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) tree.setSelectionPath(path);
                    menu.show(tree, e.getX(), e.getY());
                }
            }
        });
    }

    private static NodeData nodeData(DefaultMutableTreeNode node) {
        Object value = node.getUserObject();
        return value instanceof NodeData ? (NodeData) value : null;
    }

    private static final class NodeData {
        final Path path;
        final boolean directory;

        NodeData(Path path, boolean directory) {
            this.path = path;
            this.directory = directory;
        }

        @Override public String toString() {
            Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }
    }

    private final class WorkspaceRenderer extends DefaultTreeCellRenderer {
        WorkspaceRenderer() {
            Icon folder = UIManager.getIcon("FileView.directoryIcon");
            Icon file = UIManager.getIcon("FileView.fileIcon");
            if (folder != null) {
                setClosedIcon(folder);
                setOpenIcon(folder);
            }
            if (file != null) setLeafIcon(file);
            Font base = UIManager.getFont("Tree.font");
            setFont((base == null ? new Font("SansSerif", Font.PLAIN, 12) : base).deriveFont(12f));
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode) {
                NodeData data = nodeData((DefaultMutableTreeNode) value);
                if (data != null) {
                    setText(data.toString());
                    setToolTipText(data.path.toString());
                    if (!data.directory && data.path.getFileName() != null) {
                        String name = data.path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (name.endsWith(".uni")) setForeground(new Color(0x1F5FBF));
                        else setForeground(new Color(0x34495E));
                    }
                } else {
                    setToolTipText(null);
                    setForeground(new Color(0x95A5A6));
                }
            }
            return this;
        }
    }
}
