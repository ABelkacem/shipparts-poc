-- ═══════════════════════════════════════════════════════════════════
-- V1__init_schema.sql
-- ShipParts AI Pipeline – Initial Schema
-- PostgreSQL 16 + pgvector
-- ═══════════════════════════════════════════════════════════════════

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── Artikel (ERP Mirror – Single Source of Truth) ───────────────────
CREATE TABLE artikel (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    artikel_nr          TEXT UNIQUE NOT NULL,        -- ERP primary key
    beschreibung_lang   TEXT NOT NULL,
    beschreibung_kurz   TEXT,
    hersteller          TEXT,
    maschinentyp        TEXT,
    tags                TEXT[]       DEFAULT '{}',
    synonyme            TEXT[]       DEFAULT '{}',   -- populated by feedback loop
    technische_merkmale JSONB        DEFAULT '{}',
    preis_eur           NUMERIC(12,2),
    bestand             INTEGER      DEFAULT 0,
    lieferzeit_tage     INTEGER,
    erp_updated_at      TIMESTAMPTZ,
    indexed_at          TIMESTAMPTZ  DEFAULT NOW(),
    created_at          TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX idx_artikel_nummer ON artikel(artikel_nr);
CREATE INDEX idx_artikel_hersteller ON artikel(hersteller);
CREATE INDEX idx_artikel_bestand ON artikel(bestand) WHERE bestand > 0;

-- ── Artikel Embeddings (pgvector) ───────────────────────────────────
CREATE TABLE artikel_embedding (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    artikel_id  UUID NOT NULL REFERENCES artikel(id) ON DELETE CASCADE,
    embedding   VECTOR(384),         -- nomic-embed-text / MiniLM dimensions
    model_name  TEXT NOT NULL,
    version     INTEGER DEFAULT 1,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (artikel_id, version)
);

-- HNSW index for fast cosine similarity search
-- m=16, ef_construction=64 is good for catalogs up to ~100k items
CREATE INDEX idx_artikel_embedding_hnsw
    ON artikel_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ── Eingehende Anfragen ─────────────────────────────────────────────
CREATE TABLE anfrage (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email_from      TEXT,
    email_subject   TEXT,
    email_body_raw  TEXT,
    pdf_text        TEXT,            -- extracted text from all PDF attachments
    pdf_type        TEXT,            -- TEXT_PDF | SCANNED | FORM | IMAGE | NONE
    extrakt_json    JSONB,           -- LLM extraction result
    status          TEXT NOT NULL DEFAULT 'NEW',
    -- NEW | EXTRACTING | MATCHING | REVIEW_PENDING | OFFER_SENT | REJECTED
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_anfrage_status ON anfrage(status);
CREATE INDEX idx_anfrage_created ON anfrage(created_at DESC);

-- ── Match Feedback (Human-in-the-Loop) ──────────────────────────────
CREATE TABLE match_feedback (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    anfrage_id          UUID NOT NULL REFERENCES anfrage(id),
    artikel_id          UUID REFERENCES artikel(id),      -- KI-suggested match
    konfidenz           NUMERIC(5,4),                     -- 0.0000 – 1.0000
    bestaetigt          BOOLEAN DEFAULT FALSE,
    korrektur_id        UUID REFERENCES artikel(id),      -- human-corrected article
    reviewer            TEXT,
    merkmale_ergaenzt   JSONB DEFAULT '{}',               -- fields added by reviewer
    interne_notiz       TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_feedback_anfrage ON match_feedback(anfrage_id);
CREATE INDEX idx_feedback_bestaetigt ON match_feedback(bestaetigt);
CREATE INDEX idx_feedback_created ON match_feedback(created_at DESC);

-- ── Angebote ─────────────────────────────────────────────────────────
CREATE TABLE angebot (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    anfrage_id      UUID NOT NULL REFERENCES anfrage(id),
    artikel_id      UUID REFERENCES artikel(id),
    angebot_text    TEXT,
    konfidenz       NUMERIC(5,4),
    auto_sent       BOOLEAN DEFAULT FALSE,
    versandt_am     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ── ERP Sync Log ─────────────────────────────────────────────────────
CREATE TABLE erp_sync_log (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    sync_type       TEXT,            -- FULL | INCREMENTAL | EVENT
    artikel_count   INTEGER DEFAULT 0,
    embedded_count  INTEGER DEFAULT 0,
    errors          INTEGER DEFAULT 0,
    started_at      TIMESTAMPTZ DEFAULT NOW(),
    finished_at     TIMESTAMPTZ,
    status          TEXT DEFAULT 'RUNNING'
);

-- ── Seed data (Demo-Artikel) ─────────────────────────────────────────
INSERT INTO artikel (artikel_nr, beschreibung_lang, beschreibung_kurz, hersteller, maschinentyp, tags, preis_eur, bestand, lieferzeit_tage) VALUES
('MAN-BR-7710',   'Turbolader-Radiallager MAN B&W TCA Kompressor-Seite',      'Radiallager TCA',    'MAN B&W',  'TCA55',      ARRAY['lager','turbolader','radial'],       2340.00, 12, 5),
('MAN-BR-7715',   'Turbolader-Axiallager MAN B&W TCA Turbinen-Seite',         'Axiallager TCA',     'MAN B&W',  'TCA55',      ARRAY['lager','turbolader','axial'],        1890.00, 4,  7),
('WAR-FIV-2234',  'Kraftstoff-Einspritzventil Wärtsilä RT-flex58T',           'Injektionsventil',   'Wärtsilä', 'RT-flex58T', ARRAY['einspritzventil','kraftstoff'],     3150.00, 8,  4),
('WAR-FIP-5501',  'Einspritzpumpe komplett Wärtsilä RT-flex58T',              'Einspritzpumpe',     'Wärtsilä', 'RT-flex58T', ARRAY['einspritzpumpe','kraftstoff'],      8900.00, 2,  14),
('MAN-EX-3301',   'Auslassventil-Spindle MAN B&W S60MC',                      'Auslassventil',      'MAN B&W',  'S60MC',      ARRAY['auslassventil','spindle'],          1560.00, 6,  6),
('MAN-CS-4412',   'Kolbenring-Satz komplett MAN B&W S60MC (4 Ringe)',         'Kolbenringe',        'MAN B&W',  'S60MC',      ARRAY['kolbenring','satz'],                 780.00, 20, 3),
('ABB-TPS48-LG',  'Turbolader-Lager komplett ABB TPS48',                      'Lager TPS48',        'ABB',      'TPS48',      ARRAY['lager','turbolader','abb'],          4100.00, 2,  10),
('CAT-OFS-7721',  'O-Ring-Dichtung Satz Caterpillar 3516B (50 Stück)',        'O-Ring Satz CAT',    'Caterpillar','3516B',     ARRAY['o-ring','dichtung','satz'],           245.00, 50, 2),
('MAN-TP-6601',   'Tellerventil-Dichtung MAN B&W Turbolader',                 'Tellerventil Dicht', 'MAN B&W',  'TCA',        ARRAY['dichtung','tellerventil'],           340.00, 15, 4),
('WAR-CP-8801',   'Kurbelwellenlager-Schale Wärtsilä 6L32',                   'Kurbelwellenlager',  'Wärtsilä', '6L32',       ARRAY['kurbelwellenlager','schale'],       2100.00, 7,  8);
