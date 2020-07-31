package org.cloudfoundry.multiapps.controller.process.steps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.cloudfoundry.client.lib.CloudOperationException;
import org.cloudfoundry.client.lib.domain.CloudBuild;
import org.cloudfoundry.client.lib.domain.CloudPackage;
import org.cloudfoundry.client.lib.domain.ImmutableCloudBuild;
import org.cloudfoundry.client.lib.domain.ImmutableCloudMetadata;
import org.cloudfoundry.client.lib.domain.ImmutableCloudPackage;
import org.cloudfoundry.client.lib.domain.ImmutableDropletInfo;
import org.cloudfoundry.client.lib.domain.ImmutableUploadToken;
import org.cloudfoundry.client.lib.domain.Status;
import org.cloudfoundry.client.lib.domain.UploadToken;
import org.cloudfoundry.multiapps.common.SLException;
import org.cloudfoundry.multiapps.common.util.JsonUtil;
import org.cloudfoundry.multiapps.controller.client.lib.domain.CloudApplicationExtended;
import org.cloudfoundry.multiapps.controller.client.lib.domain.ImmutableCloudApplicationExtended;
import org.cloudfoundry.multiapps.controller.core.Constants;
import org.cloudfoundry.multiapps.controller.core.helpers.MtaArchiveElements;
import org.cloudfoundry.multiapps.controller.core.util.ApplicationConfiguration;
import org.cloudfoundry.multiapps.controller.persistence.services.FileContentProcessor;
import org.cloudfoundry.multiapps.controller.process.Messages;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationArchiveContext;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationArchiveReader;
import org.cloudfoundry.multiapps.controller.process.util.ApplicationZipBuilder;
import org.cloudfoundry.multiapps.controller.process.util.CloudPackagesGetter;
import org.cloudfoundry.multiapps.controller.process.variables.Variables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class UploadAppStepGeneralTest extends SyncFlowableStepTest<UploadAppStep> {

    private static final String APP_NAME = "sample-app-backend";
    private static final String APP_FILE = "web.zip";
    private static final String SPACE = "space";
    private static final String APP_ARCHIVE = "sample-app.mtar";
    private static final String CURRENT_MODULE_DIGEST = "439B99DFFD0583200D5D21F4CD1BF035";
    private static final String NEW_MODULE_DIGEST = "539B99DFFD0583200D5D21F4CD1BF035";
    private static final UUID APP_GUID = UUID.randomUUID();
    private static final IOException IO_EXCEPTION = new IOException();
    private static final CloudOperationException CO_EXCEPTION = new CloudOperationException(HttpStatus.BAD_REQUEST);
    private static final UploadToken UPLOAD_TOKEN = ImmutableUploadToken.builder()
                                                                        .packageGuid(UUID.randomUUID())
                                                                        .build();
    private final UUID PACKAGE_GUID = UUID.randomUUID();
    private final MtaArchiveElements mtaArchiveElements = new MtaArchiveElements();
    private final CloudPackagesGetter cloudPackagesGetter = mock(CloudPackagesGetter.class);
    @TempDir
    Path tempDir;
    private File appFile;

    private static Stream<Arguments> testWithAvailableExpiredCloudPackageAndDifferentContent() {
        return Stream.of(Arguments.of(CURRENT_MODULE_DIGEST), Arguments.of(NEW_MODULE_DIGEST));
    }

    private static Stream<Arguments> testFailedUploadWithException() {
        return Stream.of(Arguments.of(MessageFormat.format(Messages.ERROR_RETRIEVING_MTA_MODULE_CONTENT, APP_FILE), null),
                         Arguments.of(null, MessageFormat.format(Messages.CF_ERROR, CO_EXCEPTION.getMessage())));
    }

    private static Stream<Arguments> testWithBuildStates() {
        return Stream.of(Arguments.of(Collections.singletonList(ImmutableCloudBuild.builder()
                                                                                   .metadata(ImmutableCloudMetadata.builder()
                                                                                                                   .createdAt(new Date())
                                                                                                                   .build())
                                                                                   .state(CloudBuild.State.STAGED)
                                                                                   .dropletInfo(ImmutableDropletInfo.builder()
                                                                                                                    .guid(UUID.randomUUID())
                                                                                                                    .build())
                                                                                   .build()),
                                      StepPhase.DONE, null),
                         Arguments.of(Collections.singletonList(ImmutableCloudBuild.builder()
                                                                                   .metadata(ImmutableCloudMetadata.builder()
                                                                                                                   .createdAt(new Date())
                                                                                                                   .build())
                                                                                   .state(CloudBuild.State.FAILED)
                                                                                   .dropletInfo(ImmutableDropletInfo.builder()
                                                                                                                    .guid(UUID.randomUUID())
                                                                                                                    .build())
                                                                                   .build()),
                                      StepPhase.POLL, UPLOAD_TOKEN),
                         Arguments.of(Collections.singletonList(ImmutableCloudBuild.builder()
                                                                                   .state(CloudBuild.State.STAGING)
                                                                                   .dropletInfo(ImmutableDropletInfo.builder()
                                                                                                                    .guid(UUID.randomUUID())
                                                                                                                    .build())
                                                                                   .build()),
                                      StepPhase.POLL, UPLOAD_TOKEN));
    }

    @BeforeEach
    public void setUp() throws Exception {
        prepareFileService();
        prepareContext();
    }

    @SuppressWarnings("rawtypes")
    private void prepareFileService() throws Exception {
        appFile = new File(tempDir.toString() + File.separator + APP_FILE);
        if (!appFile.exists()) {
            appFile.createNewFile();
        }
        doAnswer(invocation -> {
            FileContentProcessor contentProcessor = invocation.getArgument(2);
            return contentProcessor.process(null);
        }).when(fileService)
          .processFileContent(anyString(), anyString(), any());
    }

    private void prepareContext() {
        CloudApplicationExtended app = ImmutableCloudApplicationExtended.builder()
                                                                        .metadata(ImmutableCloudMetadata.builder()
                                                                                                        .guid(APP_GUID)
                                                                                                        .build())
                                                                        .name(APP_NAME)
                                                                        .moduleName(APP_NAME)
                                                                        .build();
        context.setVariable(Variables.APP_TO_PROCESS, app);
        context.setVariable(Variables.MODULES_INDEX, 0);
        context.setVariable(Variables.APP_ARCHIVE_ID, APP_ARCHIVE);
        context.setVariable(Variables.SPACE_GUID, SPACE);
        mtaArchiveElements.addModuleFileName(APP_NAME, APP_FILE);
        context.setVariable(Variables.MTA_ARCHIVE_ELEMENTS, mtaArchiveElements);
        context.setVariable(Variables.VCAP_APP_PROPERTIES_CHANGED, false);
        when(configuration.getMaxResourceFileSize()).thenReturn(ApplicationConfiguration.DEFAULT_MAX_RESOURCE_FILE_SIZE);
    }

    @AfterEach
    public void tearDown() {
        FileUtils.deleteQuietly(appFile.getParentFile());
    }

    @Test
    void testSuccessfulUpload() throws Exception {
        prepareClients(null, null, NEW_MODULE_DIGEST);
        step.execute(execution);
        assertEquals(UPLOAD_TOKEN, context.getVariable(Variables.UPLOAD_TOKEN));
        assertEquals(StepPhase.POLL.toString(), getExecutionStatus());
    }

    @MethodSource
    @ParameterizedTest
    void testFailedUploadWithException(String expectedIOExceptionMessage, String expectedCFExceptionMessage) throws Exception {
        prepareClients(expectedIOExceptionMessage, expectedCFExceptionMessage, NEW_MODULE_DIGEST);
        assertThrows(SLException.class, () -> step.execute(execution));
        assertFalse(appFile.exists());
        assertNull(context.getVariable(Variables.UPLOAD_TOKEN));
        assertEquals(StepPhase.RETRY.toString(), getExecutionStatus());
    }

    @Test
    void testWithAvailableValidCloudPackage() throws Exception {
        prepareClients(null, null, CURRENT_MODULE_DIGEST);
        mockCloudPackagesGetter(createCloudPackage(Status.PROCESSING_UPLOAD));
        step.execute(execution);
        UploadToken uploadToken = context.getVariable(Variables.UPLOAD_TOKEN);
        assertEquals(PACKAGE_GUID, uploadToken.getPackageGuid());
        assertEquals(StepPhase.POLL.toString(), getExecutionStatus());
    }

    @Test
    void testWithAvailableFailedLatestPackageAndNonChangedApplicationContent() throws Exception {
        prepareClients(null, null, CURRENT_MODULE_DIGEST);
        mockCloudPackagesGetter(createCloudPackage(Status.FAILED));
        step.execute(execution);
        assertEquals(UPLOAD_TOKEN, context.getVariable(Variables.UPLOAD_TOKEN));
        assertEquals(StepPhase.POLL.toString(), getExecutionStatus());
    }

    @MethodSource
    @ParameterizedTest
    void testWithAvailableExpiredCloudPackageAndDifferentContent(String moduleDigest) throws Exception {
        prepareClients(null, null, moduleDigest);
        mockCloudPackagesGetter(createCloudPackage(Status.EXPIRED));
        step.execute(execution);
        assertEquals(UPLOAD_TOKEN, context.getVariable(Variables.UPLOAD_TOKEN));
        assertEquals(StepPhase.POLL.toString(), getExecutionStatus());
    }

    @MethodSource
    @ParameterizedTest
    void testWithBuildStates(List<CloudBuild> builds, StepPhase stepPhase, UploadToken uploadToken) throws Exception {
        when(client.getBuildsForApplication(any())).thenReturn(builds);
        prepareClients(null, null, CURRENT_MODULE_DIGEST);
        step.execute(execution);
        assertEquals(uploadToken, context.getVariable(Variables.UPLOAD_TOKEN));
        assertEquals(stepPhase.toString(), getExecutionStatus());
    }

    private CloudPackage createCloudPackage(Status status) {
        ImmutableCloudMetadata cloudMetadata = ImmutableCloudMetadata.builder()
                                                                     .guid(PACKAGE_GUID)
                                                                     .build();
        return ImmutableCloudPackage.builder()
                                    .metadata(cloudMetadata)
                                    .status(status)
                                    .build();
    }

    private void mockCloudPackagesGetter(CloudPackage cloudPackage) {
        when(cloudPackagesGetter.getLatestUnusedPackage(any(), any())).thenReturn(Optional.of(cloudPackage));
    }

    private void prepareClients(String expectedIOExceptionMessage, String expectedCFExceptionMessage, String applicationDigest)
        throws Exception {
        if (expectedIOExceptionMessage == null && expectedCFExceptionMessage == null) {
            when(client.asyncUploadApplication(eq(APP_NAME), eq(appFile), any())).thenReturn(UPLOAD_TOKEN);
        } else if (expectedIOExceptionMessage != null) {
            when(client.asyncUploadApplication(eq(APP_NAME), eq(appFile), any())).thenThrow(IO_EXCEPTION);
        } else {
            when(client.asyncUploadApplication(eq(APP_NAME), eq(appFile), any())).thenThrow(CO_EXCEPTION);
        }
        CloudApplicationExtended application = createApplication(applicationDigest);
        when(client.getApplication(APP_NAME)).thenReturn(application);
    }

    private CloudApplicationExtended createApplication(String digest) {
        Map<String, Object> deployAttributes = new HashMap<>();
        deployAttributes.put(Constants.ATTR_APP_CONTENT_DIGEST, digest);
        return ImmutableCloudApplicationExtended.builder()
                                                .metadata(ImmutableCloudMetadata.builder()
                                                                                .guid(APP_GUID)
                                                                                .build())
                                                .name(UploadAppStepGeneralTest.APP_NAME)
                                                .moduleName(UploadAppStepGeneralTest.APP_NAME)
                                                .putEnv(Constants.ENV_DEPLOY_ATTRIBUTES, JsonUtil.toJson(deployAttributes))
                                                .build();
    }

    @Override
    protected UploadAppStep createStep() {
        return new UploadAppStepMock();
    }

    private class UploadAppStepMock extends UploadAppStep {

        public UploadAppStepMock() {
            applicationArchiveReader = getApplicationArchiveReader();
            applicationZipBuilder = getApplicationZipBuilder(applicationArchiveReader);
            cloudPackagesGetter = UploadAppStepGeneralTest.this.cloudPackagesGetter;
        }

        @Override
        protected ApplicationArchiveContext createApplicationArchiveContext(InputStream appArchiveStream, String fileName, long maxSize) {
            return super.createApplicationArchiveContext(getClass().getResourceAsStream(APP_ARCHIVE), fileName, maxSize);
        }

        private ApplicationArchiveReader getApplicationArchiveReader() {
            return new ApplicationArchiveReader();
        }

        private ApplicationZipBuilder getApplicationZipBuilder(ApplicationArchiveReader applicationArchiveReader) {
            return new ApplicationZipBuilder(applicationArchiveReader) {
                @Override
                protected Path createTempFile() {
                    return appFile.toPath();
                }
            };
        }

    }

}