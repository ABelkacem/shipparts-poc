package com.shipparts.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "anfrage")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Anfrage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "email_from")
    private String emailFrom;

    @Column(name = "email_subject")
    private String emailSubject;

    @Column(name = "email_body_raw", columnDefinition = "TEXT")
    private String emailBodyRaw;

    /** Extracted text from all PDF attachments */
    @Column(name = "pdf_text", columnDefinition = "TEXT")
    private String pdfText;

    /** TEXT_PDF | SCANNED | FORM | IMAGE | NONE */
    @Column(name = "pdf_type")
    private String pdfType;

    /** JSON result from LLM extraction */
    @Column(name = "extrakt_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> extraktJson;

    @Column(name = "kanal", nullable = false)
    @Enumerated(EnumType.STRING)
    private Kanal kanal = Kanal.EMAIL;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private AnfrageStatus status = AnfrageStatus.NEW;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public enum Kanal {
        EMAIL, PROCUREMENT
    }

    public enum AnfrageStatus {
        NEW, EXTRACTING, MATCHING, REVIEW_PENDING, OFFER_SENT, REJECTED
    }
}
