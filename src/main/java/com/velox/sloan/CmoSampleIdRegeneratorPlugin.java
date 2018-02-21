package com.velox.sloan;

import com.velox.api.datarecord.DataRecord;
import com.velox.api.datarecord.InvalidValue;
import com.velox.api.datarecord.IoError;
import com.velox.api.datarecord.NotFound;
import com.velox.api.plugin.PluginResult;
import com.velox.api.util.ServerException;
import com.velox.sapioutils.server.plugin.DefaultGenericPlugin;
import com.velox.sapioutils.shared.enums.PluginOrder;
import org.mskcc.util.lims.LimsPluginUtils;
import org.mskcc.util.rest.Header;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthorizationInterceptor;
import org.springframework.web.client.RestTemplate;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public class CmoSampleIdRegeneratorPlugin extends DefaultGenericPlugin {
    private RestTemplate restTemplate;

    private String limsRestUrl;
    private String getCmoIdEndpoint;
    private String propertiesFilePath = "sapio/exemplarlims/plugins/cmo-sample-id-regeneration.properties";

    public CmoSampleIdRegeneratorPlugin() {
        setTaskSubmit(true);
        setOrder(PluginOrder.LATE.getOrder() - 5);
    }

    @Override
    protected boolean shouldRun() throws Throwable {
        return new LimsPluginUtils(managerContext).shouldRunPlugin();
    }

    @Override
    protected PluginResult run() throws Throwable {
        try {
            init();
            List<DataRecord> sampleCMOInfoRecords = activeTask.getAttachedDataRecords("SampleCMOInfoRecords", user);
            logDebug("Regenerating CMO Sample Ids for all attached Sample CMO Info Records");
            for (DataRecord sampleCMOInfoRecord : sampleCMOInfoRecords) {
                regenerateCMOSampleId(sampleCMOInfoRecord);
            }
        } catch (Throwable e) {
            logError(String.format("Unable to regenerate CMO Sample Id for workflow: %s, task: %s", activeWorkflow
                    .getActiveWorkflowName(), activeTask.getFullName()), e);
            displayError(String.format("Error while trying to regenerate CMO Sample Id: %s", e.getMessage()));

            return new PluginResult(false);
        }

        return new PluginResult(true);
    }

    private void regenerateCMOSampleId(DataRecord sampleCMOInfoRecord) throws RemoteException, IoError, NotFound,
            ServerException, InvalidValue {
        String userSampleID = sampleCMOInfoRecord.getStringVal("UserSampleID", user);
        String currentCmoSampleId = sampleCMOInfoRecord.getStringVal("CorrectedCMOID", user);

        logDebug(String.format("Regenerating CMO Sample id for sample: %s", userSampleID));

        String url = getUrl(sampleCMOInfoRecord, userSampleID);
        ResponseEntity<String> cmoSampleIdResponse = restTemplate.getForEntity(url, String.class);
        validateResponse(userSampleID, cmoSampleIdResponse);

        String cmoSampleId = cmoSampleIdResponse.getBody();
        if (isCMOSampleIdDifferent(currentCmoSampleId, cmoSampleId)) {
            replaceOldValueIfAccepted(sampleCMOInfoRecord, userSampleID, currentCmoSampleId, cmoSampleId);
        }
    }

    private void validateResponse(String userSampleID, ResponseEntity<String> cmoSampleIdResponse) {
        if (hasErrors(cmoSampleIdResponse))
            throw new RuntimeException(String.format("CMO Sample Id for sample %s couldn't be retrieved. Cause: %s",
                    userSampleID, cmoSampleIdResponse.getHeaders().get(Header.ERRORS.name())));
    }

    private void replaceOldValueIfAccepted(DataRecord sampleCMOInfoRecord, String userSampleID, String
            currentCmoSampleId, String cmoSampleId) throws ServerException, IoError, InvalidValue, NotFound,
            RemoteException {
        String message = String.format("CMO Sample Id for sample %s is going to change.\n\n%s - " +
                "current value\n%s - new value", userSampleID, currentCmoSampleId, cmoSampleId);
        logInfo(message);

        String popupMessage = String.format("%s. \n\nIf you don't want to save that changes, cancel " +
                "the workflow", message, userSampleID);

        boolean showOkCancelDialog = clientCallback.showOkCancelDialog("CMO Sample Id changes!",
                popupMessage);

        if (!showOkCancelDialog)
            throw new RuntimeException(String.format("CMO Sample Id changes not accepted for sample: " +
                    "%s", userSampleID));
        sampleCMOInfoRecord.setDataField("CorrectedCMOID", cmoSampleId, user);
    }

    private String getUrl(DataRecord sampleCMOInfoRecord, String userSampleID) throws
            IoError, RemoteException, NotFound {
        String cmoPatientId = sampleCMOInfoRecord.getStringVal("CmoPatientId", user);
        String sampleClass = sampleCMOInfoRecord.getStringVal("CMOSampleClass", user);
        String sampleOrigin = sampleCMOInfoRecord.getStringVal("SampleOrigin", user);
        String specimenType = sampleCMOInfoRecord.getStringVal("SpecimenType", user);
        String requestId = sampleCMOInfoRecord.getStringVal("RequestId", user);
        String igoId = sampleCMOInfoRecord.getStringVal("SampleId", user);

        DataRecord parentSample = retrieveParentSample(sampleCMOInfoRecord);
        String nucleidAcid = parentSample.getStringVal("NAtoExtract", user);

        return String.format("%s/%s?igoId=%s&userSampleId=%s&requestId=%s&patientId=%s&sampleClass=%s&sampleOrigin" +
                        "=%s&specimenType=%s&nucleidAcid=%s", limsRestUrl, getCmoIdEndpoint, igoId, userSampleID,
                requestId, cmoPatientId, sampleClass, sampleOrigin, specimenType, nucleidAcid);
    }

    private boolean isCMOSampleIdDifferent(String currentCmoSampleId, String cmoSampleId) {
        return !Objects.equals(cmoSampleId, currentCmoSampleId);
    }

    private void init() {
        if (restTemplate == null) {
            try (InputStream input = new FileInputStream(propertiesFilePath)) {
                Properties prop = new Properties();
                prop.load(input);
                limsRestUrl = prop.getProperty("lims.rest.url");
                String limsRestUsername = prop.getProperty("lims.rest.username");
                String limsRestPassword = prop.getProperty("lims.rest.password");
                getCmoIdEndpoint = prop.getProperty("lims.rest.cmoid.endpoint");
                restTemplate = new RestTemplate();

                addBasicAuth(restTemplate, limsRestUsername, limsRestPassword);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot read properties file with lims rest connection.", ex);
            }
        }
    }

    private DataRecord retrieveParentSample(DataRecord sampleCMOInfoRecord) throws IoError, RemoteException, NotFound {
        String igoId = sampleCMOInfoRecord.getStringVal("SampleId", user);

        List<DataRecord> parentSamples = sampleCMOInfoRecord.getParentsOfType("Sample", user);
        for (DataRecord parentSample : parentSamples) {
            String parentSampleId = parentSample.getStringVal("SampleId", user);
            if (Objects.equals(parentSampleId, igoId))
                return parentSample;
        }

        throw new RuntimeException(String.format("No parent samples found for Sample Level Info record with igo id: " +
                "%s", igoId));
    }

    private boolean hasErrors(ResponseEntity<String> cmoSampleIdResponse) {
        return cmoSampleIdResponse.getHeaders().containsKey(Header.ERRORS.name()) && cmoSampleIdResponse.getHeaders()
                .get(Header.ERRORS.name()).size() > 0;
    }

    private void addBasicAuth(RestTemplate restTemplate, String username, String password) {
        List<ClientHttpRequestInterceptor> interceptors = Collections.singletonList(new BasicAuthorizationInterceptor
                (username, password));
        restTemplate.setRequestFactory(new InterceptingClientHttpRequestFactory(restTemplate.getRequestFactory(),
                interceptors));
    }
}
