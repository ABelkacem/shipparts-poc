package com.shipparts.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Structured extraction result from the LLM.
 * Maps directly to the JSON schema enforced in the system prompt.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractionResult {

    @JsonProperty("artikel_nummer")
    private String artikelNummer;

    @JsonProperty("artikel_beschreibung")
    private String artikelBeschreibung;

    @JsonProperty("menge")
    private Integer menge;

    @JsonProperty("einheit")
    private String einheit;           // Stück | Satz | kg | Liter

    @JsonProperty("hersteller")
    private String hersteller;

    @JsonProperty("maschinentyp")
    private String maschinentyp;

    @JsonProperty("schiffsname")
    private String schiffsname;

    @JsonProperty("hafen_lieferung")
    private String hafenLieferung;

    @JsonProperty("dringlichkeit")
    private String dringlichkeit;     // urgent | normal | null

    @JsonProperty("lieferdatum_gewuenscht")
    private String lieferdatumGewuenscht;  // ISO date string

    @JsonProperty("konfidenz_artikelnr")
    private Double konfidenzArtikelnr;    // 0.0 – 1.0 LLM self-assessment

    public boolean hasArtikelNummer() {
        return artikelNummer != null && !artikelNummer.isBlank();
    }

    public boolean isUrgent() {
        return "urgent".equalsIgnoreCase(dringlichkeit);
    }
}
