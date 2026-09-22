package com.automation.service.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Draws the website's bespectacled sentinel meerkat with a small status badge. */
final class TrayIconRenderer {
    private static final int ARTBOARD = 256;
    private static final Color BACKGROUND = new Color(0x1A1410);
    private static final Color FUR = new Color(0xD4A060);
    private static final Color FUR_DARK = new Color(0xA87840);
    private static final Color BELLY = new Color(0xECD4A8);
    private static final Color MASK = new Color(0x8B6030);
    private static final Color INK = new Color(0x2A1A0D);

    private TrayIconRenderer() { }

    static BufferedImage render(SystemTrayService.IconColor status, int size) {
        int renderSize = Math.max(ARTBOARD, size * 4);
        // X11/AWT tray implementations do not consistently preserve alpha. A transparent
        // canvas was therefore flattened to a white square on some Linux desktops. The
        // website icon already uses this dark background, so render it explicitly.
        BufferedImage canvas = new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, renderSize, renderSize);
        g.scale(renderSize / (double) ARTBOARD, renderSize / (double) ARTBOARD);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        drawMeerkat(g);
        drawStatus(g, status);
        g.dispose();
        return downscale(canvas, size);
    }

    private static void drawMeerkat(Graphics2D g) {
        g.setColor(FUR_DARK);
        g.fill(new Ellipse2D.Double(39, 30, 48, 64));
        g.fill(new Ellipse2D.Double(169, 30, 48, 64));

        Path2D body = new Path2D.Double();
        body.moveTo(75, 219);
        body.curveTo(65, 170, 70, 125, 92, 105);
        body.curveTo(108, 91, 148, 91, 164, 105);
        body.curveTo(186, 125, 191, 170, 181, 219);
        body.closePath();
        g.setColor(FUR);
        g.fill(body);
        g.setColor(BELLY);
        g.fill(new Ellipse2D.Double(103, 133, 50, 91));
        g.setColor(FUR);
        g.fill(new Ellipse2D.Double(56, 42, 144, 126));

        Path2D faceMask = new Path2D.Double();
        faceMask.moveTo(77, 91);
        faceMask.curveTo(87, 62, 105, 55, 128, 55);
        faceMask.curveTo(151, 55, 169, 62, 179, 91);
        faceMask.curveTo(169, 119, 151, 131, 128, 131);
        faceMask.curveTo(105, 131, 87, 119, 77, 91);
        faceMask.closePath();
        g.setColor(MASK);
        g.fill(faceMask);

        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(INK);
        g.draw(new Ellipse2D.Double(84, 72, 42, 42));
        g.draw(new Ellipse2D.Double(130, 72, 42, 42));
        g.drawLine(126, 91, 130, 91);
        g.fill(new Ellipse2D.Double(100, 85, 10, 13));
        g.fill(new Ellipse2D.Double(146, 85, 10, 13));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(102, 86, 3.5, 4));
        g.fill(new Ellipse2D.Double(148, 86, 3.5, 4));
        g.setColor(INK);
        g.fill(new Ellipse2D.Double(119, 112, 18, 12));

        g.setColor(FUR_DARK);
        g.setStroke(new BasicStroke(13, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(91, 158, 111, 185);
        g.drawLine(165, 158, 145, 185);
        g.setColor(FUR);
        g.fill(new Ellipse2D.Double(103, 178, 22, 14));
        g.fill(new Ellipse2D.Double(131, 178, 22, 14));
    }

    private static void drawStatus(Graphics2D g, SystemTrayService.IconColor status) {
        Color color = switch (status) {
            case GREEN -> new Color(0x36C98F);
            case YELLOW -> new Color(0xF3B63F);
            case RED -> new Color(0xEF5A5A);
            case GRAY -> new Color(0x8B9098);
        };
        g.setColor(new Color(0x211A14));
        g.fill(new Ellipse2D.Double(174, 174, 70, 70));
        g.setColor(color);
        g.fill(new Ellipse2D.Double(182, 182, 54, 54));
        g.setColor(new Color(255, 255, 255, 110));
        g.fill(new Ellipse2D.Double(193, 190, 18, 12));
    }

    private static BufferedImage downscale(BufferedImage source, int size) {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, size, size);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return result;
    }
}
