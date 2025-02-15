package org.cloudfoundry.multiapps.controller.process.steps;

import java.text.MessageFormat;

import jakarta.inject.Named;

import org.cloudfoundry.multiapps.controller.process.Messages;
import org.cloudfoundry.multiapps.controller.process.util.ExceptionMessageTailMapper;
import org.cloudfoundry.multiapps.controller.process.util.ExceptionMessageTailMapper.CloudComponents;
import org.cloudfoundry.multiapps.controller.process.variables.Variables;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;

import com.sap.cloudfoundry.client.facade.CloudControllerClient;
import com.sap.cloudfoundry.client.facade.CloudControllerException;
import com.sap.cloudfoundry.client.facade.CloudOperationException;
import com.sap.cloudfoundry.client.facade.domain.CloudApplication;
import com.sap.cloudfoundry.client.facade.domain.CloudServiceBroker;
import com.sap.cloudfoundry.client.facade.domain.ImmutableCloudServiceBroker;

@Named("updateServiceBrokerSubscriberStep")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class UpdateServiceBrokerSubscriberStep extends CreateOrUpdateServiceBrokerStep {

    @Override
    protected StepPhase executeAsyncStep(ProcessContext context) {
        CloudApplication serviceBrokerApplication = StepsUtil.getUpdatedServiceBrokerSubscriber(context);
        CloudControllerClient client = context.getControllerClient();
        var appEnv = client.getApplicationEnvironment(serviceBrokerApplication.getGuid());
        CloudServiceBroker serviceBroker = getServiceBrokerFromApp(context, serviceBrokerApplication, appEnv);

        String jobId = null;
        try {
            CloudServiceBroker existingServiceBroker = client.getServiceBroker(serviceBroker.getName(), false);
            if (existingServiceBroker == null) {
                getStepLogger().warn(MessageFormat.format(Messages.SERVICE_BROKER_DOES_NOT_EXIST, serviceBroker.getName()));
            } else {
                serviceBroker = ImmutableCloudServiceBroker.copyOf(serviceBroker)
                                                           .withMetadata(existingServiceBroker.getMetadata());
                jobId = updateServiceBroker(context, serviceBroker, client);
                getStepLogger().debug(MessageFormat.format(Messages.UPDATE_SERVICE_BROKER_TRIGGERED, serviceBroker.getName()));
            }

            if (jobId != null) {
                context.setVariable(Variables.SERVICE_BROKER_ASYNC_JOB_ID, jobId);
                context.setVariable(Variables.CREATED_OR_UPDATED_SERVICE_BROKER, serviceBroker);
                return StepPhase.POLL;
            }
            return StepPhase.DONE;
        } catch (CloudOperationException coe) {
            CloudControllerException e = new CloudControllerException(coe);
            getStepLogger().warn(MessageFormat.format(Messages.FAILED_SERVICE_BROKER_UPDATE, serviceBroker.getName()), e,
                                 ExceptionMessageTailMapper.map(configuration, CloudComponents.SERVICE_BROKERS, serviceBroker.getName()));
            return StepPhase.DONE;
        }
    }

}
