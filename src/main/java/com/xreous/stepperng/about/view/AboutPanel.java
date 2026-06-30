package com.xreous.stepperng.about.view;
import com.xreous.stepperng.Globals;
import com.xreous.stepperng.Stepper;
import com.xreous.stepperng.util.Utils;
import com.xreous.stepperng.util.view.Themes;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
/** About tab: header, project links, and the Quick Start / Features reference. */
public class AboutPanel extends JPanel {
    public AboutPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);
    }
    private JComponent buildHeader() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel header = new JLabel("Stepper v" + Globals.VERSION);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 20f));
        header.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(header);

        JLabel subtitle = new JLabel("A multi-stage repeater with dynamic variable extraction");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.ITALIC, 12f));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitle);

        JPanel credits = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 2));
        credits.add(new JLabel("Maintained by Xre0uS  ·  Based on Stepper by CoreyD97"));
        credits.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(Box.createVerticalStrut(6));
        panel.add(credits);

        JPanel links = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 2));
        links.add(linkButton("Stepper on GitHub", "https://github.com/Xre0uS/stepper-ng", githubIcon()));
        links.add(linkButton("Original Stepper", "https://github.com/CoreyD97/Stepper", null));
        links.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(links);

        panel.add(Box.createVerticalStrut(6));
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(sep);
        return panel;
    }
    private JComponent buildContent() {
        JEditorPane pane = new JEditorPane();
        HTMLEditorKit kit = new HTMLEditorKit();
        pane.setEditorKit(kit);
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(null);
        // Match the surrounding theme rather than the editor kit's default light palette.
        StyleSheet css = kit.getStyleSheet();
        Color fg = pane.getForeground();
        if (fg == null) fg = UIManager.getColor("Label.foreground");
        if (fg == null) fg = Themes.isDark() ? Color.WHITE : Color.BLACK;
        String fgHex = String.format("#%02x%02x%02x", fg.getRed(), fg.getGreen(), fg.getBlue());
        css.addRule("body { font-family: sans-serif; font-size: 12pt; color: " + fgHex + "; }");
        css.addRule("h3 { margin: 14px 0 4px 0; font-size: 13pt; }");
        css.addRule("p { margin: 0 0 8px 0; }");
        css.addRule("ul, ol { margin: 0 0 8px 18px; padding: 0; }");
        css.addRule("li { margin: 2px 0; }");
        css.addRule("code { font-family: monospace; }");
        pane.setText(buildHtml());
        pane.setCaretPosition(0);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openUrl(e.getURL() != null ? e.getURL().toString() : e.getDescription());
            }
        });
        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }
    private static String buildHtml() {
        return "<html><body>"
                + "<p>A multi-stage repeater for Burp Suite. Build request sequences, extract values with regex, "
                + "and chain them across steps. Replaces session handling macros with automatic token management "
                + "via published variables.</p>"
                + "<h3>Quick Start</h3>"
                + "<ol>"
                + "<li>Create a sequence with <b>+ Sequence</b>, right-click &rarr; <i>Rename</i> to retitle.</li>"
                + "<li>Add steps via <b>+ Step</b> or right-click a request in Burp &rarr; <i>Add to Stepper sequence</i>. "
                + "Drag step nodes to reorder, or drag across sequences to move (with auto-rebind of <code>$VAR:</code> refs).</li>"
                + "<li>Define regex variables to extract response values (or highlight text &rarr; <i>Auto Regex</i>).</li>"
                + "<li>Reference <code>$VAR:name$</code> in later steps.</li>"
                + "<li>Click <b>Run all</b> to execute, or <b>Run through here</b> for a partial run, or <b>Run step</b> for a single step.</li>"
                + "</ol>"
                + "<p>For auto session handling: publish a variable, reference it as <code>$VAR:seq:name$</code> "
                + "in other tools, and the sequence auto-executes when needed.</p>"
                + "<h3>Variable Syntax</h3>"
                + "<ul>"
                + "<li><code>$VAR:name$</code> &mdash; within the same sequence</li>"
                + "<li><code>$VAR:SeqName:name$</code> &mdash; cross-sequence / other tools</li>"
                + "<li><code>$GVAR:name$</code> &mdash; static global (manual key-value)</li>"
                + "<li><code>$DVAR:name$</code> &mdash; dynamic global (auto-extracted from responses)</li>"
                + "</ul>"
                + "<h3>Regex Variables</h3>"
                + "<p>Post-execution variables run regex against the response. The first capture group is used "
                + "as the value; no groups means the whole match. Use <code>(?:&hellip;)</code> for non-capturing.</p>"
                + "<p>Example: response <i>Hello World!</i> with <code>Hello (World|Earth)!</code> &rarr; <code>World</code>.</p>"
                + "<h3>Key Features</h3>"
                + "<ul>"
                + "<li><b>Published variables</b> &mdash; expose a variable outside its sequence. Any tool that "
                + "sends <code>$VAR:seq:name$</code> triggers the owning sequence to refresh the value. Combined "
                + "with Burp session handling rules, this gives fully automatic token/session management.</li>"
                + "<li><b>Passthrough &amp; sync</b> &mdash; every response flowing through Burp is matched against "
                + "published regexes, keeping tokens current without rerunning the sequence.</li>"
                + "<li><b>Conditional steps</b> &mdash; if/else on status code or body regex; continue, skip, goto, "
                + "or retry up to N times with a delay.</li>"
                + "<li><b>Validation step</b> &mdash; runs first; if its condition passes (e.g. cookie still valid), "
                + "the remaining steps are skipped. <i>Validate every N requests</i> reduces overhead during scans.</li>"
                + "<li><b>Post-validation step</b> &mdash; runs after the sequence to verify recovery. On repeated "
                + "failure Stepper pauses Burp's task engine and alerts you.</li>"
                + "<li>Shared variable names sync across steps of a sequence.</li>"
                + "<li>Hold-requests mode blocks concurrent workers until the sequence finishes (<i>Preferences</i>).</li>"
                + "<li>Static (<code>$GVAR:</code>) and dynamic (<code>$DVAR:</code>) globals across all tools.</li>"
                + "<li>Auto-backup &mdash; periodic JSON backups, restorable via <i>Import</i>.</li>"
                + "<li>Sequence args via <code>X-Stepper-Execute-Before/After</code> headers with inline variables.</li>"
                + "<li>Disable individual steps or whole sequences from the right-click menu.</li>"
                + "<li>Replacements tab &mdash; preview variable substitutions with highlighting.</li>"
                + "<li>Sequence Overview tab &mdash; summary of steps, conditions, and variables.</li>"
                + "<li>Loop prevention &mdash; max depth " + Globals.MAX_SEQUENCE_DEPTH + ", circular dependency detection.</li>"
                + "</ul>"
                + "<h3>Session Handling Action</h3>"
                + "<p>For Extensions scope, add <i>Stepper: Variable Replacement for Extensions</i> as a session "
                + "handling action after any rule that injects <code>$VAR:</code> references. This resolves them "
                + "inside the session-handling phase, since the HTTP handler runs before session rules for extensions.</p>"
                + "</body></html>";
    }
    private static JButton linkButton(String label, String url, ImageIcon icon) {
        JButton btn;
        if (icon != null) {
            btn = new JButton(label, icon);
            btn.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            btn.setIconTextGap(7);
        } else {
            btn = new JButton(label);
        }
        btn.addActionListener(a -> openUrl(url));
        return btn;
    }
    private static ImageIcon githubIcon() {
        String name = "GitHubLogo" + (Themes.isDark() ? "White" : "Black") + ".png";
        return Utils.loadImage(name, 24, 24);
    }
    private static void openUrl(String url) {
        if (url == null) return;
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (IOException | URISyntaxException | UnsupportedOperationException e) {
            try { Stepper.montoya.logging().logToError("Failed to open URL: " + url); } catch (Exception ignored) {}
        }
    }
}
