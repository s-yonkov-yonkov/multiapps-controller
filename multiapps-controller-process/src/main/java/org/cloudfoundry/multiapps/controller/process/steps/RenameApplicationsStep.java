package org.cloudfoundry.multiapps.controller.process.steps;

import java.util.ArrayList;
import java.util.List;

import org.cloudfoundry.multiapps.common.ConflictException;
import org.cloudfoundry.multiapps.controller.api.model.ProcessType;
import org.cloudfoundry.multiapps.controller.core.helpers.ApplicationNameSuffixAppender;
import org.cloudfoundry.multiapps.controller.core.model.ApplicationColor;
import org.cloudfoundry.multiapps.controller.core.model.BlueGreenApplicationNameSuffix;
import org.cloudfoundry.multiapps.controller.core.model.DeployedMta;
import org.cloudfoundry.multiapps.controller.core.model.DeployedMtaApplication;
import org.cloudfoundry.multiapps.controller.core.model.ImmutableDeployedMta;
import org.cloudfoundry.multiapps.controller.core.model.ImmutableDeployedMtaApplication;
import org.cloudfoundry.multiapps.controller.core.util.NameUtil;
import org.cloudfoundry.multiapps.controller.process.Constants;
import org.cloudfoundry.multiapps.controller.process.Messages;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationColorDetector;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationProductizationStateUpdater;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationProductizationStateUpdaterBasedOnAge;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationProductizationStateUpdaterBasedOnColor;
import org.cloudfoundry.multiapps.controller.process.util.ProcessTypeParser;
import org.cloudfoundry.multiapps.controller.process.variables.Variables;
import org.cloudfoundry.multiapps.mta.model.DeploymentDescriptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;

import com.sap.cloudfoundry.client.facade.CloudControllerClient;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("renameApplicationsStep")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class RenameApplicationsStep extends SyncFlowableStep {

    @Inject
    private ApplicationColorDetector applicationColorDetector;
    @Inject
    private ProcessTypeParser processTypeParser;

    @Override
    protected StepPhase executeStep(ProcessContext context) {
        RenameFlow renameFlow = createFlow(context);
        renameFlow.execute(context);
        return StepPhase.DONE;
    }

    private RenameFlow createFlow(ProcessContext context) {
        if (processTypeParser.getProcessType(context.getExecution())
                             .equals(ProcessType.ROLLBACK_MTA)) {
            return new RenameApplicationsForRollbackFlow();
        }
        if (context.getVariable(Variables.KEEP_ORIGINAL_APP_NAMES_AFTER_DEPLOY)) {
            return new RenameApplicationsWithOldNewSuffix();
        }
        return new RenameApplicationsWithBlueGreenSuffix();
    }

    @Override
    protected String getStepErrorMessage(ProcessContext context) {
        return Messages.ERROR_RENAMING_APPLICATIONS;
    }

    interface RenameFlow {
        void execute(ProcessContext context);
    }

    class RenameApplicationsWithOldNewSuffix implements RenameFlow {

        @Override
        public void execute(ProcessContext context) {
            List<String> appsToRename = context.getVariable(Variables.APPS_TO_RENAME);
            if (appsToRename != null) {
                renameOldApps(appsToRename, context.getControllerClient());
            }

            DeployedMta deployedMta = context.getVariable(Variables.DEPLOYED_MTA);
            if (deployedMta != null) {
                ApplicationProductizationStateUpdater appUpdater = new ApplicationProductizationStateUpdaterBasedOnAge(getStepLogger());
                setIdleApplications(context, deployedMta, appUpdater);
            }

            getStepLogger().debug(Messages.UPDATING_APP_NAMES_WITH_NEW_SUFFIX);
            updateApplicationNamesInDescriptor(context, BlueGreenApplicationNameSuffix.IDLE.asSuffix());
        }

        private void renameOldApps(List<String> appsToRename, CloudControllerClient client) {
            for (String appName : appsToRename) {
                String newName = appName + BlueGreenApplicationNameSuffix.LIVE.asSuffix();
                getStepLogger().info(Messages.RENAMING_APPLICATION_0_TO_1, appName, newName);
                client.rename(appName, newName);
            }
        }

    }

    class RenameApplicationsWithBlueGreenSuffix implements RenameFlow {

        private final ApplicationColor defaultMtaColor = ApplicationColor.BLUE;

        @Override
        public void execute(ProcessContext context) {
            getStepLogger().debug(Messages.DETECTING_COLOR_OF_DEPLOYED_MTA);
            DeployedMta deployedMta = context.getVariable(Variables.DEPLOYED_MTA);
            ApplicationColor idleMtaColor = defaultMtaColor;

            if (deployedMta == null) {
                getStepLogger().info(Messages.NEW_MTA_COLOR, idleMtaColor);
                context.setVariable(Variables.IDLE_MTA_COLOR, idleMtaColor);
                updateApplicationNamesInDescriptor(context, idleMtaColor.asSuffix());
                return;
            }

            ApplicationColor liveMtaColor = computeLiveMtaColor(context, deployedMta);
            if (liveMtaColor != null) {
                idleMtaColor = liveMtaColor.getAlternativeColor();
            }
            getStepLogger().info(Messages.NEW_MTA_COLOR, idleMtaColor);

            ApplicationProductizationStateUpdater appUpdater = new ApplicationProductizationStateUpdaterBasedOnColor(getStepLogger(),
                                                                                                                     liveMtaColor);
            setIdleApplications(context, deployedMta, appUpdater);
            context.setVariable(Variables.LIVE_MTA_COLOR, liveMtaColor);
            context.setVariable(Variables.IDLE_MTA_COLOR, idleMtaColor);
            updateApplicationNamesInDescriptor(context, idleMtaColor.asSuffix());
        }

        private ApplicationColor computeLiveMtaColor(ProcessContext context, DeployedMta deployedMta) {
            try {
                ApplicationColor liveMtaColor = applicationColorDetector.detectSingularDeployedApplicationColor(deployedMta);
                getStepLogger().info(Messages.DEPLOYED_MTA_COLOR, liveMtaColor);
                return liveMtaColor;
            } catch (ConflictException e) {
                getStepLogger().warn(e.getMessage());
                ApplicationColor liveMtaColor = applicationColorDetector.detectLiveApplicationColor(deployedMta,
                                                                                                    context.getVariable(Variables.CORRELATION_ID));
                ApplicationColor idleMtaColor = liveMtaColor.getAlternativeColor();
                getStepLogger().info(Messages.ASSUMED_LIVE_AND_IDLE_COLORS, liveMtaColor, idleMtaColor);
                return liveMtaColor;
            }
        }

    }

    private void setIdleApplications(ProcessContext context, DeployedMta deployedMta, ApplicationProductizationStateUpdater appUpdater) {
        List<DeployedMtaApplication> updatedApplications = appUpdater.updateApplicationsProductizationState(deployedMta.getApplications());
        context.setVariable(Variables.DEPLOYED_MTA, ImmutableDeployedMta.copyOf(deployedMta)
                                                                        .withApplications(updatedApplications));
    }

    private void updateApplicationNamesInDescriptor(ProcessContext context, String suffix) {
        DeploymentDescriptor descriptor = context.getVariable(Variables.DEPLOYMENT_DESCRIPTOR);
        ApplicationNameSuffixAppender appender = new ApplicationNameSuffixAppender(suffix);
        descriptor.accept(appender);
        context.setVariable(Variables.DEPLOYMENT_DESCRIPTOR, descriptor);
    }

    class RenameApplicationsForRollbackFlow implements RenameFlow {

        @Override
        public void execute(ProcessContext context) {
            getStepLogger().debug(Messages.RENAME_APPLICATIONS_FOR_ROLLBACK);
            DeployedMta deployedMta = context.getVariable(Variables.DEPLOYED_MTA);
            DeployedMta backupMta = context.getVariable(Variables.BACKUP_MTA);
            CloudControllerClient client = context.getControllerClient();

            List<DeployedMtaApplication> deployedMtaApplications = new ArrayList<>();
            for (DeployedMtaApplication deployedMtaApplication : deployedMta.getApplications()) {
                String deployedApplicationName = deployedMtaApplication.getName();
                String toBeDeletedApplicationName = NameUtil.computeValidApplicationName(deployedApplicationName,
                                                                                         Constants.MTA_FOR_DELETION_PREFIX, true, false);
                getStepLogger().info(Messages.RENAME_CURRENTLY_DEPLOYED_APPLICATION_0_TO_1, deployedApplicationName,
                                     toBeDeletedApplicationName);
                client.rename(deployedApplicationName, toBeDeletedApplicationName);
                deployedMtaApplications.add(ImmutableDeployedMtaApplication.copyOf(deployedMtaApplication)
                                                                           .withName(toBeDeletedApplicationName));
            }

            List<DeployedMtaApplication> backupMtaApplications = new ArrayList<>();
            for (DeployedMtaApplication backupMtaApplication : backupMta.getApplications()) {
                String backupApplicationName = backupMtaApplication.getName();
                String applicationNameWithoutMtaBackupNamespace = backupApplicationName.substring(NameUtil.getNamespacePrefix(Constants.MTA_BACKUP_NAMESPACE)
                                                                                                          .length());
                getStepLogger().info(Messages.RENAME_BACKUP_APPLICATION_0_TO_1, backupApplicationName,
                                     applicationNameWithoutMtaBackupNamespace);
                client.rename(backupApplicationName, applicationNameWithoutMtaBackupNamespace);
                backupMtaApplications.add(ImmutableDeployedMtaApplication.copyOf(backupMtaApplication)
                                                                         .withName(applicationNameWithoutMtaBackupNamespace));
            }

            context.setVariable(Variables.DEPLOYED_MTA, ImmutableDeployedMta.copyOf(deployedMta)
                                                                            .withApplications(deployedMtaApplications));
            context.setVariable(Variables.BACKUP_MTA, ImmutableDeployedMta.copyOf(backupMta)
                                                                          .withApplications(backupMtaApplications));
        }

    }

}
