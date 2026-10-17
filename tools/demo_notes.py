#!/usr/bin/env python3
"""Demodata til butikkens skærmbilleder: danske og engelske noter i samlingerne noter-da og noter-en
på en lokal Radicale (testserveren fra afsnittet "Test" i docs/SPEC.md). Fremgangsmåden står i docs/RELEASE.md.

Brug:  python3 -B tools/demo_notes.py <server-url> <bruger> <adgangskode>
Datoerne regnes fra i dag, så listen får grupperne I dag, I går, Seneste 7 dage, september og august.
"""
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "reference"))
import notesync  # noqa: E402

if len(sys.argv) != 4:
    sys.exit(__doc__)
base, user, password = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
today = datetime.now(timezone.utc).date()


def stamp(days_ago, hhmm_utc):
    d = today - timedelta(days=days_ago)
    return f"{d:%Y%m%d}T{hhmm_utc}00Z"


LISBON_DA = """- Bestil tog til **Sintra**
- Smag *pastéis de nata* i Belém
- Sporvogn 28 tidligt om morgenen

## Praktisk
1. Køb et *Viva Viagem*-kort
2. Køreplan: https://www.cp.pt"""

LISBON_EN = """- Book the train to **Sintra**
- Try *pastéis de nata* in Belém
- Tram 28 early in the morning

## Practical
1. Get a *Viva Viagem* card
2. Timetable: https://www.cp.pt"""

DATA = {
    "noter-da": [
        ("Indkøbsliste", "- Mælk\n- Rugbrød\n- Kaffe\n- Æbler\n- Havregryn", ["Hjem"], stamp(0, "0712")),
        ("Ny hjemmeside", "Vi er enige om den nye menu.\n\n**Næste skridt:**\n1. Tekst til Om os-siden\n2. Test på telefoner",
         ["Arbejde"], stamp(0, "0630")),
        ("Bøger at læse", "- *Mørkets venstre hånd*\n- *Piranesi*\n- *De besiddelsesløse*", ["Læsning"], stamp(1, "1845")),
        ("Tur til Lissabon", LISBON_DA, ["Rejser", "Planer"], stamp(3, "1605")),
        ("Cykelservice", "Kædeolie, nye bremseklodser foran, tjek dæktryk.", [], stamp(5, "0550")),
        ("Linsesuppe", "1 løg, 2 gulerødder, 250 g røde linser, 1 l bouillon, spidskommen.\nSimrer i 25 minutter og blendes.",
         ["Opskrifter"], "20260922T150000Z"),
        ("Idéer til haven", "Højbed ved hegnet.\nFlere krydderurter: timian, salvie, purløg.", ["Hjem", "Have"],
         "20260821T100000Z"),
    ],
    "noter-en": [
        ("Grocery list", "- Milk\n- Rye bread\n- Coffee\n- Apples\n- Oats", ["Home"], stamp(0, "0712")),
        ("Website relaunch", "Agreed on the new navigation.\n\n**Next steps:**\n1. Copy for the About page\n2. Test on phones",
         ["Work"], stamp(0, "0630")),
        ("Book recommendations", "- *The Left Hand of Darkness*\n- *Piranesi*\n- *The Dispossessed*", ["Reading"],
         stamp(1, "1845")),
        ("Trip to Lisbon", LISBON_EN, ["Travel", "Plans"], stamp(3, "1605")),
        ("Bike maintenance", "Chain oil, brake pads at the front, check tyre pressure.", [], stamp(5, "0550")),
        ("Lentil soup", "1 onion, 2 carrots, 250 g red lentils, 1 l stock, cumin.\nSimmer for 25 minutes and blend.",
         ["Recipes"], "20260922T150000Z"),
        ("Ideas for the garden", "Raised bed by the fence.\nMore herbs: thyme, sage, chives.", ["Home", "Garden"],
         "20260821T100000Z"),
    ],
}

for coll, notes in DATA.items():
    client = notesync.DavClient(f"{base}/{user}/{coll}/", user, password)
    client.mkcalendar(coll)
    for title, body, tags, ts in notes:
        uid = str(uuid.uuid4())
        raw = notesync.new_ics(uid, title, body, tags)
        for prop in ("DTSTAMP", "CREATED", "LAST-MODIFIED"):
            raw = "\r\n".join(f"{prop}:{ts}" if line.startswith(prop + ":") else line for line in raw.split("\r\n"))
        assert notesync.parse_ics(raw).body == body
        client.put(f"/{user}/{coll}/{uid}.ics", raw, None)
    print(coll, len(notes))
