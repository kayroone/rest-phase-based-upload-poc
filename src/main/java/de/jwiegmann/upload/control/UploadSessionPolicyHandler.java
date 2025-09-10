package de.jwiegmann.upload.control;

import de.jwiegmann.upload.boundary.dto.batch.BatchUploadResult;
import de.jwiegmann.upload.boundary.dto.init.UploadSession;
import de.jwiegmann.upload.boundary.dto.status.BatchUploadResultStatus;
import de.jwiegmann.upload.boundary.dto.status.UploadSessionStatus;
import org.springframework.stereotype.Component;

@Component
public class UploadSessionPolicyHandler {

    public BatchUploadResult validateSessionPolicy(UploadSession session, int seqNo) {

        // Session ist bereits geschlossen
        if (session.getStatus() == UploadSessionStatus.SEALED) {
            return null; // Weiter mit handleSealedItem
        }

        // Session nicht aktiv
        if (session.getStatus() != UploadSessionStatus.ACTIVE) {
            return BatchUploadResult.builder()
                    .seqNo(seqNo)
                    .status(BatchUploadResultStatus.CONFLICT)
                    .error(UploadErrorFactory.uploadSessionNotOpen(session.getUploadId()))
                    .build();
        }

        return null; // Session OK, normale Verarbeitung geht weiter
    }
}
