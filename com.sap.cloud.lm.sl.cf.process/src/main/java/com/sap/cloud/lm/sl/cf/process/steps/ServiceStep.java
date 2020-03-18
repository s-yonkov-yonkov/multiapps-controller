package com.sap.cloud.lm.sl.cf.process.steps;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.cloudfoundry.client.lib.CloudControllerClient;
import org.cloudfoundry.client.lib.CloudOperationException;

import com.sap.cloud.lm.sl.cf.client.lib.domain.CloudServiceExtended;
import com.sap.cloud.lm.sl.cf.core.cf.clients.ServiceUpdater;
import com.sap.cloud.lm.sl.cf.core.model.ServiceOperation;
import com.sap.cloud.lm.sl.cf.core.util.MethodExecution;
import com.sap.cloud.lm.sl.cf.core.util.MethodExecution.ExecutionState;
import com.sap.cloud.lm.sl.cf.process.Messages;
import com.sap.cloud.lm.sl.cf.process.util.ServiceOperationGetter;
import com.sap.cloud.lm.sl.cf.process.util.ServiceProgressReporter;
import com.sap.cloud.lm.sl.cf.process.variables.Variables;
import com.sap.cloud.lm.sl.common.util.JsonUtil;

public abstract class ServiceStep extends AsyncFlowableStep {

    @Inject
    @Named("serviceUpdater")
    private ServiceUpdater serviceUpdater;

    @Inject
    private ServiceOperationGetter serviceOperationGetter;
    @Inject
    private ServiceProgressReporter serviceProgressReporter;

    @Override
    protected StepPhase executeAsyncStep(ExecutionWrapper execution) {
        CloudServiceExtended serviceToProcess = execution.getVariable(Variables.SERVICE_TO_PROCESS);
        MethodExecution<String> methodExecution = executeOperationAndHandleExceptions(execution, execution.getControllerClient(),
                                                                                      serviceToProcess);
        if (methodExecution.getState()
                           .equals(ExecutionState.FINISHED)) {
            return StepPhase.DONE;
        }

        Map<String, ServiceOperation.Type> serviceOperation = new HashMap<>();
        serviceOperation.put(serviceToProcess.getName(), getOperationType());

        execution.getStepLogger()
                 .debug(Messages.TRIGGERED_SERVICE_OPERATIONS, JsonUtil.toJson(serviceOperation, true));
        execution.setVariable(Variables.TRIGGERED_SERVICE_OPERATIONS, serviceOperation);

        StepsUtil.isServiceUpdated(true, execution.getContext());
        return StepPhase.POLL;
    }

    @Override
    protected String getStepErrorMessage(ExecutionWrapper execution) {
        return Messages.ERROR_SERVICE_OPERATION;
    }

    private MethodExecution<String> executeOperationAndHandleExceptions(ExecutionWrapper execution, CloudControllerClient controllerClient,
                                                                        CloudServiceExtended service) {
        try {
            return executeOperation(execution, controllerClient, service);
        } catch (CloudOperationException e) {
            String serviceUpdateFailedMessage = MessageFormat.format(Messages.FAILED_SERVICE_UPDATE, service.getName(), e.getStatusText());
            throw new CloudOperationException(e.getStatusCode(), serviceUpdateFailedMessage, e.getDescription(), e);
        }
    }

    protected abstract MethodExecution<String> executeOperation(ExecutionWrapper execution, CloudControllerClient controllerClient,
                                                                CloudServiceExtended service);

    protected abstract ServiceOperation.Type getOperationType();

    protected ServiceUpdater getServiceUpdater() {
        return serviceUpdater;
    }

    protected ServiceOperationGetter getServiceOperationGetter() {
        return serviceOperationGetter;
    }

    protected ServiceProgressReporter getServiceProgressReporter() {
        return serviceProgressReporter;
    }

}
