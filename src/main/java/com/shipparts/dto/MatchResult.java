package com.shipparts.dto;

import com.shipparts.domain.Artikel;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single match result from SQL direct search or semantic similarity search.
 */
@Data
@Builder
public class MatchResult {

    private UUID artikelId;
    private String artikelNr;
    private String beschreibung;
    private String hersteller;
    private String maschinentyp;
    private BigDecimal preisEur;
    private Integer bestand;
    private Integer lieferzeitTage;

    /** Confidence score: 1.0 for direct SQL match, 0.0–1.0 for semantic */
    private double konfidenz;

    /** How the match was found */
    private MatchType matchType;

    public enum MatchType {
        DIRECT_ARTIKELNR,    // exact artikel_nr match
        SEMANTIC_EMBEDDING,  // pgvector cosine similarity
        KEYWORD_FALLBACK     // text LIKE fallback
    }

    public static MatchResult fromArtikel(Artikel a, double konfidenz, MatchType type) {
        return MatchResult.builder()
                .artikelId(a.getId())
                .artikelNr(a.getArtikelNr())
                .beschreibung(a.getBeschreibungLang())
                .hersteller(a.getHersteller())
                .maschinentyp(a.getMaschinentyp())
                .preisEur(a.getPreisEur())
                .bestand(a.getBestand())
                .lieferzeitTage(a.getLieferzeitTage())
                .konfidenz(konfidenz)
                .matchType(type)
                .build();
    }
}
