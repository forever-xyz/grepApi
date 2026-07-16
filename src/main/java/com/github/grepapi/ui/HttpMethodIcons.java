package com.github.grepapi.ui;

import com.github.grepapi.model.ApiRoute;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

final class HttpMethodIcons {
    private static final Icon GET = new MethodIcon("G", new JBColor(0x20A464, 0x35C77D));
    private static final Icon POST = new MethodIcon("P", new JBColor(0x2E8BCB, 0x429FE3));
    private static final Icon PUT = new MethodIcon("U", new JBColor(0xD98719, 0xF0A134));
    private static final Icon DELETE = new MethodIcon("D", new JBColor(0xD64545, 0xF05252));
    private static final Icon PATCH = new MethodIcon("H", new JBColor(0x8A5CC7, 0xA67BE0));
    private static final Icon ANY = new MethodIcon("A", new JBColor(0x75808A, 0x8B949E));

    private HttpMethodIcons() {
    }

    static @NotNull Icon forRoute(@NotNull ApiRoute route) {
        if (route.httpMethods().isEmpty()) {
            return ANY;
        }
        String method = route.httpMethods().iterator().next();
        return switch (method) {
            case "GET" -> GET;
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "DELETE" -> DELETE;
            case "PATCH" -> PATCH;
            default -> ANY;
        };
    }

    private record MethodIcon(@NotNull String letter, @NotNull Color color) implements Icon {
        private static final int SIZE = 16;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.fillOval(x + 1, y + 1, SIZE - 2, SIZE - 2);
                Font font = component.getFont().deriveFont(Font.BOLD, 10f);
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics();
                int textX = x + (SIZE - metrics.stringWidth(letter)) / 2;
                int textY = y + (SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
                g.setColor(Color.WHITE);
                g.drawString(letter, textX, textY);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
