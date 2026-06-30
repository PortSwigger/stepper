package com.xreous.stepperng;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.sessions.ActionResult;
import burp.api.montoya.http.sessions.SessionHandlingAction;
import burp.api.montoya.http.sessions.SessionHandlingActionData;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.xreous.stepperng.sequence.StepSequence;
import com.xreous.stepperng.sequencemanager.SequenceManager;
import com.xreous.stepperng.variable.DynamicGlobalVariableManager;
import com.xreous.stepperng.variable.StepVariable;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Session handling action that performs Stepper-NG variable replacements inside
 * the session handling phase. Required when the rule scope includes Extensions.
 */
public class StepperSessionHandlingAction implements SessionHandlingAction {

    private final SequenceManager sequenceManager;
    private final Preferences preferences;
    private final DynamicGlobalVariableManager dynamicGlobalVariableManager;

    public StepperSessionHandlingAction(SequenceManager sequenceManager, Preferences preferences,
                                        DynamicGlobalVariableManager dynamicGlobalVariableManager) {
        this.sequenceManager = sequenceManager;
        this.preferences = preferences;
        this.dynamicGlobalVariableManager = dynamicGlobalVariableManager;
    }

    @Override
    public String name() {
        return "Stepper: Variable Replacement for Extensions";
    }

    @Override
    public ActionResult performAction(SessionHandlingActionData actionData) {
        HttpRequest request = actionData.request();
        Annotations annotations = actionData.annotations();

        try {
            byte[] requestBytes = request.toByteArray().getBytes();
            String requestString = new String(requestBytes);
            boolean hasDollar = requestString.indexOf('$') >= 0;

            if (!hasDollar) {
                return ActionResult.actionResult(request, annotations);
            }

            // Auto-execute sequences for published variable references
            Set<StepSequence> autoExecSequences = sequenceManager.getSequencesToAutoExecute(requestString);
            if (!autoExecSequences.isEmpty()) {
                int validateEveryN = 1;
                if (preferences != null) {
                    try {
                        Object val = preferences.getSetting(Globals.PREF_SESSION_VALIDATE_EVERY_N);
                        if (val instanceof Integer i) validateEveryN = Math.max(1, i);
                    } catch (Exception ignored) {}
                }

                boolean holdRequests = false;
                if (preferences != null) {
                    try {
                        Object val = preferences.getSetting(Globals.PREF_HOLD_REQUESTS_DURING_EXECUTION);
                        if (val instanceof Boolean b) holdRequests = b;
                    } catch (Exception ignored) {}
                }

                int count = MessageProcessor.incrementAndGetRequestCount();
                boolean shouldValidate = (count % validateEveryN == 0);

                for (StepSequence seq : autoExecSequences) {
                    if (!MessageProcessor.isSequenceOnStack(seq.getSequenceId())
                            && MessageProcessor.getStackDepth() < Globals.MAX_SEQUENCE_DEPTH) {
                        if (!seq.isExecuting() && shouldValidate) {
                            seq.executeBlocking();
                        } else if (holdRequests && seq.isExecuting()
                                && MessageProcessor.getStackDepth() == 0) {
                            seq.awaitExecution();
                        }
                    }
                }
            }

            boolean updateLength = preferences == null || Boolean.TRUE.equals(preferences.getSetting(Globals.PREF_UPDATE_REQUEST_LENGTH));

            // Replace $VAR:SequenceName:variable$ references
            if (MessageProcessor.hasStepVariable(requestString)) {
                HashMap<StepSequence, List<StepVariable>> allVariables = sequenceManager.getRollingVariablesFromAllSequences();
                if (!allVariables.isEmpty()) {
                    requestBytes = MessageProcessor.makeReplacementsForAllSequences(requestBytes, allVariables);
                    if (updateLength) {
                        requestBytes = MessageProcessor.updateContentLength(requestBytes);
                    }
                }
            }

            // Replace $DVAR:name$ references
            if (MessageProcessor.hasDvarVariable(requestString)) {
                requestBytes = MessageProcessor.makeDvarReplacements(requestBytes, dynamicGlobalVariableManager);
                if (updateLength) {
                    requestBytes = MessageProcessor.updateContentLength(requestBytes);
                }
            }

            // Replace $GVAR:name$ references
            if (MessageProcessor.hasGvarVariable(requestString)) {
                requestBytes = MessageProcessor.makeGvarReplacements(requestBytes, dynamicGlobalVariableManager);
                if (updateLength) {
                    requestBytes = MessageProcessor.updateContentLength(requestBytes);
                }
            }

            request = HttpRequest.httpRequest(request.httpService(), burp.api.montoya.core.ByteArray.byteArray(requestBytes));
        } catch (Exception e) {
            try {
                Stepper.montoya.logging().logToError("Stepper-NG: Session handling action error: " + e.getMessage());
            } catch (Exception ignored) {}
        }

        return ActionResult.actionResult(request, annotations);
    }
}




