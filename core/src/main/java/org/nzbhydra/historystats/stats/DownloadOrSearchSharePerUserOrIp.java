package org.nzbhydra.historystats.stats;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DownloadOrSearchSharePerUserOrIp {
    private String key = null;
    private int count;
    private float percentage;
}
