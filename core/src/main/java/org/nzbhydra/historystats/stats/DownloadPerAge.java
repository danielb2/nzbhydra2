package org.nzbhydra.historystats.stats;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DownloadPerAge {
    private Integer age = null;
    private Integer count = null;
}
