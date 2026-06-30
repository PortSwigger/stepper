package com.xreous.stepperng.util;

import com.xreous.stepperng.Stepper;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.variable.DynamicGlobalVariable;
import com.xreous.stepperng.variable.DynamicGlobalVariableManager;
import com.xreous.stepperng.variable.StaticGlobalVariable;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Centralised duplicate-name detection. User actions: non-blocking warning. Imports: single summary.
 * Names are compared case-insensitively, matching {@code SequenceManager.findSequence} semantics.
 */
public final class DuplicateNameWarning {

    private DuplicateNameWarning() {}

    public static void checkSequenceTitle(Component parent, StepSequence sequence) {
        if (sequence == null) return;
        String title = sequence.getTitle();
        if (title == null || title.isBlank()) return;
        int matches = 0;
        for (StepSequence other : Stepper.getSequenceManager().getSequences()) {
            if (other == sequence) continue;
            if (title.equalsIgnoreCase(other.getTitle())) matches++;
        }
        if (matches > 0) warn(parent, "sequence", title,
                "Cross-sequence references like $VAR:" + title + ":name$ become ambiguous.");
    }


    public static void checkStaticGlobalName(Component parent, StaticGlobalVariable variable) {
        DynamicGlobalVariableManager mgr = Stepper.getDynamicGlobalVariableManager();
        if (mgr == null || variable == null) return;
        String name = variable.getIdentifier();
        if (name == null || name.isBlank()) return;
        int matches = 0;
        for (StaticGlobalVariable v : mgr.getStaticVariables()) {
            if (v == variable) continue;
            if (name.equalsIgnoreCase(v.getIdentifier())) matches++;
        }
        if (matches > 0) warn(parent, "static global variable ($GVAR)", name, null);
    }

    public static void checkDynamicGlobalName(Component parent, DynamicGlobalVariable variable) {
        DynamicGlobalVariableManager mgr = Stepper.getDynamicGlobalVariableManager();
        if (mgr == null || variable == null) return;
        String name = variable.getIdentifier();
        if (name == null || name.isBlank()) return;
        int matches = 0;
        for (DynamicGlobalVariable v : mgr.getVariables()) {
            if (v == variable) continue;
            if (name.equalsIgnoreCase(v.getIdentifier())) matches++;
        }
        if (matches > 0) warn(parent, "dynamic global variable ($DVAR)", name, null);
    }

    /**
     * Scans the current state for any duplicate names across all three scopes.
     * Returns a human-readable summary, or null if nothing collides.
     */
    public static String summarise() {
        List<String> lines = new ArrayList<>();

        if (Stepper.getSequenceManager() != null) {
            Map<String, Integer> seqCounts = new HashMap<>();
            for (StepSequence s : Stepper.getSequenceManager().getSequences()) {
                if (s.getTitle() == null) continue;
                seqCounts.merge(s.getTitle().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            Set<String> reported = new HashSet<>();
            for (StepSequence s : Stepper.getSequenceManager().getSequences()) {
                if (s.getTitle() == null) continue;
                String key = s.getTitle().toLowerCase(Locale.ROOT);
                if (seqCounts.getOrDefault(key, 0) > 1 && reported.add(key)) {
                    lines.add("  • Sequence \"" + s.getTitle() + "\" (×" + seqCounts.get(key) + ")");
                }
            }
        }

        DynamicGlobalVariableManager mgr = Stepper.getDynamicGlobalVariableManager();
        if (mgr != null) {
            collectGlobalDupes(mgr.getStaticVariables().stream().map(StaticGlobalVariable::getIdentifier).toList(),
                    "$GVAR", lines);
            collectGlobalDupes(mgr.getVariables().stream().map(DynamicGlobalVariable::getIdentifier).toList(),
                    "$DVAR", lines);
        }

        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Stepper detected duplicate names. Please rename to avoid ambiguous variable resolution:\n\n");
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    /** Runs {@link #summarise()} and shows a warning dialog on the EDT if anything collides. */
    public static void warnImportSummary(Component parent) {
        String summary = summarise();
        if (summary == null) return;
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(resolveParent(parent), summary,
                        "Stepper — Duplicate Names", JOptionPane.WARNING_MESSAGE));
    }

    private static void collectGlobalDupes(List<String> names, String label, List<String> out) {
        Map<String, Integer> counts = new HashMap<>();
        for (String n : names) {
            if (n == null) continue;
            counts.merge(n.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                out.add("  • " + label + ":" + e.getKey() + " (×" + e.getValue() + ")");
            }
        }
    }

    private static void warn(Component parent, String scope, String name, String detail) {
        StringBuilder sb = new StringBuilder("Another ").append(scope)
                .append(" named \"").append(name).append("\" already exists.");
        if (detail != null) sb.append("\n\n").append(detail);
        sb.append("\n\nPlease rename one of them.");
        final String msg = sb.toString();
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(resolveParent(parent), msg,
                        "Stepper — Duplicate Name", JOptionPane.WARNING_MESSAGE));
    }

    private static Component resolveParent(Component parent) {
        if (parent != null) return parent;
        try { return Stepper.suiteFrame(); } catch (Exception ignored) { return null; }
    }
}

