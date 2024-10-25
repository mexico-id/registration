package io.mosip.registration.processor.core.curpbiomanager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CurpBioDtorequest {
    private String curpId;
    private List<String> matchedCurpIds;
    private String statusCode;
    private String statusComment;
    private String curpStatus;
    private Boolean isLatestBio;
    private String curpType;
}
