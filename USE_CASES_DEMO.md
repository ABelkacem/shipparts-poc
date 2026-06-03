# ShipParts AI Pipeline — Use Cases & Kunden-Demo

Dieses Dokument beschreibt alle Use Cases der KI-Pipeline und liefert ein **fertiges Drehbuch** für die Kunden-Demo.

---

## Teil 1 — Use Cases im Überblick

### UC-1: Direkter Artikel-Match (High Confidence)
**Szenario:** Kunde nennt in der E-Mail eine exakte Artikel-Nummer aus dem ERP.

- **Input:** E-Mail mit Text wie `"Part-Nr WAR-FIV-2234"`
- **KI-Pfad:** Extraktion → direkter Lookup in `artikel` Tabelle
- **Konfidenz:** 1.0
- **Ergebnis:** Status `READY_TO_SEND` oder `OFFER_SENT` — kein Human Review nötig
- **Nutzen:** Vollautomatisch, Bearbeitungszeit Sekunden statt Minuten

### UC-2: Semantischer Match (Beschreibung statt Nummer)
**Szenario:** Kunde beschreibt das Teil mit eigenen Worten, evtl. in anderer Sprache.

- **Input:** `"Turbolader-Lager für MAN B&W Motor"` oder `"injection pump Wartsila"`
- **KI-Pfad:** Extraktion → Vektor-Embedding → pgvector-Ähnlichkeitssuche
- **Konfidenz:** 0.5–0.85 je nach Ähnlichkeit
- **Ergebnis:** Status `REVIEW_PENDING` mit Top-3-Vorschlägen
- **Nutzen:** Auch unscharfe Anfragen werden vor-sortiert; der Mensch entscheidet nur noch

### UC-3: Anfrage mit PDF-Anhang (Text-PDF)
**Szenario:** Kunde schickt digitale Bestellung als PDF mit.

- **Input:** E-Mail + digitales PDF (z. B. aus Word exportiert)
- **KI-Pfad:** PDFBox extrahiert Text → wird zum E-Mail-Body addiert → Extraktion
- **Ergebnis:** Wie UC-1/UC-2, aber mit kombiniertem Input
- **Nutzen:** Strukturierte Bestellformulare werden ohne Mehraufwand verarbeitet

### UC-4: Anfrage mit Formular-PDF
**Szenario:** Standardisiertes Bestellformular mit Ausfüllfeldern.

- **KI-Pfad:** `PdfScanService` erkennt AcroForm → liest Feldwerte (Name: Wert)
- **Nutzen:** Hohe Genauigkeit bei strukturierten Formularen

### UC-5: Anfrage mit gescanntem PDF (OCR)
**Szenario:** Kunde schickt eingescannte handschriftliche oder Bild-Bestellung.

- **KI-Pfad:** Tesseract OCR (Sprachen: Deutsch + Englisch) → Text-Extraktion → KI
- **Voraussetzung:** Tesseract muss auf dem Host installiert sein
- **Fallback:** Status mit Hinweis "manuelle Prüfung erforderlich"
- **Nutzen:** Auch Papierprozesse können in die Automatisierung integriert werden

### UC-6: Technische Zeichnung als PDF
**Szenario:** Anfrage mit Bauteilzeichnung statt Text.

- **KI-Pfad:** Wird als `IMAGE` klassifiziert → direkt in Review-Queue mit Hinweis
- **Nutzen:** Saubere Trennung — kein falscher Auto-Match auf Bildern

### UC-7: Human Review — Bestätigung
**Szenario:** Mitarbeiter prüft KI-Vorschlag in der Review-Queue.

- **Aktion:** `POST /api/review/{feedbackId}/confirm`
- **Effekt:** Feedback wird gespeichert; Status → `READY_TO_SEND`
- **Nutzen:** Schnelle Freigabe (1 Klick), Audit-Trail wer wann bestätigt hat

### UC-8: Human Review — Korrektur
**Szenario:** KI hat falschen Artikel vorgeschlagen, Mitarbeiter wählt richtigen aus.

- **Aktion:** `POST /api/review/{feedbackId}/correct` mit `correctArtikelId`
- **Effekt:** `match_feedback.korrektur_id` gesetzt → System lernt aus Korrektur
- **Nutzen:** Jede Korrektur verbessert künftige Matches (über Re-Embedding)

### UC-9: Human Review — Ablehnung
**Szenario:** Anfrage ist Spam oder nicht bearbeitbar.

- **Aktion:** `POST /api/review/{feedbackId}/reject`
- **Effekt:** Status `REJECTED`, Notiz wird gespeichert
- **Nutzen:** Saubere Datenbasis, keine Karteileichen

### UC-10: ERP-Sync — neue Artikel aufnehmen
**Szenario:** ERP hat neue Artikel; KI soll sie matchen können.

- **Aktion:** `POST /api/admin/reembed` (inkrementell) oder `/reembed/full` (alles)
- **Effekt:** Neue Embeddings werden erzeugt und in pgvector gespeichert
- **Automatisierung:** `ReEmbeddingScheduler` läuft nachts automatisch
- **Nutzen:** Kein manuelles Mapping nötig; ERP bleibt Single Source of Truth

### UC-11: Monitoring & Reporting
**Szenario:** Management will Übersicht über Volumen und Automatisierungsgrad.

- **Aktion:** `GET /api/admin/stats`
- **Liefert:** Zähler je Status (`new`, `review_pending`, `offer_sent`, `rejected`)
- **Nutzen:** Echtzeit-KPIs für Geschäftsführung

---

## Teil 2 — Kunden-Demo Drehbuch (15–20 Min)

### Vorbereitung (vor dem Termin)

1. Infrastruktur hochfahren:
   ```powershell
   cd C:\Private-Pocs\shipparts-poc\shipparts-poc
   docker compose up -d
   docker exec shipparts-ollama ollama pull mistral:7b-instruct
   docker exec shipparts-ollama ollama pull nomic-embed-text
   ```

2. App starten:
   ```powershell
   mvn spring-boot:run
   ```

3. Index aufbauen:
   ```powershell
   curl.exe -X POST http://localhost:8080/api/admin/reembed/full
   ```

4. Browser-Tabs vorbereiten:
   - `http://localhost:8080/actuator/health` (Beweis: läuft)
   - DBeaver mit Verbindung zu Postgres (Beweis: KI lernt)

5. Test-PDF bereitlegen (digitale Bestellung mit Text).

### Akt 1 — Das Problem (2 Min, ohne Tool)

> "Heute kommen pro Tag dutzende E-Mails mit Ersatzteil-Anfragen rein.
> Jede muss ein Mitarbeiter lesen, Artikel im ERP suchen, Angebot schreiben.
> 5–15 Minuten pro Anfrage. Bei 50 Anfragen am Tag → 4–12 Stunden manuelle Arbeit."

### Akt 2 — Smoke-Test (1 Min)

```powershell
curl.exe http://localhost:8080/actuator/health
curl.exe http://localhost:8080/api/admin/stats
```

> "Das System läuft. Wir haben heute 0 Anfragen — schauen wir, wie sich das ändert."

### Akt 3 — Der goldene Fall (3 Min) — UC-1

> "Kunde nennt die exakte Artikel-Nummer."

```powershell
curl.exe -X POST http://localhost:8080/api/pipeline/process `
  -F "from=captain@vessel.com" `
  -F "subject=Spare part request" `
  -F "emailBody=Please send offer for article number WAR-FIV-2234"
```

> "Sekunden später: Status `READY_TO_SEND`. Vollautomatisch. Kein Mensch hat es angefasst."

**Im DB-Tool zeigen:**
```sql
SELECT email_subject, status, extrakt_json FROM anfrage ORDER BY created_at DESC LIMIT 1;
```

### Akt 4 — Der realistische Fall (4 Min) — UC-2

> "Kunde schreibt frei: keine Artikelnummer, eigene Worte."

```powershell
curl.exe -X POST http://localhost:8080/api/pipeline/process `
  -F "from=chief@vessel.com" `
  -F "subject=Need part" `
  -F "emailBody=Turbolader Lager fuer MAN B und W Motor, Baujahr 2018"
```

> "Status: `REVIEW_PENDING`. Die KI hat einen Vorschlag, ist aber nicht 100% sicher."

```powershell
curl.exe http://localhost:8080/api/pipeline/queue
```

> "Hier sieht der Reviewer: Vorschlag `MAN-BR-7710`, Konfidenz 0.78. Er bestätigt mit einem Klick."

### Akt 5 — Der Human-Review-Flow (3 Min) — UC-7

> "Reviewer öffnet die Anfrage, prüft kurz, bestätigt."

```powershell
$fid = "FEEDBACK-ID-AUS-AKT-4"
curl.exe -X POST "http://localhost:8080/api/review/$fid/confirm" `
  -H "Content-Type: application/json" `
  -d '{\"reviewer\":\"jane.doe\",\"notiz\":\"OK, passt\"}'
```

> "Aus 5 Minuten manueller Arbeit werden 5 Sekunden Klick."

### Akt 6 — PDF-Anhang verarbeiten (3 Min) — UC-3

> "Realität: viele Kunden schicken PDFs mit."

```powershell
$pdf = "C:\Users\belkacea\Downloads\bestellung.pdf"
curl.exe -X POST http://localhost:8080/api/pipeline/process `
  -F "from=ops@vessel.com" `
  -F "subject=Bestellung Q4" `
  -F "emailBody=Bitte siehe Anhang" `
  -F "pdf=@$pdf"
```

> "PDF wird automatisch ausgelesen — egal ob Text, Formular oder gescannt."

### Akt 7 — Das System lernt (2 Min) — UC-8

> "Jede Korrektur fließt in den nächsten Indexlauf ein."

```sql
SELECT a.email_subject,
       v.artikel_nr AS ki_vorschlag,
       k.artikel_nr AS korrigiert_zu
FROM match_feedback mf
JOIN anfrage a ON a.id = mf.anfrage_id
LEFT JOIN artikel v ON v.id = mf.artikel_id
LEFT JOIN artikel k ON k.id = mf.korrektur_id
WHERE mf.korrektur_id IS NOT NULL;
```

> "Korrekturen sind Trainingsdaten. Nachts läuft Re-Embedding und das System wird schlauer — ohne Datawissenschaftler."

### Akt 8 — Reporting fürs Management (1 Min) — UC-11

```powershell
curl.exe http://localhost:8080/api/admin/stats
```

> "Echtzeit-KPIs: wieviel automatisch, wieviel Review, wieviel rejected. Direkt für Dashboards nutzbar."

### Akt 9 — Architektur-Zusammenfassung (2 Min)

Einfache Whiteboard-Skizze:

```
E-Mail/PDF
   ↓
[PDF-Scanner]  →  text + formfields + OCR
   ↓
[KI Extraction]  →  JSON {artikel_nr, hersteller, ...}
   ↓
[Matching]  →  direkt | semantisch (pgvector) | keyword
   ↓                        ↓
Konfidenz ≥ 0.85?    Konfidenz < 0.85?
   ↓                        ↓
Auto-Angebot           Human Review → Feedback → Re-Embedding
```

Tech-Stack (für die Fragenrunde):
- **Spring Boot 3.3** (Java 21)
- **PostgreSQL 16 + pgvector** (DB + Vektoren in einer DB)
- **Spring AI + Ollama** (LLM lokal, kein Cloud-Vendor-Lock-in)
- **PDFBox + Tesseract** (PDF/OCR)
- **Flyway** (DB-Migrations)
- **Docker Compose** (einfaches Deployment)

---

## Teil 3 — Häufige Kundenfragen

| Frage | Antwort |
|---|---|
| "Sind unsere Daten sicher?" | LLM läuft **lokal** (Ollama-Container). Keine Daten verlassen Ihr Netzwerk. |
| "Was kostet das im Betrieb?" | Keine LLM-API-Kosten. Nur Server-Hardware (für POC reicht 16 GB RAM, GPU optional). |
| "Was passiert bei falschen Matches?" | Human Review fängt alles unter Konfidenzschwelle ab. Korrekturen verbessern das System. |
| "Kann das mit unserem ERP?" | Ja — `artikel` Tabelle ist Single Source of Truth. ERP-Sync via Re-Embedding-Endpoint. |
| "Skalierbarkeit?" | Stateless Spring-Boot-App. Horizontal skalierbar. pgvector skaliert bis Millionen Vektoren. |
| "Wie genau ist die KI?" | Direktmatch (Artikel-Nr): 100%. Semantik: 70–90% je nach Datenqualität. Mit Feedback steigt es. |
| "Was wenn die KI komplett daneben liegt?" | Konfidenzschwelle (konfigurierbar) sortiert unsichere Fälle automatisch zum Menschen. |
| "Wie lange dauert die Einführung produktiv?" | Mit echtem Artikelkatalog: 2–4 Wochen für POC-zu-Pilot. ERP-Anbindung je nach API 1–3 Wochen. |

---

## Teil 4 — Was die Demo bewusst weglässt (für die Fragerunde)

- **IMAP-Listener** (`EmailListenerService`) ist vorhanden, aber im POC mit Dummy-Credentials.
- **Auto-Send** (`shipparts.offer.auto-send-enabled=false`) — im POC werden Angebote nicht wirklich versendet.
- **Authentifizierung** — Endpoints sind im POC ungeschützt; in Produktion mit Spring Security / OAuth abzusichern.
- **Tesseract-OCR** — muss auf dem Host installiert sein; im POC mit graceful fallback.
- **Großes LLM** — POC nutzt Mistral 7B; Produktion könnte Llama 70B oder Mixtral nutzen für bessere Extraktion.

---

## Teil 5 — Nächste Schritte nach erfolgreicher Demo

1. **Echte Artikeldaten importieren** (statt Seed-Daten)
2. **Test mit realen E-Mails** aus dem Tagesgeschäft (anonymisiert)
3. **Konfidenzschwelle kalibrieren** — wieviel Auto, wieviel Review
4. **Review-UI bauen** (React/Vue) auf den bestehenden Endpoints
5. **ERP-Connector** für bi-direktionalen Sync
6. **Produktions-Härtung:** Auth, Logging, Monitoring (Actuator → Prometheus/Grafana)
