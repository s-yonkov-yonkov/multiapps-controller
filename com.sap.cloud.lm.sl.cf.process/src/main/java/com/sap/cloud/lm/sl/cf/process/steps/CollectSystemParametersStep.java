package com.sap.cloud.lm.sl.cf.process.steps;

import java.net.URL;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.cloudfoundry.client.lib.CloudControllerClient;
import org.cloudfoundry.client.lib.CloudControllerException;
import org.cloudfoundry.client.lib.CloudOperationException;
import org.cloudfoundry.client.lib.domain.CloudDomain;
import org.cloudfoundry.client.lib.domain.CloudInfo;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.sap.cloud.lm.sl.cf.client.XsCloudControllerClient;
import com.sap.cloud.lm.sl.cf.client.lib.domain.CloudInfoExtended;
import com.sap.cloud.lm.sl.cf.core.helpers.CredentialsGenerator;
import com.sap.cloud.lm.sl.cf.core.helpers.ModuleToDeployHelper;
import com.sap.cloud.lm.sl.cf.core.helpers.PortAllocator;
import com.sap.cloud.lm.sl.cf.core.helpers.SystemParameters;
import com.sap.cloud.lm.sl.cf.core.model.DeployedMta;
import com.sap.cloud.lm.sl.cf.core.model.SupportedParameters;
import com.sap.cloud.lm.sl.cf.core.security.serialization.SecureSerializationFacade;
import com.sap.cloud.lm.sl.cf.core.util.ApplicationConfiguration;
import com.sap.cloud.lm.sl.cf.process.Constants;
import com.sap.cloud.lm.sl.cf.process.message.Messages;
import com.sap.cloud.lm.sl.common.ContentException;
import com.sap.cloud.lm.sl.common.SLException;
import com.sap.cloud.lm.sl.mta.model.DeploymentDescriptor;
import com.sap.cloud.lm.sl.mta.model.DeploymentType;
import com.sap.cloud.lm.sl.mta.model.Version;
import com.sap.cloud.lm.sl.mta.model.VersionRule;

@Component("collectSystemParametersStep") // rename to collect system parameters and allocate ports?
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class CollectSystemParametersStep extends SyncFlowableStep {

    private SecureSerializationFacade secureSerializer = new SecureSerializationFacade();

    @Inject
    private ApplicationConfiguration configuration;
    @Inject
    private ModuleToDeployHelper moduleToDeployHelper;

    protected Supplier<CredentialsGenerator> credentialsGeneratorSupplier = CredentialsGenerator::new;
    protected Supplier<String> timestampSupplier = () -> new SimpleDateFormat("yyyyMMddHHmmss").format(Calendar.getInstance()
        .getTime());

    @Override
    protected StepPhase executeStep(ExecutionWrapper execution) {
        return executeStepInternal(execution, false);
    }

    protected StepPhase executeStepInternal(ExecutionWrapper execution, boolean reserveTemporaryRoutes) {
        getStepLogger().debug(Messages.COLLECTING_SYSTEM_PARAMETERS);
        PortAllocator portAllocator = null;
        try {
            CloudControllerClient client = execution.getControllerClient();
            String defaultDomainName = getDefaultDomain(client);
            getStepLogger().debug(Messages.DEFAULT_DOMAIN, defaultDomainName);
            boolean portBasedRouting = StepsUtil.isPortBasedRouting(execution);
            getStepLogger().debug(Messages.PORT_BASED_ROUTING, portBasedRouting);
            if (client instanceof XsCloudControllerClient) {
                XsCloudControllerClient xsClient = execution.getXsControllerClient();
                portAllocator = clientProvider.getPortAllocator(xsClient, defaultDomainName);
            }

            DeploymentDescriptor descriptor = StepsUtil.getDeploymentDescriptor(execution.getContext());
            SystemParameters systemParameters = createSystemParameters(execution.getContext(), client, portAllocator, portBasedRouting,
                defaultDomainName, reserveTemporaryRoutes);
            systemParameters.injectInto(descriptor);
            getStepLogger().debug(Messages.DESCRIPTOR_WITH_SYSTEM_PARAMETERS, secureSerializer.toJson(descriptor));

            determineIsVersionAccepted(execution.getContext(), descriptor);

            if (client instanceof XsCloudControllerClient) {
                StepsUtil.setAllocatedPorts(execution.getContext(), portAllocator.getAllocatedPorts());
                getStepLogger().debug(Messages.ALLOCATED_PORTS, portAllocator.getAllocatedPorts());
            }
            execution.getContext()
                .setVariable(Constants.VAR_PORT_BASED_ROUTING, portBasedRouting);

            StepsUtil.setDeploymentDescriptorWithSystemParameters(execution.getContext(), descriptor);
        } catch (CloudOperationException coe) {
            CloudControllerException e = new CloudControllerException(coe);
            cleanUp(portAllocator);
            getStepLogger().error(e, Messages.ERROR_COLLECTING_SYSTEM_PARAMETERS);
            throw e;
        } catch (SLException e) {
            cleanUp(portAllocator);
            getStepLogger().error(e, Messages.ERROR_COLLECTING_SYSTEM_PARAMETERS);
            throw e;
        }
        getStepLogger().debug(Messages.SYSTEM_PARAMETERS_COLLECTED);

        return StepPhase.DONE;
    }

    private String getDefaultDomain(CloudControllerClient client) {
        CloudDomain defaultDomain = client.getDefaultDomain();
        if (defaultDomain != null) {
            return defaultDomain.getName();
        }
        return null;
    }

    private SystemParameters createSystemParameters(DelegateExecution context, CloudControllerClient client, PortAllocator portAllocator,
        boolean portBasedRouting, String defaultDomain, boolean reserveTemporaryRoutes) {
        DeployedMta deployedMta = StepsUtil.getDeployedMta(context);

        String authorizationEndpoint = client.getCloudInfo()
            .getAuthorizationEndpoint();
        int routerPort = configuration.getRouterPort();
        String user = (String) context.getVariable(Constants.VAR_USER);

        URL controllerUrl = configuration.getControllerUrl();

        String deployServiceUrl = getDeployServiceUrl(client);
        Map<String, Object> xsPlaceholderReplacementValues = buildXsPlaceholderReplacementValues(defaultDomain, authorizationEndpoint,
            deployServiceUrl, routerPort, controllerUrl.toString(), controllerUrl.getProtocol());
        StepsUtil.setXsPlaceholderReplacementValues(context, xsPlaceholderReplacementValues);

        return new SystemParameters.Builder().organization(StepsUtil.getOrg(context))
            .space(StepsUtil.getSpace(context))
            .user(user)
            .defaultDomain(defaultDomain)
            .platformType(configuration.getPlatformType())
            .controllerUrl(controllerUrl)
            .authorizationEndpoint(authorizationEndpoint)
            .deployServiceUrl(deployServiceUrl)
            .routerPort(routerPort)
            .portBasedRouting(portBasedRouting)
            .reserveTemporaryRoutes(reserveTemporaryRoutes)
            .portAllocator(portAllocator)
            .deployedMta(deployedMta)
            .credentialsGenerator(credentialsGeneratorSupplier.get())
            .areXsPlaceholdersSupported(configuration.areXsPlaceholdersSupported())
            .timestampSupplier(timestampSupplier)
            .moduleToDeployHelper(moduleToDeployHelper)
            .build();
    }

    private Map<String, Object> buildXsPlaceholderReplacementValues(String defaultDomain, String authorizationEndpoint,
        String deployServiceUrl, int routerPort, String controllerEndpoint, String protocol) {
        Map<String, Object> result = new TreeMap<>();
        result.put(SupportedParameters.XSA_CONTROLLER_ENDPOINT_PLACEHOLDER, controllerEndpoint);
        result.put(SupportedParameters.XSA_ROUTER_PORT_PLACEHOLDER, routerPort);
        result.put(SupportedParameters.XSA_AUTHORIZATION_ENDPOINT_PLACEHOLDER, authorizationEndpoint);
        result.put(SupportedParameters.XSA_DEFAULT_DOMAIN_PLACEHOLDER, defaultDomain);
        result.put(SupportedParameters.XSA_DEPLOY_SERVICE_URL_PLACEHOLDER, deployServiceUrl);
        result.put(SupportedParameters.XSA_PROTOCOL_PLACEHOLDER, protocol);
        return result;
    }

    private String getDeployServiceUrl(CloudControllerClient client) {
        CloudInfo info = client.getCloudInfo();
        if (info instanceof CloudInfoExtended) {
            return ((CloudInfoExtended) info).getDeployServiceUrl();
        }
        return configuration.getDeployServiceUrl();
    }

    private void determineIsVersionAccepted(DelegateExecution context, DeploymentDescriptor descriptor) {
        DeployedMta deployedMta = StepsUtil.getDeployedMta(context);
        VersionRule versionRule = VersionRule.valueOf((String) context.getVariable(Constants.PARAM_VERSION_RULE));
        getStepLogger().debug(Messages.VERSION_RULE, versionRule);

        Version mtaVersion = Version.parseVersion(descriptor.getVersion());
        getStepLogger().info(Messages.DETECTED_NEW_MTA_VERSION, mtaVersion);
        DeploymentType deploymentType = getDeploymentType(deployedMta, mtaVersion);
        if (versionRule.allows(deploymentType)) {
            getStepLogger().debug(Messages.MTA_VERSION_ACCEPTED);
            return;
        }
        if (deploymentType == DeploymentType.DOWNGRADE) {
            throw new ContentException(Messages.HIGHER_VERSION_ALREADY_DEPLOYED);
        }
        if (deploymentType == DeploymentType.REDEPLOYMENT) {
            throw new ContentException(Messages.SAME_VERSION_ALREADY_DEPLOYED);
        }
        throw new IllegalStateException(
            MessageFormat.format(Messages.VERSION_RULE_DOES_NOT_ALLOW_DEPLOYMENT_TYPE, versionRule, deploymentType));
    }

    private DeploymentType getDeploymentType(DeployedMta deployedMta, Version newMtaVersion) {
        if (deployedMta == null) {
            return DeploymentType.DEPLOYMENT;
        }
        if (deployedMta.getMetadata()
            .isVersionUnknown()) {
            getStepLogger().warn(Messages.IGNORING_VERSION_RULE);
            return DeploymentType.UPGRADE;
        }
        Version deployedMtaVersion = deployedMta.getMetadata()
            .getVersion();
        getStepLogger().info(Messages.DEPLOYED_MTA_VERSION, deployedMtaVersion);
        return DeploymentType.fromVersions(deployedMtaVersion, newMtaVersion);
    }

    private void cleanUp(PortAllocator portAllocator) {
        if (portAllocator != null) {
            portAllocator.freeAll();
        }
    }

}
