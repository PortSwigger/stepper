package com.xreous.stepperng.util.view;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
/**
 * Theme-aware colour palette. UI code routes hard-coded RGB values through here
 * so they adapt to Burp's light/dark look-and-feels.
 */
public final class Themes {
    private Themes() {}
    public static boolean isDark(Component c) {
        Color bg = null;
        if (c != null) bg = c.getBackground();
        if (bg == null) bg = UIManager.getColor("Panel.background");
        if (bg == null) bg = UIManager.getColor("control");
        if (bg == null) return false;
        // ITU-R BT.709 perceptual luminance
        return (0.2126 * bg.getRed() + 0.7152 * bg.getGreen() + 0.0722 * bg.getBlue()) / 255.0 < 0.5;
    }
    public static boolean isDark() {
        return isDark(null);
    }
    public static Color lineColor(Component c) {
        return isDark(c) ? new Color(255, 255, 255, 55) : new Color(0, 0, 0, 55);
    }
    public static Color successForeground(Component c) {
        return isDark(c) ? new Color(130, 200, 130) : new Color(50, 130, 50);
    }
    public static Color warningForeground(Component c) {
        return isDark(c) ? new Color(230, 180, 90) : new Color(190, 130, 40);
    }
    public static Color errorForeground(Component c) {
        return isDark(c) ? new Color(240, 120, 110) : new Color(190, 60, 55);
    }
    public static Color accentForeground(Component c) {
        return isDark(c) ? new Color(255, 150, 70) : new Color(0xE5, 0x6B, 0x22);
    }
    public static Color rowHighlightTint(Component c) {
        return isDark(c) ? new Color(110, 160, 220, 60) : new Color(70, 130, 180, 30);
    }
    /**
     * Opaque equivalent of {@link #rowHighlightTint}, composited over the component's
     * current background. Use this for {@code setBackground} on opaque Swing components:
     * a translucent colour there ghosts because the base is never cleared before the blend.
     */
    public static Color rowHighlightBackground(Component c) {
        Color base = c != null ? c.getBackground() : null;
        if (base == null) base = UIManager.getColor("List.background");
        if (base == null) base = UIManager.getColor("Panel.background");
        if (base == null) base = UIManager.getColor("control");
        if (base == null) base = Color.GRAY;
        Color tint = rowHighlightTint(c);
        double a = tint.getAlpha() / 255.0;
        int r = (int) Math.round(tint.getRed() * a + base.getRed() * (1 - a));
        int g = (int) Math.round(tint.getGreen() * a + base.getGreen() * (1 - a));
        int b = (int) Math.round(tint.getBlue() * a + base.getBlue() * (1 - a));
        return new Color(r, g, b);
    }
    public static Color okBackground(Component c) {
        return isDark(c) ? new Color(60, 140, 80) : new Color(76, 200, 130);
    }
    public static Color badBackground(Component c) {
        return isDark(c) ? new Color(180, 60, 50) : new Color(200, 70, 60);
    }
    public static Color disabledForeground(Component c) {
        Color def = UIManager.getColor("Label.disabledForeground");
        if (def != null) return def;
        return isDark(c) ? new Color(180, 180, 180) : new Color(120, 120, 120);
    }
}
