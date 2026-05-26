package com.shipparts.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "match_feedback")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anfrage_id", nullable = false)
    private Anfrage anfrage;

    /** KI-suggested article */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artikel_id")
    private Artikel artikel;

    @Column(precision = 5, scale = 4)
    private BigDecimal konfidenz;

    private Boolean bestaetigt = false;

    /** Human-corrected article (if different from KI suggestion) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "korrektur_id")
    private Artikel korrektur;

    private String reviewer;

    /** Fields added/corrected by human reviewer */
    @Column(name = "merkmale_ergaenzt", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> merkmaleErgaenzt;

    @Column(name = "interne_notiz")
    private String interneNotiz;

    @Column(name = "created_at")
    private Instant createdAt;
}
