# ShipParts AI Pipeline — Spring Boot POC

KI-gestützte Auftragsverarbeitungs-Pipeline für Schiffsersatzteile.
ERP als Single Source of Truth · PostgreSQL 16 + pgvector · Open-Source-LLM (Mistral/Llama)

---

## Architektur

```
Email (IMAP)
  │
  ▼
PdfScanService          ← PDFBox: Text-PDF, OCR, Formulare, Bilder
  │
  ▼
ExtractionService       ← KI #1: LLM extrahiert JSON-Felder (Mistral 7B)
  │
  ├─ Artikel-Nr vorhanden? ──► ArtikelRepository.findByArtikelNr()  [Konfidenz 1.0]
  │
  └─ Kein Artikel-Nr ────────► EmbeddingService → pgvector Similarity Search [0.0–1.0]
                                                    KI #2
  │
  ▼
OfferService            ← KI #3: LLM generiert Angebotstext (Mistral 7B)
  │
  ├─ Konfidenz ≥ 0.85 ──────► Auto-Send (Dunkel-Prozess)
  │                             FeedbackService → bestaetigt=true
  │
  └─ Konfidenz < 0.85 ──────► REVIEW_PENDING
                               Human Review (REST API / Web UI)
                               FeedbackService → Korrektur + Synonyme
                                 │
                                 ▼
                               ReEmbeddingScheduler  ← KI #4: Nightly 02:00
                               Synonyme → Re-Embedding → pgvector HNSW Index
```

---

## Quick Start

### 1. Infrastructure starten

```bash
docker-compose up -d
```

### 2. Ollama-Modelle laden (einmalig, ~5GB)

```bash
# Chat/Extraction model
docker exec -it shipparts-ollama ollama pull mistral:7b-instruct

# Embedding model
docker exec -it shipparts-ollama ollama pull nomic-embed-text
```

### 3. Spring Boot starten

```bash
mvn spring-boot:run
```

Flyway führt automatisch `V1__init_schema.sql` aus:
- pgvector Extension wird aktiviert
- Alle Tabellen werden erstellt
- 10 Demo-Artikel werden eingefügt

### 4. Artikel-Index aufbauen (einmalig)

```bash
curl -X POST http://localhost:8080/api/admin/reembed/full
# → {"reembedded": 10, "status": "OK"}
```

---

## API Beispiele

### Pipeline triggern — MIT Artikel-Nummer (Direkt-Match)

```bash
curl -X POST http://localhost:8080/api/pipeline/process \
  -F "from=captain@vessel.com" \
  -F "subject=Urgent spare part request" \
  -F 'emailBody=Dear Sir, we urgently need 2x fuel injection valve P/N WAR-FIV-2234
for MV Atlantic Star, Wärtsilä RT-flex58T. Delivery to Rotterdam. Please quote ASAP.'
```

Antwort:
```json
{
  "id": "550e8400-...",
  "status": "REVIEW_PENDING",
  "emailFrom": "captain@vessel.com",
  "emailSubject": "Urgent spare part request",
  "pdfType": null
}
```

### Pipeline triggern — OHNE Artikel-Nummer (Semantic Search)

```bash
curl -X POST http://localhost:8080/api/pipeline/process \
  -F "from=chief@tanker.com" \
  -F "subject=Bearing replacement needed" \
  -F 'emailBody=Hello, we need a replacement bearing for the turbocharger on our
main engine MAN B&W. The old one cracked. Please send quote urgently.
MV Baltic Pioneer, delivery Hamburg.'
```

### Mit PDF-Anhang

```bash
curl -X POST http://localhost:8080/api/pipeline/process \
  -F "from=captain@vessel.com" \
  -F "subject=Part request with datasheet" \
  -F "emailBody=See attached PDF for part details." \
  -F "pdf=@/path/to/technical_sheet.pdf"
```

### Review-Warteschlange abrufen

```bash
curl http://localhost:8080/api/pipeline/queue
```

### KI-Vorschlag bestätigen

```bash
curl -X POST http://localhost:8080/api/review/{feedbackId}/confirm \
  -H "Content-Type: application/json" \
  -d '{
    "reviewer": "jane.doe",
    "additionalFields": {"maschinentyp": "6S60MC"},
    "notiz": "Checked with technical manual - correct part"
  }'
```

### Mit anderem Artikel korrigieren

```bash
curl -X POST http://localhost:8080/api/review/{feedbackId}/correct \
  -H "Content-Type: application/json" \
  -d '{
    "reviewer": "john.smith",
    "correctArtikelId": "uuid-of-correct-article",
    "additionalFields": {"synonym": "turbo bearing MAN"},
    "notiz": "Customer has TCA55 not TCA48"
  }'
```

### Statistik

```bash
curl http://localhost:8080/api/admin/stats
# → {"pending_review": 2, "offer_sent": 14, "new": 0, "rejected": 1}
```

---

## Projektstruktur

```
shipparts-poc/
├── src/main/java/com/shipparts/
│   ├── ShipPartsApplication.java          # Spring Boot Einstiegspunkt
│   ├── config/
│   │   └── AppConfig.java                 # Spring AI ChatClient Bean
│   ├── domain/
│   │   ├── Artikel.java                   # ERP-Stammdaten Entity
│   │   ├── ArtikelEmbedding.java          # pgvector VECTOR(384) Entity
│   │   ├── Anfrage.java                   # Eingehende Email Entity
│   │   ├── MatchFeedback.java             # Human Review Entity
│   │   └── Angebot.java                   # Generiertes Angebot
│   ├── repository/
│   │   ├── ArtikelRepository.java
│   │   ├── ArtikelEmbeddingRepository.java  # Native pgvector similarity query
│   │   ├── AnfrageRepository.java
│   │   └── MatchFeedbackRepository.java
│   ├── service/
│   │   ├── PipelineOrchestrator.java      # Haupt-Pipeline-Flow
│   │   ├── pdf/PdfScanService.java        # PDF-Typ-Erkennung + Extraktion
│   │   ├── embedding/
│   │   │   ├── ExtractionService.java     # KI #1: LLM Freitext → JSON
│   │   │   └── EmbeddingService.java      # KI #2: Text → Vektor
│   │   ├── matching/MatchingService.java  # SQL + Semantic Search
│   │   ├── offer/OfferService.java        # KI #3: Angebotstext
│   │   ├── feedback/FeedbackService.java  # Human-in-the-Loop
│   │   └── email/EmailListenerService.java # IMAP Listener
│   ├── scheduler/
│   │   └── ReEmbeddingScheduler.java      # KI #4: Nightly Batch
│   ├── controller/
│   │   └── PipelineController.java        # REST API
│   └── dto/
│       ├── ExtractionResult.java
│       └── MatchResult.java
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/
│       └── V1__init_schema.sql            # pgvector + alle Tabellen + Seed-Daten
├── src/test/java/com/shipparts/
│   └── PipelineIntegrationTest.java       # Testcontainers Integration Tests
├── docker-compose.yml                     # Postgres + Ollama + Redis
└── README.md
```

---

## LLM-Konfiguration

### Ollama (lokal, kostenlos, empfohlen für POC)
```properties
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.model=mistral:7b-instruct
spring.ai.ollama.embedding.model=nomic-embed-text
```

### OpenAI (Cloud, höhere Qualität)
```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o-mini
spring.ai.openai.embedding.options.model=text-embedding-3-small
```

Beide Optionen nutzen **dieselbe Spring AI Abstraktionsschicht** — nur die
`application.properties` muss geändert werden, kein Code-Umbau.

---

## Tests ausführen

```bash
# Unit + Integration Tests (benötigt Docker für Testcontainers)
mvn test

# Nur Integration Tests
mvn test -Dtest=PipelineIntegrationTest

# Build ohne Tests
mvn package -DskipTests
```

---

## Produktions-Checkliste

- [ ] `shipparts.offer.auto-send-enabled=true` setzen
- [ ] IMAP-Zugangsdaten konfigurieren
- [ ] OPENAI_API_KEY oder Ollama GPU-Server einrichten
- [ ] PostgreSQL HA (Streaming Replication) aufsetzen
- [ ] Prometheus + Grafana einbinden (`/actuator/metrics`)
- [ ] JWT-Authentifizierung für Review-API aktivieren
- [ ] E-Mail-Versand via JavaMailSender implementieren (Stub in OfferService)
- [ ] ERP-Sync via Debezium CDC oder Batch-Script anschließen
