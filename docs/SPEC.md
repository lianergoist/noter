# Specifikation: Noter (Android)

Alt her er afledt af referenceimplementeringen i `reference/` og er afprøvet mod en rigtig Radicale 3.8,
mod jtx Board/DAVx5 og fra et kommandolinjeværktøj. Hvor teksten og referencekoden er uenige, er **koden** facit.

## 1. Mål og afgrænsning

Må:
- vise, søge i, oprette, redigere og slette noter (titel, tekst, tags)
- fungere uden net og sende ændringer senere
- sameksistere med andre klienter på samme samling (jtx Board via DAVx5, webklient, `notesync.py`)

Må ikke / senere:
- Markdown-visning, vedhæftninger, flere konti, delte noter, push-notifikationer
- Idéer til senere: del-tekst-til-ny-note (`ACTION_SEND`), widget, hurtig-note

## 2. Dataformat (VJOURNAL)

Én note = ét objekt i samlingen, filnavn `<UID>.ics` (kun for noter, appen selv opretter; eksisterende
noter bruger den `href`, serveren har givet dem).

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Noter Android//DA
BEGIN:VJOURNAL
UID:3f2c9e1a-7b0e-4c58-9d39-1f6a2c0f5a11
DTSTAMP:20261010T170000Z
CREATED:20261010T170000Z
LAST-MODIFIED:20261010T170000Z
SUMMARY:Indkøbsliste
DESCRIPTION:Mælk\nBrød\nKaffe
CATEGORIES:Privat,Hjem
END:VJOURNAL
END:VCALENDAR
```

Regler (alle dækket af tests i referencen):
- Linjeskift er `CRLF`. **Line folding** ved 75 oktetter (UTF-8); fortsættelseslinjer begynder med ét mellemrum og
  rummer 74 oktetter. Skær aldrig et flerbytetegn over (æøå, emoji).
- **Escaping** af TEXT: `\` → `\\`, `;` → `\;`, `,` → `\,`, linjeskift → `\n`. Ved læsning er `\n` og `\N` linjeskift, og `\x` giver `x`.
- Egenskabsnavne er ikke versalfølsomme. En egenskab kan have parametre (`DESCRIPTION;LANGUAGE=da:...`). Find kolon uden for citationstegn.
- `CATEGORIES` er en kommasepareret liste med `\,` for komma i et tag. Der kan stå flere `CATEGORIES`-linjer; slå dem sammen.
- Tidsstempler skrives som UTC: `yyyyMMdd'T'HHmmss'Z'`. Læs også tidsstempler uden `Z`. Manglende `LAST-MODIFIED` (fremmed note) giver gruppen "Uden dato".
- Objekter uden `VJOURNAL` (fx en `VEVENT`, Radicale håndhæver ikke komponenttypen) eller uden `UID` er **ikke noter**: ignorér dem, men husk deres etag (se `ignored` nedenfor).
- Ny note: `UID` (UUID v4), `DTSTAMP`, `CREATED`, `LAST-MODIFIED`, `SUMMARY`; `DESCRIPTION` og `CATEGORIES` kun hvis de ikke er tomme.
- **Redigering** (`update_ics`): tag den gemte rå tekst, ret kun de felter, der faktisk er ændret, og fornyr `DTSTAMP` og `LAST-MODIFIED`.
  Er intet ændret, returnér den uændrede tekst og send ingenting til serveren. Alle andre linjer (`PRODID`, `SEQUENCE`, `CLASS`, `STATUS`,
  `X-...`, `ATTACH` ...) bevares ordret. jtx Board skriver fx `PRODID:DAVx5/... ical4j/...` og `SEQUENCE:n`; begge skal overleve.
- `SEQUENCE` øges **ikke** (bevidst valg indtil videre; synkroniseringen bruger etag).
- Titel og tekst sammenlignes efter normalisering af `\r\n`/`\r` til `\n`.

## 3. Lokalt lager

`NoteStore`-interface i `:core`, Room-implementering i `:app`, in-memory-implementering til tests.

Tabel `notes`:

| Felt | Type | Betydning |
|---|---|---|
| `uid` | tekst, primærnøgle | noters UID (eller `href:<href>` for ignorerede objekter) |
| `href` | tekst, nullable, unik | afkodet sti på serveren, `null` = ikke sendt endnu |
| `etag` | tekst, nullable | serverens etag **inkl. citationstegn**, sendes uændret tilbage i `If-Match` |
| `raw` | tekst | hele `.ics`-teksten |
| `title`, `body` | tekst | udtrukket af `raw` |
| `tags` | **liste, ikke kommasepareret tekst** | tags kan indeholde komma; brug en TypeConverter med JSON |
| `modified` | tekst | `LAST-MODIFIED` som rå streng (UTC-strenge kan sorteres leksikalsk) |
| `dirty` | bool | lokal ændring der skal sendes |
| `deleted` | bool | slettet lokalt, skal slettes på serveren |
| `ignored` | bool | findes på serveren, men er ikke en note |

Tabel `meta` (nøgle/værdi): `sync_token`. Indstillinger (URL, bruger) gemmes separat.

Visning: `deleted = 0 AND ignored = 0`, sorteret efter `modified` faldende, derefter titel.
Søgning: titel, tekst og tags, uden forskel på store/små bogstaver. Gør den lokalt (evt. FTS senere).
Operationer (se `Store` i `notesync.py`): `byHref`, `byUid`, `pending` (`dirty AND NOT ignored`), `withHref`, `insert`, `putClean`
(erstat række med serverversion, `dirty=0`), `markPushed`, `detach` (`href=null, etag=null, dirty=1, deleted=0`), `remove`, `add`, `edit`, `deleteNote`.
`deleteNote`: uden `href` fjernes rækken helt; ellers `deleted=1, dirty=1`.

## 4. CalDAV (præcis hvad der sendes)

Alle kald bruger `Authorization: Basic ...` og HTTPS. XML-kald har `Content-Type: application/xml; charset=utf-8`.

Hrefs: gem altid den **afkodede** sti (`/bruger/noter/abc.ics`), og kodér pr. stisegment, når der bygges URL'er.
Etags: gem og send dem ordret, med citationstegn.

| Formål | Kald |
|---|---|
| Opret samling (valgfrit) | `MKCALENDAR <samling>` med `supported-calendar-component-set` = kun `VJOURNAL` og `displayname` |
| Find principal (valgfrit) | `PROPFIND /` `Depth: 0` med `current-user-principal`, derefter `PROPFIND <principal>` `Depth: 1` med `displayname`, `resourcetype`, `supported-calendar-component-set`; vælg samlinger der indeholder `VJOURNAL`, helst dem der **kun** gør |
| Hent ændringer | `REPORT <samling>` med `sync-collection` (se nedenfor) |
| Hent indhold | `REPORT <samling>` med `calendar-multiget`, højst 50 hrefs pr. kald |
| Opret | `PUT <samling><uid>.ics`, `If-None-Match: *`, `Content-Type: text/calendar; charset=utf-8` |
| Opdatér | `PUT <href>`, `If-Match: <etag>`, samme content-type |
| Slet | `DELETE <href>`, `If-Match: <etag>` |
| Hent én | `GET <href>` (404 = findes ikke) |
| Etag efter skrivning | headeren `ETag` i svaret; mangler den, `PROPFIND <href>` `Depth: 0` på `getetag` |

`sync-collection` (RFC 6578):

```xml
<?xml version="1.0" encoding="utf-8"?>
<D:sync-collection xmlns:D="DAV:">
  <D:sync-token>TOKEN</D:sync-token>   <!-- tomt element første gang: <D:sync-token/> -->
  <D:sync-level>1</D:sync-level>
  <D:prop><D:getetag/></D:prop>
</D:sync-collection>
```

Svaret er `207` med en `D:response` pr. ændret objekt. En response, hvis `D:status` indeholder ` 404`, er **slettet**;
ellers læses `getetag` fra den `propstat`, hvis status indeholder ` 200`. Spring hrefs der ender på `/` over (selve samlingen).
Nyt token er `D:sync-token` direkte under roden. Afviser serveren et gammelt token (HTTP 403, 409 eller 410),
så start forfra uden token (fuld resync, se nedenfor).

`calendar-multiget`:

```xml
<C:calendar-multiget xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop><D:getetag/><C:calendar-data/></D:prop>
  <D:href>/bruger/noter/abc.ics</D:href> ...
</C:calendar-multiget>
```

XML: brug en **navneområde-bevidst** parser (`DocumentBuilderFactory.setNamespaceAware(true)`) og find elementer via
(navneområde, lokalt navn), aldrig via præfiks. Læs kun direkte børn, så en indlejret `href` ikke forveksles.

Fejl: `401` → `AuthError`. Netværksfejl/timeout → `NetworkError` (ændringer er allerede gemt lokalt). Alt andet uventet → `DavError(status)`.

HTTP-klient: **OkHttp**. `HttpURLConnection` kan ikke sende `PROPFIND`/`REPORT`/`MKCALENDAR`.
Brug `Request.Builder().method("REPORT", body)`; `REPORT` og `PUT` kræver en body, `DELETE` bruger `.delete()`.
Slå cache fra, sæt timeouts (ca. 30 s), og læg `Authorization` i en interceptor, ikke i hver forespørgsel.

## 5. Synkroniseringsalgoritmen (Syncer)

En sync = **pull, derefter push**. Højst én sync ad gangen (`Mutex` plus unikt WorkManager-job).

### Pull

1. `token = meta.sync_token`. `full = (token == null)`.
2. `sync-collection(token)`. Ved ugyldigt token: `full = true`, kør igen uden token.
3. For hvert `(href, etag)` i svaret:
   - `etag == null` (slettet på serveren): hvis der findes en lokal række med den `href`, kald `serverDeleted(lokal)`.
   - ellers hvis der ingen lokal række er, eller dens etag er anderledes: læg `href` på hentelisten.
4. Hvis `full`: alle lokale rækker med `href`, som **ikke** nævnes i svaret (med etag), er slettet på serveren → `serverDeleted`.
5. `multiget` for hentelisten, og kald `adopt(href, etag, raw)` på hvert resultat.
6. Gem det nye `sync_token` **til sidst** (først når alt ovenfor lykkedes).

`serverDeleted(lokal)`:
- Er den lokalt redigeret (`dirty` og ikke `deleted`): behold den, men `detach` (den oprettes igen ved næste push). **Redigering vinder over sletning.**
- Ellers fjern rækken.

`adopt(href, etag, raw)`:
1. Kan `raw` ikke parses som en note (ingen `VJOURNAL`): gem en række med `uid = "href:<href>"`, `ignored = 1`, så etagen er kendt og objektet ikke hentes igen. Færdig.
2. `lokal = byHref(href) ?: byUid(uid)`.
3. Hvis `lokal` er `dirty` og ikke `deleted`, og `(titel, tekst, tags)` er **forskellige** fra serverens: **konflikt**.
   Opret en ny note (nyt UUID) med titlen `"<lokal titel> (konflikt)"` (trimmet), den lokale tekst og tags, `dirty = 1`, uden `href`.
   Er indholdet ens, er der ingen konflikt (to enheder skrev det samme).
4. Erstat `lokal` med serverens version: `putClean(...)` med `dirty = 0`. Serverversionen bliver altså stående i originalen.

### Push

Gentag højst 5 runder, indtil `pending` er tom; er den ikke tom efter 5 runder, er det en fejl.
For hver ventende række:

- **`deleted`**: uden `href` → fjern rækken. Ellers `DELETE` med `If-Match`: `200/204/404` → fjern rækken. `412` → `resolve412`.
- **Ny** (`href == null`): `href = samlingssti + uid + ".ics"`, `PUT` med `If-None-Match: *`.
- **Ændret**: `PUT` med `If-Match: <etag>`.
- Svar `200/201/204` → `markPushed(uid, href, nyEtag)`. `412` → `resolve412`. Andet → `DavError`.

`resolve412(note, href)`: `GET href`.
- `404` (findes ikke længere): var den slettet lokalt, fjern rækken; ellers `detach` (oprettes igen i næste runde).
- ellers `adopt(href, etag, raw)` (samme konfliktregel som ovenfor). En lokal sletning mod en ændret serverversion ender altså med, at noten **genskabes** med serverens indhold.

### Lokale handlinger (ingen netværk)

- Opret: `add` → række med `dirty = 1`, `href = null`.
- Redigér: `update_ics` på `raw`, opdatér udtrukne felter og `modified`, `dirty = 1`. Ingen ændring → ingenting sker.
- Slet: `deleteNote` (se afsnit 3).
- Efter hver lokal ændring planlægges en engangs-sync (med nettilslutning som krav).

## 6. Android-specifikt

- **Sync-triggere:** unik periodisk `WorkManager`-opgave (minimum 15 min), en engangsopgave efter lokale ændringer
  (`NetworkType.CONNECTED`, med backoff), samt når appen kommer i forgrunden, og træk-for-at-opdatere.
  Vis tidspunkt for sidste vellykkede sync og seneste fejl på en rolig måde (ingen dialoger ved offline).
- `AuthError` → bed om nye loginoplysninger, og stop de periodiske forsøg, til de er rettet.
- **Redigering:** editoren skriver til den lokale række (`dirty = 1`) efter ca. 1,2 sekund uden tastetryk og når man forlader skærmen. En tom ny note oprettes ikke.
  Ændres rækken af en sync (ikke `dirty`), mens editoren er åben og brugeren ikke har rørt noget, så opdatér visningen; ellers lad teksten stå.
- **Oprettelse af samling:** knap i indstillingerne ("Opret noter-samling") som kalder `MKCALENDAR`, hvis serveren ikke har nogen.
- **Login:** URL, bruger og adgangskode. Gem adgangskoden med Android Keystore. `androidx.security:security-crypto` er forældet, så vælg den nuværende anbefalede løsning (fx DataStore + Keystore-nøgle eller Tink). Afvis `http://`, undtagen i debug-bygget til `10.0.2.2`/`localhost` via en network security config.
- **Skærme:** liste (grupperet efter dato) → editor, og indstillinger. Søgefelt øverst på listen.
  Grupper: "I dag", "I går", "Seneste 7 dage", derefter måned og år, og til sidst "Uden dato".
  Listepunkt: titel (eller "Uden titel"), første ikke-tomme linje af teksten, tags og klokkeslæt/ugedag/dato.
  Lys/mørk følger systemet. Brug serif til noteindhold, hvis det passer til temaet; udseendet er ikke et krav.
- **Tekster (dansk, som i webklienten):** "Noter", "Ny note", "Søg i noter", "Titel", "Tilføj tags, adskilt med komma", "Skriv her", "Slet",
  "Gemmer", "Gemt", "Ikke gemt", "Ingen noter endnu. Tryk på Ny note for at skrive den første.", "Ingen noter matcher søgningen.",
  "Noten blev ændret et andet sted. Din version er gemt som en ny note med \"(konflikt)\" i titlen.",
  "Kan ikke nå serveren. Dine ændringer gemmes igen, så snart forbindelsen er tilbage.", "Du er ikke logget ind. Log ind igen for at se og gemme dine noter."

## 7. Test

Kernen testes i `:core` med JUnit mod (a) en in-memory `NoteStore` og (b) en rigtig Radicale. Port scenarierne fra `reference/selftest.py`:

- format: rundtur af komma/semikolon/backslash/linjeskift/tags med komma/æøå/emoji; folding ≤ 75 oktetter
- redigering bevarer `CLASS`, `STATUS`, `X-...`, `CATEGORIES`, `PRODID`, parametre på `DESCRIPTION`, `ATTACH` med kolon i citeret parameter; uændret input giver uændret tekst
- to enheder A/B: A opretter to noter og sender; B henter dem; ny sync gør ingenting; redigering og sletning når den anden
- note skrevet af anden klient læses; redigering af den bevarer dens egenskaber på serveren
- **konflikt:** begge redigerer offline → originalen får den enes version, den andens tekst ligger i en ny note med "(konflikt)", og begge ender ens efter sync
- ens redigering begge steder er ingen konflikt
- redigering vinder over sletning; sletning mod ændret serverversion genskaber noten
- ugyldigt sync-token giver fuld resync, som også opdager slettede noter
- offline giver `NetworkError`, og ændringerne ligger tilbage til næste sync
- forkert adgangskode giver `AuthError`
- en `VEVENT` i samlingen ignoreres
- til sidst har A og B de samme noter og ingen ventende ændringer

**Lokal testserver** (som `selftest.py` gør): 

```
python3 -m venv .venv && .venv/bin/pip install radicale
mkdir -p /tmp/radicale-test && printf 'anna:hemmelig\n' > /tmp/radicale-test/users
cat > /tmp/radicale-test/config <<'EOF'
[server]
hosts = 127.0.0.1:5232
[auth]
type = htpasswd
htpasswd_filename = /tmp/radicale-test/users
htpasswd_encryption = plain
[storage]
filesystem_folder = /tmp/radicale-test/collections
EOF
.venv/bin/python -m radicale --config /tmp/radicale-test/config
```

Samlingen oprettes med `MKCALENDAR /anna/noter/` (brug klientens egen `mkcalendar`). Fra en Android-emulator er værten `http://10.0.2.2:5232/`.
Integrationstests mod Radicale springes over, hvis miljøvariablen `NOTES_TEST_URL` ikke er sat, så `./gradlew test` også virker uden server.

## 8. Kendte begrænsninger og åbne valg

- `SEQUENCE` øges ikke (jtx Board gør). Afgør senere, om det skal ændres.
- Tags splittes på komma i editoren; et tag kan derfor ikke selv indeholde komma, når man skriver det (formatet kan godt).
- Konfliktkopier er almindelige noter, som brugeren selv rydder op i.
- Thunderbird viser samlingen som en tom kalender, fordi den ikke kan vise `VJOURNAL`. Det er forventet og kan ikke skjules fra serveren.
- Hele samlingen hentes ved første sync; derefter kun ændringer via `sync-collection`.
