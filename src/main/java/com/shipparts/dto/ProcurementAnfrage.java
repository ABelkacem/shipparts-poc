package com.shipparts.dto;

/**
 * Structured intake request from an ERP / Procurement system (REQ-IN-02).
 */
public record ProcurementAnfrage(
        String bestellNummer,   // PO number from ERP
        String system,          // Source system name, e.g. "SAP", "Oracle"
        String artikelNr,       // Optional: direct article number
        String beschreibung,    // Free-text part description
        String hersteller,      // Optional: manufacturer
        String maschinentyp,    // Optional: machine/engine type
        Integer menge,          // Requested quantity
        String lieferadresse,   // Optional: delivery address
        String kontaktEmail,    // Contact email for response routing (REQ-IN-04)
        String anforderungsText // Optional: additional free text
) {}
