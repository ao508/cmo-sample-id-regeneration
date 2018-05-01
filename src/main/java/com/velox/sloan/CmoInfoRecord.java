package com.velox.sloan;

import com.velox.api.datarecord.DataRecord;
import org.mskcc.domain.sample.CorrectedCmoSampleView;

class CmoInfoRecord {
    private final DataRecord record;
    private String currentCmoId;
    private String newCmoId;
    private CorrectedCmoSampleView correctedCmoSampleView;

    CmoInfoRecord(DataRecord record) {
        this.record = record;
    }

    public DataRecord getRecord() {
        return record;
    }

    public String getCurrentCmoId() {
        return currentCmoId;
    }

    public void setCurrentCmoId(String currentCmoId) {
        this.currentCmoId = currentCmoId;
    }

    public String getNewCmoId() {
        return newCmoId;
    }

    public void setNewCmoId(String newCmoId) {
        this.newCmoId = newCmoId;
    }

    public CorrectedCmoSampleView getCorrectedCmoSampleView() {
        return correctedCmoSampleView;
    }

    public void setCorrectedCmoSampleView(CorrectedCmoSampleView correctedCmoSampleView) {
        this.correctedCmoSampleView = correctedCmoSampleView;
    }
}
