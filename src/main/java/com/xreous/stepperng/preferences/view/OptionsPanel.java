package com.xreous.stepperng.preferences.view;

import com.coreyd97.BurpExtenderUtilities.Alignment;
import com.coreyd97.BurpExtenderUtilities.ComponentGroup;
import com.coreyd97.BurpExtenderUtilities.PanelBuilder;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.xreous.stepperng.Globals;
import com.xreous.stepperng.Stepper;
import com.xreous.stepperng.AutoBackupManager;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.variable.DynamicGlobalVariable;
import com.xreous.stepperng.variable.DynamicGlobalVariableManager;
import com.xreous.stepperng.variable.StaticGlobalVariable;
import com.xreous.stepperng.util.view.Themes;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import burp.api.montoya.ui.settings.SettingsPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class OptionsPanel extends JPanel implements SettingsPanel {

    private final SequenceManager sequenceManager;
    private final Preferences preferences;

    public OptionsPanel(SequenceManager sequenceManager){
        this.sequenceManager = sequenceManager;
        this.preferences = Stepper.getPreferences();

        if (this.preferences != null) {
            buildPanel();
        } else {
            buildDegradedPanel();
        }
    }

    private void buildDegradedPanel() {
        ComponentGroup warningGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Status");
        JTextArea warningText = new JTextArea(
                "Stepper is running in degraded mode because the Burp project file is corrupted.\n\n"
                + "Preferences cannot be loaded or saved. Use the Import/Export buttons below to\n"
                + "recover or back up your sequences and variables.");
        warningText.setEditable(false);
        warningText.setLineWrap(true);
        warningText.setWrapStyleWord(true);
        warningText.setOpaque(false);
        warningText.setForeground(Themes.errorForeground(warningText));
        warningText.setFont(warningText.getFont().deriveFont(Font.BOLD));
        warningText.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        warningGroup.add(warningText);

        ComponentGroup importExportGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Import / Export");
        addImportExportButtons(importExportGroup);

        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        for (JComponent g : new JComponent[]{warningGroup, importExportGroup}) {
            g.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(g);
            stack.add(Box.createVerticalStrut(10));
        }
        stack.add(Box.createVerticalGlue());

        this.setLayout(new BorderLayout());
        JScrollPane scroll = new JScrollPane(capWidth(stack),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        this.add(scroll, BorderLayout.CENTER);
    }

    private void buildPanel() {
        ComponentGroup configGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Config");
        configGroup.addPreferenceComponent(preferences, Globals.PREF_UPDATE_REQUEST_LENGTH, "Automatically update the Content-Length header");

        ComponentGroup toolEnabledGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Allow Variables Usage");
        JCheckBox allToolsCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_ALL_TOOLS, "All Tools");
        JCheckBox proxyCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_PROXY, "Proxy");
        JCheckBox repeaterCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_REPEATER, "Repeater");
        JCheckBox intruderCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_INTRUDER, "Intruder");
        JCheckBox scannerCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_SCANNER, "Scanner");
        JCheckBox sequencerCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_SEQUENCER, "Sequencer");
        JCheckBox extenderCheckbox = toolEnabledGroup.addPreferenceComponent(preferences, Globals.PREF_VARS_IN_EXTENDER, "Extensions");

        Runnable syncIndividualToolEnabled = () -> {
            boolean individualEnabled = !allToolsCheckbox.isSelected();
            proxyCheckbox.setEnabled(individualEnabled);
            repeaterCheckbox.setEnabled(individualEnabled);
            intruderCheckbox.setEnabled(individualEnabled);
            scannerCheckbox.setEnabled(individualEnabled);
            sequencerCheckbox.setEnabled(individualEnabled);
            extenderCheckbox.setEnabled(individualEnabled);
        };
        syncIndividualToolEnabled.run();
        allToolsCheckbox.addChangeListener(changeEvent -> syncIndividualToolEnabled.run());


        ComponentGroup sessionGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Session Handling");

        JPanel sessionRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 2));
        sessionRow.add(new JLabel("Validate every N requests:"));
        int currentN = 1;
        try { Object v = preferences.getSetting(Globals.PREF_SESSION_VALIDATE_EVERY_N); if (v instanceof Integer i) currentN = i; } catch (Exception ignored) {}
        JSpinner nSpinner = new JSpinner(new SpinnerNumberModel(currentN, 1, 1000, 1));
        nSpinner.addChangeListener(ce -> preferences.setSetting(Globals.PREF_SESSION_VALIDATE_EVERY_N, (int) nSpinner.getValue()));
        sessionRow.add(nSpinner);
        sessionRow.add(new JLabel("(1 = every request)"));
        sessionGroup.add(sessionRow);

        JCheckBox pauseOnFailCheckbox = sessionGroup.addPreferenceComponent(preferences,
                Globals.PREF_PAUSE_ON_POST_VALIDATION_FAIL,
                "Pause task execution engine on post-validation failure");
        pauseOnFailCheckbox.setToolTipText(
                "When enabled, the task engine is automatically paused after consecutive post-validation failures. " +
                "When disabled, an alert is shown but tasks keep running.");

        JCheckBox holdRequestsCheckbox = sessionGroup.addPreferenceComponent(preferences,
                Globals.PREF_HOLD_REQUESTS_DURING_EXECUTION,
                "Hold requests while sequence is executing");
        holdRequestsCheckbox.setToolTipText(
                "When a sequence is already running (e.g. refreshing a token), hold other worker threads "
                + "until it finishes so they receive the updated variable values instead of stale ones.");

        sessionGroup.add(helpLabel(
                "Requests containing $VAR: references auto-execute the owning sequence. "
                + "Set N > 1 to skip redundant re-executions during scans/intruder runs. "
                + "Enable 'Hold requests' to prevent concurrent workers from using stale tokens "
                + "while a sequence is mid-execution."));

        ComponentGroup importExportGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Import / Export");
        addImportExportButtons(importExportGroup);

        ComponentGroup backupGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Auto-Backup");
        addAutoBackupControls(backupGroup);

        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        for (JComponent g : new JComponent[]{configGroup, toolEnabledGroup, sessionGroup, importExportGroup, backupGroup}) {
            g.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(g);
            stack.add(Box.createVerticalStrut(10));
        }
        stack.add(Box.createVerticalGlue());

        this.setLayout(new BorderLayout());
        JScrollPane scroll = new JScrollPane(capWidth(stack),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        this.add(scroll, BorderLayout.CENTER);
    }

    /** Keep preferences readable on wide screens: left-align the stack capped at 720px. */
    private static JComponent capWidth(JComponent inner) {
        inner.setMaximumSize(new Dimension(720, Integer.MAX_VALUE));
        inner.setPreferredSize(new Dimension(720, inner.getPreferredSize().height));
        JPanel holder = new JPanel(new BorderLayout());
        JPanel west = new JPanel(new BorderLayout());
        west.add(inner, BorderLayout.NORTH);
        holder.add(west, BorderLayout.WEST);
        return holder;
    }

    private void addImportExportButtons(ComponentGroup group) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        JButton importButton = sizedButton("Import\u2026", 140);
        JPopupMenu importMenu = new JPopupMenu();
        importMenu.add(new JMenuItem(new AbstractAction("From File") {
            @Override public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                fc.setMultiSelectionEnabled(false);
                if (fc.showOpenDialog(OptionsPanel.this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        importUnified(new String(Files.readAllBytes(fc.getSelectedFile().toPath())));
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(OptionsPanel.this, "Unable to open file: " + ex.getMessage(),
                                "Import Failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }));
        importMenu.add(new JMenuItem(new AbstractAction("From String") {
            @Override public void actionPerformed(ActionEvent e) {
                JTextArea inputArea = new JTextArea();
                inputArea.setWrapStyleWord(true);
                inputArea.setLineWrap(true);
                JScrollPane sp = new JScrollPane(inputArea);
                sp.setPreferredSize(new Dimension(500, 600));
                sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
                if (JOptionPane.showConfirmDialog(OptionsPanel.this, sp,
                        "Import", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    importUnified(inputArea.getText());
                }
            }
        }));
        importButton.addActionListener(e -> importMenu.show(importButton, 0, importButton.getHeight()));
        row.add(importButton);

        JButton exportButton = sizedButton("Export\u2026", 140);
        JPopupMenu exportMenu = new JPopupMenu();
        exportMenu.add(new JMenuItem(new AbstractAction("To File") {
            @Override public void actionPerformed(ActionEvent e) {
                String json = showExportDialog();
                if (json == null || json.isEmpty()) return;
                JFileChooser fc = new JFileChooser();
                fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                if (fc.showSaveDialog(OptionsPanel.this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        Files.write(fc.getSelectedFile().toPath(), json.getBytes());
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(OptionsPanel.this, "Unable to write file: " + ex.getMessage(),
                                "Export Failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }));
        exportMenu.add(new JMenuItem(new AbstractAction("As String") {
            @Override public void actionPerformed(ActionEvent e) {
                String json = showExportDialog();
                if (json == null || json.isEmpty()) return;
                JTextArea area = new JTextArea(json);
                area.setWrapStyleWord(true);
                area.setLineWrap(true);
                area.setEditable(false);
                JScrollPane sp = new JScrollPane(area);
                sp.setPreferredSize(new Dimension(500, 600));
                sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
                JOptionPane.showMessageDialog(OptionsPanel.this, sp, "Exported Data", JOptionPane.PLAIN_MESSAGE);
            }
        }));
        exportButton.addActionListener(e -> exportMenu.show(exportButton, 0, exportButton.getHeight()));
        row.add(exportButton);
        group.add(row);
    }

    private void addAutoBackupControls(ComponentGroup group) {
        JCheckBox enabledCheckbox = group.addPreferenceComponent(preferences, Globals.PREF_AUTO_BACKUP_ENABLED,
                "Enable periodic auto-backup");

        JPanel intervalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        intervalRow.add(new JLabel("Backup every:"));
        int currentInterval = Globals.DEFAULT_AUTO_BACKUP_INTERVAL_MINUTES;
        try {
            Object v = preferences.getSetting(Globals.PREF_AUTO_BACKUP_INTERVAL_MINUTES);
            if (v instanceof Integer i && i > 0) currentInterval = i;
        } catch (Exception ignored) {}
        JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(currentInterval, 1, 1440, 5));
        intervalSpinner.addChangeListener(ce -> {
            preferences.setSetting(Globals.PREF_AUTO_BACKUP_INTERVAL_MINUTES, (int) intervalSpinner.getValue());
            restartBackupIfEnabled();
        });
        intervalRow.add(intervalSpinner);
        intervalRow.add(new JLabel("minute(s)"));
        group.add(intervalRow);

        JPanel maxFilesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        maxFilesRow.add(new JLabel("Keep last:"));
        int currentMax = Globals.DEFAULT_AUTO_BACKUP_MAX_FILES;
        try {
            Object v = preferences.getSetting(Globals.PREF_AUTO_BACKUP_MAX_FILES);
            if (v instanceof Integer i && i > 0) currentMax = i;
        } catch (Exception ignored) {}
        JSpinner maxSpinner = new JSpinner(new SpinnerNumberModel(currentMax, 1, 100, 1));
        maxSpinner.addChangeListener(ce -> {
            preferences.setSetting(Globals.PREF_AUTO_BACKUP_MAX_FILES, (int) maxSpinner.getValue());
        });
        maxFilesRow.add(maxSpinner);
        maxFilesRow.add(new JLabel("backup file(s)"));
        group.add(maxFilesRow);

        JPanel dirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        dirRow.add(new JLabel("Directory:"));
        String currentDir = "";
        try {
            Object v = preferences.getSetting(Globals.PREF_AUTO_BACKUP_DIR);
            if (v instanceof String s) currentDir = s;
        } catch (Exception ignored) {}
        JTextField dirField = new JTextField(currentDir, 22);
        dirField.setEditable(false);
        dirField.setToolTipText(currentDir.isEmpty() ? "No directory selected" : currentDir);
        dirRow.add(dirField);

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            String cur = dirField.getText();
            if (cur != null && !cur.isEmpty()) {
                fc.setCurrentDirectory(new java.io.File(cur));
            }
            if (fc.showOpenDialog(OptionsPanel.this) == JFileChooser.APPROVE_OPTION) {
                String path = fc.getSelectedFile().getAbsolutePath();
                dirField.setText(path);
                dirField.setToolTipText(path);
                preferences.setSetting(Globals.PREF_AUTO_BACKUP_DIR, path);
                restartBackupIfEnabled();
            }
        });
        dirRow.add(browseButton);
        group.add(dirRow);

        JButton backupNowButton = new JButton("Run now");
        backupNowButton.setToolTipText("Perform a backup immediately");
        backupNowButton.addActionListener(e -> {
            String dir = dirField.getText();
            if (dir == null || dir.isBlank()) {
                JOptionPane.showMessageDialog(OptionsPanel.this,
                        "Please select a backup directory first.",
                        "No Directory", JOptionPane.WARNING_MESSAGE);
                return;
            }
            AutoBackupManager mgr = Stepper.getInstance() != null
                    ? Stepper.getInstance().getAutoBackupManager() : null;
            if (mgr != null) {
                String result = mgr.performBackup();
                if (result != null) {
                    JOptionPane.showMessageDialog(OptionsPanel.this,
                            "Backup saved to:\n" + result,
                            "Backup Complete", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(OptionsPanel.this,
                            "Backup failed. Check the extension error log for details.",
                            "Backup Failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        JPanel backupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        backupRow.add(backupNowButton);
        group.add(backupRow);        Runnable updateEnabled = () -> {
            boolean on = enabledCheckbox.isSelected();
            intervalSpinner.setEnabled(on);
            maxSpinner.setEnabled(on);
            browseButton.setEnabled(on);
        };
        updateEnabled.run();
        enabledCheckbox.addChangeListener(ce -> {
            updateEnabled.run();
            restartBackupIfEnabled();
        });

        group.add(helpLabel(
                "Periodically saves all sequences and global variables to a JSON file. "
                + "Acts as a safety net against Burp project file corruption. "
                + "Backups use the same format as manual Export and can be restored via Import."));
    }

    private static JButton sizedButton(String label, int minWidth) {
        JButton b = new JButton(label);
        Dimension d = b.getPreferredSize();
        b.setPreferredSize(new Dimension(Math.max(minWidth, d.width), d.height));
        return b;
    }

    private JComponent helpLabel(String text) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setFocusable(false);
        a.setFont(a.getFont().deriveFont(Font.ITALIC, a.getFont().getSize2D() - 1f));
        a.setForeground(Themes.disabledForeground(a));
        a.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        int width = 680;
        a.setSize(width, Short.MAX_VALUE);
        int h = a.getPreferredSize().height;
        Dimension d = new Dimension(width, h);
        a.setPreferredSize(d);
        a.setMaximumSize(d);
        a.setMinimumSize(new Dimension(120, h));
        return a;
    }

    private void restartBackupIfEnabled() {
        AutoBackupManager mgr = Stepper.getInstance() != null
                ? Stepper.getInstance().getAutoBackupManager() : null;
        if (mgr != null) {
            mgr.start();
        }
    }

    private String showExportDialog() {
        SequenceSelectionDialog dialog = new SequenceSelectionDialog(
                (Frame) SwingUtilities.getWindowAncestor(this), "Export",
                sequenceManager.getSequences(), true);
        List<StepSequence> selected = dialog.run();
        if (dialog.isCancelled()) return null;

        boolean includeGlobals = dialog.isGlobalsSelected();
        if ((selected == null || selected.isEmpty()) && !includeGlobals) return null;

        Gson gson = Stepper.getGsonProvider().getGson();
        JsonObject root = new JsonObject();

        if (selected != null && !selected.isEmpty()) {
            root.add("sequences", gson.toJsonTree(selected, new TypeToken<ArrayList<StepSequence>>(){}.getType()));
        }

        if (includeGlobals) {
            DynamicGlobalVariableManager mgr = Stepper.getDynamicGlobalVariableManager();
            if (mgr != null) {
                List<DynamicGlobalVariable> dvars = new ArrayList<>(mgr.getVariables());
                List<StaticGlobalVariable> svars = new ArrayList<>(mgr.getStaticVariables());
                if (!dvars.isEmpty()) {
                    root.add("dynamicVariables", gson.toJsonTree(dvars, new TypeToken<ArrayList<DynamicGlobalVariable>>(){}.getType()));
                }
                if (!svars.isEmpty()) {
                    root.add("staticVariables", gson.toJsonTree(svars, new TypeToken<ArrayList<StaticGlobalVariable>>(){}.getType()));
                }
            }
        }

        return gson.toJson(root);
    }

    private void importUnified(String json) {
        if (json == null || json.isBlank()) {
            JOptionPane.showMessageDialog(com.xreous.stepperng.Stepper.suiteFrame(), "No data to import.", "Import Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Gson gson = Stepper.getGsonProvider().getGson();
        int seqCount = 0;
        int globalCount = 0;

        try {
            json = json.trim();

            // Detect format: bare array = old sequence-only format, object = unified or globals-only
            if (json.startsWith("[")) {
                // Legacy: bare array of sequences
                ArrayList<StepSequence> sequences = gson.fromJson(json, new TypeToken<ArrayList<StepSequence>>(){}.getType());
                if (sequences != null) {
                    for (StepSequence seq : sequences) {
                        SequenceManager.reseedIds(seq);
                        sequenceManager.addStepSequence(seq);
                        seqCount++;
                    }
                }
            } else {
                JsonObject root = gson.fromJson(json, JsonObject.class);
                if (root == null) {
                    JOptionPane.showMessageDialog(com.xreous.stepperng.Stepper.suiteFrame(), "Invalid JSON.", "Import Failed", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // Import sequences
                if (root.has("sequences") && root.get("sequences").isJsonArray()) {
                    ArrayList<StepSequence> sequences = gson.fromJson(
                            root.getAsJsonArray("sequences"), new TypeToken<ArrayList<StepSequence>>(){}.getType());
                    if (sequences != null) {
                        for (StepSequence seq : sequences) {
                            sequenceManager.reseedIds(seq);
                            sequenceManager.addStepSequence(seq);
                            seqCount++;
                        }
                    }
                }

                // Import global variables
                DynamicGlobalVariableManager mgr = Stepper.getDynamicGlobalVariableManager();
                if (mgr != null) {
                    if (root.has("dynamicVariables") && root.get("dynamicVariables").isJsonArray()) {
                        List<DynamicGlobalVariable> dvars = gson.fromJson(
                                root.getAsJsonArray("dynamicVariables"),
                                new TypeToken<ArrayList<DynamicGlobalVariable>>(){}.getType());
                        if (dvars != null) {
                            for (DynamicGlobalVariable v : dvars) { mgr.addVariable(v); globalCount++; }
                        }
                    }
                    if (root.has("staticVariables") && root.get("staticVariables").isJsonArray()) {
                        List<StaticGlobalVariable> svars = gson.fromJson(
                                root.getAsJsonArray("staticVariables"),
                                new TypeToken<ArrayList<StaticGlobalVariable>>(){}.getType());
                        if (svars != null) {
                            for (StaticGlobalVariable v : svars) { mgr.addStaticVariable(v); globalCount++; }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Stepper.montoya.logging().logToError("Stepper-NG: Failed to parse import JSON: " + e.getMessage());
            JOptionPane.showMessageDialog(com.xreous.stepperng.Stepper.suiteFrame(), "Failed to parse JSON: " + e.getMessage(),
                    "Import Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (seqCount == 0 && globalCount == 0) {
            JOptionPane.showMessageDialog(com.xreous.stepperng.Stepper.suiteFrame(), "No sequences or global variables found in the data.",
                    "Import Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        StringBuilder msg = new StringBuilder("Imported:");
        if (seqCount > 0) msg.append("\n  ").append(seqCount).append(" sequence(s)");
        if (globalCount > 0) msg.append("\n  ").append(globalCount).append(" global variable(s)");
        JOptionPane.showMessageDialog(com.xreous.stepperng.Stepper.suiteFrame(), msg.toString(), "Import Complete", JOptionPane.INFORMATION_MESSAGE);
        com.xreous.stepperng.util.DuplicateNameWarning.warnImportSummary(com.xreous.stepperng.Stepper.suiteFrame());
    }

    @Override
    public JComponent uiComponent() {
        return this;
    }

    @Override
    public Set<String> keywords() {
        return Set.of("stepper", "variables", "session handling", "auto-backup", "content-length");
    }
}
