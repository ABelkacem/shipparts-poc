package com.shipparts.controller;

import com.shipparts.domain.Anfrage;
import com.shipparts.dto.ProcurementAnfrage;
import com.shipparts.service.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Intake endpoints for all non-email channels (REQ-IN-02, REQ-IN-03, REQ-IN-04).
 *
 * POST /api/intake/procurement — accepts structured requests from ERP/Procurement systems.
 * Both channels feed the same PipelineOrchestrator (unified processing, REQ-IN-03).
 */
@RestController
@RequestMapping("/api/intake")
@RequiredArgsConstructor
public class IntakeController {

    private final PipelineOrchestrator pipeline;

    /**
     * Accept a procurement/ERP request and run it through the unified pipeline.
     *
     * Example:
     *   curl -X POST http://localhost:8080/api/intake/procurement \
     *     -H "Content-Type: application/json" \
     *     -d '{"bestellNummer":"PO-2024-001","artikelNr":"WAR-FIV-2234","menge":2,
     *          "beschreibung":"Fuel injection valve","kontaktEmail":"buyer@shipping.com"}'
     */
    @PostMapping("/procurement")
    public ResponseEntity<ProcurementResponse> processProcurement(
            @RequestBody ProcurementAnfrage request) {

        Anfrage result = pipeline.processProcurement(request);
        return ResponseEntity.ok(ProcurementResponse.from(result, request));
    }

    public record ProcurementResponse(
            UUID id,
            String status,
            String kanal,
            String bestellNummer,
            String artikelNr,
            Integer menge,
            String kontaktEmail) {

        static ProcurementResponse from(Anfrage a, ProcurementAnfrage req) {
            return new ProcurementResponse(
                    a.getId(),
                    a.getStatus().name(),
                    a.getKanal().name(),
                    req.bestellNummer(),
                    req.artikelNr(),
                    req.menge(),
                    req.kontaktEmail());
        }
    }
}
