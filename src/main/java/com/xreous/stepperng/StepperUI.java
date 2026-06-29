package com.xreous.stepperng;
import com.coreyd97.BurpExtenderUtilities.CustomTabComponent;
import com.coreyd97.BurpExtenderUtilities.PopOutPanel;
import com.xreous.stepperng.sequencemanager.listener.StepSequenceListener;
import com.xreous.stepperng.about.view.AboutPanel;
import com.xreous.stepperng.preferences.view.OptionsPanel;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.sequence.view.SequenceOverviewPanel;
import com.xreous.stepperng.sequence.view.tree.SequenceTreePanel;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.step.Step;
import com.xreous.stepperng.step.listener.StepAdapter;
import com.xreous.stepperng.step.listener.StepListener;
import com.xreous.stepperng.step.view.StepPanel;
import com.xreous.stepperng.util.view.SplitPaneDoubleClick;
import com.xreous.stepperng.util.view.Themes;
import com.xreous.stepperng.variable.DynamicGlobalVariableManager;
import com.xreous.stepperng.variable.view.DynamicGlobalVariablesPanel;
import burp.api.montoya.ui.settings.SettingsPanel;
import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
public class StepperUI {
    private final SequenceManager sequenceManager;
    private final PopOutPanel popOutPanel;
    private final Map<Step, StepPanel> stepPanelMap = new HashMap<>();
    private final Map<StepSequence, SequenceOverviewPanel> overviewMap = new HashMap<>();
    private final Map<StepSequence, StepListener> treeStepListeners = new HashMap<>();
    private final SequenceTreePanel treePanel;
    private final OptionsPanel optionsPanel;
    private final JPanel detailContainer;
    private final CardLayout detailCards;
    private final JSplitPane splitPane;
    private final StepActionBar stepActionBar;
    private JPanel header;
    private JPanel detailWithToolbar;
    private boolean hasUnseen = false;
    private StepSequenceListener managerListener;
    private StepSequence selectedSequence;
    private Step selectedStep;
    public StepperUI(SequenceManager sequenceManager, DynamicGlobalVariableManager dynamicVarManager){
        this.sequenceManager = sequenceManager;
        DynamicGlobalVariablesPanel globalVarsPanel = new DynamicGlobalVariablesPanel(dynamicVarManager);
        this.optionsPanel = new OptionsPanel(this.sequenceManager);
        AboutPanel aboutPanel = new AboutPanel();
        this.detailCards = new CardLayout();
        this.detailContainer = new JPanel(detailCards);
        this.detailContainer.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        this.detailContainer.add(wrap(globalVarsPanel), SequenceTreePanel.BUILTIN_GLOBAL_VARS);
        this.detailContainer.add(wrap(buildPreferencesRedirect()), SequenceTreePanel.BUILTIN_PREFERENCES);
        this.detailContainer.add(wrap(aboutPanel), SequenceTreePanel.BUILTIN_ABOUT);
        this.stepActionBar = new StepActionBar(step -> stepPanelMap.get(step));
        this.stepActionBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        for (StepSequence sequence : this.sequenceManager.getSequences()) {
            registerSequenceDetail(sequence);
        }
        this.treePanel = new SequenceTreePanel(sequenceManager, new TreeSelectionBridge());
        this.managerListener = new StepSequenceListener() {
            @Override public void onStepSequenceAdded(StepSequence s) {
                SwingUtilities.invokeLater(() -> registerSequenceDetail(s));
            }
            @Override public void onStepSequenceRemoved(StepSequence s) {
                SwingUtilities.invokeLater(() -> unregisterSequenceDetail(s));
            }
            @Override public void onStepSequenceModified(StepSequence s) {
                SwingUtilities.invokeLater(() -> {
                    SequenceOverviewPanel ov = overviewMap.get(s);
                    if (ov != null) ov.refresh();
                    for (Step step : s.getSteps()) {
                        StepPanel sp = stepPanelMap.get(step);
                        if (sp != null) sp.refreshValidationState();
                    }
                });
            }
        };
        this.sequenceManager.addStepSequenceListener(this.managerListener);
        this.splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, detailContainer);
        this.splitPane.setContinuousLayout(true);
        this.splitPane.setBorder(null);
        this.splitPane.setResizeWeight(0.0);
        SplitPaneDoubleClick.install(splitPane, this::fitDividerToTree);
        primeDividerFromContent();
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Themes.lineColor(new JLabel())));
        header.add(stepActionBar, BorderLayout.CENTER);
        this.header = header;
        JPanel detailWithToolbar = new JPanel(new BorderLayout());
        detailWithToolbar.setOpaque(true);
        detailWithToolbar.add(header, BorderLayout.NORTH);
        detailWithToolbar.add(splitPane, BorderLayout.CENTER);
        this.detailWithToolbar = detailWithToolbar;
        this.popOutPanel = new PopOutPanel(Stepper.montoya, detailWithToolbar, "Stepper");
        this.popOutPanel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && popOutPanel.isShowing()) {
                clearHighlight();
            }
        });
        if (this.sequenceManager.getSequences().isEmpty()) {
            SwingUtilities.invokeLater(() -> treePanel.selectBuiltIn(SequenceTreePanel.BUILTIN_ABOUT));
        }
    }
    private void primeDividerFromContent() {
        // Wait for the first real layout pass: HierarchyEvent SHOWING_CHANGED can fire before the
        // split pane has been sized. ComponentListener.componentResized fires once layout assigns width.
        splitPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (splitPane.getWidth() <= 0) return;
                splitPane.removeComponentListener(this);
                SwingUtilities.invokeLater(StepperUI.this::fitDividerToTree);
            }
        });
    }

    /** Expand every node and size the divider so the widest tree row is fully visible. */
    private void fitDividerToTree() {
        if (treePanel == null || splitPane.getWidth() <= 0) return;
        treePanel.expandAll();
        JTree tree = treePanel.getTree();
        int contentW = 0;
        for (int r = 0; r < tree.getRowCount(); r++) {
            Rectangle rb = tree.getRowBounds(r);
            if (rb != null) contentW = Math.max(contentW, rb.x + rb.width);
        }
        if (contentW <= 0) return;
        int scrollbar = Math.max(12, UIManager.getInt("ScrollBar.width"));
        int desired = Math.max(contentW + scrollbar + 16, treePanel.toolbarPreferredWidth());
        int max = Math.max(240, splitPane.getWidth() - 200);
        splitPane.setDividerLocation(Math.max(240, Math.min(desired, max)));
    }
    private static JComponent wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.add(c, BorderLayout.CENTER);
        return p;
    }
    private String cardKeyForSequence(StepSequence s) { return "seq:" + s.getSequenceId(); }
    private String cardKeyForStep(Step step)          { return "step:" + step.getStepId(); }
    private final class TreeSelectionBridge implements SequenceTreePanel.SelectionHandler {
        @Override public void onSelection(StepSequence sequence, Step step) {
            selectedSequence = sequence;
            selectedStep = step;
            if (header != null) header.setVisible(true);
            if (sequence == null) return;
            if (step != null) {
                if (ensureStepPanel(sequence, step) != null) {
                    detailCards.show(detailContainer, cardKeyForStep(step));
                }
            } else {
                if (!overviewMap.containsKey(sequence)) registerSequenceDetail(sequence);
                if (overviewMap.get(sequence) != null) {
                    detailCards.show(detailContainer, cardKeyForSequence(sequence));
                }
            }
            if (stepActionBar != null) stepActionBar.setSelection(sequence, step);
            if (detailWithToolbar != null) { detailWithToolbar.revalidate(); detailWithToolbar.repaint(); }
        }
        @Override public void onBuiltInSelection(String key) {
            selectedSequence = null;
            selectedStep = null;
            if (header != null) header.setVisible(false);
            detailCards.show(detailContainer, key);
            if (SequenceTreePanel.BUILTIN_PREFERENCES.equals(key)) openSettingsWindow();
            if (stepActionBar != null) stepActionBar.setSelection(null, null);
            if (detailWithToolbar != null) { detailWithToolbar.revalidate(); detailWithToolbar.repaint(); }
        }
    }
    /** The Preferences UI, registered as a native panel in Burp's Settings window. */
    public SettingsPanel getSettingsPanel() {
        return optionsPanel;
    }

    private void openSettingsWindow() {
        try {
            Stepper.montoya.userInterface().openSettingsWindow();
        } catch (Exception ex) {
            try { Stepper.montoya.logging().logToError("Stepper: Failed to open settings window: " + ex.getMessage()); } catch (Exception ignored) {}
        }
    }

    /** Sidebar placeholder shown when "Preferences" is selected; the real panel lives in Burp's Settings window. */
    private JComponent buildPreferencesRedirect() {
        JPanel panel = new JPanel(new GridBagLayout());
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Preferences are in Burp's Settings window.");
        title.setForeground(Themes.disabledForeground(panel));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton open = new JButton("Open Settings…");
        open.setAlignmentX(Component.CENTER_ALIGNMENT);
        open.addActionListener(e -> openSettingsWindow());
        inner.add(title);
        inner.add(Box.createVerticalStrut(12));
        inner.add(open);
        panel.add(inner);
        return panel;
    }
    private void registerSequenceDetail(StepSequence sequence) {
        if (overviewMap.containsKey(sequence)) return;
        SequenceOverviewPanel overview = new SequenceOverviewPanel(sequence);
        overviewMap.put(sequence, overview);
        detailContainer.add(wrap(overview), cardKeyForSequence(sequence));
        for (Step step : sequence.getSteps()) ensureStepPanel(sequence, step);
        StepListener listener = new StepAdapter() {
            @Override public void onStepAdded(Step step) {
                SwingUtilities.invokeLater(() -> ensureStepPanel(sequence, step));
            }
            @Override public void onStepRemoved(Step step) {
                SwingUtilities.invokeLater(() -> removeStepPanel(step));
            }
        };
        sequence.addStepListener(listener);
        treeStepListeners.put(sequence, listener);
    }
    private void unregisterSequenceDetail(StepSequence sequence) {
        StepListener l = treeStepListeners.remove(sequence);
        if (l != null) try { sequence.removeStepListener(l); } catch (Exception ignored) {}
        for (Step step : sequence.getSteps()) removeStepPanel(step);
        SequenceOverviewPanel ov = overviewMap.remove(sequence);
        if (ov != null) {
            for (Component c : detailContainer.getComponents()) {
                if (c instanceof JPanel p && p.getComponentCount() > 0 && p.getComponent(0) == ov) {
                    detailContainer.remove(p);
                    break;
                }
            }
        }
    }
    private StepPanel ensureStepPanel(StepSequence sequence, Step step) {
        StepPanel existing = stepPanelMap.get(step);
        if (existing != null) return existing;
        StepPanel panel = new StepPanel(step,
                p -> refreshSubsequentPanels(sequence, p),
                this::broadcastSplitReset);
        stepPanelMap.put(step, panel);
        detailContainer.add(panel, cardKeyForStep(step));
        return panel;
    }

    /** Re-applies the same divider reset to every open step panel. */
    private void broadcastSplitReset(StepPanel.SplitKind kind) {
        for (StepPanel p : stepPanelMap.values()) p.applySplit(kind);
    }
    private void removeStepPanel(Step step) {
        StepPanel panel = stepPanelMap.remove(step);
        if (panel != null) {
            panel.dispose();
            detailContainer.remove(panel);
        }
    }
    private void refreshSubsequentPanels(StepSequence sequence, StepPanel changed) {
        var steps = sequence.getSteps();
        int idx = steps.indexOf(changed.getStep());
        if (idx < 0) return;
        for (int i = idx + 1; i < steps.size(); i++) {
            StepPanel sp = stepPanelMap.get(steps.get(i));
            if (sp != null) sp.refreshRequestPanel();
        }
    }
    /** Abstraction so {@link com.xreous.stepperng.sequence.StepSequence} doesn't need UI types. */
    public interface ExecutionHost {
        void beginExecution();
        void endExecution();
        void setActive(Step step);
        byte[] liveRequestBytes(Step step);
    }
    public ExecutionHost getExecutionHost(StepSequence sequence) {
        return new ExecutionHost() {
            @Override public void beginExecution() {}
            @Override public void endExecution() {}
            @Override public void setActive(Step step) {}
            @Override public byte[] liveRequestBytes(Step step) {
                StepPanel p = stepPanelMap.get(step);
                if (p != null) {
                    try {
                        byte[] b = p.getRequestEditor().getMessage();
                        if (b != null && b.length > 0) return b;
                    } catch (Exception ignored) {}
                }
                return step.getRequest();
            }
        };
    }
    public StepSequence getSelectedSequence() { return selectedSequence; }
    public Step getSelectedStep() { return selectedStep; }
    public Component getUiComponent() { return this.popOutPanel; }
    public void highlightTab() {
        if (hasUnseen) return;
        hasUnseen = true;
        SwingUtilities.invokeLater(() -> {
            JTabbedPane burpTabs = findBurpSuiteTabs();
            if (burpTabs == null) return;
            int idx = findStepperTabIndex(burpTabs);
            if (idx < 0) return;
            String title = burpTabs.getTitleAt(idx);
            if (title != null && !title.endsWith(" \u25CF")) burpTabs.setTitleAt(idx, title + " \u25CF");
            Component tabComp = burpTabs.getTabComponentAt(idx);
            if (tabComp instanceof CustomTabComponent ctc) ctc.showDot();
            else if (tabComp != null) addDotToSwingLabel(tabComp, true);
        });
    }
    private void clearHighlight() {
        if (!hasUnseen) return;
        hasUnseen = false;
        SwingUtilities.invokeLater(() -> {
            JTabbedPane burpTabs = findBurpSuiteTabs();
            if (burpTabs == null) return;
            int idx = findStepperTabIndex(burpTabs);
            if (idx < 0) return;
            String title = burpTabs.getTitleAt(idx);
            if (title != null && title.endsWith(" \u25CF"))
                burpTabs.setTitleAt(idx, title.substring(0, title.length() - 2));
            Component tabComp = burpTabs.getTabComponentAt(idx);
            if (tabComp instanceof CustomTabComponent ctc) ctc.hideDot();
            else if (tabComp != null) addDotToSwingLabel(tabComp, false);
        });
    }
    private void addDotToSwingLabel(Component comp, boolean show) {
        if (comp instanceof JLabel label) {
            String text = label.getText();
            if (text == null) return;
            if (show && !text.endsWith(" \u25CF")) {
                label.setText(text + " \u25CF");
                label.setForeground(Themes.accentForeground(label));
            } else if (!show && text.endsWith(" \u25CF")) {
                label.setText(text.substring(0, text.length() - 2));
                label.setForeground(null);
            }
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) addDotToSwingLabel(child, show);
        }
    }
    /** Reserved for a future per-sequence tree badge; currently a no-op. */
    public void highlightSequenceTab(StepSequence sequence) { /* no-op in tree mode */ }
    private JTabbedPane findBurpSuiteTabs() {
        Component c = popOutPanel;
        while (c != null) {
            c = c.getParent();
            if (c instanceof JTabbedPane tp) {
                for (int i = 0; i < tp.getTabCount(); i++) {
                    if (tp.getComponentAt(i) == popOutPanel || isAncestorOf(tp.getComponentAt(i), popOutPanel)) {
                        return tp;
                    }
                }
            }
        }
        return null;
    }
    private int findStepperTabIndex(JTabbedPane burpTabs) {
        for (int i = 0; i < burpTabs.getTabCount(); i++) {
            Component comp = burpTabs.getComponentAt(i);
            if (comp == popOutPanel || isAncestorOf(comp, popOutPanel)) return i;
        }
        return -1;
    }
    private boolean isAncestorOf(Component ancestor, Component child) {
        Component c = child;
        while (c != null) {
            if (c == ancestor) return true;
            c = c.getParent();
        }
        return false;
    }
    public boolean isStepperTabVisible() {
        JTabbedPane burpTabs = findBurpSuiteTabs();
        if (burpTabs == null) return false;
        int idx = findStepperTabIndex(burpTabs);
        return idx >= 0 && burpTabs.getSelectedIndex() == idx;
    }
    public boolean isSequenceVisible(StepSequence sequence) {
        return isStepperTabVisible() && selectedSequence == sequence;
    }
    public void dispose() {
        if (managerListener != null) {
            try { sequenceManager.removeStepSequenceListener(managerListener); } catch (Exception ignored) {}
        }
        if (treePanel != null) {
            try { treePanel.dispose(); } catch (Exception ignored) {}
        }
    }
}
