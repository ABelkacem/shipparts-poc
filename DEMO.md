# ShipParts AI Pipeline — Demo-Anleitung

Schritt-für-Schritt-Guide für die Live-Demo. Folge den Abschnitten in Reihenfolge.

---

## 0. Was zeigt die Demo?

Eine KI-gestützte Pipeline, die Ersatzteil-Anfragen aus E-Mails automatisch verarbeitet:

1. **Email kommt rein** (oder wird per API simuliert)
2. **PDF-Anhänge** werden gelesen (Text, OCR, Formulare)
3. **KI extrahiert** strukturierte Daten (Artikel-Nr, Hersteller, Maschinentyp)
4. **Matching** gegen Artikelkatalog
   - Exakte Artikel-Nr → Konfidenz 1.0 → **Auto-Angebot**
   - Vage Beschreibung → Semantik-Suche → **Human Review**
5. **Feedback** des Reviewers verbessert künftige Matches

---

## 1. Voraussetzungen

- **Java 21** (`java -version`)
- **Maven 3.9+** (`mvn -version`)
- **Docker Desktop** oder **Rancher Desktop** läuft
- Ports frei: `5432` (Postgres), `6379` (Redis), `11434` (Ollama), `8080` (App)

---

## 2. Infrastruktur starten

Im Projekt-Verzeichnis:

```powershell
cd C:\Private-Pocs\shipparts-poc\shipparts-poc
docker compose up -d
```

Prüfen:

```powershell
docker compose ps
```

Erwartet: 3 Container `Up` — `shipparts-postgres`, `shipparts-redis`, `shipparts-ollama`.

---

## 3. Ollama-Modelle laden (einmalig, ~5-10 Min)

```powershell
docker exec shipparts-ollama ollama pull mistral:7b-instruct
docker exec shipparts-ollama ollama pull nomic-embed-text
```

Verifizieren:

```powershell
docker exec shipparts-ollama ollama list
```

Beide Modelle müssen aufgelistet sein.

---

## 4. App bauen und starten

```powershell
mvn clean package -DskipTests
mvn spring-boot:run
```

Erfolg sieht so aus:

```
Started ShipPartsApplication in 9.xxx seconds
Tomcat started on port 8080
```

Lass dieses Terminal-Fenster **offen** — die App läuft hier.

---

## 5. Smoke-Test (neues Terminal öffnen)

```powershell
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/admin/stats
```

Erwartet: `{"status":"UP"}` und ein Stats-JSON mit Zählern.

---

## 6. Einmaliger Index-Aufbau

Erzeugt Vektor-Embeddings für die 10 Seed-Artikel:

```powershell
curl -X POST http://localhost:8080/api/admin/reembed/full
```

Antwort: `{"reembedded": 10, "status": "OK"}`

---

## 7. Demo-Szenario A — Direkter Treffer (High Confidence)

E-Mail mit **exakter Artikel-Nr** im Text:

```powershell
curl -X POST http://localhost:8080/api/pipeline/process `
  -F "from=captain@vessel.com" `
  -F "subject=Spare part request" `
  -F "emailBody=We urgently need 2x fuel injection valve, Part-Nr WAR-FIV-2234 for our Wartsila RT-flex58T"
```

Ergebnis: Status `OFFER_SENT` oder `READY_TO_SEND` — Konfidenz 1.0, kein Review nötig.

---

## 8. Demo-Szenario B — Vage Beschreibung (Semantik-Match)

E-Mail **ohne Artikel-Nr**, nur freier Text:

```powershell
curl -X POST http://localhost:8080/api/pipeline/process `
  -F "from=chief@vessel.com" `
  -F "subject=Need part" `
  -F "emailBody=Turbolader Lager fuer MAN B und W Motor, Baujahr 2018, bitte um Angebot"
```

Ergebnis: Status `REVIEW_PENDING` — KI hat Vorschlag, Konfidenz < 0.85 → wartet auf Mensch.

---

## 9. Review-Queue anzeigen

```powershell
curl http://localhost:8080/api/pipeline/queue
```

Zeigt alle Anfragen, die auf manuelle Bestätigung warten — mit KI-Vorschlag und Konfidenz-Score.

---

## 10. Stats am Ende

```powershell
curl http://localhost:8080/api/admin/stats
```

Zeigt die finalen Zähler — `pending_review`, `offer_sent`, `new`, `rejected`.

---

## 11. App beenden

Im Spring-Boot-Terminal: `Ctrl+C`

Container stoppen (optional):

```powershell
docker compose down
```

---

## Troubleshooting

| Problem | Lösung |
|---|---|
| `Port 8080 already in use` | Anderen Java-Prozess beenden: `taskkill /F /IM java.exe` |
| `Connection refused: 5432` | `docker compose ps` — Postgres läuft? |
| Pipeline hängt | Ollama-Modelle geladen? (Schritt 3) |
| `ChatModel not found` | `mvn clean package -DskipTests` neu ausführen |
| Testcontainers-Fehler | Für Demo irrelevant — Tests mit `-DskipTests` überspringen |

---

## API-Referenz (Kurz)

| Endpoint | Zweck |
|---|---|
| `GET  /actuator/health` | App-Status |
| `GET  /api/admin/stats` | Anfragen-Zähler |
| `POST /api/admin/reembed/full` | Alle Artikel neu indexieren |
| `POST /api/pipeline/process` | E-Mail durch Pipeline schicken |
| `GET  /api/pipeline/queue` | Offene Review-Anfragen |
| `GET  /api/pipeline/{id}` | Detail einer Anfrage |
| `POST /api/review/{feedbackId}/confirm` | KI-Vorschlag bestätigen |
| `POST /api/review/{feedbackId}/correct` | Mit anderem Artikel korrigieren |
| `POST /api/review/{feedbackId}/reject` | Anfrage ablehnen |
