package com.xreous.stepperng.sequence;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.burpsuite.TaskExecutionEngine;
import com.xreous.stepperng.Globals;
import com.xreous.stepperng.MessageProcessor;
import com.xreous.stepperng.Stepper;
import com.xreous.stepperng.StepperUI;
import com.xreous.stepperng.condition.ConditionFailAction;
import com.xreous.stepperng.condition.StepCondition;
import com.xreous.stepperng.exception.SequenceCancelledException;
import com.xreous.stepperng.exception.SequenceExecutionException;
import com.xreous.stepperng.sequence.listener.SequenceExecutionListener;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.step.Step;
import com.xreous.stepperng.step.StepExecutionInfo;
import com.xreous.stepperng.variable.PreExecutionStepVariable;
import com.xreous.stepperng.variable.StepVariable;
import com.xreous.stepperng.variable.VariableManager;
import com.xreous.stepperng.step.listener.StepListener;

import javax.swing.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class StepSequence
{
    private String sequenceId;
    private String title;
    private volatile boolean isExecuting;
    private volatile boolean disabled;
    private ArrayList<Step> steps;
    private String validationStepId;
    private String postValidationStepId;
    private int maxConsecutiveFailures = Globals.DEFAULT_MAX_CONSECUTIVE_FAILURES;
    private final CopyOnWriteArrayList<StepListener> stepListeners;
    private final CopyOnWriteArrayList<SequenceExecutionListener> sequenceExecutionListeners;

    private transient int consecutiveFailures = 0;
    private transient final AtomicBoolean showingBrokenDialog = new AtomicBoolean(false);
    private transient volatile long lastBrokenDialogDismissedAt = 0;
    private static final long BROKEN_DIALOG_COOLDOWN_MS = 30_000;

    /** Lock + condition used to hold concurrent requests while a sequence is executing. */
    private transient final ReentrantLock executionLock = new ReentrantLock();
    private transient final Condition executionDone = executionLock.newCondition();
    private static final long HOLD_TIMEOUT_MS = 30_000;

    public StepSequence(String title){
        this.sequenceId = UUID.randomUUID().toString();
        this.steps = new ArrayList<>();
        this.stepListeners = new CopyOnWriteArrayList<>();
        this.sequenceExecutionListeners = new CopyOnWriteArrayList<>();
        this.title = title;
    }

    public StepSequence(){
        this("Step Sequence");
    }

    public void executeBlocking(){
        executeBlocking(new HashMap<>(), false);
    }

    public void executeThroughStep(Step target) {
        if (target == null || this.isExecuting || this.disabled) return;
        int targetIdx = this.steps.indexOf(target);
        if (targetIdx < 0) return;

        if (MessageProcessor.isSequenceOnStack(this.sequenceId)) return;
        if (MessageProcessor.getStackDepth() >= Globals.MAX_SEQUENCE_DEPTH) return;

        MessageProcessor.pushSequence(this.sequenceId);
        try {
            synchronized (this) {
                if (this.isExecuting || this.disabled) return;
                this.isExecuting = true;
                StepperUI.ExecutionHost host = null;
                try {
                    host = Stepper.getUI() != null ? Stepper.getUI().getExecutionHost(this) : null;
                    if (host != null) host.beginExecution();
                    for (SequenceExecutionListener l : this.sequenceExecutionListeners) {
                        try { l.beforeSequenceStart(this.steps.subList(0, targetIdx + 1)); }
                        catch (Exception ex) { Stepper.montoya.logging().logToError("Stepper-NG: listener failed: " + ex.getMessage()); }
                    }
                    runConditionalLoop(targetIdx, host);
                } finally {
                    for (SequenceExecutionListener l : this.sequenceExecutionListeners) {
                        try { l.afterSequenceEnd(true); }
                        catch (Exception ex) { Stepper.montoya.logging().logToError("Stepper-NG: listener failed: " + ex.getMessage()); }
                    }
                    if (host != null) {
                        try { host.endExecution(); } catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            MessageProcessor.popSequence();
            this.isExecuting = false;
            executionLock.lock();
            try { executionDone.signalAll(); } finally { executionLock.unlock(); }
        }
    }

    public void executeStepBlocking(Step target) {
        if (target == null || this.isExecuting || this.disabled) return;
        if (this.steps.indexOf(target) < 0) return;
        if (MessageProcessor.isSequenceOnStack(this.sequenceId)) return;

        MessageProcessor.pushSequence(this.sequenceId);
        try {
            synchronized (this) {
                if (this.isExecuting || this.disabled) return;
                this.isExecuting = true;
                StepperUI.ExecutionHost host = null;
                try {
                    host = Stepper.getUI() != null ? Stepper.getUI().getExecutionHost(this) : null;
                    if (host != null) {
                        host.beginExecution();
                        host.setActive(target);
                        byte[] live = host.liveRequestBytes(target);
                        if (live != null && live.length > 0) target.setRequestBody(live);
                    }
                    for (SequenceExecutionListener l : this.sequenceExecutionListeners) {
                        try { l.beforeSequenceStart(java.util.Collections.singletonList(target)); }
                        catch (Exception ex) { Stepper.montoya.logging().logToError("Stepper-NG: listener failed: " + ex.getMessage()); }
                    }
                    try {
                        List<StepVariable> rolling = getRollingVariablesUpToStep(target);
                        StepExecutionInfo info = target.executeStep(rolling);
                        target.setLastExecutionTime(System.currentTimeMillis());
                        target.setLastConditionResult("Executed (single step)");
                        stepModified(target);
                        final StepExecutionInfo fi = info;
                        this.sequenceExecutionListeners.forEach(l -> l.sequenceStepExecuted(fi));
                    } catch (Exception e) {
                        Stepper.montoya.logging().logToError("Stepper-NG: single-step run failed: " + e.getMessage());
                    }
                } finally {
                    for (SequenceExecutionListener l : this.sequenceExecutionListeners) {
                        try { l.afterSequenceEnd(true); }
                        catch (Exception ex) { Stepper.montoya.logging().logToError("Stepper-NG: listener failed: " + ex.getMessage()); }
                    }
                    if (host != null) {
                        try { host.endExecution(); } catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            MessageProcessor.popSequence();
            this.isExecuting = false;
            executionLock.lock();
            try { executionDone.signalAll(); } finally { executionLock.unlock(); }
        }
    }

    /**
     * Condition-aware execution loop bounded by {@code lastIdx} (inclusive). Mirrors the body of
     * {@link #executeBlocking(Map)} for retries / goto / skip / else action so "Run through here"
     * behaves the same as a full run, just stopped early. Skips pre/post-validation steps so the
     * debug aid doesn't trigger session-recovery side effects. A goto/else-goto whose target is
     * past {@code lastIdx} terminates the loop instead of running beyond what the user requested.
     */
    private void runConditionalLoop(int lastIdx, StepperUI.ExecutionHost host) {
        int maxIterations = (lastIdx + 1) * 10;
        int iterations = 0;
        int stepIndex = 0;
        while (stepIndex <= lastIdx && iterations < maxIterations) {
            Step step = steps.get(stepIndex);
            iterations++;

            if (!step.isEnabled()) { stepIndex++; continue; }
            if (step.getStepId().equals(postValidationStepId)) { stepIndex++; continue; }

            if (host != null) {
                byte[] live = host.liveRequestBytes(step);
                if (live != null && live.length > 0) step.setRequestBody(live);
                host.setActive(step);
            }

            List<StepVariable> rolling = getRollingVariablesUpToStep(step);
            StepCondition cond = step.getCondition();
            boolean isAlways = cond != null && cond.getType() == StepCondition.ConditionType.ALWAYS;
            int maxRetries = (cond != null && !isAlways) ? cond.getRetryCount() : 0;
            StepExecutionInfo stepResult;
            boolean conditionTriggered = false;

            try {
                stepResult = null;
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    if (attempt > 0 && cond != null && cond.getRetryDelayMs() > 0) {
                        try { Thread.sleep(cond.getRetryDelayMs()); } catch (InterruptedException ignored) {}
                    }
                    stepResult = step.executeStep(rolling);
                    final StepExecutionInfo result = stepResult;
                    this.sequenceExecutionListeners.forEach(l -> l.sequenceStepExecuted(result));

                    if (cond != null && cond.isConfigured()) {
                        conditionTriggered = cond.evaluate(stepResult);
                        if (!conditionTriggered) break;
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                Stepper.montoya.logging().logToError("Stepper-NG: run-through-here failed at step '"
                        + step.getTitle() + "': " + e.getMessage());
                step.setLastExecutionTime(System.currentTimeMillis());
                step.setLastConditionResult("Error - run-through-here aborted");
                stepModified(step);
                return;
            }

            step.setLastExecutionTime(System.currentTimeMillis());
            boolean reachedTarget = (stepIndex == lastIdx);

            if (conditionTriggered && cond != null) {
                switch (cond.getAction()) {
                    case SKIP_REMAINING -> {
                        step.setLastConditionResult("Triggered - skipped remaining steps");
                        stepModified(step);
                        return;
                    }
                    case GOTO_STEP -> {
                        int gotoTarget = resolveStepIndex(cond.getGotoTarget());
                        String name = resolveStepIdToDisplay(cond.getGotoTarget());
                        step.setLastConditionResult("Triggered - goto " + (name != null ? name : cond.getGotoTarget()));
                        stepModified(step);
                        if (gotoTarget < 0 || gotoTarget > lastIdx) return;
                        stepIndex = gotoTarget;
                        continue;
                    }
                    case CONTINUE -> step.setLastConditionResult("Triggered - continued");
                }
            } else if (cond != null && cond.isConfigured()) {
                ConditionFailAction elseAct = cond.getElseAction();
                switch (elseAct) {
                    case SKIP_REMAINING -> {
                        step.setLastConditionResult("Not triggered - skipped remaining steps");
                        stepModified(step);
                        return;
                    }
                    case GOTO_STEP -> {
                        int gotoTarget = resolveStepIndex(cond.getElseGotoTarget());
                        String name = resolveStepIdToDisplay(cond.getElseGotoTarget());
                        step.setLastConditionResult("Not triggered - goto " + (name != null ? name : cond.getElseGotoTarget()));
                        stepModified(step);
                        if (gotoTarget < 0 || gotoTarget > lastIdx) return;
                        stepIndex = gotoTarget;
                        continue;
                    }
                    case CONTINUE -> step.setLastConditionResult("Not triggered - continued");
                }
            } else {
                step.setLastConditionResult(reachedTarget ? "Executed (run through here)" : "Executed");
            }
            stepModified(step);

            stepIndex++;
        }
    }

    public void executeThroughStepAsync(Step target) {
        Thread t = new Thread(() -> {
            try { executeThroughStep(target); }
            catch (Throwable th) {
                try { Stepper.montoya.logging().logToError("Stepper-NG: run-through worker crashed: " + th); } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.setName("Stepper-NG-RunThrough-" + (target != null ? target.getTitle() : "?"));
        t.start();
    }

    public void insertStepAt(int index, Step step) {
        if (index < 0) index = 0;
        if (index > this.steps.size()) index = this.steps.size();
        this.steps.add(index, step);
        step.setSequence(this);
        for (StepListener stepListener : this.stepListeners) {
            try { stepListener.onStepAdded(step); } catch (Exception e) {
                Stepper.montoya.logging().logToError("Stepper-NG: Error notifying step listener: " + e.getMessage());
            }
        }
    }

    public void executeBlocking(Map<String, String> arguments){
        executeBlocking(arguments, false);
    }

    /**
     * Run the sequence end-to-end. The pre-validation step is always executed when configured;
     * the difference is what happens on success. When {@code continueAfterPreValidation} is false
     * (automatic triggers from {@code MessageProcessor} / session-handling action), a successful
     * pre-validation short-circuits the run — that's the whole point of the throttle during scans.
     * When true (manual UI triggers — Run all), the pre-validation step still executes, but the
     * sequence proceeds through all remaining steps regardless of the result.
     */
    public void executeBlocking(Map<String, String> arguments, boolean continueAfterPreValidation){
        if(this.isExecuting) return;
        if(this.disabled) return;

        if (MessageProcessor.isSequenceOnStack(this.sequenceId)) {
            Stepper.montoya.logging().logToError("Stepper-NG: Skipping sequence '" + this.title + "' - already on execution stack (cycle detected).");
            return;
        }
        if (MessageProcessor.getStackDepth() >= Globals.MAX_SEQUENCE_DEPTH) {
            Stepper.montoya.logging().logToError("Stepper-NG: Skipping sequence '" + this.title + "' - max depth (" + Globals.MAX_SEQUENCE_DEPTH + ") reached.");
            return;
        }

        boolean needsBrokenDialog = false;

        MessageProcessor.pushSequence(this.sequenceId);
        try {
            synchronized (StepSequence.this) {
                if (this.isExecuting) return;
                if (this.disabled) return;
                this.isExecuting = true;

                Map<StepVariable, String> savedValues = new HashMap<>();
                if (arguments != null && !arguments.isEmpty()) {
                    // Override step-level variables by identifier; restored in finally.
                    for (Step step : this.steps) {
                        for (StepVariable variable : step.getVariableManager().getVariables()) {
                            if (arguments.containsKey(variable.getIdentifier())) {
                                savedValues.putIfAbsent(variable, variable.getValue());
                                variable.setValue(arguments.get(variable.getIdentifier()));
                            }
                        }
                    }
                }

                StepperUI.ExecutionHost host = null;
                try {
                    host = Stepper.getUI() != null ? Stepper.getUI().getExecutionHost(this) : null;
                    if (host != null) host.beginExecution();

                    if (Stepper.getUI() != null && !Stepper.getUI().isSequenceVisible(this)) {
                        if (!Stepper.getUI().isStepperTabVisible()) {
                            Stepper.getUI().highlightTab();
                        }
                        Stepper.getUI().highlightSequenceTab(this);
                    }

                    for (SequenceExecutionListener stepListener : this.sequenceExecutionListeners) {
                        stepListener.beforeSequenceStart(this.steps);
                    }

                    int validationStepIndex = resolveValidationStepIndex();
                    int postValidationStepIndex = resolvePostValidationStepIndex();

                    if (validationStepIndex >= 0 && validationStepIndex < steps.size()) {
                        Step valStep = steps.get(validationStepIndex);
                        StepCondition valCondition = valStep.getCondition();
                        boolean hasUsableCondition = valCondition != null && valCondition.isConfigured()
                                && valCondition.getType() != StepCondition.ConditionType.ALWAYS;
                        if (hasUsableCondition && valStep.isEnabled() && valStep.isReadyToExecute()) {
                            byte[] valRequest = host != null ? host.liveRequestBytes(valStep) : valStep.getRequest();
                            if (valRequest != null) valStep.setRequestBody(valRequest);

                            boolean hasUnresolvedVars = false;
                            if (valRequest != null && MessageProcessor.hasStepVariable(valRequest)) {
                                List<StepVariable> allVars = this.getRollingVariablesForWholeSequence();
                                hasUnresolvedVars = allVars.stream()
                                        .anyMatch(v -> v.getValue() == null || v.getValue().isEmpty());
                            }

                            if (hasUnresolvedVars) {
                                valStep.setLastConditionResult("Skipped - variables not yet populated");
                                stepModified(valStep);
                            } else {
                                if (host != null) host.setActive(valStep);
                                try {
                                    List<StepVariable> valReplacements = this.getRollingVariablesUpToStep(valStep);
                                    StepExecutionInfo valResult = valStep.executeStep(valReplacements);
                                    this.sequenceExecutionListeners.forEach(l -> l.sequenceStepExecuted(valResult));
                                    valStep.setLastExecutionTime(System.currentTimeMillis());
                                    if (valCondition.evaluate(valResult)) {
                                        if (continueAfterPreValidation) {
                                            valStep.setLastConditionResult("Triggered - session valid (continuing full run)");
                                            stepModified(valStep);
                                        } else {
                                            valStep.setLastConditionResult("Triggered - session valid, skipped rest");
                                            stepModified(valStep);
                                            for (SequenceExecutionListener l : sequenceExecutionListeners) l.afterSequenceEnd(true);
                                            return;
                                        }
                                    } else {
                                        valStep.setLastConditionResult("Not triggered - session invalid, running full sequence");
                                        stepModified(valStep);
                                    }
                                } catch (Exception e) {
                                    valStep.setLastConditionResult("Error - running full sequence");
                                    stepModified(valStep);
                                }
                            }
                        }
                    }

                    for (Step step : this.steps) {
                        if (!step.isEnabled()) continue;
                        if (host != null) {
                            byte[] live = host.liveRequestBytes(step);
                            if (live != null && live.length > 0) step.setRequestBody(live);
                        }
                        if (!step.isReadyToExecute()) {
                            Stepper.montoya.logging().logToError("Stepper-NG: Sequence '" + title + "' - step '" + step.getTitle() + "' is not ready to execute.");
                            for (SequenceExecutionListener l : this.sequenceExecutionListeners) l.afterSequenceEnd(false);
                            return;
                        }
                    }

                    boolean sequenceSuccess = false;
                    int maxIterations = steps.size() * 10;
                    int iterations = 0;
                    try {
                        int stepIndex = 0;
                        while (stepIndex < steps.size() && iterations < maxIterations) {
                            Step step = steps.get(stepIndex);
                            iterations++;

                            if (!step.isEnabled()) { stepIndex++; continue; }

                            if (validationStepId != null && step.getStepId().equals(validationStepId)) {
                                stepIndex++;
                                continue;
                            }

                            if (postValidationStepId != null && step.getStepId().equals(postValidationStepId)) {
                                stepIndex++;
                                continue;
                            }

                            if (host != null) host.setActive(step);
                            List<StepVariable> rollingReplacements = this.getRollingVariablesUpToStep(step);

                            StepCondition cond = step.getCondition();
                            boolean isAlways = cond != null && cond.getType() == StepCondition.ConditionType.ALWAYS;
                            int maxRetries = (cond != null && !isAlways) ? cond.getRetryCount() : 0;
                            StepExecutionInfo stepResult = null;
                            boolean conditionTriggered = false;

                            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                                if (attempt > 0 && cond != null && cond.getRetryDelayMs() > 0) {
                                    try { Thread.sleep(cond.getRetryDelayMs()); } catch (InterruptedException ignored) {}
                                }
                                stepResult = step.executeStep(rollingReplacements);
                                final StepExecutionInfo result = stepResult;
                                this.sequenceExecutionListeners.forEach(l -> l.sequenceStepExecuted(result));

                                if (cond != null && cond.isConfigured()) {
                                    conditionTriggered = cond.evaluate(stepResult);
                                    if (!conditionTriggered) break;
                                } else {
                                    break;
                                }
                            }

                            step.setLastExecutionTime(System.currentTimeMillis());

                            if (conditionTriggered && cond != null) {
                                switch (cond.getAction()) {
                                    case SKIP_REMAINING -> {
                                        step.setLastConditionResult("Triggered - skipped remaining steps");
                                        stepModified(step);
                                        stepIndex = steps.size();
                                        continue;
                                    }
                                    case GOTO_STEP -> {
                                        int target = resolveStepIndex(cond.getGotoTarget());
                                        String targetName = resolveStepIdToDisplay(cond.getGotoTarget());
                                        step.setLastConditionResult("Triggered - goto " + (targetName != null ? targetName : cond.getGotoTarget()));
                                        stepModified(step);
                                        if (target >= 0) { stepIndex = target; continue; }
                                        stepIndex++;
                                        continue;
                                    }
                                    case CONTINUE -> step.setLastConditionResult("Triggered - continued");
                                }
                            } else if (cond != null && cond.isConfigured()) {
                                ConditionFailAction elseAct = cond.getElseAction();
                                switch (elseAct) {
                                    case SKIP_REMAINING -> {
                                        step.setLastConditionResult("Not triggered - skipped remaining steps");
                                        stepModified(step);
                                        stepIndex = steps.size();
                                        continue;
                                    }
                                    case GOTO_STEP -> {
                                        int target = resolveStepIndex(cond.getElseGotoTarget());
                                        String targetName = resolveStepIdToDisplay(cond.getElseGotoTarget());
                                        step.setLastConditionResult("Not triggered - goto " + (targetName != null ? targetName : cond.getElseGotoTarget()));
                                        stepModified(step);
                                        if (target >= 0) { stepIndex = target; continue; }
                                        stepIndex++;
                                        continue;
                                    }
                                    case CONTINUE -> step.setLastConditionResult("Not triggered - continued");
                                }
                            } else {
                                step.setLastConditionResult("Executed");
                            }
                            stepModified(step);

                            stepIndex++;
                        }
                        sequenceSuccess = true;
                    } catch (SequenceCancelledException e) {
                        try { Stepper.montoya.logging().logToOutput("Stepper-NG: Sequence '" + title + "' cancelled."); } catch (Exception ignored) {}
                    } catch (SequenceExecutionException e) {
                        try { Stepper.montoya.logging().logToError("Stepper-NG: Sequence '" + title + "' stopped: " + e.getMessage()); } catch (Exception ignored) {}
                    } catch (Exception e) {
                        try { Stepper.montoya.logging().logToError("Stepper-NG: Sequence '" + title + "' failed: " + e.getMessage()); } catch (Exception ignored) {}
                    }

                    if (sequenceSuccess
                            && postValidationStepIndex >= 0 && postValidationStepIndex < steps.size()) {
                        try {
                            Step postValStep = steps.get(postValidationStepIndex);
                            StepCondition postValCondition = postValStep.getCondition();
                            boolean hasUsableCondition = postValCondition != null && postValCondition.isConfigured()
                                    && postValCondition.getType() != StepCondition.ConditionType.ALWAYS;
                            if (hasUsableCondition && postValStep.isEnabled() && postValStep.isReadyToExecute()) {
                                if (host != null) host.setActive(postValStep);
                                List<StepVariable> postValReplacements = this.getRollingVariablesUpToStep(postValStep);
                                StepExecutionInfo postValResult = postValStep.executeStep(postValReplacements);
                                postValStep.setLastExecutionTime(System.currentTimeMillis());

                                if (postValCondition.evaluate(postValResult)) {
                                    consecutiveFailures = 0;
                                    postValStep.setLastConditionResult("Post-validate: session recovered");
                                    stepModified(postValStep);
                                } else {
                                    consecutiveFailures++;
                                    postValStep.setLastConditionResult("Post-validate: session still invalid ("
                                            + consecutiveFailures + "/" + maxConsecutiveFailures + ")");
                                    stepModified(postValStep);
                                    Stepper.montoya.logging().logToError("Stepper-NG: Post-validation failed for '"
                                            + title + "' (" + consecutiveFailures + "/" + maxConsecutiveFailures + ")");

                                    if (consecutiveFailures >= maxConsecutiveFailures) {
                                        sequenceSuccess = false;
                                        needsBrokenDialog = true;
                                    }
                                }
                            }
                        } catch (Exception postValEx) {
                            Stepper.montoya.logging().logToError("Stepper-NG: Post-validation error for '"
                                    + title + "': " + postValEx.getMessage());
                        }
                    }

                    for (SequenceExecutionListener listener : sequenceExecutionListeners) {
                        listener.afterSequenceEnd(sequenceSuccess);
                    }
                } finally {
                    for (Map.Entry<StepVariable, String> entry : savedValues.entrySet()) {
                        entry.getKey().setValue(entry.getValue());
                    }
                    if (host != null) {
                        try { host.endExecution(); } catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            MessageProcessor.popSequence();
            this.isExecuting = false;
            // Wake up any threads waiting for execution to finish
            executionLock.lock();
            try {
                executionDone.signalAll();
            } finally {
                executionLock.unlock();
            }
        }

        if (needsBrokenDialog) {
            handleSessionBroken();
        }
    }

    /**
     * Blocks until this sequence is no longer executing, or until the timeout expires.
     * Used by concurrent request threads to wait for fresh variable values.
     */
    public void awaitExecution() {
        if (!this.isExecuting) return;
        executionLock.lock();
        try {
            long deadline = System.currentTimeMillis() + HOLD_TIMEOUT_MS;
            while (this.isExecuting) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    executionDone.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            executionLock.unlock();
        }
    }

    private int resolveStepIndex(String stepIdOrTitle) {
        if (stepIdOrTitle == null || stepIdOrTitle.isEmpty()) return -1;
        for (int i = 0; i < steps.size(); i++) {
            if (stepIdOrTitle.equals(steps.get(i).getStepId())) return i;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getTitle().equalsIgnoreCase(stepIdOrTitle.trim())) return i;
        }
        try { return Integer.parseInt(stepIdOrTitle.trim()) - 1; } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Resolves a step ID to a display string. Returns null if not found.
     */
    public String resolveStepIdToDisplay(String stepId) {
        if (stepId == null || stepId.isEmpty()) return null;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (stepId.equals(s.getStepId()) || s.getTitle().equalsIgnoreCase(stepId.trim())) {
                return s.getTitle();
            }
        }
        return null;
    }

    public void executeAsync(){
        // Run-all from the UI: always run pre-validation *and* continue through remaining steps.
        Thread t = new Thread(() -> {
            try { executeBlocking(new HashMap<>(), true); }
            catch (Throwable th) {
                try { Stepper.montoya.logging().logToError("Stepper-NG: run-all worker crashed: " + th); } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.setName("Stepper-NG-Exec-" + this.title + "-" + this.sequenceId.substring(0, 8));
        t.start();
    }

    public void addStep(Step step){
        int postValIdx = resolvePostValidationStepIndex();
        if (postValIdx >= 0 && postValIdx < this.steps.size()) {
            this.steps.add(postValIdx, step);
        } else {
            this.steps.add(step);
        }
        step.setSequence(this);
        for (StepListener stepListener : this.stepListeners) {
            try {
                stepListener.onStepAdded(step);
            }catch (Exception e){
                Stepper.montoya.logging().logToError("Stepper-NG: Error notifying step listener: " + e.getMessage());
            }
        }
        if (Stepper.getUI() != null && !Stepper.getUI().isStepperTabVisible()) {
            Stepper.getUI().highlightTab();
        }
    }

    public void addStep(){
        this.addStep(new Step(this));
    }

    public void addStep(HttpRequestResponse requestResponse) {
        Step step = new Step(this);
        if(requestResponse.request() != null) {
            step.setRequestBody(requestResponse.request().toByteArray().getBytes());
        }
        if(requestResponse.response() != null) {
            step.setResponseBody(requestResponse.response().toByteArray().getBytes());
        }
        if(requestResponse.httpService() != null) {
            step.setHttpService(requestResponse.httpService());
        }
        addStep(step);
    }

    public void stepModified(Step step){
        for (StepListener stepListener : this.stepListeners) {
            stepListener.onStepUpdated(step);
        }
    }

    public void removeStep(Step step) {
        if(!this.steps.remove(step)) throw new IllegalArgumentException("Step not valid for sequence");
        for (StepListener stepListener : this.stepListeners) {
            stepListener.onStepRemoved(step);
        }
    }

    public ArrayList<Step> getSteps() {
        return this.steps;
    }

    public boolean isExecuting() { return this.isExecuting; }

    public void moveStep(int from, int to){
        if (from == to) return;

        Step movedStep = this.steps.remove(from);
        this.steps.add(to, movedStep);


        for (StepListener stepListener : this.stepListeners) {
            stepListener.onStepUpdated(movedStep);
        }
    }

    public void addSequenceExecutionListener(SequenceExecutionListener listener){
        this.sequenceExecutionListeners.add(listener);
    }

    public void removeSequenceExecutionListener(SequenceExecutionListener listener){
        this.sequenceExecutionListeners.remove(listener);
    }

    public void addStepListener(StepListener listener){
        this.stepListeners.add(listener);
    }

    public void removeStepListener(StepListener listener){
        this.stepListeners.remove(listener);
    }

    public void syncVariableValues(Step sourceStep) {
        for (StepVariable sourceVar : sourceStep.getVariableManager().getVariables()) {
            String val = sourceVar.getValue();
            if (val == null || val.isEmpty()) continue;
            String id = sourceVar.getIdentifier();
            for (Step otherStep : this.steps) {
                if (otherStep == sourceStep) continue;
                for (StepVariable otherVar : otherStep.getVariableManager().getVariables()) {
                    if (otherVar.getIdentifier().equals(id) && !val.equals(otherVar.getValue())) {
                        otherVar.setValue(val);
                    }
                }
            }
        }
    }

    public List<StepVariable> getRollingVariablesForWholeSequence() {
        return getRollingVariablesUpToStep(null);
    }

    public List<StepVariable> getRollingVariablesUpToStep(Step uptoStep){
        LinkedHashMap<String, StepVariable> rolling = new LinkedHashMap<>();

        for (Step step : this.steps) {
            if(uptoStep == step){
                if (step.isEnabled()) {
                    for (PreExecutionStepVariable preExecutionVariable : step.getVariableManager().getPreExecutionVariables()) {
                        rolling.put(preExecutionVariable.getIdentifier(), preExecutionVariable);
                    }
                }
                break;
            }
            if (!step.isEnabled()) continue;
            for (StepVariable variable : step.getVariableManager().getVariables()) {
                rolling.put(variable.getIdentifier(), variable);
            }
        }

        return new ArrayList<>(rolling.values());
    }

    public java.util.List<StepListener> getStepListeners() {
        return stepListeners;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSequenceId() {
        return sequenceId;
    }

    public void setSequenceId(String sequenceId) {
        this.sequenceId = sequenceId;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }


    public String getValidationStepId() {
        return validationStepId;
    }

    public void setValidationStepId(String validationStepId) {
        this.validationStepId = validationStepId;
    }

    public String getPostValidationStepId() {
        return postValidationStepId;
    }

    public void setPostValidationStepId(String postValidationStepId) {
        this.postValidationStepId = postValidationStepId;
    }

    /**
     * Resolves the current list index of the pre-validation step. Returns -1 if not set or not found.
     */
    public int resolveValidationStepIndex() {
        if (validationStepId == null) return -1;
        for (int i = 0; i < steps.size(); i++) {
            if (validationStepId.equals(steps.get(i).getStepId())) return i;
        }
        return -1;
    }

    /**
     * Resolves the current list index of the post-validation step. Returns -1 if not set or not found.
     */
    public int resolvePostValidationStepIndex() {
        if (postValidationStepId == null) return -1;
        for (int i = 0; i < steps.size(); i++) {
            if (postValidationStepId.equals(steps.get(i).getStepId())) return i;
        }
        return -1;
    }

    public int getMaxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    public void setMaxConsecutiveFailures(int maxConsecutiveFailures) {
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Called when post-validation has failed {@code maxConsecutiveFailures} times.
     */
    private void handleSessionBroken() {
        long now = System.currentTimeMillis();
        if (now - lastBrokenDialogDismissedAt < BROKEN_DIALOG_COOLDOWN_MS) return;

        if (!showingBrokenDialog.compareAndSet(false, true)) return;

        this.disabled = true;

        boolean shouldPause = true;
        try {
            if (Stepper.getPreferences() != null) {
                Object val = Stepper.getPreferences().getSetting(Globals.PREF_PAUSE_ON_POST_VALIDATION_FAIL);
                if (val instanceof Boolean b) shouldPause = b;
            }
        } catch (Exception e) {
            try { Stepper.montoya.logging().logToError("Stepper-NG: Failed to read pause preference: " + e.getMessage()); } catch (Exception ignored) {}
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Stepper.montoya.logging().logToOutput(String.format("[ %s ] Post-validation failed %d consecutive times for sequence '%s'.",
                timestamp, maxConsecutiveFailures, title));

        if (shouldPause) {
            try {
                Stepper.montoya.burpSuite().taskExecutionEngine()
                        .setState(TaskExecutionEngine.TaskExecutionEngineState.PAUSED);
                Stepper.montoya.logging().logToOutput(String.format("[ %s ] Task execution engine PAUSED.", timestamp));
            } catch (Exception ex) {
                Stepper.montoya.logging().logToOutput(String.format("[ %s ] Failed to pause task execution engine: %s", timestamp, ex.getMessage()));
            }

            SwingUtilities.invokeLater(() -> {
                java.awt.Component parent = Stepper.suiteFrame();
                try {
                    JOptionPane.showMessageDialog(
                            parent,
                            "Session recovery failed for sequence '" + title + "'\n"
                                    + "after " + maxConsecutiveFailures + " consecutive attempts.\n"
                                    + "Time: " + timestamp + "\n\n"
                                    + "The task execution engine has been PAUSED.\n"
                                    + "The sequence has been temporarily disabled.\n\n"
                                    + "Please review and fix the issue, then manually\n"
                                    + "resume the engine in Burp's Dashboard.",
                            "Stepper - Session Broken",
                            JOptionPane.WARNING_MESSAGE);
                } finally {
                    consecutiveFailures = 0;
                    disabled = false;
                    lastBrokenDialogDismissedAt = System.currentTimeMillis();
                    showingBrokenDialog.set(false);
                    SequenceManager sm = Stepper.getSequenceManager();
                    if (sm != null) sm.sequenceModified(StepSequence.this);
                    if (!steps.isEmpty()) stepModified(steps.getFirst());
                }
            });
        } else {
            SwingUtilities.invokeLater(() -> {
                java.awt.Component parent = Stepper.suiteFrame();
                try {
                    Object[] options = {"Pause Tasks", "OK"};
                    int choice = JOptionPane.showOptionDialog(
                            parent,
                            "Session recovery failed for sequence '" + title + "'\n"
                                    + "after " + maxConsecutiveFailures + " consecutive attempts.\n"
                                    + "Time: " + timestamp + "\n\n"
                                    + "Tasks are still running. The sequence has been temporarily disabled.\n"
                                    + "You can pause the task execution engine to investigate,\n"
                                    + "or click OK to dismiss and continue.",
                            "Stepper - Session Broken",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                            null, options, options[1]);

                    if (choice == 0) {
                        try {
                            Stepper.montoya.burpSuite().taskExecutionEngine()
                                    .setState(TaskExecutionEngine.TaskExecutionEngineState.PAUSED);
                            Stepper.montoya.logging().logToOutput(String.format("[ %s ] Task execution engine PAUSED by user.", timestamp));
                        } catch (Exception ex) {
                            Stepper.montoya.logging().logToOutput(String.format("[ %s ] Failed to pause task execution engine: %s", timestamp, ex.getMessage()));
                        }
                    }
                } finally {
                    consecutiveFailures = 0;
                    disabled = false;
                    lastBrokenDialogDismissedAt = System.currentTimeMillis();
                    showingBrokenDialog.set(false);
                    SequenceManager sm = Stepper.getSequenceManager();
                    if (sm != null) sm.sequenceModified(StepSequence.this);
                    if (!steps.isEmpty()) stepModified(steps.getFirst());
                }
            });
        }
    }
}

