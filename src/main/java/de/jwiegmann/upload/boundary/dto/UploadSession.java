package de.jwiegmann.upload.boundary.dto;

import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadSession {

    private String sessionId;
    private UploadSessionStatus status;   // ACTIVE | SEALED | COMPLETED | ABORTED

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    // Metadaten (eine VSL pro Session)
    private String bewNr;
    private String vslNummer;

    // Fortschritt
    private int expectedCount;   // Anzahl der Datensaetze insgesamt
    private int receivedCount;   // Anzahl bereits angenommener Items
}
