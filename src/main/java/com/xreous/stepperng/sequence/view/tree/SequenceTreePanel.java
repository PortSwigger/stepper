package com.xreous.stepperng.sequence.view.tree;

import com.xreous.stepperng.Stepper;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.sequence.listener.SequenceExecutionAdapter;
import com.xreous.stepperng.sequence.listener.SequenceExecutionListener;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.sequencemanager.listener.StepSequenceListener;
import com.xreous.stepperng.step.Step;
import com.xreous.stepperng.step.StepExecutionInfo;
import com.xreous.stepperng.step.listener.StepAdapter;
import com.xreous.stepperng.step.listener.StepExecutionAdapter;
import com.xreous.stepperng.step.listener.StepExecutionListener;
import com.xreous.stepperng.step.listener.StepListener;
import com.xreous.stepperng.util.view.ConnectorTree;
import com.xreous.stepperng.util.view.Themes;
import com.xreous.stepperng.variable.StepVariable;
import com.xreous.stepperng.variable.listener.StepVariableListener;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Tree navigation for Stepper-NG's master-detail layout (Sequence -> Step),
 * with intra-sequence drag-and-drop reorder.
 */
public final class SequenceTreePanel extends JPanel {

    public interface SelectionHandler {
        void onSelection(StepSequence sequence, Step step);
        void onBuiltInSelection(String key);
    }

    public static final String BUILTIN_GLOBAL_VARS = "builtin.globalVars";
    public static final String BUILTIN_PREFERENCES = "builtin.preferences";
    public static final String BUILTIN_ABOUT = "builtin.about";

    private final SequenceManager sequenceManager;
    private final SelectionHandler selectionHandler;
    private final DefaultMutableTreeNode root;
    private final DefaultTreeModel model;
    private final JTree tree;
    private final Map<StepSequence, StepListener> registeredStepListeners = new HashMap<>();
    private final Map<StepSequence, SequenceExecutionListener> registeredExecListeners = new HashMap<>();
    private final Map<Step, StepExecutionListener> registeredStepExecListeners = new HashMap<>();
    private final Map<Step, StepVariableListener> registeredStepVarListeners = new HashMap<>();
    private final StepSequenceListener managerListener;
    private final JList<BuiltInRef> navList;
    private final JComponent toolbar;
    private boolean suppressSelectionEvent = false;

    public SequenceTreePanel(SequenceManager sequenceManager, SelectionHandler handler) {
        super(new BorderLayout());
        this.sequenceManager = sequenceManager;
        this.selectionHandler = handler;

        this.root = new DefaultMutableTreeNode("root");
        this.model = new DefaultTreeModel(root);
        this.tree = new ConnectorTree(model);
        this.tree.setRootVisible(false);
        this.tree.setShowsRootHandles(true);
        this.tree.setToggleClickCount(0);
        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.setCellRenderer(new NodeRenderer());
        this.tree.setDragEnabled(true);
        this.tree.setDropMode(DropMode.ON_OR_INSERT);
        this.tree.setTransferHandler(new StepTransferHandler(this));
        this.tree.putClientProperty("JTree.lineStyle", "Angled");
        this.tree.setRowHeight(Math.max(22, this.tree.getRowHeight()));
        applyTreeIndents();
        this.tree.setLargeModel(false);

        rebuildTree();
        this.tree.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                applyTreeIndents();
                tree.treeDidChange();
                tree.revalidate();
                tree.repaint();
            }
            @Override public void ancestorRemoved(javax.swing.event.AncestorEvent e) {}
            @Override public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
        });

        this.tree.addTreeSelectionListener(e -> {
            if (suppressSelectionEvent) return;
            fireSelection(e.getNewLeadSelectionPath());
        });

        this.tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent ev) { maybePopup(ev); }
            @Override public void mouseReleased(MouseEvent ev) { maybePopup(ev); }
            @Override public void mouseClicked(MouseEvent ev) { maybeRename(ev); }
        });

        this.managerListener = new StepSequenceListener() {
            @Override public void onStepSequenceAdded(StepSequence s) {
                attachStepListener(s);
                SwingUtilities.invokeLater(() -> rebuildTree(s));
            }
            @Override public void onStepSequenceRemoved(StepSequence s) {
                detachStepListener(s);
                SwingUtilities.invokeLater(() -> rebuildTree(null));
            }
            @Override public void onStepSequenceModified(StepSequence s) {
                SwingUtilities.invokeLater(() -> refreshSequenceNode(s));
            }
        };
        this.sequenceManager.addStepSequenceListener(managerListener);
        for (StepSequence s : this.sequenceManager.getSequences()) attachStepListener(s);

        JScrollPane scroller = new JScrollPane(this.tree);
        scroller.setBorder(BorderFactory.createEmptyBorder());

        this.navList = buildNavList();
        JPanel navPanel = new JPanel(new BorderLayout());
        navPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Themes.lineColor(navPanel)));
        navPanel.add(navList, BorderLayout.CENTER);

        this.toolbar = buildToolbar();
        add(toolbar,        BorderLayout.NORTH);
        add(scroller,       BorderLayout.CENTER);
        add(navPanel,       BorderLayout.SOUTH);
        setMinimumSize(new Dimension(140, 0));
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize();
        int scrollbar = Math.max(12, UIManager.getInt("ScrollBar.width"));
        FontMetrics fm = getFontMetrics(getFont());
        int em = Math.max(1, fm.charWidth('M'));
        int treeW = 0;
        if (tree != null) {
            Dimension tp = tree.getPreferredSize();
            if (tp != null) treeW = tp.width;
            for (int r = 0; r < tree.getRowCount(); r++) {
                Rectangle rb = tree.getRowBounds(r);
                if (rb != null) treeW = Math.max(treeW, rb.x + rb.width);
            }
        }
        int navW = navList != null ? navList.getPreferredSize().width : 0;
        int content = Math.max(treeW + scrollbar + em, navW + em);
        int w = Math.min(Math.max(content, em * 14), em * 24);
        w = Math.max(w, toolbarPreferredWidth());   // never clip the +Sequence / +Step buttons
        return new Dimension(w, Math.max(base.height, 300));
    }

    /** Preferred width of the toolbar row, so the divider can be sized to show the full button labels. */
    public int toolbarPreferredWidth() {
        return toolbar != null ? toolbar.getPreferredSize().width : 0;
    }

    public JTree getTree() { return tree; }

    public SequenceManager getSequenceManager() { return sequenceManager; }

    public void selectSequence(StepSequence sequence) {
        DefaultMutableTreeNode n = findSequenceNode(sequence);
        if (n != null) selectNode(n);
    }

    public void selectBuiltIn(String key) {
        for (int i = 0; i < navList.getModel().getSize(); i++) {
            if (key.equals(navList.getModel().getElementAt(i).key)) {
                navList.setSelectedIndex(i);
                return;
            }
        }
    }

    public void selectStep(StepSequence sequence, Step step) {
        DefaultMutableTreeNode seqNode = findSequenceNode(sequence);
        if (seqNode == null) return;
        for (int i = 0; i < seqNode.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) seqNode.getChildAt(i);
            if (c.getUserObject() instanceof StepRef ref && ref.step == step) {
                selectNode(c);
                return;
            }
        }
    }

    private boolean isStepSelected(Step step) {
        Object o = tree.getLastSelectedPathComponent();
        return o instanceof DefaultMutableTreeNode n
                && n.getUserObject() instanceof StepRef r
                && r.step == step;
    }

    public void dispose() {
        try { sequenceManager.removeStepSequenceListener(managerListener); } catch (Exception ignored) {}
        for (Map.Entry<StepSequence, StepListener> e : new ArrayList<>(registeredStepListeners.entrySet())) {
            try { e.getKey().removeStepListener(e.getValue()); } catch (Exception ignored) {}
        }
        registeredStepListeners.clear();
        for (Map.Entry<StepSequence, SequenceExecutionListener> e : new ArrayList<>(registeredExecListeners.entrySet())) {
            try { e.getKey().removeSequenceExecutionListener(e.getValue()); } catch (Exception ignored) {}
        }
        registeredExecListeners.clear();
        for (Map.Entry<Step, StepExecutionListener> e : new ArrayList<>(registeredStepExecListeners.entrySet())) {
            try { e.getKey().removeExecutionListener(e.getValue()); } catch (Exception ignored) {}
        }
        registeredStepExecListeners.clear();
        for (Map.Entry<Step, StepVariableListener> e : new ArrayList<>(registeredStepVarListeners.entrySet())) {
            try { e.getKey().getVariableManager().removeVariableListener(e.getValue()); } catch (Exception ignored) {}
        }
        registeredStepVarListeners.clear();
    }

    private JList<BuiltInRef> buildNavList() {
        DefaultListModel<BuiltInRef> m = new DefaultListModel<>();
        m.addElement(new BuiltInRef(BUILTIN_GLOBAL_VARS, "Global Variables"));
        m.addElement(new BuiltInRef(BUILTIN_PREFERENCES, "Preferences"));
        m.addElement(new BuiltInRef(BUILTIN_ABOUT, "About"));

        JList<BuiltInRef> list = new JList<>(m);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBorder(BorderFactory.createEmptyBorder());
        list.setBackground(Themes.rowHighlightBackground(list));
        list.setFont(list.getFont().deriveFont(Font.ITALIC));
        list.setFixedCellHeight(list.getFontMetrics(list.getFont()).getHeight() + 10);
        list.setCellRenderer(new NavCellRenderer());
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            BuiltInRef ref = list.getSelectedValue();
            if (ref == null || selectionHandler == null) return;
            suppressSelectionEvent = true;
            try { tree.clearSelection(); } finally { suppressSelectionEvent = false; }
            selectionHandler.onBuiltInSelection(ref.key);
        });
        tree.addTreeSelectionListener(e -> {
            if (e.getNewLeadSelectionPath() != null) list.clearSelection();
        });
        return list;
    }

    private static final class NavCellRenderer extends DefaultListCellRenderer {
        private final Icon globalsIcon = scaled(UIManager.getIcon("FileChooser.listViewIcon"), 16);
        private final Icon prefsIcon   = scaled(UIManager.getIcon("FileView.computerIcon"), 16);
        private final Icon aboutIcon   = scaled(UIManager.getIcon("OptionPane.informationIcon"), 16);

        private static Icon scaled(Icon src, int size) {
            if (src == null) return null;
            if (src.getIconHeight() <= size && src.getIconWidth() <= size) return src;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    src.getIconWidth(), src.getIconHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try { src.paintIcon(null, g, 0, 0); } finally { g.dispose(); }
            return new ImageIcon(img.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH));
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            setFont(list.getFont());
            if (!isSelected) setBackground(list.getBackground());
            if (value instanceof BuiltInRef r) {
                setText(r.label);
                switch (r.key) {
                    case BUILTIN_GLOBAL_VARS -> setIcon(globalsIcon);
                    case BUILTIN_PREFERENCES -> setIcon(prefsIcon);
                    case BUILTIN_ABOUT       -> setIcon(aboutIcon);
                }
                if (getIcon() == null) setIcon(aboutIcon);
            }
            return this;
        }
    }

    private void applyTreeIndents() {
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI bui) {
            bui.setLeftChildIndent(16);
            bui.setRightChildIndent(12);
        }
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new GridLayout(1, 2, 6, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Themes.lineColor(bar)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        JButton addSeq  = makeToolbarButton("+ Sequence", "Create a new empty sequence");
        addSeq.addActionListener(e -> {
            StepSequence created = new StepSequence();
            sequenceManager.addStepSequence(created);
            com.xreous.stepperng.util.DuplicateNameWarning.checkSequenceTitle(parentWindow(), created);
        });
        bar.add(addSeq);

        JButton addStep = makeToolbarButton("+ Step", "Add a step to the currently selected sequence");
        addStep.addActionListener(e -> {
            StepSequence s = currentSequence();
            if (s != null) s.addStep();
        });
        bar.add(addStep);

        return bar;
    }

    private static JButton makeToolbarButton(String label, String tip) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setToolTipText(tip);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.putClientProperty("JButton.minimumWidth", 0);
        return b;
    }

    private void rebuildTree() { rebuildTree(null); }

    private void rebuildTree(StepSequence autoSelect) {
        Set<String> expandedSeqIds = snapshotExpanded();
        Object prevSelection = tree.getLastSelectedPathComponent();

        suppressSelectionEvent = true;
        try {
            root.removeAllChildren();
            for (StepSequence s : sequenceManager.getSequences()) {
                root.add(buildSequenceNode(s));
            }
            model.reload();

            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
                if (n.getUserObject() instanceof SequenceRef) {
                    tree.expandPath(new TreePath(n.getPath()));
                }
            }
            restoreExpanded(expandedSeqIds);

            DefaultMutableTreeNode toSelect = null;
            if (autoSelect != null) {
                toSelect = findFirstStepOrSequenceNode(autoSelect);
            } else if (prevSelection instanceof DefaultMutableTreeNode dmt) {
                toSelect = matchEquivalentNode(dmt);
            }
            if (toSelect == null) {
                for (int i = 0; i < root.getChildCount(); i++) {
                    DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
                    if (n.getUserObject() instanceof SequenceRef) {
                        toSelect = (n.getChildCount() > 0)
                                ? (DefaultMutableTreeNode) n.getChildAt(0) : n;
                        break;
                    }
                }
                if (toSelect == null && root.getChildCount() > 0) {
                    toSelect = (DefaultMutableTreeNode) root.getChildAt(0);
                }
            }
            if (toSelect != null) selectNode(toSelect);
        } finally {
            suppressSelectionEvent = false;
        }
        // Always fire so the detail pane and action bar sync on startup / after add.
        fireSelection(tree.getSelectionPath());
    }

    private DefaultMutableTreeNode findFirstStepOrSequenceNode(StepSequence seq) {
        DefaultMutableTreeNode n = findSequenceNode(seq);
        if (n == null) return null;
        return n.getChildCount() > 0 ? (DefaultMutableTreeNode) n.getChildAt(0) : n;
    }

    private void refreshSequenceNode(StepSequence sequence) {
        DefaultMutableTreeNode n = findSequenceNode(sequence);
        if (n == null) { rebuildTree(); return; }

        boolean structureChanged = n.getChildCount() != sequence.getSteps().size();
        if (!structureChanged) {
            for (int i = 0; i < sequence.getSteps().size(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) n.getChildAt(i);
                if (!(child.getUserObject() instanceof StepRef ref) || ref.step != sequence.getSteps().get(i)) {
                    structureChanged = true;
                    break;
                }
            }
        }
        if (structureChanged) {
            n.removeAllChildren();
            for (Step step : sequence.getSteps()) {
                n.add(new DefaultMutableTreeNode(new StepRef(sequence, step)));
            }
            model.nodeStructureChanged(n);
            tree.expandPath(new TreePath(n.getPath()));
        } else {
            for (int i = 0; i < n.getChildCount(); i++) {
                model.nodeChanged((DefaultMutableTreeNode) n.getChildAt(i));
            }
            model.nodeChanged(n);
        }
    }

    private DefaultMutableTreeNode buildSequenceNode(StepSequence s) {
        DefaultMutableTreeNode seqNode = new DefaultMutableTreeNode(new SequenceRef(s));
        for (Step step : s.getSteps()) {
            seqNode.add(new DefaultMutableTreeNode(new StepRef(s, step)));
        }
        return seqNode;
    }

    private Set<String> snapshotExpanded() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
            if (n.getUserObject() instanceof SequenceRef r
                    && tree.isExpanded(new TreePath(n.getPath()))) {
                ids.add(r.sequence.getSequenceId());
            }
        }
        return ids;
    }

    private void restoreExpanded(Set<String> expandedIds) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
            if (n.getUserObject() instanceof SequenceRef r
                    && expandedIds.contains(r.sequence.getSequenceId())) {
                tree.expandPath(new TreePath(n.getPath()));
            }
        }
    }

    private DefaultMutableTreeNode matchEquivalentNode(DefaultMutableTreeNode prev) {
        Object u = prev.getUserObject();
        if (u instanceof SequenceRef r) return findSequenceNode(r.sequence);
        if (u instanceof StepRef r) {
            DefaultMutableTreeNode sn = findSequenceNode(r.sequence);
            if (sn == null) return null;
            for (int i = 0; i < sn.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) sn.getChildAt(i);
                if (c.getUserObject() instanceof StepRef sr && sr.step == r.step) return c;
            }
            return sn;
        }
        if (u instanceof BuiltInRef) return null;
        return null;
    }

    DefaultMutableTreeNode findSequenceNode(StepSequence sequence) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
            if (n.getUserObject() instanceof SequenceRef r && r.sequence == sequence) return n;
        }
        return null;
    }

    private void selectNode(DefaultMutableTreeNode n) {
        TreePath p = new TreePath(n.getPath());
        tree.setSelectionPath(p);
        tree.scrollPathToVisible(p);
    }

    private void fireSelection(TreePath path) {
        if (path == null || selectionHandler == null) return;
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) return;
        Object u = node.getUserObject();
        if (u instanceof SequenceRef r) selectionHandler.onSelection(r.sequence, null);
        else if (u instanceof StepRef r) selectionHandler.onSelection(r.sequence, r.step);
    }

    private StepSequence currentSequence() {
        Object o = tree.getLastSelectedPathComponent();
        if (!(o instanceof DefaultMutableTreeNode node)) return null;
        Object u = node.getUserObject();
        if (u instanceof SequenceRef r) return r.sequence;
        if (u instanceof StepRef r) return r.sequence;
        return null;
    }

    private void attachStepListener(StepSequence s) {
        if (registeredStepListeners.containsKey(s)) return;
        StepListener l = new StepAdapter() {
            @Override public void onStepAdded(Step step) {
                attachStepExecutionListener(step);
                SwingUtilities.invokeLater(() -> { refreshSequenceNode(s); selectStep(s, step); });
            }
            @Override public void onStepRemoved(Step step) {
                detachStepExecutionListener(step);
                boolean wasSelected = isStepSelected(step);
                SwingUtilities.invokeLater(() -> {
                    refreshSequenceNode(s);
                    if (wasSelected) selectSequence(s);
                });
            }
            @Override public void onStepUpdated(Step step) {
                SwingUtilities.invokeLater(() -> refreshSequenceNode(s));
            }
        };
        s.addStepListener(l);
        registeredStepListeners.put(s, l);

        SequenceExecutionListener el = new SequenceExecutionAdapter() {
            @Override public void beforeSequenceStart(List<Step> steps) {
                SwingUtilities.invokeLater(() -> refreshSequenceRow(s));
            }
            @Override public void afterSequenceEnd(boolean success) {
                SwingUtilities.invokeLater(() -> refreshSequenceRow(s));
            }
        };
        s.addSequenceExecutionListener(el);
        registeredExecListeners.put(s, el);

        for (Step step : s.getSteps()) attachStepExecutionListener(step);
    }

    private void detachStepListener(StepSequence s) {
        StepListener l = registeredStepListeners.remove(s);
        if (l != null) s.removeStepListener(l);
        SequenceExecutionListener el = registeredExecListeners.remove(s);
        if (el != null) s.removeSequenceExecutionListener(el);
        for (Step step : s.getSteps()) detachStepExecutionListener(step);
    }

    private void attachStepExecutionListener(Step step) {
        if (registeredStepExecListeners.containsKey(step)) return;
        StepExecutionListener l = new StepExecutionAdapter() {
            @Override public void stepExecuted(StepExecutionInfo info) {
                SwingUtilities.invokeLater(() -> refreshStepRow(step));
            }
        };
        step.addExecutionListener(l);
        registeredStepExecListeners.put(step, l);

        if (!registeredStepVarListeners.containsKey(step)) {
            StepSequence owner = step.getSequence();
            StepVariableListener vl = new StepVariableListener() {
                @Override public void onVariableAdded(StepVariable v)   { SwingUtilities.invokeLater(() -> refreshSequenceRow(owner)); }
                @Override public void onVariableRemoved(StepVariable v) { SwingUtilities.invokeLater(() -> refreshSequenceRow(owner)); }
                @Override public void onVariableChange(StepVariable v)  { SwingUtilities.invokeLater(() -> refreshSequenceRow(owner)); }
            };
            step.getVariableManager().addVariableListener(vl);
            registeredStepVarListeners.put(step, vl);
        }
    }

    private void detachStepExecutionListener(Step step) {
        StepExecutionListener l = registeredStepExecListeners.remove(step);
        if (l != null) step.removeExecutionListener(l);
        StepVariableListener vl = registeredStepVarListeners.remove(step);
        if (vl != null) step.getVariableManager().removeVariableListener(vl);
    }

    private void refreshSequenceRow(StepSequence s) {
        DefaultMutableTreeNode n = findSequenceNode(s);
        if (n != null) model.nodeChanged(n);
    }

    private void refreshStepRow(Step step) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode sn = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < sn.getChildCount(); j++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) sn.getChildAt(j);
                if (c.getUserObject() instanceof StepRef sr && sr.step == step) {
                    model.nodeChanged(c);
                    return;
                }
            }
        }
    }

    private void maybePopup(MouseEvent ev) {
        if (!ev.isPopupTrigger()) return;
        TreePath path = tree.getPathForLocation(ev.getX(), ev.getY());
        if (path == null) return;
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) return;
        Object u = node.getUserObject();
        JPopupMenu menu;
        if (u instanceof SequenceRef r) menu = sequenceMenu(r.sequence);
        else if (u instanceof StepRef r) menu = stepMenu(r.sequence, r.step);
        else return;
        menu.show(tree, ev.getX(), ev.getY());
    }

    private void maybeRename(MouseEvent ev) {
        if (ev.getClickCount() != 2 || ev.getButton() != MouseEvent.BUTTON1) return;
        TreePath path = tree.getPathForLocation(ev.getX(), ev.getY());
        if (path == null) return;
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) return;
        Object u = node.getUserObject();
        if (u instanceof SequenceRef r) renameSequence(r.sequence);
        else if (u instanceof StepRef r) renameStep(r.sequence, r.step);
    }

    private void renameSequence(StepSequence s) {
        String nt = JOptionPane.showInputDialog(parentWindow(), "Sequence name:", s.getTitle());
        if (nt != null && !nt.trim().isEmpty()) {
            s.setTitle(nt.trim());
            sequenceManager.sequenceModified(s);
            com.xreous.stepperng.util.DuplicateNameWarning.checkSequenceTitle(parentWindow(), s);
        }
    }

    private void renameStep(StepSequence s, Step step) {
        String current = StepRef.displayTitle(step);
        String nt = JOptionPane.showInputDialog(parentWindow(), "Step name:", current);
        if (nt != null && !nt.trim().isEmpty()) {
            step.setTitle(nt.trim());
            s.stepModified(step);
        }
    }

    private Component parentWindow() { return SwingUtilities.getWindowAncestor(this); }

    private JPopupMenu sequenceMenu(StepSequence s) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Add Step", e -> s.addStep()));
        m.add(item(s.isDisabled() ? "Enable Sequence" : "Disable Sequence", e -> {
            s.setDisabled(!s.isDisabled());
            sequenceManager.sequenceModified(s);
        }));
        m.add(item("Rename\u2026", e -> renameSequence(s)));
        m.add(item("Duplicate", e -> duplicateSequence(s)));
        m.addSeparator();
        m.add(item("Delete", e -> {
            int r = JOptionPane.showConfirmDialog(parentWindow(),
                    "Remove sequence '" + s.getTitle() + "'?",
                    "Remove Sequence", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) sequenceManager.removeStepSequence(s);
        }));
        return m;
    }

    private JPopupMenu stepMenu(StepSequence s, Step step) {
        JPopupMenu m = new JPopupMenu();
        m.add(item(step.isEnabled() ? "Disable Step" : "Enable Step", e -> {
            step.setEnabled(!step.isEnabled());
            s.stepModified(step);
        }));
        m.add(item("Rename\u2026", e -> renameStep(s, step)));
        m.addSeparator();
        boolean isPre  = step.getStepId().equals(s.getValidationStepId());
        boolean isPost = step.getStepId().equals(s.getPostValidationStepId());
        m.add(item(isPre ? "Clear Pre-Validation Step" : "Set as Pre-Validation Step", e -> {
            if (isPre) {
                s.setValidationStepId(null);
            } else {
                s.setValidationStepId(step.getStepId());
                int idx = s.getSteps().indexOf(step);
                if (idx > 0) s.moveStep(idx, 0);
            }
            Stepper.getSequenceManager().sequenceModified(s);
        }));
        m.add(item(isPost ? "Clear Post-Validation Step" : "Set as Post-Validation Step", e -> {
            if (isPost) {
                s.setPostValidationStepId(null);
            } else {
                s.setPostValidationStepId(step.getStepId());
                int idx = s.getSteps().indexOf(step);
                int last = s.getSteps().size() - 1;
                if (idx >= 0 && idx != last) s.moveStep(idx, last);
            }
            Stepper.getSequenceManager().sequenceModified(s);
        }));
        m.addSeparator();
        m.add(item("Delete", e -> {
            int r = JOptionPane.showConfirmDialog(parentWindow(),
                    "Remove step '" + step.getTitle() + "'?",
                    "Remove Step", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) s.removeStep(step);
        }));
        return m;
    }

    private static JMenuItem item(String label, Consumer<Object> action) {
        JMenuItem it = new JMenuItem(label);
        it.addActionListener(e -> action.accept(null));
        return it;
    }

    private void duplicateSequence(StepSequence original) {
        StepSequence copy = sequenceManager.duplicate(original);
        if (copy == null) {
            JOptionPane.showMessageDialog(parentWindow(), "Failed to duplicate '" + original.getTitle()
                    + "'. See the extension error log for details.",
                    "Duplicate Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        com.xreous.stepperng.util.DuplicateNameWarning.checkSequenceTitle(parentWindow(), copy);
    }

    /** Expands every row in the tree (re-walks because expanding a row may reveal new rows). */
    public void expandAll() {
        int i = 0;
        while (i < tree.getRowCount()) {
            tree.expandRow(i);
            i++;
        }
    }

    record SequenceRef(StepSequence sequence) {
        @Override public String toString() { return sequence.getTitle(); }
    }

    public record StepRef(StepSequence sequence, Step step) {
        @Override public String toString() { return displayTitle(step); }

        /** User-set title, falling back to the request URI path. */
        public static String displayTitle(Step step) {
            String t = step.getTitle();
            if (t != null && !t.isBlank() && !t.startsWith("Step ")) return t;
            String path = extractPath(step.getRequest());
            if (path != null && !path.isBlank()) return path;
            return t != null ? t : "Step";
        }

        private static String extractPath(byte[] req) {
            if (req == null || req.length == 0) return null;
            int end = Math.min(req.length, 2048);
            int sp1 = -1, sp2 = -1;
            for (int i = 0; i < end; i++) {
                byte b = req[i];
                if (b == ' ') { if (sp1 < 0) sp1 = i; else { sp2 = i; break; } }
                if (b == '\n' || b == '\r') break;
            }
            if (sp1 < 0 || sp2 < 0 || sp2 <= sp1 + 1) return null;
            return new String(req, sp1 + 1, sp2 - sp1 - 1);
        }
    }

    record BuiltInRef(String key, String label) {
        @Override public String toString() { return label; }
    }

    /** Sequence/step tree rows with icon, bold title, and coloured state chips. */
    private static final class NodeRenderer implements TreeCellRenderer {
        private final Icon folderIcon = UIManager.getIcon("FileView.directoryIcon");
        private final Icon fileIcon   = UIManager.getIcon("FileView.fileIcon");

        private final JPanel panel       = new JPanel();
        private final JLabel mainLabel   = new JLabel();
        private final JPanel chipsPanel  = new JPanel();

        NodeRenderer() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
            chipsPanel.setLayout(new BoxLayout(chipsPanel, BoxLayout.X_AXIS));
            panel.setOpaque(false);
            chipsPanel.setOpaque(false);
            mainLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
            chipsPanel.setAlignmentY(Component.CENTER_ALIGNMENT);
            panel.add(mainLabel);
            panel.add(Box.createHorizontalStrut(6));
            panel.add(chipsPanel);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            chipsPanel.removeAll();
            mainLabel.setIcon(null);
            mainLabel.setFont(tree.getFont());
            Color fg = sel ? UIManager.getColor("Tree.selectionForeground") : UIManager.getColor("Tree.textForeground");
            if (fg == null) fg = tree.getForeground();
            mainLabel.setForeground(fg);
            panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            panel.setBackground(sel ? UIManager.getColor("Tree.selectionBackground") : tree.getBackground());
            panel.setOpaque(sel);

            if (value instanceof DefaultMutableTreeNode n) {
                Object u = n.getUserObject();
                if (u instanceof SequenceRef r)  { mainLabel.setIcon(folderIcon); decorateSequence(r.sequence, sel); }
                else if (u instanceof StepRef r) { mainLabel.setIcon(fileIcon);   decorateStep(r.sequence, r.step, sel); }
                else                              { mainLabel.setText(String.valueOf(u)); }
            }
            return panel;
        }

        private void decorateSequence(StepSequence seq, boolean sel) {
            mainLabel.setFont(mainLabel.getFont().deriveFont(Font.BOLD));
            mainLabel.setText(seq.getTitle());

            int pubCount = countPublished(seq);
            if (pubCount > 0)      addChip("published\u00D7" + pubCount, sel, Themes.successForeground(mainLabel));
            if (seq.isExecuting()) addChip("running",                    sel, Themes.successForeground(mainLabel));
            if (seq.isDisabled()) {
                addChip("disabled", sel, Themes.disabledForeground(mainLabel));
                if (!sel) mainLabel.setForeground(Themes.disabledForeground(mainLabel));
            }

            StringBuilder tip = new StringBuilder("<html><b>").append(escape(seq.getTitle())).append("</b>");
            if (seq.isDisabled()) tip.append("<br/>Sequence is <b>disabled</b> (won't auto-trigger)");
            if (seq.isExecuting()) tip.append("<br/>Currently executing\u2026");
            if (pubCount > 0) tip.append("<br/>").append(pubCount).append(" published variable")
                    .append(pubCount == 1 ? "" : "s").append(" (session handling source)");
            tip.append("</html>");
            panel.setToolTipText(tip.toString());
        }

        private void decorateStep(StepSequence seq, Step step, boolean sel) {
            mainLabel.setText(StepRef.displayTitle(step));

            boolean disabled     = !step.isEnabled();
            boolean hasCondition = step.getCondition() != null && step.getCondition().isConfigured();
            boolean isPre        = step.getStepId().equals(seq.getValidationStepId());
            boolean isPost       = step.getStepId().equals(seq.getPostValidationStepId());
            Integer sc           = lastStatusCode(step);

            if (isPre)                           addChip("pre",      sel, Themes.accentForeground(mainLabel));
            if (isPost)                          addChip("post",     sel, Themes.accentForeground(mainLabel));
            if (hasCondition && !isPre && !isPost) addChip("if",     sel, Themes.accentForeground(mainLabel));
            if (sc != null)   addChip(sc + "",    sel, statusColor(sc, mainLabel));
            if (disabled)     addChip("disabled", sel, Themes.disabledForeground(mainLabel));

            if (!sel) {
                if (disabled)       mainLabel.setForeground(Themes.disabledForeground(mainLabel));
                else if (sc != null) mainLabel.setForeground(statusColor(sc, mainLabel));
            }

            StringBuilder tip = new StringBuilder("<html><b>").append(escape(StepRef.displayTitle(step))).append("</b>");
            if (disabled)     tip.append("<br/>Step is <b>disabled</b> (skipped during execution)");
            if (isPre)        tip.append("<br/>Pre-validation step (auto-triggers session restore)");
            if (isPost)       tip.append("<br/>Post-validation step (verifies recovery)");
            if (hasCondition) tip.append("<br/>Has a post-execution condition (see the 'If … Then / else' row)");
            if (sc != null)   tip.append("<br/>Last response status: <b>").append(sc).append("</b>");
            tip.append("</html>");
            panel.setToolTipText(tip.toString());
        }

        private void addChip(String text, boolean selected, Color color) {
            JLabel chip = new JLabel(text);
            chip.setFont(chip.getFont().deriveFont(chip.getFont().getSize2D() - 1f));
            Color stroke = selected ? mainLabel.getForeground() : color;
            chip.setForeground(stroke);
            chip.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(alpha(stroke, 140), 1, true),
                    BorderFactory.createEmptyBorder(0, 5, 0, 5)));
            chip.setAlignmentY(Component.CENTER_ALIGNMENT);
            if (chipsPanel.getComponentCount() > 0) chipsPanel.add(Box.createHorizontalStrut(4));
            chipsPanel.add(chip);
        }

        private static Color alpha(Color c, int a) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
        }

        private static Color statusColor(int sc, JLabel l) {
            if (sc >= 200 && sc < 300) return Themes.successForeground(l);
            if (sc >= 400)             return Themes.errorForeground(l);
            if (sc >= 300)             return Themes.warningForeground(l);
            return Themes.disabledForeground(l);
        }

        private static int countPublished(StepSequence seq) {
            int n = 0;
            for (Step s : seq.getSteps()) {
                for (StepVariable v : s.getVariableManager().getVariables()) {
                    if (v.isPublished()) n++;
                }
            }
            return n;
        }

        private static Integer lastStatusCode(Step step) {
            try {
                StepExecutionInfo info = step.getLastExecutionResult();
                if (info == null || info.getRequestResponse() == null) return null;
                var resp = info.getRequestResponse().response();
                if (resp == null) return null;
                return (int) resp.statusCode();
            } catch (Exception e) {
                return null;
            }
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    BiConsumer<StepSequence, Integer> getPostMoveSelector() {
        return (seq, newIndex) -> {
            if (newIndex < 0 || newIndex >= seq.getSteps().size()) return;
            selectStep(seq, seq.getSteps().get(newIndex));
        };
    }
}
