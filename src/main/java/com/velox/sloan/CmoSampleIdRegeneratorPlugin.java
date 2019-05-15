package com.velox.sloan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.velox.api.datarecord.DataRecord;
import com.velox.api.datarecord.InvalidValue;
import com.velox.api.datarecord.IoError;
import com.velox.api.datarecord.NotFound;
import com.velox.api.plugin.PluginResult;
import com.velox.api.util.ServerException;
import com.velox.sapioutils.server.plugin.DefaultGenericPlugin;
import com.velox.sapioutils.shared.enums.PluginOrder;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.mskcc.domain.sample.*;
import org.mskcc.util.lims.LimsPluginUtils;
import org.mskcc.util.rest.Header;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthorizationInterceptor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Plugin being run during workflow Adding or Amending CMO Information. In case of changes in Sample Level Info
 * Record which require change in CMO sample Id, this plugin takes new entered values and retreives new CMO Sample Id
 * from List Rest and saves new value in Corrected Cmo Sample id field. Before it saves user is informed about the
 * change and can cancel it. If CMO Sample Id is changed, there is no possibility to save other fields changes
 * without changing CMO Sample Id.
 */
public class CmoSampleIdRegeneratorPlugin extends DefaultGenericPlugin {
    private static final Profile DEFAULT_PROFILE = Profile.PROD;
    private RestTemplate restTemplate;
    private String limsRestUrl;
    private String getCmoIdEndpoint;
    private String propertiesFilePath = "sapio/exemplarlims/plugins/cmo-sample-id-regeneration.properties";
    private Profile profile;
    private Multimap<String, String> sample2Errors = HashMultimap.create();

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
            Map<String, CmoInfoRecord> igoId2CmoInfoRecords = getCmoInfoRecords(sampleCMOInfoRecords);

            fillInNewCmoSampleIds(igoId2CmoInfoRecords);
            updateIdsIfUserAccepts(igoId2CmoInfoRecords);
        } catch (Throwable e) {
            logError(format("Unable to regenerate CMO Sample Id for workflow: %s, task: %s", activeWorkflow
                    .getActiveWorkflowName(), activeTask.getFullName()), e);
            displayError(format("Unable to generate CMO Sample Id: \n%s \n%s", getSampleRecordsErrors(), e.getMessage()));

            return new PluginResult(false);
        }

        return new PluginResult(true);
    }

    private String getSampleRecordsErrors() {
        StringBuilder errors = new StringBuilder();

        for (Map.Entry<String, Collection<String>> sample2Error : sample2Errors.asMap().entrySet()) {
            errors.append(sample2Error.getKey()).append(":\n");
            for (String error : sample2Error.getValue()) {
                errors.append("\t- ").append(error).append("\n");
            }
        }

        return errors.toString();
    }

    private Map<String, CmoInfoRecord> getCmoInfoRecords(List<DataRecord> sampleCMOInfoRecords) throws Exception {
        Map<String, CmoInfoRecord> igoId2CmoInfoRecords = new HashMap<>();
        validate(sampleCMOInfoRecords);

        if(sample2Errors.size() > 0)
            throw new RuntimeException();

        for (DataRecord sampleCMOInfoRecord : sampleCMOInfoRecords) {
            String igoId = sampleCMOInfoRecord.getStringVal("SampleId", user);
            igoId2CmoInfoRecords.put(igoId, getCmoInfoRecord(sampleCMOInfoRecord));
        }

        return igoId2CmoInfoRecords;
    }

    private void fillInNewCmoSampleIds(Map<String, CmoInfoRecord> igoId2CmoInfoRecords) throws JsonProcessingException {
        List<CorrectedCmoSampleView> correctedCmoSampleViews = igoId2CmoInfoRecords.values().stream()
                .map( r -> r.getCorrectedCmoSampleView())
                .collect(Collectors.toList());

        Map<String, String> igoId2CmoSampleIds = getNewCmoIds(correctedCmoSampleViews);
        for (Map.Entry<String, String> igoId2CmoSampleId : igoId2CmoSampleIds.entrySet()) {
            String igoId = igoId2CmoSampleId.getKey();
            if (!igoId2CmoInfoRecords.containsKey(igoId))
                throw new RuntimeException(String.format("Sample %s was not added to Ammend workflow", igoId));

            CmoInfoRecord cmoInfoRecord = igoId2CmoInfoRecords.get(igoId);
            cmoInfoRecord.setNewCmoId(igoId2CmoSampleId.getValue());
        }
    }

    private CmoInfoRecord getCmoInfoRecord(DataRecord sampleCMOInfoRecord) throws Exception {
        String correctedCMOID = sampleCMOInfoRecord.getStringVal("CorrectedCMOID", user);
        CmoInfoRecord cmoInfoRecord = new CmoInfoRecord(sampleCMOInfoRecord);
        cmoInfoRecord.setCurrentCmoId(correctedCMOID);
        cmoInfoRecord.setCorrectedCmoSampleView(convert(sampleCMOInfoRecord));

        return cmoInfoRecord;
    }

    private CorrectedCmoSampleView convert(DataRecord sampleCMOInfoRecord) throws Exception {
        return new SampleCMOInfoRecordToCmoSampleViewConverter().convert(sampleCMOInfoRecord);
    }

    private Map<String, String> getNewCmoIds(List<CorrectedCmoSampleView> correctedCmoSampleViews) throws
            JsonProcessingException {
        String correctedViewString = getCorrectedViewString(correctedCmoSampleViews);

        logInfo(String.format("Invoking %s with entity: %s", getUrl(), correctedViewString));

        ResponseEntity<Map<String, String>> cmoSampleIdResponse = restTemplate.exchange(getUrl(), HttpMethod.POST,
                new HttpEntity<Object>(correctedViewString, getHeaders()), getResponseType());

        validateResponse(cmoSampleIdResponse);
        Map<String, String> igoIdToCmoSampleId = cmoSampleIdResponse.getBody();
        logInfo(String.format("New corrected cmo sample ids received: %s", igoIdToCmoSampleId));

        return igoIdToCmoSampleId;
    }

    private MultiValueMap<String, String> getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ParameterizedTypeReference<Map<String, String>> getResponseType() {
        return new ParameterizedTypeReference<Map<String,
                String>>() {
        };
    }

    private String getCorrectedViewString(List<CorrectedCmoSampleView> correctedCmoSampleViews) throws
            JsonProcessingException {
        return new ObjectMapper().writeValueAsString(correctedCmoSampleViews);
    }

    private void updateIdsIfUserAccepts(Map<String, CmoInfoRecord> igoId2CmoInfoRecords) throws Exception {
        StringBuilder message = new StringBuilder("CMO Sample Ids which are going to be changed: \n");

        if(anyCmoSampleIdChanged(igoId2CmoInfoRecords)) {
            for (Map.Entry<String, CmoInfoRecord> igoIdToCmoRecord : igoId2CmoInfoRecords.entrySet()) {
                CmoInfoRecord cmoInfoRecord = igoIdToCmoRecord.getValue();
                if (cmoSampleIdRequiresChange(cmoInfoRecord))
                    message.append(String.format("%s -> %s\n", cmoInfoRecord.getCurrentCmoId(), cmoInfoRecord.getNewCmoId

                            ()));
            }

            logInfo(message.toString());

            validateUserAcceptsChanges(igoId2CmoInfoRecords, message.toString());
            updateCmoIds(igoId2CmoInfoRecords);
        }
    }

    private boolean anyCmoSampleIdChanged(Map<String, CmoInfoRecord> igoId2CmoInfoRecords) {
        return igoId2CmoInfoRecords.values().stream()
                .anyMatch(r -> !Objects.equals(r.getCurrentCmoId(), r.getNewCmoId()));
    }

    private boolean cmoSampleIdRequiresChange(CmoInfoRecord cmoInfoRecord) {
        return !Objects.equals(cmoInfoRecord.getCurrentCmoId(), cmoInfoRecord.getNewCmoId());
    }

    private void validateUserAcceptsChanges(Map<String, CmoInfoRecord> igoId2CmoInfoRecords, String message) throws ServerException {
        String popupMessage = format("%s\n\nIf you don't want to save that changes, cancel " +
                "the workflow", message);

        boolean showOkCancelDialog = clientCallback.showOkCancelDialog("CMO Sample Id changes!",
                popupMessage);

        if (!showOkCancelDialog)
            throw new RuntimeException(format("CMO Sample Id changes not accepted for: %s", igoId2CmoInfoRecords));
    }

    private void updateCmoIds(Map<String, CmoInfoRecord> igoId2CmoInfoRecords) throws IoError, InvalidValue, NotFound, RemoteException {
        for (Map.Entry<String, CmoInfoRecord> igoId2CmoRecord : igoId2CmoInfoRecords.entrySet()) {
            CmoInfoRecord cmoInfoRecord = igoId2CmoRecord.getValue();
            cmoInfoRecord.getRecord().setDataField("CorrectedCMOID", cmoInfoRecord.getNewCmoId(), user);
        }
    }

    private void validateResponse(ResponseEntity<Map<String, String>> cmoSampleIdResponse) {
        if (hasErrors(cmoSampleIdResponse))
            throw new RuntimeException(format("CMO Sample Ids  couldn't be retrieved. Cause: %s", cmoSampleIdResponse
                    .getHeaders().get(Header.ERRORS.name())));
    }

    private String getUrl() {
        return format("%s/%s", limsRestUrl, getCmoIdEndpoint);
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
                profile = getProfile(prop);
                restTemplate = getRestTemplate();

                addBasicAuth(restTemplate, limsRestUsername, limsRestPassword);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot read properties file with lims rest connection.", ex);
            }
        }
    }

    private Profile getProfile(Properties prop) {
        String profile = prop.getProperty("profile");

        try {
            return Profile.fromString(profile);
        } catch (Exception e) {
            logError(String.format("Unknown profile with name: %s. Using default one: %s", profile, DEFAULT_PROFILE));
            return DEFAULT_PROFILE;
        }
    }

    private RestTemplate getRestTemplate() {
        if (profile == Profile.DEV) {
            return getInsecureRestTemplate();
        } else {
            return new RestTemplate();
        }
    }

    /**
     * Insecure Rest Template accepting all host names in certificate. For DEV only!!!
     * Needed until certificate on tango is self signed and host name is not machine tango.mskc.org
     *
     * @return
     */
    private RestTemplate getInsecureRestTemplate() {
        try {
            TrustStrategy acceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;

            SSLContext sslContext = org.apache.http.ssl.SSLContexts.custom()
                    .loadTrustMaterial(null, acceptingTrustStrategy)
                    .build();

            SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslContext, SSLSocketFactory
                    .ALLOW_ALL_HOSTNAME_VERIFIER);

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setSSLSocketFactory(csf)
                    .build();

            HttpComponentsClientHttpRequestFactory requestFactory =
                    new HttpComponentsClientHttpRequestFactory();

            requestFactory.setHttpClient(httpClient);

            return new RestTemplate(requestFactory);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void validateIgoId(DataRecord sampleCMOInfoRecord, String igoId) {
        if (StringUtils.isEmpty(igoId))
            throw new RuntimeException(format("Igo id is empty for record: %%s%d", sampleCMOInfoRecord
                    .getRecordId()));
    }

    private boolean hasErrors(ResponseEntity<Map<String, String>> cmoSampleIdResponse) {
        return cmoSampleIdResponse.getHeaders().containsKey(Header.ERRORS.name()) && cmoSampleIdResponse.getHeaders()
                .get(Header.ERRORS.name()).size() > 0;
    }

    private void addBasicAuth(RestTemplate restTemplate, String username, String password) {
        List<ClientHttpRequestInterceptor> interceptors = Collections.singletonList(new BasicAuthorizationInterceptor
                (username, password));
        restTemplate.setRequestFactory(new InterceptingClientHttpRequestFactory(restTemplate.getRequestFactory(),
                interceptors));
    }

    private void validate(List<DataRecord> sampleCMOInfoRecords) throws NotFound, RemoteException, IoError  {
        for (DataRecord sampleCMOInfoRecord : sampleCMOInfoRecords) {
            validate(sampleCMOInfoRecord);
        }
    }

    private void validate(DataRecord sampleCMOInfoRecord) throws NotFound, RemoteException {
        String igoId = sampleCMOInfoRecord.getStringVal("SampleId", user);
        String specimenTypeStr = sampleCMOInfoRecord.getStringVal("SpecimenType", user);

        if (!StringUtils.isEmpty(specimenTypeStr)) {
            try {
                SpecimenType specimenType = SpecimenType.fromValue(specimenTypeStr);

                if(specimenType == SpecimenType.CELLLINE) {
                    String requestId = sampleCMOInfoRecord.getStringVal("RequestId", user);
                    if(requestId.isEmpty())
                        sample2Errors.put(igoId, "Request is id empty");
                } else {
                    validateCmoPatientId(sampleCMOInfoRecord, igoId);
                    validateCmoSampleClass(sampleCMOInfoRecord, igoId);
                    validateSampleOrigin(sampleCMOInfoRecord, igoId);

                    DataRecord parentSample = retrieveParentSample(sampleCMOInfoRecord);

                    //validateNucleidAcid(igoId, parentSample);
                    validateSampleType(igoId, parentSample);
                }
            } catch (Exception e) {
                sample2Errors.put(igoId, e.getMessage());
            }
        } else {
            sample2Errors.put(igoId, "Specimen Type is empty");
        }
    }

    private void validateSampleType(String igoId, DataRecord parentSample) {
        try {
            SampleType.fromString(parentSample.getStringVal(Sample.EXEMPLAR_SAMPLE_TYPE, user));
        } catch (Exception e) {
            sample2Errors.put(igoId, e.getMessage());
        }
    }

    private void validateNucleidAcid(String igoId, DataRecord parentSample) throws NotFound, RemoteException {
        String naToExtract = parentSample.getStringVal(Sample.NATO_EXTRACT, user);
        if (!StringUtils.isEmpty(naToExtract)) {
            try {
                NucleicAcid.fromValue(naToExtract);
            } catch (Exception e) {
                sample2Errors.put(igoId, e.getMessage());
            }
        } else {
            sample2Errors.put(igoId, "Nucleic acid is empty");
        }
    }

    private void validateSampleOrigin(DataRecord sampleCMOInfoRecord, String igoId) throws NotFound, RemoteException {
        String sampleOrigin = sampleCMOInfoRecord.getStringVal("SampleOrigin", user);
        if (!StringUtils.isEmpty(sampleOrigin)) {
            try {
                SampleOrigin.fromValue(sampleOrigin);
            } catch (Exception e) {
                sample2Errors.put(igoId, e.getMessage());
            }
        }
    }

    private void validateCmoPatientId(DataRecord sampleCMOInfoRecord, String igoId) throws NotFound, RemoteException {
        String cmoPatientId = sampleCMOInfoRecord.getStringVal("CmoPatientId", user);

        if(cmoPatientId.isEmpty())
            sample2Errors.put(igoId, "Cmo Patient id is empty");
    }

    private void validateCmoSampleClass(DataRecord sampleCMOInfoRecord, String igoId) throws NotFound, RemoteException {
        String cmoSampleClass = sampleCMOInfoRecord.getStringVal("CMOSampleClass", user);
        if (!StringUtils.isEmpty(cmoSampleClass)) {
            try {
                SampleClass.fromValue(cmoSampleClass);
            } catch (Exception e) {
                sample2Errors.put(igoId, e.getMessage());
            }
        }
    }

    private DataRecord retrieveParentSample(DataRecord sampleCMOInfoRecord) throws IoError, RemoteException, NotFound {
        String igoId = sampleCMOInfoRecord.getStringVal("SampleId", user);
        validateIgoId(sampleCMOInfoRecord, igoId);

        List<DataRecord> parentSamples = sampleCMOInfoRecord.getParentsOfType("Sample", user);
        for (DataRecord parentSample : parentSamples) {
            String parentSampleId = parentSample.getStringVal("SampleId", user);
            if (Objects.equals(parentSampleId, igoId))
                return parentSample;
        }

        throw new RuntimeException(format("No parent samples found for Sample Level Info record with igo id: " +
                "%s", igoId));
    }

    private class SampleCMOInfoRecordToCmoSampleViewConverter {
        public CorrectedCmoSampleView convert(DataRecord sampleCMOInfoRecord) throws NotFound, RemoteException, IoError {
            String igoId = sampleCMOInfoRecord.getStringVal("SampleId", user);

            CorrectedCmoSampleView correctedCmoSampleView = new CorrectedCmoSampleView(igoId);

            correctedCmoSampleView.setPatientId(sampleCMOInfoRecord.getStringVal("CmoPatientId", user));
            correctedCmoSampleView.setSampleId(sampleCMOInfoRecord.getStringVal("UserSampleID", user));
            correctedCmoSampleView.setCorrectedCmoId(sampleCMOInfoRecord.getStringVal("CorrectedCMOID", user));

            String cmoSampleClass = sampleCMOInfoRecord.getStringVal("CMOSampleClass", user);
            if (!StringUtils.isEmpty(cmoSampleClass))
                correctedCmoSampleView.setSampleClass(SampleClass.fromValue(cmoSampleClass));

            String sampleOrigin = sampleCMOInfoRecord.getStringVal("SampleOrigin", user);
            if (!StringUtils.isEmpty(sampleOrigin))
                correctedCmoSampleView.setSampleOrigin(SampleOrigin.fromValue(sampleOrigin));

            String specimenType = sampleCMOInfoRecord.getStringVal("SpecimenType", user);

            if (!StringUtils.isEmpty(specimenType))
                correctedCmoSampleView.setSpecimenType(SpecimenType.fromValue(specimenType));

            correctedCmoSampleView.setRequestId(sampleCMOInfoRecord.getStringVal("RequestId", user));

            DataRecord parentSample = retrieveParentSample(sampleCMOInfoRecord);

            String naToExtract = parentSample.getStringVal(Sample.NATO_EXTRACT, user);
            if (!StringUtils.isEmpty(naToExtract))
                correctedCmoSampleView.setNucleidAcid(NucleicAcid.fromValue(naToExtract));

            SampleType sampleType = SampleType.fromString(parentSample.getStringVal(Sample.EXEMPLAR_SAMPLE_TYPE, user));
            correctedCmoSampleView.setSampleType(sampleType);

            logInfo(String.format("Sample CMO Info record for sample %s converted: %s", igoId, correctedCmoSampleView));

            return correctedCmoSampleView;
        }


    }
}
