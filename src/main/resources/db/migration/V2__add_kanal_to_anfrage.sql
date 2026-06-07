-- REQ-IN-04: Eingangskanal je Anfrage erfassen
ALTER TABLE anfrage
    ADD COLUMN kanal TEXT NOT NULL DEFAULT 'EMAIL';

-- EMAIL | PROCUREMENT
CREATE INDEX idx_anfrage_kanal ON anfrage(kanal);
