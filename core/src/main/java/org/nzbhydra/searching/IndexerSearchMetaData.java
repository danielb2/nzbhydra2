package org.nzbhydra.searching;

import lombok.Data;

@Data
public class IndexerSearchMetaData {

    private boolean didSearch;
    private String errorMessage;
    private boolean hasMoreResults;
    private String indexerName;
    private int limit;
    private String notPickedReason;
    private int numberOfAvailableResults;
    private int numberOfFoundResults;
    private int offset;
    private long responseTime;
    private boolean totalResultsKnown;
    private boolean wasSuccessful;

}
