package de.jwiegmann.upload.boundary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadInitRequest {
    private String bewNr;
    private String vslNummer;
    private int anzahlDatensaetzeGesamt;
    private String erstellungsdatum;
}
