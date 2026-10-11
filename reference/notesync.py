#!/usr/bin/env python3
"""notesync - prototype af synkroniseringskernen til en VJOURNAL-noteapp mod Radicale.

Kun standardbibliotek. Tre lag, som kan flyttes næsten 1:1 til Kotlin:

  1. iCalendar-laget  : læs/skriv VJOURNAL uden at miste egenskaber andre klienter har sat
  2. CalDAV-klienten  : MKCALENDAR, REPORT sync-collection, calendar-multiget, PUT, DELETE
  3. Lokalt lager     : SQLite (svarer til Room) + synkroniseringsalgoritmen

Brug (kommandolinje):
  export NOTESYNC_URL=https://example.dk/radicale/anna/noter/
  export NOTESYNC_USER=anna NOTESYNC_PASSWORD=...
  notesync.py mkcol            # opret samlingen (kun VJOURNAL)
  notesync.py add "Indkøb" "Mælk\\nBrød"
  notesync.py ls
  notesync.py sync
"""
from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
import re
import sqlite3
import sys
import uuid
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from urllib.parse import quote, unquote, urlparse
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape as xml_escape

# ============================================================================
# 1. iCalendar-laget (kun VJOURNAL)
# ============================================================================

PRODID = "-//notesync prototype//DA"
CRLF = "\r\n"


def unfold(text: str) -> list[str]:
    """Fjern line folding (RFC 5545 §3.1) og returnér logiske linjer."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines: list[str] = []
    for line in text.split("\n"):
        if line[:1] in (" ", "\t") and lines:
            lines[-1] += line[1:]
        elif line != "":
            lines.append(line)
    return lines


def fold(line: str) -> str:
    """Fold til max 75 oktetter pr. linje uden at skære et UTF-8-tegn over."""
    b = line.encode("utf-8")
    if len(b) <= 75:
        return line
    parts: list[str] = []
    limit = 75
    while len(b) > limit:
        k = limit
        while (b[k] & 0xC0) == 0x80:  # midt i et flerbytetegn: gå tilbage
            k -= 1
        parts.append(b[:k].decode("utf-8"))
        b = b[k:]
        limit = 74  # fortsættelseslinjer starter med et mellemrum
    parts.append(b.decode("utf-8"))
    return (CRLF + " ").join(parts)


def escape_text(s: str) -> str:
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    return (s.replace("\\", "\\\\").replace(";", "\\;")
             .replace(",", "\\,").replace("\n", "\\n"))


_UNESC = re.compile(r"\\(.)", re.S)


def unescape_text(s: str) -> str:
    return _UNESC.sub(lambda m: "\n" if m.group(1) in "nN" else m.group(1), s)


def split_line(line: str) -> tuple[str, str, str]:
    """'NAME;PARAM=x:værdi' -> (NAME, 'PARAM=x', 'værdi'). Kolon i citerede parametre ignoreres."""
    in_quotes = False
    for i, ch in enumerate(line):
        if ch == '"':
            in_quotes = not in_quotes
        elif ch == ":" and not in_quotes:
            name, _, params = line[:i].partition(";")
            return name.upper(), params, line[i + 1:]
    raise ValueError(f"ugyldig iCalendar-linje: {line!r}")


def split_commas(value: str) -> list[str]:
    """Del en kommasepareret TEXT-liste (CATEGORIES) og respekter \\,"""
    out: list[str] = []
    cur: list[str] = []
    i = 0
    while i < len(value):
        c = value[i]
        if c == "\\" and i + 1 < len(value):
            cur.append(value[i:i + 2])
            i += 2
            continue
        if c == ",":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    out.append("".join(cur))
    return [unescape_text(x) for x in out if x != ""]


@dataclass
class ParsedNote:
    uid: str
    title: str
    body: str
    tags: list[str] = field(default_factory=list)
    modified: str = ""


def _vjournal_span(lines: list[str]) -> tuple[int, int]:
    try:
        s = next(i for i, l in enumerate(lines) if l.upper() == "BEGIN:VJOURNAL")
        e = next(i for i in range(s + 1, len(lines)) if lines[i].upper() == "END:VJOURNAL")
    except StopIteration:
        raise ValueError("objektet indeholder ingen VJOURNAL") from None
    return s, e


def parse_ics(raw: str) -> ParsedNote:
    lines = unfold(raw)
    s, e = _vjournal_span(lines)
    props: dict[str, str] = {}
    tags: list[str] = []
    for l in lines[s + 1:e]:
        name, _params, value = split_line(l)
        if name == "CATEGORIES":
            tags += split_commas(value)
        elif name not in props:
            props[name] = value
    if not props.get("UID"):
        raise ValueError("VJOURNAL mangler UID")
    return ParsedNote(
        uid=unescape_text(props["UID"]),
        title=unescape_text(props.get("SUMMARY", "")),
        body=unescape_text(props.get("DESCRIPTION", "")),
        tags=tags,
        modified=props.get("LAST-MODIFIED", ""),
    )


def now_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def serialize(lines: list[str]) -> str:
    return CRLF.join(fold(l) for l in lines) + CRLF


def new_ics(uid: str, title: str, body: str = "", tags=()) -> str:
    t = now_utc()
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", f"PRODID:{PRODID}", "BEGIN:VJOURNAL",
             f"UID:{uid}", f"DTSTAMP:{t}", f"CREATED:{t}", f"LAST-MODIFIED:{t}",
             "SUMMARY:" + escape_text(title)]
    if body:
        lines.append("DESCRIPTION:" + escape_text(body))
    if tags:
        lines.append("CATEGORIES:" + ",".join(escape_text(x) for x in tags))
    lines += ["END:VJOURNAL", "END:VCALENDAR"]
    return serialize(lines)


def update_ics(raw: str, title: str | None = None, body: str | None = None,
               tags: list[str] | None = None) -> str:
    """Ret kun de felter, der faktisk ændres. Alt andet (X-egenskaber, CLASS, STATUS,
    VTIMEZONE, ...) bevares, så vi ikke ødelægger data fra jtx Board o.l."""
    lines = unfold(raw)
    s, e = _vjournal_span(lines)
    cur = parse_ics(raw)
    inner = lines[s + 1:e]
    added: list[str] = []

    def drop(*names: str) -> None:
        nonlocal inner
        inner = [l for l in inner if split_line(l)[0] not in names]

    changed = False
    if title is not None and title != cur.title:
        drop("SUMMARY")
        added.append("SUMMARY:" + escape_text(title))
        changed = True
    if body is not None:
        body = body.replace("\r\n", "\n").replace("\r", "\n")
        if body != cur.body:
            drop("DESCRIPTION")
            if body:
                added.append("DESCRIPTION:" + escape_text(body))
            changed = True
    if tags is not None and list(tags) != cur.tags:
        drop("CATEGORIES")
        if tags:
            added.append("CATEGORIES:" + ",".join(escape_text(x) for x in tags))
        changed = True
    if not changed:
        return raw
    t = now_utc()
    drop("DTSTAMP", "LAST-MODIFIED")
    added += [f"DTSTAMP:{t}", f"LAST-MODIFIED:{t}"]
    return serialize(lines[:s + 1] + inner + added + lines[e:])


# ============================================================================
# 2. CalDAV-klienten
# ============================================================================

NS = {"D": "DAV:", "C": "urn:ietf:params:xml:ns:caldav"}
XML_CT = {"Content-Type": "application/xml; charset=utf-8"}
XML_HEAD = '<?xml version="1.0" encoding="utf-8"?>'


class NetworkError(Exception):
    """Serveren kunne ikke nås (offline, DNS, timeout ...)."""


class DavError(Exception):
    def __init__(self, status: int, msg: str = ""):
        super().__init__(f"HTTP {status}: {msg}" if status else msg)
        self.status = status


class AuthError(DavError):
    pass


class SyncTokenInvalid(Exception):
    pass


def norm_href(href: str) -> str:
    """Gem altid hrefs som afkodede stier, så sammenligning er entydig."""
    return unquote(urlparse(href).path)


def _xml(body: bytes) -> ET.Element:
    try:
        return ET.fromstring(body)
    except ET.ParseError as e:
        raise DavError(0, f"ugyldigt XML-svar fra serveren: {e}") from e


class DavClient:
    def __init__(self, collection_url: str, user: str, password: str, timeout: float = 30):
        if not collection_url.endswith("/"):
            collection_url += "/"
        u = urlparse(collection_url)
        self.collection_url = collection_url
        self.origin = f"{u.scheme}://{u.netloc}"
        self.collection_path = unquote(u.path)
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self.timeout = timeout
        loopback = u.hostname in ("localhost", "127.0.0.1", "::1")
        self.opener = urllib.request.build_opener(
            *([urllib.request.ProxyHandler({})] if loopback else []))
        self.insecure = u.scheme != "https" and not loopback

    def _url(self, path: str) -> str:
        return path if "://" in path else self.origin + quote(path)

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict[str, str] | None = None):
        req = urllib.request.Request(self._url(path), data=body, method=method,
                                     headers={"Authorization": self.auth, **(headers or {})})
        try:
            with self.opener.open(req, timeout=self.timeout) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            data = e.read()
            if e.code == 401:
                raise AuthError(401, "forkert brugernavn eller adgangskode") from e
            return e.code, e.headers, data
        except (urllib.error.URLError, OSError) as e:
            raise NetworkError(str(e)) from e

    # -- samling -----------------------------------------------------------
    def mkcalendar(self, displayname: str = "Noter") -> None:
        body = (f'{XML_HEAD}<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">'
                f'<D:set><D:prop><D:displayname>{xml_escape(displayname)}</D:displayname>'
                f'<C:supported-calendar-component-set><C:comp name="VJOURNAL"/>'
                f'</C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>')
        st, _h, b = self.request("MKCALENDAR", self.collection_url, body.encode(), XML_CT)
        if st not in (200, 201):
            raise DavError(st, b[:200].decode("utf-8", "replace"))

    def get_etag(self, href: str) -> str:
        body = f'{XML_HEAD}<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>'
        st, _h, b = self.request("PROPFIND", href, body.encode(), {**XML_CT, "Depth": "0"})
        if st != 207:
            raise DavError(st, "kunne ikke hente etag")
        etag = _xml(b).findtext(".//D:getetag", None, NS)
        if not etag:
            raise DavError(st, "svar uden etag")
        return etag

    # -- synkronisering ----------------------------------------------------
    def sync_collection(self, token: str | None):
        """RFC 6578. Returnerer ([(href, etag | None for slettet)], nyt_token)."""
        tok = f"<D:sync-token>{xml_escape(token)}</D:sync-token>" if token else "<D:sync-token/>"
        body = (f'{XML_HEAD}<D:sync-collection xmlns:D="DAV:">{tok}<D:sync-level>1</D:sync-level>'
                f'<D:prop><D:getetag/></D:prop></D:sync-collection>')
        st, _h, b = self.request("REPORT", self.collection_url, body.encode(), XML_CT)
        if token and st in (403, 409, 410):
            raise SyncTokenInvalid()
        if st != 207:
            raise DavError(st, b[:200].decode("utf-8", "replace"))
        root = _xml(b)
        changes: list[tuple[str, str | None]] = []
        for resp in root.findall("D:response", NS):
            href = norm_href(resp.findtext("D:href", "", NS))
            if href.endswith("/"):
                continue  # selve samlingen
            if " 404" in resp.findtext("D:status", "", NS):
                changes.append((href, None))
                continue
            etag = None
            for ps in resp.findall("D:propstat", NS):
                if " 200" in ps.findtext("D:status", "", NS):
                    etag = ps.findtext("D:prop/D:getetag", None, NS)
            if etag:
                changes.append((href, etag))
        new_token = root.findtext("D:sync-token", None, NS)
        if not new_token:
            raise DavError(st, "svar uden sync-token")
        return changes, new_token

    def multiget(self, hrefs: list[str]) -> list[tuple[str, str, str]]:
        """Returnerer [(href, etag, ics-tekst)]."""
        out: list[tuple[str, str, str]] = []
        for i in range(0, len(hrefs), 50):
            items = "".join(f"<D:href>{xml_escape(quote(h))}</D:href>" for h in hrefs[i:i + 50])
            body = (f'{XML_HEAD}<C:calendar-multiget xmlns:D="DAV:" '
                    f'xmlns:C="urn:ietf:params:xml:ns:caldav"><D:prop><D:getetag/>'
                    f'<C:calendar-data/></D:prop>{items}</C:calendar-multiget>')
            st, _h, b = self.request("REPORT", self.collection_url, body.encode(),
                                     {**XML_CT, "Depth": "1"})
            if st != 207:
                raise DavError(st, b[:200].decode("utf-8", "replace"))
            for resp in _xml(b).findall("D:response", NS):
                href = norm_href(resp.findtext("D:href", "", NS))
                for ps in resp.findall("D:propstat", NS):
                    if " 200" not in ps.findtext("D:status", "", NS):
                        continue
                    etag = ps.findtext("D:prop/D:getetag", None, NS)
                    data = ps.findtext("D:prop/C:calendar-data", None, NS)
                    if etag and data:
                        out.append((href, etag, data))
        return out

    # -- enkeltobjekter ----------------------------------------------------
    def get(self, href: str):
        st, h, b = self.request("GET", href)
        if st == 404:
            return None
        if st != 200:
            raise DavError(st, f"GET {href}")
        return (h.get("ETag") or self.get_etag(href)), b.decode("utf-8")

    def put(self, href: str, raw: str, etag: str | None):
        """etag=None -> opret (If-None-Match: *), ellers opdatér (If-Match)."""
        headers = {"Content-Type": "text/calendar; charset=utf-8"}
        headers["If-Match" if etag else "If-None-Match"] = etag or "*"
        st, h, _b = self.request("PUT", href, raw.encode("utf-8"), headers)
        if st in (200, 201, 204):
            return st, (h.get("ETag") or self.get_etag(href))
        return st, None

    def delete(self, href: str, etag: str | None) -> int:
        st, _h, _b = self.request("DELETE", href, None, {"If-Match": etag} if etag else {})
        return st


# ============================================================================
# 3. Lokalt lager (svarer til Room-tabellen) og synkroniseringsalgoritmen
# ============================================================================

SCHEMA = """
CREATE TABLE IF NOT EXISTS notes(
  uid TEXT PRIMARY KEY, href TEXT, etag TEXT, raw TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
  tags TEXT NOT NULL DEFAULT '[]', modified TEXT NOT NULL DEFAULT '',
  dirty INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
  ignored INTEGER NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX IF NOT EXISTS notes_href ON notes(href) WHERE href IS NOT NULL;
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
"""


@dataclass
class Note:
    uid: str
    href: str | None
    etag: str | None
    raw: str
    title: str
    body: str
    tags: list[str]
    modified: str
    dirty: bool
    deleted: bool
    ignored: bool


class Store:
    def __init__(self, path: str):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    @staticmethod
    def _note(r: sqlite3.Row | None) -> Note | None:
        if r is None:
            return None
        return Note(r["uid"], r["href"], r["etag"], r["raw"], r["title"], r["body"],
                    json.loads(r["tags"]), r["modified"], bool(r["dirty"]),
                    bool(r["deleted"]), bool(r["ignored"]))

    def _one(self, sql: str, *args) -> Note | None:
        return self._note(self.db.execute(sql, args).fetchone())

    def _many(self, sql: str, *args) -> list[Note]:
        return [self._note(r) for r in self.db.execute(sql, args).fetchall()]

    # -- opslag ------------------------------------------------------------
    def by_href(self, href: str): return self._one("SELECT * FROM notes WHERE href=?", href)
    def by_uid(self, uid: str): return self._one("SELECT * FROM notes WHERE uid=?", uid)
    def pending(self): return self._many("SELECT * FROM notes WHERE dirty=1 AND ignored=0")
    def with_href(self): return self._many("SELECT * FROM notes WHERE href IS NOT NULL")

    def visible(self):
        return self._many("SELECT * FROM notes WHERE deleted=0 AND ignored=0 "
                          "ORDER BY modified DESC, title")

    def find(self, prefix: str) -> Note:
        hits = [n for n in self.visible() if n.uid.startswith(prefix)]
        if len(hits) != 1:
            raise SystemExit(f"'{prefix}' matcher {len(hits)} noter")
        return hits[0]

    def get_meta(self, k: str) -> str | None:
        r = self.db.execute("SELECT v FROM meta WHERE k=?", (k,)).fetchone()
        return r[0] if r else None

    def set_meta(self, k: str, v: str) -> None:
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO meta VALUES(?,?)", (k, v))

    # -- skrivning ---------------------------------------------------------
    def insert(self, uid, href, etag, raw, p: ParsedNote | None, dirty=0, ignored=0) -> None:
        with self.db:
            self.db.execute(
                "INSERT OR REPLACE INTO notes(uid,href,etag,raw,title,body,tags,modified,dirty,"
                "deleted,ignored) VALUES(?,?,?,?,?,?,?,?,?,0,?)",
                (uid, href, etag, raw, p.title if p else "", p.body if p else "",
                 json.dumps(p.tags if p else []), p.modified if p else "", dirty, ignored))

    def put_clean(self, old_uid: str | None, uid: str, href: str, etag: str, raw: str,
                  p: ParsedNote | None) -> None:
        """Erstat en række med serverens version (dirty=0, deleted=0)."""
        with self.db:
            if old_uid:
                self.db.execute("DELETE FROM notes WHERE uid=?", (old_uid,))
        self.insert(uid, href, etag, raw, p, dirty=0, ignored=0 if p else 1)

    def mark_pushed(self, uid: str, href: str, etag: str) -> None:
        with self.db:
            self.db.execute("UPDATE notes SET href=?, etag=?, dirty=0 WHERE uid=?",
                            (href, etag, uid))

    def detach(self, uid: str) -> None:
        """Serveren har mistet noten, men vi har lokale ændringer: opret den igen."""
        with self.db:
            self.db.execute("UPDATE notes SET href=NULL, etag=NULL, dirty=1, deleted=0 "
                            "WHERE uid=?", (uid,))

    def remove(self, uid: str) -> None:
        with self.db:
            self.db.execute("DELETE FROM notes WHERE uid=?", (uid,))

    def add(self, title: str, body: str = "", tags=()) -> Note:
        uid = str(uuid.uuid4())
        raw = new_ics(uid, title, body, tags)
        self.insert(uid, None, None, raw, parse_ics(raw), dirty=1)
        return self.by_uid(uid)

    def edit(self, uid: str, title=None, body=None, tags=None) -> Note:
        n = self.by_uid(uid)
        raw = update_ics(n.raw, title, body, tags)
        if raw != n.raw:
            p = parse_ics(raw)
            with self.db:
                self.db.execute(
                    "UPDATE notes SET raw=?, title=?, body=?, tags=?, modified=?, dirty=1 "
                    "WHERE uid=?", (raw, p.title, p.body, json.dumps(p.tags), p.modified, uid))
        return self.by_uid(uid)

    def delete_note(self, uid: str) -> None:
        n = self.by_uid(uid)
        if n.href is None:
            self.remove(uid)  # aldrig sendt til serveren
        else:
            with self.db:
                self.db.execute("UPDATE notes SET deleted=1, dirty=1 WHERE uid=?", (uid,))


class Syncer:
    """Pull -> push med etag-baseret optimistisk låsning og konfliktkopi."""

    def __init__(self, store: Store, client: DavClient):
        self.store = store
        self.client = client
        self.stats = {"hentet": 0, "slettet_lokalt": 0, "sendt": 0,
                      "slettet_paa_server": 0, "konflikter": 0}

    def sync(self) -> dict[str, int]:
        self.pull()
        self.push()
        return self.stats

    # -- pull --------------------------------------------------------------
    def pull(self) -> None:
        token = self.store.get_meta("sync_token")
        full = token is None
        try:
            changes, new_token = self.client.sync_collection(token)
        except SyncTokenInvalid:
            full = True
            changes, new_token = self.client.sync_collection(None)

        to_fetch: list[str] = []
        for href, etag in changes:
            local = self.store.by_href(href)
            if etag is None:
                if local:
                    self._server_deleted(local)
            elif local is None or local.etag != etag:
                to_fetch.append(href)

        if full:  # fuld resync: alt, vi kender, men serveren ikke nævner, er væk
            on_server = {h for h, e in changes if e is not None}
            for local in self.store.with_href():
                if local.href not in on_server:
                    self._server_deleted(local)

        for href, etag, raw in self.client.multiget(to_fetch):
            self._adopt(href, etag, raw)
        self.store.set_meta("sync_token", new_token)

    def _server_deleted(self, local: Note) -> None:
        if local.dirty and not local.deleted:
            self.store.detach(local.uid)  # redigeret lokalt, slettet andetsteds: behold
        else:
            self.store.remove(local.uid)
            self.stats["slettet_lokalt"] += 1

    def _adopt(self, href: str, etag: str, raw: str) -> None:
        """Tag serverens version ind. Er noten også ændret lokalt, gemmes den lokale
        version som en ny note med '(konflikt)' i titlen."""
        try:
            p = parse_ics(raw)
        except ValueError:
            old = self.store.by_href(href)
            self.store.put_clean(old.uid if old else None, f"href:{href}", href, etag, raw, None)
            return
        local = self.store.by_href(href) or self.store.by_uid(p.uid)
        self.stats["hentet"] += 1
        if local and local.dirty and not local.deleted:
            if (local.title, local.body, local.tags) != (p.title, p.body, p.tags):
                self.stats["konflikter"] += 1
                copy_uid = str(uuid.uuid4())
                copy_raw = new_ics(copy_uid, f"{local.title} (konflikt)".strip(),
                                   local.body, local.tags)
                self.store.insert(copy_uid, None, None, copy_raw, parse_ics(copy_raw), dirty=1)
        self.store.put_clean(local.uid if local else None, p.uid, href, etag, raw, p)

    # -- push --------------------------------------------------------------
    def push(self) -> None:
        for _ in range(5):
            pending = self.store.pending()
            if not pending:
                return
            for n in pending:
                self._push_one(n)
        raise DavError(0, "lokale ændringer kunne ikke sendes efter 5 forsøg")

    def _push_one(self, n: Note) -> None:
        c, st = self.client, self.store
        if n.deleted:
            if not n.href:
                st.remove(n.uid)
                return
            code = c.delete(n.href, n.etag)
            if code in (200, 204, 404):
                st.remove(n.uid)
                self.stats["slettet_paa_server"] += 1
            elif code == 412:
                self._resolve_412(n, n.href)
            else:
                raise DavError(code, f"DELETE {n.href}")
            return
        create = n.href is None
        href = c.collection_path + n.uid + ".ics" if create else n.href
        code, etag = c.put(href, n.raw, None if create else n.etag)
        if code in (200, 201, 204):
            st.mark_pushed(n.uid, href, etag)
            self.stats["sendt"] += 1
        elif code == 412:
            self._resolve_412(n, href)
        else:
            raise DavError(code, f"PUT {href}")

    def _resolve_412(self, n: Note, href: str) -> None:
        got = self.client.get(href)
        if got is None:  # findes ikke længere på serveren
            if n.deleted:
                self.store.remove(n.uid)
            else:
                self.store.detach(n.uid)  # oprettes igen i næste runde
            return
        etag, raw = got
        self._adopt(href, etag, raw)


# ============================================================================
# Kommandolinje
# ============================================================================

def _client(args) -> DavClient:
    if not args.url or not args.user:
        raise SystemExit("angiv --url og --user (eller NOTESYNC_URL / NOTESYNC_USER)")
    pw = os.environ.get("NOTESYNC_PASSWORD") or (
        getpass.getpass("Adgangskode: ") if sys.stdin.isatty() else "")
    c = DavClient(args.url, args.user, pw)
    if c.insecure:
        print("ADVARSEL: Basic auth over ukrypteret HTTP - brug https", file=sys.stderr)
    return c


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="VJOURNAL-notesync (prototype)")
    ap.add_argument("--db", default=os.environ.get("NOTESYNC_DB", os.path.expanduser("~/.notesync.db")))
    ap.add_argument("--url", default=os.environ.get("NOTESYNC_URL"))
    ap.add_argument("--user", default=os.environ.get("NOTESYNC_USER"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("mkcol", help="opret samlingen på serveren")
    sub.add_parser("sync", help="synkronisér med serveren")
    sub.add_parser("ls", help="list noter")
    p = sub.add_parser("show"); p.add_argument("uid")
    p = sub.add_parser("add"); p.add_argument("title"); p.add_argument("body", nargs="?", default="")
    p.add_argument("--tags", default="")
    p = sub.add_parser("edit"); p.add_argument("uid")
    p.add_argument("--title"); p.add_argument("--body", help="'-' læser fra stdin"); p.add_argument("--tags")
    p = sub.add_parser("rm"); p.add_argument("uid")
    args = ap.parse_args(argv)

    store = Store(args.db)
    try:
        if args.cmd == "mkcol":
            _client(args).mkcalendar()
            print("samling oprettet")
        elif args.cmd == "sync":
            print(Syncer(store, _client(args)).sync())
        elif args.cmd == "ls":
            for n in store.visible():
                print(f"{n.uid[:8]} {'*' if n.dirty else ' '} {n.title}")
        elif args.cmd == "show":
            n = store.find(args.uid)
            print(f"# {n.title}\n{', '.join(n.tags)}\n\n{n.body}")
        elif args.cmd == "add":
            tags = [t for t in args.tags.split(",") if t]
            print(store.add(args.title, args.body.replace("\\n", "\n"), tags).uid)
        elif args.cmd == "edit":
            n = store.find(args.uid)
            body = sys.stdin.read() if args.body == "-" else args.body
            tags = None if args.tags is None else [t for t in args.tags.split(",") if t]
            store.edit(n.uid, args.title, body, tags)
        elif args.cmd == "rm":
            store.delete_note(store.find(args.uid).uid)
    except AuthError as e:
        print(f"fejl: {e}", file=sys.stderr)
        return 3
    except NetworkError as e:
        print(f"serveren kunne ikke nås ({e}); lokale ændringer er gemt til næste sync",
              file=sys.stderr)
        return 2
    except DavError as e:
        print(f"serverfejl: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
