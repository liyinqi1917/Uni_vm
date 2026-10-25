import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/** Vector toolbar and menu icons, drawn without external image assets. */
public final class Icons {

    private static final Color BLUE = new Color(0x2F80ED);
    private static final Color BLUE_DARK = new Color(0x1F5FBF);
    private static final Color GREEN = new Color(0x27AE60);
    private static final Color RED = new Color(0xE74C3C);
    private static final Color AMBER = new Color(0xF2A900);
    private static final Color INK = new Color(0x34495E);
    private static final Color PAPER = new Color(0xFFFFFF);
    private static final Color FOLDER = new Color(0xF4C542);

    private Icons() {
    }

    public static Icon newFile() {
        return make(18, (g, w, h) -> {
            page(g, 3, 1, 11, 16);
            g.setColor(BLUE);
            g.setStroke(stroke());
            g.drawLine(13, 9, 17, 9);
            g.drawLine(15, 7, 15, 11);
        });
    }

    public static Icon open() {
        return make(18, (g, w, h) -> {
            Path2D.Float folder = new Path2D.Float();
            folder.moveTo(1, 4);
            folder.lineTo(7, 4);
            folder.lineTo(9, 6);
            folder.lineTo(17, 6);
            folder.lineTo(17, 15);
            folder.lineTo(1, 15);
            folder.closePath();
            g.setColor(FOLDER);
            g.fill(folder);
            g.setColor(new Color(0xD7A900));
            g.setStroke(stroke());
            g.draw(folder);
            g.setColor(BLUE);
            g.drawLine(5, 9, 13, 9);
            g.drawLine(5, 12, 13, 12);
        });
    }

    public static Icon save() {
        return make(18, (g, w, h) -> {
            g.setColor(BLUE);
            g.fillRoundRect(2, 2, 14, 14, 3, 3);
            g.setColor(PAPER);
            g.fillRect(5, 3, 7, 5);
            g.setColor(BLUE_DARK);
            g.fillRect(5, 11, 8, 5);
            g.setColor(PAPER);
            g.drawLine(6, 13, 11, 13);
            g.drawLine(6, 15, 11, 15);
        });
    }

    public static Icon compile() {
        return make(18, (g, w, h) -> {
            g.setColor(BLUE);
            g.setStroke(stroke());
            g.drawOval(3, 3, 12, 12);
            g.drawLine(9, 1, 9, 4);
            g.drawLine(9, 14, 9, 17);
            g.drawLine(1, 9, 4, 9);
            g.drawLine(14, 9, 17, 9);
            g.drawLine(3, 3, 5, 5);
            g.drawLine(15, 3, 13, 5);
            g.drawLine(3, 15, 5, 13);
            g.drawLine(15, 15, 13, 13);
            g.setColor(GREEN);
            g.drawLine(6, 9, 8, 11);
            g.drawLine(8, 11, 12, 6);
        });
    }

    public static Icon generate() {
        return make(18, (g, w, h) -> {
            g.setColor(AMBER);
            g.setStroke(stroke());
            g.drawRoundRect(2, 5, 10, 10, 2, 2);
            g.setColor(BLUE);
            g.drawLine(9, 9, 16, 9);
            g.drawLine(16, 9, 13, 6);
            g.drawLine(16, 9, 13, 12);
        });
    }

    public static Icon run() {
        return make(18, (g, w, h) -> {
            Path2D.Float play = new Path2D.Float();
            play.moveTo(5, 3);
            play.lineTo(15, 9);
            play.lineTo(5, 15);
            play.closePath();
            g.setColor(GREEN);
            g.fill(play);
            g.setColor(new Color(0x1E8449));
            g.setStroke(stroke());
            g.draw(play);
        });
    }

    public static Icon stop() {
        return make(18, (g, w, h) -> {
            g.setColor(RED);
            g.fillRoundRect(4, 4, 10, 10, 2, 2);
            g.setColor(new Color(0xA93226));
            g.setStroke(stroke());
            g.drawRoundRect(4, 4, 10, 10, 2, 2);
        });
    }

    public static Icon format() {
        return make(18, (g, w, h) -> {
            g.setColor(BLUE);
            g.setStroke(stroke());
            g.drawLine(2, 4, 16, 4);
            g.drawLine(6, 7, 16, 7);
            g.drawLine(6, 10, 14, 10);
            g.drawLine(6, 13, 16, 13);
            g.setColor(AMBER);
            g.drawLine(2, 7, 2, 14);
            g.drawLine(2, 10, 4, 8);
            g.drawLine(2, 10, 4, 12);
        });
    }

    public static Icon fold() {
        return make(18, (g, w, h) -> {
            g.setColor(BLUE);
            g.setStroke(stroke());
            g.drawLine(3, 5, 15, 5);
            g.drawLine(3, 13, 15, 13);
            g.drawLine(9, 7, 6, 10);
            g.drawLine(9, 7, 12, 10);
        });
    }

    public static Icon unfold() {
        return make(18, (g, w, h) -> {
            g.setColor(BLUE);
            g.setStroke(stroke());
            g.drawLine(3, 5, 15, 5);
            g.drawLine(3, 13, 15, 13);
            g.drawLine(9, 11, 6, 8);
            g.drawLine(9, 11, 12, 8);
        });
    }

    public static Icon refresh() {
        return make(18, (g, w, h) -> {
            g.setColor(BLUE);
            g.setStroke(stroke());
            g.drawArc(2, 2, 14, 14, 35, 290);
            g.drawLine(14, 2, 16, 6);
            g.drawLine(16, 6, 12, 5);
        });
    }

    public static Icon close() {
        return make(12, (g, w, h) -> {
            g.setColor(new Color(0x7F8C8D));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(3, 3, 9, 9);
            g.drawLine(9, 3, 3, 9);
        });
    }

    public static Image appImage(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x4A9BF5), size, size, new Color(0x1F5FBF)));
        g.fillRoundRect(1, 1, size - 2, size - 2, size / 4, size / 4);
        g.setColor(new Color(255, 255, 255, 235));
        Path2D.Float play = new Path2D.Float();
        play.moveTo(size * 0.36, size * 0.26);
        play.lineTo(size * 0.76, size * 0.50);
        play.lineTo(size * 0.36, size * 0.74);
        play.closePath();
        g.fill(play);
        g.dispose();
        return image;
    }

    private static void page(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(PAPER);
        g.fillRoundRect(x, y, w, h, 2, 2);
        g.setColor(INK);
        g.setStroke(stroke());
        g.drawRoundRect(x, y, w, h, 2, 2);
        g.setColor(new Color(0xB6C2CF));
        g.drawLine(x + 3, y + 5, x + w - 3, y + 5);
        g.drawLine(x + 3, y + 8, x + w - 3, y + 8);
        g.drawLine(x + 3, y + 11, x + w - 4, y + 11);
    }

    private static BasicStroke stroke() {
        return new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static Icon make(int size, Painter painter) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                try {
                    g.translate(x, y);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                            RenderingHints.VALUE_STROKE_PURE);
                    painter.paint(g, size, size);
                } finally {
                    g.dispose();
                }
            }
        };
    }

    private interface Painter {
        void paint(Graphics2D g, int w, int h);
    }
}
