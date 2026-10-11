#!/usr/bin/env python3
"""Selvtest for notesync: enhedstests af iCalendar-laget + integrationstest med to
simulerede enheder mod en rigtig, lokal Radicale-server (htpasswd-login).

Kør:  python3 selftest.py        (kræver:  pip install radicale)
"""
import os
import socket
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import notesync as ns  # noqa: E402

FAILED = 0


def check(name, cond, detail=""):
    global FAILED
    if not cond:
        FAILED += 1
    print(f"  [{'ok' if cond else 'FEJL'}] {name}" + (f"   -> {detail}" if detail and not cond else ""))


def info(msg):
    print(f"  [info] {msg}")


# ----------------------------------------------------------------------------
print("\n== iCalendar-laget ==")

raw = ns.new_ics("u-1", "Indkøb, og mere; ting", "Mælk\nBrød\\rugbrød", ["Privat", "A,B"])
p = ns.parse_ics(raw)
check("rundtur: titel med komma/semikolon", p.title == "Indkøb, og mere; ting", p.title)
check("rundtur: flere linjer og backslash", p.body == "Mælk\nBrød\\rugbrød", repr(p.body))
check("rundtur: tags (inkl. komma i tag)", p.tags == ["Privat", "A,B"], p.tags)

long_body = ("Lang linje med æøå og 😀 " * 40).strip()
raw = ns.new_ics("u-2", "Lang", long_body)
phys = raw.split("\r\n")[:-1]
check("folding: alle linjer <= 75 oktetter", all(len(l.encode()) <= 75 for l in phys),
      max(len(l.encode()) for l in phys))
check("folding: rundtur uden tab af æøå/emoji", ns.parse_ics(raw).body == long_body)

# Note skrevet af en 'fremmed' klient (som jtx Board) med egenskaber vi ikke kender
foreign = (
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//ical4j//jtx Board//EN\r\n"
    "BEGIN:VJOURNAL\r\nUID:foreign-1\r\nDTSTAMP:20260101T100000Z\r\n"
    "SUMMARY:Fra jtx\r\nDESCRIPTION;LANGUAGE=da:Hej\\, verden\r\n"
    "CATEGORIES:Arbejde,Privat\r\nCLASS:PUBLIC\r\nSTATUS:FINAL\r\nX-JTX-FOO:bar\r\n"
    "ATTACH;FMTTYPE=\"text/x:y\":https://example.dk/a.txt\r\n"
    "END:VJOURNAL\r\nEND:VCALENDAR\r\n")
upd = ns.update_ics(foreign, title="Ny titel")
for needle in ("CLASS:PUBLIC", "STATUS:FINAL", "X-JTX-FOO:bar", "CATEGORIES:Arbejde,Privat",
               "ATTACH;FMTTYPE=\"text/x:y\":https://example.dk/a.txt", "PRODID:-//ical4j//jtx Board//EN",
               "DESCRIPTION;LANGUAGE=da:Hej\\, verden"):
    check(f"update bevarer: {needle[:34]}", needle in upd)
check("update ændrer titlen", ns.parse_ics(upd).title == "Ny titel")
check("update uden ændring giver uændret tekst", ns.update_ics(foreign, title="Fra jtx") == foreign)

# ----------------------------------------------------------------------------
print("\n== Integrationstest mod Radicale ==")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


tmp = tempfile.mkdtemp(prefix="notesync-test-")
port = free_port()
with open(f"{tmp}/users", "w") as f:
    f.write("anna:hemmelig\n")
with open(f"{tmp}/config", "w") as f:
    f.write(f"[server]\nhosts = 127.0.0.1:{port}\n[auth]\ntype = htpasswd\n"
            f"htpasswd_filename = {tmp}/users\nhtpasswd_encryption = plain\n"
            f"[storage]\nfilesystem_folder = {tmp}/collections\n[logging]\nlevel = warning\n")
server = subprocess.Popen([sys.executable, "-m", "radicale", "--config", f"{tmp}/config"],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
try:
    for _ in range(100):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            break
        except OSError:
            time.sleep(0.1)
    else:
        raise SystemExit("Radicale startede ikke")

    URL = f"http://127.0.0.1:{port}/anna/noter/"

    def device(name):
        store = ns.Store(f"{tmp}/{name}.db")
        client = ns.DavClient(URL, "anna", "hemmelig", timeout=10)
        return store, client, ns.Syncer(store, client)

    def titles(store):
        return sorted(n.title for n in store.visible())

    a_store, a_cli, _ = device("A")
    b_store, b_cli, _ = device("B")

    # -- samling ----------------------------------------------------------
    a_cli.mkcalendar("Noter")
    check("MKCALENDAR oprettede samlingen", True)
    body = ('<?xml version="1.0"?><D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">'
            '<D:prop><C:supported-calendar-component-set/></D:prop></D:propfind>')
    st, _h, b = a_cli.request("PROPFIND", URL, body.encode(), {**ns.XML_CT, "Depth": "0"})
    comps = [c.get("name") for c in ns._xml(b).iter("{urn:ietf:params:xml:ns:caldav}comp")]
    info(f"serverens supported-calendar-component-set efter MKCALENDAR: {comps or 'ikke gemt'}")
    ev = ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:ev-1\r\n"
          "DTSTAMP:20260101T100000Z\r\nDTSTART:20260101T100000Z\r\nSUMMARY:Møde\r\n"
          "END:VEVENT\r\nEND:VCALENDAR\r\n")
    code, _e = a_cli.put(URL.replace(f"http://127.0.0.1:{port}", "") + "ev-1.ics", ev, None)
    info(f"PUT af en VEVENT i VJOURNAL-samlingen -> HTTP {code} "
         f"({'afvist' if code >= 400 else 'ACCEPTERET - serveren håndhæver ikke komponenttypen'})")
    if code < 400:
        a_cli.delete(URL.replace(f"http://127.0.0.1:{port}", "") + "ev-1.ics", None)

    # -- login ------------------------------------------------------------
    bad = ns.DavClient(URL, "anna", "forkert", timeout=10)
    try:
        ns.Syncer(ns.Store(":memory:"), bad).sync()
        check("forkert adgangskode giver AuthError", False)
    except ns.AuthError:
        check("forkert adgangskode giver AuthError", True)

    # -- grundlæggende synk A -> B -----------------------------------------
    a_store.add("Indkøbsliste", "Mælk\nBrød\nKaffe", ["Privat"])
    a_store.add("Idéer – æøå 😀", "Én linje")
    sa = ns.Syncer(a_store, a_cli)
    r = sa.sync()
    check("A sender 2 nye noter", r["sendt"] == 2, r)
    check("A har ingen ventende ændringer", not a_store.pending())

    sb = ns.Syncer(b_store, b_cli)
    r = sb.sync()
    check("B henter 2 noter ved første sync", r["hentet"] == 2, r)
    check("B har samme titler (inkl. æøå/emoji)", titles(b_store) == titles(a_store), titles(b_store))
    n_b = next(n for n in b_store.visible() if n.title == "Indkøbsliste")
    check("B har brødtekst og tag intakt", n_b.body == "Mælk\nBrød\nKaffe" and n_b.tags == ["Privat"])

    r = ns.Syncer(b_store, b_cli).sync()
    check("ny sync uden ændringer gør intet", r["hentet"] == 0 and r["sendt"] == 0, r)
    r = ns.Syncer(a_store, a_cli).sync()
    check("A henter ikke sine egne ændringer igen", r["hentet"] == 0, r)

    # -- redigering B -> A --------------------------------------------------
    b_store.edit(n_b.uid, body="Mælk\nBrød\nKaffe\nTe")
    ns.Syncer(b_store, b_cli).sync()
    ns.Syncer(a_store, a_cli).sync()
    check("redigering på B ses på A", a_store.by_uid(n_b.uid).body.endswith("Te"))

    # -- fremmed klient: egenskaber skal overleve ------------------------------
    f_href = URL.replace(f"http://127.0.0.1:{port}", "") + "foreign-1.ics"
    a_cli.put(f_href, foreign, None)
    ns.Syncer(a_store, a_cli).sync()
    f_note = a_store.by_uid("foreign-1")
    check("A henter note skrevet af anden klient", f_note is not None and f_note.title == "Fra jtx")
    a_store.edit("foreign-1", title="Fra jtx (rettet i vores app)")
    ns.Syncer(a_store, a_cli).sync()
    _etag, server_raw = a_cli.get(f_href)
    for needle in ("X-JTX-FOO:bar", "CLASS:PUBLIC", "STATUS:FINAL", "CATEGORIES:Arbejde,Privat",
                   "PRODID:-//ical4j//jtx Board//EN"):
        check(f"serveren har stadig {needle}", needle in server_raw.replace("\r\n ", ""))
    check("serveren har den nye titel", "Fra jtx (rettet i vores app)" in server_raw)

    # -- sletning --------------------------------------------------------------
    ns.Syncer(b_store, b_cli).sync()
    victim = next(n for n in a_store.visible() if n.title.startswith("Idéer"))
    a_store.delete_note(victim.uid)
    r = ns.Syncer(a_store, a_cli).sync()
    check("A sletter på serveren", r["slettet_paa_server"] == 1, r)
    r = ns.Syncer(b_store, b_cli).sync()
    check("sletning når B", r["slettet_lokalt"] == 1 and not any(n.title.startswith("Idéer") for n in b_store.visible()), r)

    # -- konflikt: begge redigerer samme note offline ---------------------------------
    ns.Syncer(a_store, a_cli).sync()
    ns.Syncer(b_store, b_cli).sync()
    uid = n_b.uid
    a_store.edit(uid, body="VERSION FRA A")
    b_store.edit(uid, body="VERSION FRA B")
    ns.Syncer(a_store, a_cli).sync()
    r = ns.Syncer(b_store, b_cli).sync()
    check("konflikt registreres på B", r["konflikter"] == 1, r)
    check("B beholder serverens version (A) i originalen", b_store.by_uid(uid).body == "VERSION FRA A")
    copies = [n for n in b_store.visible() if "(konflikt)" in n.title]
    check("B gemmer sin egen tekst som konfliktkopi", len(copies) == 1 and copies[0].body == "VERSION FRA B")
    check("konfliktkopien er sendt til serveren", not b_store.pending() and copies[0].href is not None)
    ns.Syncer(a_store, a_cli).sync()
    check("A ser konfliktkopien efter næste sync", any("(konflikt)" in n.title for n in a_store.visible()))

    # -- ens redigering på begge sider er ingen konflikt ------------------------------
    uid = next(n.uid for n in a_store.visible() if n.title == "Indkøbsliste")
    ns.Syncer(b_store, b_cli).sync()
    a_store.edit(uid, body="SAMME TEKST")
    b_store.edit(uid, body="SAMME TEKST")
    ns.Syncer(a_store, a_cli).sync()
    r = ns.Syncer(b_store, b_cli).sync()
    check("identiske redigeringer giver ingen konflikt", r["konflikter"] == 0, r)

    # -- redigeret på B, slettet på A: redigeringen overlever -----------------------
    ns.Syncer(a_store, a_cli).sync()
    uid = next(n.uid for n in a_store.visible() if n.title == "Indkøbsliste")
    a_store.delete_note(uid)
    b_store.edit(uid, body="REDIGERET FØR SLETNING NÅEDE HELT HERHEN")
    ns.Syncer(a_store, a_cli).sync()
    ns.Syncer(b_store, b_cli).sync()
    ns.Syncer(a_store, a_cli).sync()
    check("redigering vinder over sletning (note genskabt på B)", b_store.by_uid(uid) is not None and not b_store.by_uid(uid).dirty)
    check("noten er tilbage på A", a_store.by_uid(uid) is not None and a_store.by_uid(uid).body.startswith("REDIGERET"))

    # -- ugyldigt sync-token -> fuld resync, også med sletning ---------------------------
    gone = next(n for n in a_store.visible() if n.uid == "foreign-1")
    a_cli.delete(gone.href, None)  # slettet på serveren uden at A hørte om det
    a_store.set_meta("sync_token", "http://radicale.org/ns/sync/ugyldig")
    r = ns.Syncer(a_store, a_cli).sync()
    check("ugyldigt token giver fuld resync uden fejl", True)
    check("fuld resync opdager slettet note", a_store.by_uid("foreign-1") is None, r)

    # -- offline: lokale ændringer overlever, og næste sync sender dem -------------------
    dead = ns.DavClient(f"http://127.0.0.1:{free_port()}/anna/noter/", "anna", "hemmelig", timeout=2)
    a_store.add("Skrevet i toget", "ingen dækning")
    try:
        ns.Syncer(a_store, dead).sync()
        check("offline giver NetworkError", False)
    except ns.NetworkError:
        check("offline giver NetworkError", True)
    check("note ligger stadig som ventende lokalt", any(n.title == "Skrevet i toget" for n in a_store.pending()))
    ns.Syncer(a_store, a_cli).sync()
    ns.Syncer(b_store, b_cli).sync()
    check("noten når B, når nettet er tilbage", "Skrevet i toget" in titles(b_store))

    # -- slut: begge enheder er ens --------------------------------------------------------
    ns.Syncer(a_store, a_cli).sync()
    check("A og B ender med samme noter", titles(a_store) == titles(b_store), (titles(a_store), titles(b_store)))
    check("ingen ventende ændringer tilbage", not a_store.pending() and not b_store.pending())
finally:
    server.terminate()
    try:
        server.wait(timeout=5)
    except subprocess.TimeoutExpired:
        server.kill()
    out = (server.stdout.read() or b"").decode("utf-8", "replace").strip()
    if out and FAILED:
        print("\n-- Radicale-log --\n" + out[-2000:])

print(f"\n{'ALLE TESTS BESTÅET' if not FAILED else f'{FAILED} TEST(S) FEJLEDE'}")
sys.exit(1 if FAILED else 0)
