package com.shipparts.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "artikel")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Artikel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "artikel_nr", unique = true, nullable = false)
    private String artikelNr;

    @Column(name = "beschreibung_lang", nullable = false)
    private String beschreibungLang;

    @Column(name = "beschreibung_kurz")
    private String beschreibungKurz;

    private String hersteller;
    private String maschinentyp;

    @Column(columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> tags;

    /** Synonyme werden vom Feedback-Loop befüllt */
    @Column(columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> synonyme;

    @Column(name = "technische_merkmale", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> technischeMerkmale;

    @Column(name = "preis_eur", precision = 12, scale = 2)
    private BigDecimal preisEur;

    @Column(nullable = false)
    private Integer bestand = 0;

    @Column(name = "lieferzeit_tage")
    private Integer lieferzeitTage;

    @Column(name = "erp_updated_at")
    private Instant erpUpdatedAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "created_at")
    private Instant createdAt;
}
