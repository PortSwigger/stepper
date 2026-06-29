package com.xreous.stepperng;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.coreyd97.BurpExtenderUtilities.DefaultGsonProvider;
import com.coreyd97.BurpExtenderUtilities.IGsonProvider;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.xreous.stepperng.preferences.StepperPreferenceFactory;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.util.variablereplacementstab.VariableReplacementsTabFactory;
import com.xreous.stepperng.variable.DynamicGlobalVariable;
import com.xreous.stepperng.variable.DynamicGlobalVariableManager;
import com.xreous.stepperng.variable.StaticGlobalVariable;
import com.google.gson.reflect.TypeToken;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;

public class Stepper implements BurpExtension {

    public static Stepper instance;
    private static StepperUI ui;
    public static MontoyaApi montoya;
    public static IGsonProvider gsonProvider = new DefaultGsonProvider();
    private static Preferences preferences;
    private static SequenceManager sequenceManager;
    private static DynamicGlobalVariableManager dynamicGlobalVariableManager;
    private static volatile boolean degradedMode = false;

    private StateManager stateManager;
    private MessageProcessor messageProcessor;
    private AutoBackupManager autoBackupManager;

    public Stepper(){
        Stepper.instance = this;
    }

    public static Stepper getInstance() { return instance; }
    public static SequenceManager getSequenceManager(){ return sequenceManager; }
    public static DynamicGlobalVariableManager getDynamicGlobalVariableManager() { return dynamicGlobalVariableManager; }
    public static Preferences getPreferences() { return preferences; }
    public static StepperUI getUI() { return ui; }
    public static IGsonProvider getGsonProvider() { return gsonProvider; }
    public static boolean isDegradedMode() { return degradedMode; }
    public AutoBackupManager getAutoBackupManager() { return autoBackupManager; }

    public static Component suiteFrame() {
        try {
            if (montoya != null) return montoya.userInterface().swingUtils().suiteFrame();
        } catch (Exception ignored) {}
        return ui != null ? ui.getUiComponent() : null;
    }

    public static void cleanup() {
        if (ui != null) {
            try { ui.dispose(); } catch (Exception ignored) {}
        }
        ui = null;
        sequenceManager = null;
        dynamicGlobalVariableManager = null;
        preferences = null;
        instance = null;
        montoya = null;
        degradedMode = false;
    }

    @Override
    public void initialize(MontoyaApi api) {
        Stepper.montoya = api;
        api.extension().setName(Globals.EXTENSION_NAME);
        api.logging().logToOutput("Stepper-NG " + Globals.VERSION + " initializing...");

        boolean projectCorrupted = false;
        StepperPreferenceFactory factory = new StepperPreferenceFactory(Globals.EXTENSION_NAME, gsonProvider, api);
        try {
            Stepper.preferences = factory.buildPreferences();
            projectCorrupted = factory.isProjectSettingsCorrupted();
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to load preferences: " + getStackTrace(e));
            projectCorrupted = true;
        }

        Stepper.sequenceManager = new SequenceManager();
        Stepper.dynamicGlobalVariableManager = new DynamicGlobalVariableManager();

        if (projectCorrupted && preferences == null) {
            degradedMode = true;
            api.logging().logToError("Stepper-NG: Preferences completely unavailable. Starting in degraded mode.");
        } else if (projectCorrupted) {
            degradedMode = true;
            api.logging().logToError("Stepper-NG: Project file corrupted. Project-scoped settings are in-memory only.");
        }

        if (projectCorrupted) {
            if (isPersistenceReadable(api)) {
                attemptDataRecovery(api);
            } else {
                api.logging().logToError(
                        "Stepper-NG: Persistence layer is unavailable. "
                                + "Data recovery is not possible — use Preferences > Import to restore from a backup.");
            }
        }

        this.stateManager = new StateManager(sequenceManager, preferences, dynamicGlobalVariableManager);
        if (!projectCorrupted) {
            try {
                this.stateManager.loadSavedSequences();
                this.stateManager.loadDynamicGlobalVars();
            } catch (Exception e) {
                api.logging().logToError("Stepper-NG: Failed to load saved data: " + getStackTrace(e));
            }
        }
        com.xreous.stepperng.util.DuplicateNameWarning.warnImportSummary(null);
        this.messageProcessor = new MessageProcessor(sequenceManager, preferences, dynamicGlobalVariableManager);

        this.autoBackupManager = new AutoBackupManager(sequenceManager, dynamicGlobalVariableManager, preferences);
        try {
            this.autoBackupManager.start();
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to start auto-backup: " + getStackTrace(e));
        }

        try {
            api.userInterface().registerHttpRequestEditorProvider(new VariableReplacementsTabFactory(sequenceManager));
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to register editor provider: " + getStackTrace(e));
        }
        try {
            api.userInterface().registerContextMenuItemsProvider(new ContextMenuFactory(sequenceManager));
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to register context menu: " + getStackTrace(e));
        }
        try {
            api.http().registerHttpHandler(messageProcessor);
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to register HTTP handler: " + getStackTrace(e));
        }
        try {
            api.http().registerSessionHandlingAction(
                    new StepperSessionHandlingAction(sequenceManager, preferences, dynamicGlobalVariableManager));
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to register session handling action: " + getStackTrace(e));
        }
        api.extension().registerUnloadingHandler(stateManager);

        try {
            ui = new StepperUI(sequenceManager, dynamicGlobalVariableManager);
            api.userInterface().registerSuiteTab(Globals.EXTENSION_NAME, ui.getUiComponent());
            api.userInterface().registerSettingsPanel(ui.getSettingsPanel());
            api.logging().logToOutput("Stepper-NG " + Globals.VERSION + " loaded successfully."
                    + (degradedMode ? " (DEGRADED MODE — project file corrupted)" : ""));
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Failed to register UI tab: " + getStackTrace(e));
        }

        if (degradedMode) {
            boolean hasRecoveredData = !sequenceManager.getSequences().isEmpty()
                    || !dynamicGlobalVariableManager.getVariables().isEmpty()
                    || !dynamicGlobalVariableManager.getStaticVariables().isEmpty();
            showCorruptionAlert(hasRecoveredData);
        }
    }

    private static boolean isPersistenceReadable(MontoyaApi api) {
        try {
            api.persistence().extensionData().getString(Globals.PREF_STEP_SEQUENCES);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Attempts to directly read raw JSON from Burp's persistence API
     */
    private void attemptDataRecovery(MontoyaApi api) {
        int recoveredSeqs = 0, recoveredDvars = 0, recoveredSvars = 0;
        com.google.gson.Gson gson = gsonProvider.getGson();

        try {
            String seqJson = api.persistence().extensionData().getString(Globals.PREF_STEP_SEQUENCES);
            if (seqJson != null && !seqJson.isBlank()) {
                ArrayList<StepSequence> seqs = gson.fromJson(seqJson, new TypeToken<ArrayList<StepSequence>>(){}.getType());
                if (seqs != null) {
                    for (StepSequence seq : seqs) {
                        sequenceManager.addStepSequence(seq);
                        recoveredSeqs++;
                    }
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Could not recover sequences: " + e.getMessage());
        }

        try {
            String dvarJson = api.persistence().extensionData().getString(Globals.PREF_DYNAMIC_GLOBAL_VARS);
            if (dvarJson != null && !dvarJson.isBlank()) {
                ArrayList<DynamicGlobalVariable> dvars = gson.fromJson(dvarJson, new TypeToken<ArrayList<DynamicGlobalVariable>>(){}.getType());
                if (dvars != null) {
                    for (DynamicGlobalVariable v : dvars) {
                        dynamicGlobalVariableManager.addVariable(v);
                        recoveredDvars++;
                    }
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Could not recover dynamic global variables: " + e.getMessage());
        }

        try {
            String svarJson = api.persistence().extensionData().getString(Globals.PREF_STATIC_GLOBAL_VARS);
            if (svarJson != null && !svarJson.isBlank()) {
                ArrayList<StaticGlobalVariable> svars = gson.fromJson(svarJson, new TypeToken<ArrayList<StaticGlobalVariable>>(){}.getType());
                if (svars != null) {
                    for (StaticGlobalVariable v : svars) {
                        dynamicGlobalVariableManager.addStaticVariable(v);
                        recoveredSvars++;
                    }
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Stepper-NG: Could not recover static global variables: " + e.getMessage());
        }

        if (recoveredSeqs > 0 || recoveredDvars > 0 || recoveredSvars > 0) {
            api.logging().logToOutput("Stepper-NG: Recovered " + recoveredSeqs + " sequence(s), "
                    + recoveredDvars + " dynamic var(s), " + recoveredSvars + " static var(s) from corrupted project.");
        } else {
            api.logging().logToError("Stepper-NG: No data could be recovered from the corrupted project file.");
        }
    }

    private void showCorruptionAlert(boolean hasRecoveredData) {
        SwingUtilities.invokeLater(() -> {
            String message;
            if (hasRecoveredData) {
                message = "Stepper detected a corrupted Burp project file.\n\n"
                        + "Some data was recovered and loaded into memory. Changes will NOT be saved\n"
                        + "to the project file. Use Preferences > Export to save your recovered data\n"
                        + "to a file before closing Burp.\n\n"
                        + "Recovered:\n"
                        + "  - " + sequenceManager.getSequences().size() + " sequence(s)\n"
                        + "  - " + dynamicGlobalVariableManager.getVariables().size() + " dynamic variable(s)\n"
                        + "  - " + dynamicGlobalVariableManager.getStaticVariables().size() + " static variable(s)";
            } else {
                message = "Stepper detected a corrupted Burp project file.\n\n"
                        + "No data could be recovered. The extension is running in degraded mode\n"
                        + "with default settings. Changes will NOT be saved to the project file.\n\n"
                        + "If you have a backup export file, use Preferences > Import to restore your data.";
            }

            String[] options = hasRecoveredData ? new String[]{"OK", "Export Now"} : new String[]{"OK"};
            int result = JOptionPane.showOptionDialog(
                    suiteFrame(),
                    message,
                    "Stepper — Project File Corrupted",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[0]);

            if (hasRecoveredData && result == 1) {
                exportRecoveredData();
            }
        });
    }

    private void exportRecoveredData() {
        com.google.gson.Gson gson = gsonProvider.getGson();
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();

        if (!sequenceManager.getSequences().isEmpty()) {
            root.add("sequences", gson.toJsonTree(
                    new ArrayList<>(sequenceManager.getSequences()),
                    new TypeToken<ArrayList<StepSequence>>(){}.getType()));
        }
        if (!dynamicGlobalVariableManager.getVariables().isEmpty()) {
            root.add("dynamicVariables", gson.toJsonTree(
                    new ArrayList<>(dynamicGlobalVariableManager.getVariables()),
                    new TypeToken<ArrayList<DynamicGlobalVariable>>(){}.getType()));
        }
        if (!dynamicGlobalVariableManager.getStaticVariables().isEmpty()) {
            root.add("staticVariables", gson.toJsonTree(
                    new ArrayList<>(dynamicGlobalVariableManager.getStaticVariables()),
                    new TypeToken<ArrayList<StaticGlobalVariable>>(){}.getType()));
        }

        String json = gson.toJson(root);

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("stepper-ng-recovery.json"));
        if (fc.showSaveDialog(suiteFrame()) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.write(fc.getSelectedFile().toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                JOptionPane.showMessageDialog(suiteFrame(),
                        "Data exported successfully to:\n" + fc.getSelectedFile().getAbsolutePath(),
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(suiteFrame(),
                        "Failed to write file: " + ex.getMessage(),
                        "Export Failed", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
