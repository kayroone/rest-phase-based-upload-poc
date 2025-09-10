package de.jwiegmann.upload.boundary.dto.status;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadStatusListResponse {
    private int total;                                   // Anzahl gefundener Uploads
    private List<UploadStatusResponse> items;            // Status-Daten je Upload
}
