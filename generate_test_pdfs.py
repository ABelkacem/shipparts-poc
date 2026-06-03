"""
Generates 3 test PDFs for the ShipParts demo:
  1. test_bestellung_direkt.pdf  -- contains exact Artikel-Nr -> UC-1
  2. test_bestellung_vage.pdf    -- free-text description     -> UC-2
  3. test_bestellung_multi.pdf   -- multiple items            -> UC-Multi

Run:  python generate_test_pdfs.py
Output goes into ./test-pdfs/
"""
from pathlib import Path
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib import colors
from reportlab.lib.units import cm
from datetime import date

OUT = Path("test-pdfs")
OUT.mkdir(exist_ok=True)

styles = getSampleStyleSheet()
h1 = ParagraphStyle("h1", parent=styles["Heading1"], textColor=colors.HexColor("#0f172a"))
h2 = ParagraphStyle("h2", parent=styles["Heading2"], textColor=colors.HexColor("#334155"))
body = styles["BodyText"]


def header(story, title):
    story.append(Paragraph("OCEAN BREEZE SHIPPING LTD.", h2))
    story.append(Paragraph("Pier 14, Hamburg Hafen | order@oceanbreeze.example", body))
    story.append(Spacer(1, 0.3 * cm))
    story.append(Paragraph(f"Datum: {date.today().isoformat()}", body))
    story.append(Spacer(1, 0.6 * cm))
    story.append(Paragraph(title, h1))
    story.append(Spacer(1, 0.4 * cm))


def make_direct():
    """UC-1: exact part number"""
    f = OUT / "test_bestellung_direkt.pdf"
    doc = SimpleDocTemplate(str(f), pagesize=A4)
    story = []
    header(story, "Bestellanfrage – Ersatzteil")

    story.append(Paragraph("Sehr geehrte Damen und Herren,", body))
    story.append(Spacer(1, 0.3 * cm))
    story.append(Paragraph(
        "wir benoetigen dringend folgendes Ersatzteil fuer unser Schiff "
        "<b>MV Ocean Breeze</b> (Wartsila RT-flex58T):", body))
    story.append(Spacer(1, 0.3 * cm))

    data = [
        ["Pos", "Artikel-Nr", "Beschreibung",          "Menge", "Einheit"],
        ["1",   "WAR-FIV-2234", "Fuel Injection Valve", "2",     "Stueck"],
    ]
    t = Table(data, colWidths=[1*cm, 3.5*cm, 7*cm, 2*cm, 2.5*cm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1e293b")),
        ("TEXTCOLOR",  (0, 0), (-1, 0), colors.white),
        ("GRID",       (0, 0), (-1, -1), 0.5, colors.grey),
        ("FONTSIZE",   (0, 0), (-1, -1), 10),
        ("VALIGN",     (0, 0), (-1, -1), "MIDDLE"),
    ]))
    story.append(t)
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph("Lieferung an: Hamburg Hafen, Pier 14", body))
    story.append(Paragraph("Lieferdatum: schnellstmoeglich", body))
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph("Mit freundlichen Gruessen,<br/>Captain John Smith", body))

    doc.build(story)
    print(f"OK: {f}")


def make_vague():
    """UC-2: free text, no part number"""
    f = OUT / "test_bestellung_vage.pdf"
    doc = SimpleDocTemplate(str(f), pagesize=A4)
    story = []
    header(story, "Ersatzteilanfrage")

    story.append(Paragraph("Sehr geehrtes ShipParts-Team,", body))
    story.append(Spacer(1, 0.3 * cm))
    story.append(Paragraph(
        "an unserem Hauptmotor <b>MAN B&amp;W 6S60MC-C</b> (Baujahr 2018) "
        "ist das Turbolader-Lager defekt. Wir benoetigen ein passendes "
        "Ersatzteil – leider liegt uns aktuell keine Artikelnummer vor.", body))
    story.append(Spacer(1, 0.3 * cm))
    story.append(Paragraph(
        "Bitte unterbreiten Sie uns ein Angebot fuer 1 Stueck. "
        "Lieferung idealerweise innerhalb der naechsten 7 Tage an Rotterdam.", body))
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph(
        "<b>Schiff:</b> MV Northern Star<br/>"
        "<b>Maschinentyp:</b> MAN B&amp;W 6S60MC-C<br/>"
        "<b>Komponente:</b> Turbolader-Lager", body))
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph("Vielen Dank,<br/>Chief Engineer M. Larsen", body))

    doc.build(story)
    print(f"OK: {f}")


def make_multi():
    """UC-Multi: multiple items"""
    f = OUT / "test_bestellung_multi.pdf"
    doc = SimpleDocTemplate(str(f), pagesize=A4)
    story = []
    header(story, "Sammelbestellung – Multiple Items")

    story.append(Paragraph("Hello ShipParts Team,", body))
    story.append(Spacer(1, 0.3 * cm))
    story.append(Paragraph(
        "please quote the following spare parts for delivery to "
        "<b>Hamburg port, next Friday</b>:", body))
    story.append(Spacer(1, 0.3 * cm))

    data = [
        ["Pos", "Artikel-Nr",    "Beschreibung",                    "Menge"],
        ["1",   "WAR-FIV-2234",  "Fuel Injection Valve",            "2"],
        ["2",   "MAN-BR-7710",   "Turbocharger Bearing",            "4"],
        ["3",   "—",             "Cooling pump for Wartsila RT-flex","1"],
    ]
    t = Table(data, colWidths=[1*cm, 3.5*cm, 9*cm, 2*cm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1e293b")),
        ("TEXTCOLOR",  (0, 0), (-1, 0), colors.white),
        ("GRID",       (0, 0), (-1, -1), 0.5, colors.grey),
        ("FONTSIZE",   (0, 0), (-1, -1), 10),
    ]))
    story.append(t)
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph(
        "Note: pos. 3 has no part number on file – please advise compatible part.", body))
    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph("Best regards,<br/>Purchasing Dept., MS Nordic Trader", body))

    doc.build(story)
    print(f"OK: {f}")


if __name__ == "__main__":
    make_direct()
    make_vague()
    make_multi()
    print(f"\nFertig. Dateien in: {OUT.resolve()}")
