package com.xreous.stepperng.sequence.view;

import com.xreous.stepperng.Stepper;
import com.xreous.stepperng.condition.ConditionFailAction;
import com.xreous.stepperng.condition.StepCondition;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.step.Step;
import com.xreous.stepperng.step.listener.StepAdapter;
import com.xreous.stepperng.variable.PostExecutionStepVariable;
import com.xreous.stepperng.variable.PreExecutionStepVariable;
import com.xreous.stepperng.variable.RegexVariable;
import com.xreous.stepperng.variable.StepVariable;
import com.xreous.stepperng.variable.listener.StepVariableListener;
import com.xreous.stepperng.util.view.Themes;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class SequenceOverviewPanel extends JPanel {

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private final StepSequence stepSequence;
    private final OverviewTableModel tableModel;
    private final JTable overviewTable;
    private final JLabel validationLabel;
    private final JLabel sequenceTitleLabel;
    private final JButton disableButton;
    private final PublishedVarsTableModel publishedModel;
    private final JTable publishedTable;
    private final JLabel conflictLabel;
    private volatile boolean refreshPending = false;

    public SequenceOverviewPanel(StepSequence stepSequence) {
        super(new BorderLayout());
        this.stepSequence = stepSequence;

        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        headerPanel.add(new JLabel("Sequence: "));
        sequenceTitleLabel = new JLabel(stepSequence.getTitle());
        sequenceTitleLabel.setFont(sequenceTitleLabel.getFont().deriveFont(Font.BOLD));
        headerPanel.add(sequenceTitleLabel);
        headerPanel.add(Box.createHorizontalStrut(10));
        disableButton = new JButton(stepSequence.isDisabled() ? "Enable Sequence" : "Disable Sequence");
        disableButton.addActionListener(e -> {
            stepSequence.setDisabled(!stepSequence.isDisabled());
            updateDisableButton();
            SequenceManager sm = Stepper.getSequenceManager();
            if (sm != null) sm.sequenceModified(stepSequence);
        });
        headerPanel.add(disableButton);
        headerPanel.add(Box.createHorizontalStrut(10));
        validationLabel = new JLabel();
        updateValidationLabel();
        headerPanel.add(validationLabel);
        headerPanel.add(Box.createHorizontalStrut(10));
        headerPanel.add(new JLabel("Max post-validation fails:"));
        JSpinner maxFailsSpinner = new JSpinner(
                new SpinnerNumberModel(Math.max(1, stepSequence.getMaxConsecutiveFailures()), 1, 100, 1));
        maxFailsSpinner.setToolTipText(
                "Pause task execution / alert after this many consecutive post-validation failures.");
        maxFailsSpinner.addChangeListener(ce -> {
            int v = (int) maxFailsSpinner.getValue();
            if (v != stepSequence.getMaxConsecutiveFailures()) {
                stepSequence.setMaxConsecutiveFailures(v);
                SequenceManager sm = Stepper.getSequenceManager();
                if (sm != null) sm.sequenceModified(stepSequence);
            }
        });
        headerPanel.add(maxFailsSpinner);

        this.tableModel = new OverviewTableModel();
        this.overviewTable = new JTable(tableModel);
        overviewTable.setRowHeight(overviewTable.getFontMetrics(overviewTable.getFont()).getHeight() + 10);
        overviewTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        overviewTable.getTableHeader().setReorderingAllowed(false);
        overviewTable.setDefaultRenderer(Object.class, new OverviewCellRenderer());
        packOverviewColumns();

        overviewTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { handleOverviewTablePopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { handleOverviewTablePopup(e); }
        });

        JScrollPane stepsScroll = new JScrollPane(overviewTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel publishedPanel = new JPanel(new BorderLayout(0, 4));
        publishedPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel publishedHeader = new JPanel(new BorderLayout());
        JLabel publishedTitle = new JLabel("Variables");
        publishedTitle.setFont(publishedTitle.getFont().deriveFont(Font.BOLD));
        publishedHeader.add(publishedTitle, BorderLayout.WEST);
        conflictLabel = new JLabel();
        conflictLabel.setForeground(Themes.warningForeground(conflictLabel));
        conflictLabel.setFont(conflictLabel.getFont().deriveFont(Font.ITALIC, conflictLabel.getFont().getSize2D() - 1f));
        publishedHeader.add(conflictLabel, BorderLayout.CENTER);
        publishedHeader.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 0));
        publishedPanel.add(publishedHeader, BorderLayout.NORTH);

        this.publishedModel = new PublishedVarsTableModel();
        this.publishedTable = new JTable(publishedModel);
        publishedTable.setRowHeight(publishedTable.getFontMetrics(publishedTable.getFont()).getHeight() + 8);
        publishedTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        TableColumnModel cm = publishedTable.getColumnModel();
        cm.getColumn(0).setMaxWidth(65);
        cm.getColumn(0).setPreferredWidth(65);
        cm.getColumn(1).setPreferredWidth(100);
        cm.getColumn(2).setPreferredWidth(100);
        cm.getColumn(3).setPreferredWidth(120);
        cm.getColumn(4).setPreferredWidth(400);
        cm.getColumn(5).setMaxWidth(80);
        cm.getColumn(5).setPreferredWidth(70);
        publishedTable.setDefaultRenderer(Object.class, new PublishedVarsCellRenderer());

        publishedTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { handlePublishedTablePopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { handlePublishedTablePopup(e); }
        });

        JScrollPane publishedScroll = new JScrollPane(publishedTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        publishedPanel.add(publishedScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stepsScroll, publishedPanel);
        splitPane.setResizeWeight(0.45);
        splitPane.setDividerSize(5);
        com.xreous.stepperng.util.view.SplitPaneDoubleClick.install(splitPane,
                () -> splitPane.setDividerLocation(0.5d));

        add(headerPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);

        stepSequence.addStepListener(new StepAdapter() {
            @Override public void onStepAdded(Step step) {
                attachVariableListener(step);
                refresh();
            }
            @Override public void onStepRemoved(Step step) { refresh(); }
            @Override public void onStepUpdated(Step step) { refresh(); }
        });

        for (Step step : stepSequence.getSteps()) {
            attachVariableListener(step);
        }

        refresh();
        SwingUtilities.invokeLater(() -> packTableColumns(publishedTable));

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && isShowing() && refreshPending) {
                refreshPending = false;
                doRefresh();
            }
        });
    }

    private void attachVariableListener(Step step) {
        step.getVariableManager().addVariableListener(new StepVariableListener() {
            @Override public void onVariableAdded(StepVariable variable) { SwingUtilities.invokeLater(() -> refresh()); }
            @Override public void onVariableRemoved(StepVariable variable) { SwingUtilities.invokeLater(() -> refresh()); }
            @Override public void onVariableChange(StepVariable variable) { SwingUtilities.invokeLater(() -> refresh()); }
        });
    }

    private void handlePublishedTablePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = publishedTable.rowAtPoint(e.getPoint());
        if (row < 0 || row >= publishedModel.rows.size()) return;
        publishedTable.setRowSelectionInterval(row, row);
        PublishedVarRow r = publishedModel.rows.get(row);

        JPopupMenu menu = new JPopupMenu();

        String usage = "$VAR:" + stepSequence.getTitle() + ":" + r.variable.getIdentifier() + "$";
        JMenuItem copyUsage = new JMenuItem("Copy Variable Reference: " + usage);
        copyUsage.addActionListener(a -> copyToClipboard(usage));
        menu.add(copyUsage);

        String val = r.variable.getValue();
        if (val != null && !val.isEmpty()) {
            String displayVal = val.length() > 60 ? val.substring(0, 60) + "..." : val;
            JMenuItem copyValue = new JMenuItem("Copy Value: " + displayVal);
            copyValue.addActionListener(a -> copyToClipboard(val));
            menu.add(copyValue);
        }

        JMenuItem copyName = new JMenuItem("Copy Variable Name: " + r.variable.getIdentifier());
        copyName.addActionListener(a -> copyToClipboard(r.variable.getIdentifier()));
        menu.add(copyName);

        if (r.variable instanceof RegexVariable rv && rv.getConditionText() != null && !rv.getConditionText().isEmpty()) {
            JMenuItem copyRegex = new JMenuItem("Copy Regex");
            copyRegex.addActionListener(a -> copyToClipboard(rv.getConditionText()));
            menu.add(copyRegex);
        }

        menu.addSeparator();
        JMenuItem togglePublished = new JMenuItem(r.variable.isPublished() ? "Unpublish" : "Publish");
        togglePublished.addActionListener(a -> publishedModel.setValueAt(!r.variable.isPublished(), row, 0));
        menu.add(togglePublished);

        menu.show(publishedTable, e.getX(), e.getY());
    }

    private void handleOverviewTablePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = overviewTable.rowAtPoint(e.getPoint());
        if (row < 0 || row >= stepSequence.getSteps().size()) return;
        overviewTable.setRowSelectionInterval(row, row);
        Step step = stepSequence.getSteps().get(row);

        JPopupMenu menu = new JPopupMenu();

        List<StepVariable> vars = step.getVariableManager().getVariables();
        if (!vars.isEmpty()) {
            JMenu copyVarMenu = new JMenu("Copy Variable Reference");
            for (StepVariable var : vars) {
                String usage = "$VAR:" + stepSequence.getTitle() + ":" + var.getIdentifier() + "$";
                JMenuItem item = new JMenuItem(var.getIdentifier());
                item.addActionListener(a -> copyToClipboard(usage));
                copyVarMenu.add(item);
            }
            menu.add(copyVarMenu);

            JMenu copyValMenu = new JMenu("Copy Variable Value");
            for (StepVariable var : vars) {
                String val = var.getValue();
                String display = (val == null || val.isEmpty()) ? "(empty)" : (val.length() > 40 ? val.substring(0, 40) + "..." : val);
                JMenuItem item = new JMenuItem(var.getIdentifier() + " = " + display);
                item.setEnabled(val != null && !val.isEmpty());
                item.addActionListener(a -> copyToClipboard(val));
                copyValMenu.add(item);
            }
            menu.add(copyValMenu);

            menu.addSeparator();
        }

        String execHeader = "X-Stepper-Execute-Before: " + stepSequence.getTitle();
        JMenuItem copyExecHeader = new JMenuItem("Copy Execute-Before Header");
        copyExecHeader.addActionListener(a -> copyToClipboard(execHeader));
        menu.add(copyExecHeader);

        menu.show(overviewTable, e.getX(), e.getY());
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    public void refresh() {
        if (!isShowing()) {
            refreshPending = true;
            return;
        }
        doRefresh();
    }

    private void doRefresh() {
        sequenceTitleLabel.setText(stepSequence.getTitle());
        updateValidationLabel();
        updateDisableButton();
        tableModel.fireTableDataChanged();
        publishedModel.reload();
        publishedModel.fireTableDataChanged();
        updateConflictWarning();
        int rows = Math.max(3, publishedModel.getRowCount());
        int header = publishedTable.getTableHeader() != null ? publishedTable.getTableHeader().getPreferredSize().height : 22;
        publishedTable.setPreferredScrollableViewportSize(
                new Dimension(0, rows * publishedTable.getRowHeight() + header + 4));
        SwingUtilities.invokeLater(() -> { packOverviewColumns(); packTableColumns(publishedTable); });
    }

    private void packOverviewColumns() {
        if (overviewTable.getColumnCount() == 0) return;
        TableColumnModel cm = overviewTable.getColumnModel();
        FontMetrics fm = overviewTable.getFontMetrics(overviewTable.getFont());
        int em = Math.max(1, fm.charWidth('M'));
        int pad = em * 2;
        int[] caps = {0, 9, 18, 22, 28, 22, 18, 0};
        int[] weight = {3, 8, 14, 16, 18, 14, 12, 30};
        for (int col = 0; col < cm.getColumnCount() && col < caps.length; col++) {
            int headerW = fm.stringWidth(overviewTable.getColumnName(col)) + pad;
            int contentW = headerW;
            for (int row = 0; row < overviewTable.getRowCount(); row++) {
                Object v = overviewTable.getValueAt(row, col);
                if (v != null) contentW = Math.max(contentW, fm.stringWidth(v.toString()) + pad);
            }
            int capPx = caps[col] > 0 ? caps[col] * em : Integer.MAX_VALUE;
            int pref = Math.min(contentW, capPx);
            TableColumn c = cm.getColumn(col);
            c.setMinWidth(Math.min(headerW, pref));
            c.setPreferredWidth(Math.max(pref, weight[col] * em));
            c.setMaxWidth(capPx);
        }
        int hashW = fm.stringWidth("99") + em;
        TableColumn first = cm.getColumn(0);
        first.setMinWidth(hashW);
        first.setPreferredWidth(hashW);
        first.setMaxWidth(hashW);
    }

    private void packTableColumns(JTable table) {
        if (table.getColumnCount() == 0 || table.getRowCount() == 0) return;
        TableColumnModel columnModel = table.getColumnModel();
        FontMetrics fm = table.getFontMetrics(table.getFont());
        int padding = 20;

        for (int col = 0; col < columnModel.getColumnCount(); col++) {
            TableColumn column = columnModel.getColumn(col);
            int headerWidth = fm.stringWidth(table.getColumnName(col)) + padding;
            int maxWidth = headerWidth;

            for (int row = 0; row < table.getRowCount(); row++) {
                Object value = table.getValueAt(row, col);
                if (value != null && !(value instanceof Boolean)) {
                    maxWidth = Math.max(maxWidth, fm.stringWidth(value.toString()) + padding);
                }
            }

            column.setMinWidth(Math.min(maxWidth, 60));
            column.setPreferredWidth(maxWidth);
        }

        table.revalidate();
    }

    private void updateConflictWarning() {
        SequenceManager mgr = Stepper.getSequenceManager();
        if (mgr == null) { conflictLabel.setText(""); return; }

        List<String> conflicts = new ArrayList<>();
        for (StepSequence other : mgr.getSequences()) {
            if (other == stepSequence) continue;
            List<String> otherPublished = other.getRollingVariablesForWholeSequence().stream()
                    .filter(StepVariable::isPublished)
                    .map(StepVariable::getIdentifier)
                    .collect(Collectors.toList());
            if (!otherPublished.isEmpty()) {
                conflicts.add(other.getTitle() + " (" + String.join(", ", otherPublished) + ")");
            }
        }
        if (conflicts.isEmpty()) {
            conflictLabel.setText("");
        } else {
            conflictLabel.setText("  \u26A0 Other sequences also publish vars: " + String.join("; ", conflicts));
        }
    }

    private void updateValidationLabel() {
        StringBuilder sb = new StringBuilder();
        int valIdx = stepSequence.resolveValidationStepIndex();
        if (valIdx >= 0 && valIdx < stepSequence.getSteps().size()) {
            Step valStep = stepSequence.getSteps().get(valIdx);
            sb.append("Pre-Validation: Step ").append(valIdx + 1)
              .append(" (").append(valStep.getTitle()).append(")");
        } else {
            sb.append("Pre-Validation: None");
        }
        int postValIdx = stepSequence.resolvePostValidationStepIndex();
        if (postValIdx >= 0 && postValIdx < stepSequence.getSteps().size()) {
            Step postValStep = stepSequence.getSteps().get(postValIdx);
            sb.append("  |  Post-Validation: Step ").append(postValIdx + 1)
              .append(" (").append(postValStep.getTitle()).append(")");
        } else {
            sb.append("  |  Post-Validation: None");
        }
        validationLabel.setText(sb.toString());
    }

    private void updateDisableButton() {
        disableButton.setText(stepSequence.isDisabled() ? "Enable Sequence" : "Disable Sequence");
    }


    private class PublishedVarsTableModel extends AbstractTableModel {
        private final String[] COLS = {"Published", "Variable", "Step", "Type / Regex", "Value", "Updated"};
        private List<PublishedVarRow> rows = new ArrayList<>();

        void reload() {
            rows.clear();
            var steps = stepSequence.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                for (StepVariable var : step.getVariableManager().getVariables()) {
                    String detail;
                    if (var instanceof RegexVariable rv) {
                        String regex = rv.getConditionText();
                        detail = (regex != null ? regex : "");
                    } else if (var instanceof PreExecutionStepVariable) {
                        detail = "Pre-exec (" + var.getType() + ")";
                    } else {
                        detail = var.getType();
                    }
                    rows.add(new PublishedVarRow(var, step, i, detail));
                }
            }
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int col) { return col == 0; }

        @Override
        public Object getValueAt(int row, int col) {
            PublishedVarRow r = rows.get(row);
            return switch (col) {
                case 0 -> r.variable.isPublished();
                case 1 -> r.variable.getIdentifier();
                case 2 -> r.step.getTitle();
                case 3 -> r.regex;
                case 4 -> {
                    String v = r.variable.getValue();
                    yield (v == null || v.isEmpty()) ? "-" : v;
                }
                case 5 -> {
                    long ts = r.variable.getLastUpdated();
                    yield ts <= 0 ? "-" : TIME_FORMAT.format(new Date(ts));
                }
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col != 0) return;
            boolean publish = (Boolean) value;
            PublishedVarRow r = rows.get(row);

            if (publish) {
                SequenceManager mgr = Stepper.getSequenceManager();
                if (mgr != null) {
                    List<String> conflicts = new ArrayList<>();
                    for (StepSequence other : mgr.getSequences()) {
                        if (other == stepSequence) continue;
                        for (StepVariable v : other.getRollingVariablesForWholeSequence()) {
                            if (v.isPublished()) {
                                conflicts.add(other.getTitle() + ":" + v.getIdentifier());
                            }
                        }
                    }
                    if (!conflicts.isEmpty()) {
                        int result = JOptionPane.showConfirmDialog(
                                SequenceOverviewPanel.this,
                                "Other sequences already have published variables:\n"
                                        + String.join(", ", conflicts) + "\n\n"
                                        + "Having published variables across multiple sequences may cause "
                                        + "multiple sequences to fire on the same request. Continue?",
                                "Published Variable Conflict",
                                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (result != JOptionPane.YES_OPTION) return;
                    }
                }
            }

            r.variable.setPublished(publish);
            fireTableRowsUpdated(row, row);
            updateConflictWarning();
        }
    }

    private record PublishedVarRow(StepVariable variable, Step step, int stepIndex, String regex) {}

    private class PublishedVarsCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (row >= publishedModel.rows.size()) return c;
            PublishedVarRow r = publishedModel.rows.get(row);
            if (!isSelected) {
                c.setBackground(table.getBackground());
                if (r.variable.isPublished()) {
                    c.setForeground(Themes.successForeground(table));
                } else {
                    c.setForeground(table.getForeground());
                }
            }
            if (column == 4 && !isSelected) {
                String val = r.variable.getValue();
                if (val == null || val.isEmpty()) {
                    c.setForeground(Themes.disabledForeground(table));
                }
            }
            return c;
        }
    }


    private class OverviewTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"#", "Status", "Title", "Target", "Condition", "Action", "Variables", "Last Result"};

        @Override public int getRowCount() { return stepSequence.getSteps().size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            Step step = stepSequence.getSteps().get(row);
            return switch (col) {
                case 0 -> String.valueOf(row + 1);
                case 1 -> step.isEnabled() ? "Enabled" : "Disabled";
                case 2 -> step.getTitle();
                case 3 -> step.getTargetString();
                case 4 -> getConditionSummary(step, row);
                case 5 -> getActionSummary(step, row);
                case 6 -> getVariableSummary(step);
                case 7 -> getLastResultSummary(step);
                default -> "";
            };
        }

        private String getConditionSummary(Step step, int row) {
            StepCondition cond = step.getCondition();
            if (cond == null || !cond.isConfigured()) return "-";

            boolean isPreVal = step.getStepId().equals(stepSequence.getValidationStepId());
            boolean isPostVal = step.getStepId().equals(stepSequence.getPostValidationStepId());

            if (cond.getType() == StepCondition.ConditionType.ALWAYS) return "Always";
            String what = cond.getType() == StepCondition.ConditionType.STATUS_CODE ? "status" : "response";
            String pattern = cond.getPattern();
            if (pattern.length() > 25) pattern = pattern.substring(0, 25) + "\u2026";
            String mode = cond.getMatchMode() == StepCondition.MatchMode.MATCHES ? "matches" : "doesn't match";
            String base = "If " + what + " " + mode + " /" + pattern + "/";

            if (isPreVal) {
                return base + " → session valid, skip rest";
            } else if (isPostVal) {
                return base + " → session recovered";
            }
            return base;
        }

        private String getActionSummary(Step step, int row) {
            StepCondition cond = step.getCondition();
            if (step.getStepId().equals(stepSequence.getValidationStepId())) {
                return "Pre-validation step - action ignored";
            }
            if (step.getStepId().equals(stepSequence.getPostValidationStepId())) {
                return "Post-validation step - action ignored";
            }
            if (cond == null || !cond.isConfigured()) return "-";
            java.util.function.Function<String, String> resolver = stepSequence::resolveStepIdToDisplay;
            StringBuilder sb = new StringBuilder(cond.getAction().toString());
            if (cond.getAction() == com.xreous.stepperng.condition.ConditionFailAction.GOTO_STEP
                    && cond.getGotoTarget() != null && !cond.getGotoTarget().isEmpty()) {
                String display = resolver.apply(cond.getGotoTarget());
                sb.append(" → ").append(display != null ? display : cond.getGotoTarget());
            }
            if (cond.getRetryCount() > 0 && cond.getType() != StepCondition.ConditionType.ALWAYS) {
                sb.append(", retry ").append(cond.getRetryCount()).append("x");
                if (cond.getRetryDelayMs() > 0) sb.append(" (").append(cond.getRetryDelayMs()).append("ms)");
            }
            ConditionFailAction elseAct = cond.getElseAction();
            if (elseAct != null && elseAct != com.xreous.stepperng.condition.ConditionFailAction.CONTINUE
                    && cond.getType() != StepCondition.ConditionType.ALWAYS) {
                sb.append(", else ").append(elseAct);
                if (elseAct == com.xreous.stepperng.condition.ConditionFailAction.GOTO_STEP
                        && cond.getElseGotoTarget() != null && !cond.getElseGotoTarget().isEmpty()) {
                    String display = resolver.apply(cond.getElseGotoTarget());
                    sb.append(" → ").append(display != null ? display : cond.getElseGotoTarget());
                }
            }
            return sb.toString();
        }

        private String getVariableSummary(Step step) {
            List<StepVariable> vars = step.getVariableManager().getVariables();
            if (vars.isEmpty()) return "-";
            List<String> preVars = vars.stream()
                    .filter(v -> v instanceof PreExecutionStepVariable)
                    .map(v -> v.getIdentifier() + (v.isPublished() ? " \u2726" : ""))
                    .collect(Collectors.toList());
            List<String> postVars = vars.stream()
                    .filter(v -> v instanceof PostExecutionStepVariable)
                    .map(v -> v.getIdentifier() + (v.isPublished() ? " \u2726" : ""))
                    .collect(Collectors.toList());
            StringBuilder sb = new StringBuilder();
            if (!preVars.isEmpty()) sb.append("Pre: ").append(String.join(", ", preVars));
            if (!postVars.isEmpty()) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append("Post: ").append(String.join(", ", postVars));
            }
            return sb.toString();
        }

        private String getLastResultSummary(Step step) {
            String result = step.getLastConditionResult();
            if (result == null) return "-";
            long ts = step.getLastExecutionTime();
            if (ts > 0) {
                String time = TIME_FORMAT.format(new Date(ts));
                return "[" + time + "] " + result;
            }
            return result;
        }
    }

    private class OverviewCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (row >= stepSequence.getSteps().size()) return c;
            Step step = stepSequence.getSteps().get(row);
            if (!isSelected) {
                if (!step.isEnabled()) {
                    c.setForeground(Themes.disabledForeground(table));
                } else {
                    c.setForeground(table.getForeground());
                }
                if (step.getStepId().equals(stepSequence.getValidationStepId())
                        || step.getStepId().equals(stepSequence.getPostValidationStepId())) {
                    c.setBackground(Themes.rowHighlightBackground(table));
                } else {
                    c.setBackground(table.getBackground());
                }
            }
            if (column == 1) {
                if ("Disabled".equals(value)) {
                    c.setForeground(isSelected ? c.getForeground() : Themes.errorForeground(table));
                } else if (!isSelected) {
                    c.setForeground(Themes.successForeground(table));
                }
            }
            return c;
        }
    }
}
