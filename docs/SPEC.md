# Specifikation: DavNotes (Android)

Alt her er afledt af referenceimplementeringen i `reference/` og er afprøvet mod en rigtig Radicale 3.8,
mod jtx Board/DAVx5 og fra et kommandolinjeværktøj. Hvor teksten og referencekoden er uenige, er **koden** facit.

## 1. Mål og afgrænsning

Må:
- vise, søge i, oprette, redigere og slette noter (titel, tekst, tags)
- fungere uden net og sende ændringer senere
- sameksistere med andre klienter på samme samling (jtx Board via DAVx5, webklient, `notesync.py`)
- vise let formatering (overskrifter, fed, kursiv, lister, afkrydsningslister, citater, kode, indrykning) i noteteksten, skrevet som Markdown (afsnit 6, fra v1.2)
- vise URL'er i teksten som links og åbne dem i browseren (afsnit 6, fra v1.2)
- åbne tekst- og Markdown-filer og delt tekst som nye noter og dele en note som Markdown-fil (afsnit 7, fra v1.2)

Må ikke / senere:
- fuld Markdown (`[tekst](url)`, billeder, kodeblokke, tabeller, indlejrede citater), redigering af filer direkte (en fil er aldrig en note), forhåndsvisning i særskilt tilstand, vedhæftninger, flere konti, delte noter, push-notifikationer
- Idéer til senere: widget, hurtig-note

## 2. Dataformat (VJOURNAL)

Én note = ét objekt i samlingen, filnavn `<UID>.ics` (kun for noter, appen selv opretter; eksisterende
noter bruger den `href`, serveren har givet dem).

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//DavNotes Android//EN
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
   Opret en ny note (nyt UUID) med titlen `"<lokal titel> <konfliktmærke>"` (trimmet), den lokale tekst og tags, `dirty = 1`, uden `href`.
   Konfliktmærket følger appens sprog (`conflict_label`: "(conflict)", "(konflikt)", "(Konflikt)", "(conflit)", "(conflicto)");
   `Syncer` har "(konflikt)" som standard ligesom referencen.
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

## 6. Formatering (Markdown, fra v1.2)

Noteteksten (`DESCRIPTION`) kan indeholde en lille delmængde af Markdown. Det er **kun visning**: teksten gemmes,
sammenlignes, synkroniseres og søges præcis som før, tegn for tegn. iCal-laget, `update_ics`, konfliktreglen og
sync-motoren ændres ikke. Andre klienter (webklient, jtx Board, `notesync.py`) ser markeringerne som almindelig tekst.
Titlen formateres ikke. Formatering er altid slået til; der er ingen indstilling.

Parseren ligger i `:core` (`Markdown.kt`, ren Kotlin, ingen afhængigheder) og testes i `MarkdownTest.kt`.
Ingen tredjepartsbiblioteker (Markwon, compose-markdown o.l.).

### Delmængden

Alt gælder **linje for linje**. Linjeskift er `\n`. Inline-formatering krydser aldrig et linjeskift.

| Konstruktion | Skrives | Regel |
|---|---|---|
| Overskrift | `# `, `## ` ... `###### ` i linjens start | 1–6 `#` efterfulgt af mellemrum eller linjeslut. Ingen indrykning foran. `#tag` og `####### x` er ikke overskrifter. |
| Punktliste | `- `, `* ` eller `+ ` | Efter eventuel indrykning. Mellemrum efter tegnet er påkrævet (`-x` og `---` er tekst). |
| Nummereret liste | `1. ` eller `1) ` | 1–9 cifre, `.` eller `)`, mellemrum. `1.5 kg` er tekst. |
| Afkrydsning | `- [ ] ` / `- [x] ` | Punktliste (`-`, `*`, `+`) med `[ ]`, `[x]` eller `[X]` efterfulgt af mellemrum eller linjeslut. `- [] a`, `- [ ]a` og `1. [ ] a` er ikke afkrydsning. |
| Citat | `> ` | Efter eventuel indrykning, `>` efterfulgt af mellemrum eller linjeslut (`>a` er tekst). Indholdet får kun inline-formatering; `> - a` er et citat med teksten `- a`, ikke en liste. |
| Indrykning | mellemrum/tab i linjens start | Niveau = `(kolonner + 2) / 4` (heltal), hvor tab = 4 kolonner. Altså 2–5 mellemrum = niveau 1, 6–9 = niveau 2. |
| Fed | `**tekst**` | |
| Kursiv | `*tekst*` eller `_tekst_` | `_` kun ved ordgrænser (`snake_case_navn` er tekst); `*` også inde i ord (`a*b*c`). |
| Fed og kursiv | `***tekst***` | Kursiv yderst, fed inderst (som CommonMark). |
| Link | `https://…`, `http://…`, `www.…` | Ved ordgrænse, frem til blanktegn eller `<`/`>`. Tegnsætning til sidst (`.,;:!?'"*_`) og en ulige `)`/`]` hører ikke med. Inden i et link er der ingen fed, kursiv eller escape. Andre skemaer (`ftp:`, `mailto:`, `javascript:`) er tekst. |
| Kode | `` `tekst` `` | Et løb af n backticks lukkes af det næste løb af præcis n på samme linje (` ``a`b`` ` er koden `a`b`). Inden i kode er der ingen fed, kursiv, link eller escape. Uden partner er backticks tekst. |
| Escape | `\*`, `\_`, `\#`, `\\` ... | Backslash foran ASCII-tegnsætning gør tegnet bogstaveligt. Foran andet (`C:\sti`) er backslash bare tekst. |

Regler for `*`/`_` (forenklet CommonMark): en åbnende markering må ikke følges af blanktegn, en lukkende må ikke have
blanktegn foran, og indholdet må ikke være tomt. `2 * 3 * 4`, `** x**` og `****` er derfor tekst, og det samme er
en markering uden partner (`**ufærdig`). Fed og kursiv kan indlejres (`**fed *kursiv* fed**`) og bruges i overskrifter
og listepunkter.

Alt andet (`[tekst](url)`, kodeblokke med ` ``` `, indlejrede citater, tabeller, vandrette streger, overskrifter med `===`) er almindelig tekst.

### API (`:core`)

```kotlin
enum class MdKind { HEADING, BULLET, NUMBERED, INDENT, BOLD, ITALIC, MARKER, LINK, QUOTE, TASK, CHECKBOX, DONE, CODE }
data class MdSpan(val kind: MdKind, val start: Int, val end: Int, val level: Int = 0)
data class MdEdit(val text: String, val selStart: Int, val selEnd: Int)

object Markdown {
    fun spans(text: String): List<MdSpan>
    fun plain(text: String): String
    fun toggleBold(text: String, selStart: Int, selEnd: Int): MdEdit
    fun toggleItalic(text: String, selStart: Int, selEnd: Int): MdEdit
    fun cycleHeading(text: String, selStart: Int, selEnd: Int): MdEdit
    fun toggleBullets(text: String, selStart: Int, selEnd: Int): MdEdit
    fun toggleNumbers(text: String, selStart: Int, selEnd: Int): MdEdit
    fun toggleTask(text: String, selStart: Int, selEnd: Int): MdEdit
    fun toggleQuote(text: String, selStart: Int, selEnd: Int): MdEdit
    fun toggleCode(text: String, selStart: Int, selEnd: Int): MdEdit
    fun indent(text: String, selStart: Int, selEnd: Int): MdEdit
    fun outdent(text: String, selStart: Int, selEnd: Int): MdEdit
    fun continueList(text: String, cursor: Int): MdEdit?
    fun urlAt(text: String, selStart: Int, selEnd: Int): String?
}
```

Positioner er UTF-16-indeks (`String`-indeks, som Compose bruger), `start` inklusiv, `end` eksklusiv.
Redigeringsfunktionerne forventer `selStart <= selEnd` (UI'et sender `min`/`max` af `TextRange`).
Rækkefølgen af spans er ikke fastlagt.

`spans`:
- `HEADING` dækker hele linjen (uden `\n`), `level` = antal `#`.
- `BULLET`/`NUMBERED` dækker hele linjen inkl. indrykning, `level` = indrykningsniveau.
- `TASK` dækker et afkrydsningspunkt som `BULLET` (og der er ingen `BULLET` for linjen). `CHECKBOX` dækker `[ ]`/`[x]`,
  `level` = 1, hvis det er afkrydset. `DONE` dækker teksten efter et afkrydset felt (UI'et gennemstreger og dæmper den).
  UI'et viser `- [ ]` som `☐` og `- [x]` som `☑` på stregens plads; de øvrige tegn bliver nulbredde-mellemrum.
- `QUOTE` dækker hele citatlinjen inkl. indrykning, `level` = indrykningsniveau. UI'et gør `>` usynligt og tegner en
  gul lodret streg (5 dp) på dets plads ud fra tekstlayoutet (derfor `BasicTextField` med `TextFieldDefaults.DecorationBox`,
  ikke `ParagraphStyle`, som giver forkerte markørpositioner). Teksten efter `> ` står derved let indrykket.
- `CODE` dækker teksten mellem backticks; UI'et viser den i fast bredde på grå baggrund (0,9 em).
- `INDENT` dækker en indrykket linje, der ikke er et listepunkt (kun niveau ≥ 1), `level` = niveau.
- `BOLD`/`ITALIC` dækker alt mellem den åbnende og den lukkende markering (inkl. indlejrede markeringer).
- `LINK` dækker URL'en; UI'et understreger og farver den og viser den lidt mindre (0,9 em).
- `MARKER` dækker syntakstegn, som UI'et viser dæmpet: `#`-præfikset med mellemrum, listemarkeringen med
  mellemrum (`- `, `12. `, `> `; for afkrydsning kun `- `), `*`/`_`-markeringer, backticks og escape-backslash. Tilstødende `MARKER`-spans slås sammen.

`plain` (til listens forhåndsvisning): fjerner `#`-præfiks, `> `, `*`/`_`-markeringer, backticks og escape-backslash,
skriver `- `/`* `/`+ ` som `• `, `- [ ] ` som `☐ ` og `- [x] ` som `☑ ` og lader nummerering og indrykning stå.

### Værktøjslinje (redigeringsfunktionerne)

En række knapper over tastaturet, kun når tekstfeltet har fokus: **Fed**, **Kursiv**, **Overskrift**, **Punktliste**,
**Nummereret liste**, **Afkrydsningsliste**, **Citat**, **Kode**, **Ryk ud**, **Ryk ind**. Knapperne er 40 dp med
20 dp-ikoner, så alle ti er synlige på en almindelig telefon. Ikoner som egne vektor-drawables (Material Symbols, Apache 2.0), ikke
`material-icons-extended`. Hver knap har en `contentDescription` på alle fem sprog. Editoren skifter fra `String` til
`TextFieldValue` for at kende markeringen; stylingen sker med en `VisualTransformation` med identitets-offsetmapping
(markeringerne bliver stående, men dæmpes).

- `toggleBold`/`toggleItalic`: Markeret tekst omkranses af `**`/`*`. Blanktegn i kanten af markeringen lægges uden for
  (`" fed "` → `" **fed** "`). Er teksten allerede omkranset (markeringerne lige uden for eller som en del af
  markeringen), fjernes de i stedet. Kursiv tæller kun som omkranset, hvis `*`-løbet har længde 1 eller 3, så
  `**fed**` + kursiv giver `***fed***`. Uden markering indsættes `****`/`**` med markøren i midten; står markøren midt
  i et tomt par, fjernes parret. Strækker markeringen sig over flere linjer, omkranses hver ikke-tom linje for sig.
  Den markerede tekst forbliver markeret (uden markeringstegnene).
- `toggleCode`: som kursiv, men med `` ` `` (omkranset kun ved et løb af præcis én backtick).
- `cycleHeading`: Linjerne under markeringen går ingen → `# ` → `## ` → `### ` → ingen. Næste trin bestemmes af den
  første ikke-tomme linje og gælder for alle linjerne. En listemarkering erstattes.
- `toggleBullets`/`toggleNumbers`: Er alle ikke-tomme linjer under markeringen allerede den slags liste, fjernes
  markeringen. Ellers får de den (den anden listetype eller en overskrift erstattes). Indrykning bevares.
  Nummerering starter ved 1 og tælles op pr. indrykningsniveau; et dybere niveau starter forfra efter et mindre
  indrykket punkt.
- `toggleTask`: Linjer, der ikke er afkrydsningspunkter, bliver det (`- [ ] `; en anden listemarkering eller
  overskrift erstattes, eksisterende punkter beholder deres kryds). Er alle allerede punkter, krydses de alle af,
  eller krydsene fjernes, hvis alle var afkrydset. Et tryk i teksten krydser aldrig af. Punktliste-knappen gør et
  afkrydsningspunkt til et almindeligt punkt.
- `toggleQuote`: Som punktliste: er alle linjer citater, fjernes `> `; ellers indsættes `> ` efter indrykningen.
  En listemarkering bliver stående (som tekst i citatet).
- `indent`/`outdent`: Ryk ind indsætter 4 mellemrum foran hver ikke-tom linje. Ryk ud fjerner ét tab eller op til
  4 mellemrum.
- Linjefunktionerne springer tomme linjer over, når markeringen dækker flere linjer (på en enkelt tom linje
  virker de, så man kan starte en liste). Er markeringen tom, følger markøren det tegn, den stod ved; står den
  i en markering eller indrykning, der fjernes eller udskiftes, havner den lige efter det nye præfiks. Er markeringen ikke tom, dækker resultatet de berørte linjer
  helt (første linjes start til sidste linjes slut).

**Åbn link:** Står markøren i en URL (også lige før eller efter den), eller ligger markeringen inden i en, viser
værktøjslinjen en ekstra knap fast yderst til højre, **Åbn link** (`urlAt`); formateringsknapperne til venstre kan rulles. Den åbner URL'en med `ACTION_VIEW` i standardbrowseren;
`www.…` får `https://` foran. Et tryk i teksten sætter altid markøren og åbner aldrig et link. Der er ingen websøgning
på markeret tekst, som ikke er en URL.

**Fortsæt liste ved Enter** (`continueList(text, cursor)`): Editoren kalder den, når en ændring er præcis ét indsat
`\n` (ikke ved indsæt af tekst). Er linjen før markøren et listepunkt eller et citat, får den nye linje samme indrykning og markering;
nummererede punkter får næste nummer (`3. ` → `4. `, `9) ` → `10) `), og afkrydsningspunkter starter uafkrydset. Enter midt i et punkt deler det. Er punktet tomt,
og er den nye linje også tom, fjernes punktet, og listen slutter ("Enter to gange"). Et tomt indrykket punkt afslutter
listen helt i stedet for at rykke ud. Efterfølgende numre omnummereres ikke. Overskrifter og indrykket tekst uden
listemarkering fortsættes ikke.

## 7. Android-specifikt

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
- **Filer** (`NoteFile` i `:core`, `FileImport` i `:app`). En fil er aldrig en note: åbning opretter en ny note, der
  synkroniseres som alle andre, og at dele sender en kopi. Ingen tilladelser; alt går gennem Androids filvælger og delemenu.
  - *Åbn fil* (knap i listen, `ACTION_GET_CONTENT` med typen `text/*` (så billedapps ikke tilbydes) og `EXTRA_MIME_TYPES` = `text/*` og
    `application/octet-stream`, fordi `.md` ofte har den sidste type; `GET_CONTENT` og ikke `OPEN_DOCUMENT`, så
    filvælgeren også viser andre apps, fx filhåndteringer, og filen læses kun én gang) og *Åbn med / Del* fra andre apps (`intent-filter` for `ACTION_VIEW` og `ACTION_SEND` med
    `text/plain`, `text/markdown`, `text/x-markdown`). Den nye note åbnes i editoren.
  - Indlæsning: højst 1 MB. UTF-8 (BOM fjernes), UTF-16 med BOM, ellers windows-1252. Nul-bytes = ikke tekst.
    Linjeskift normaliseres til `\n`, og tomme linjer til sidst fjernes.
  - Titel: er første linje `# Titel` (niveau 1), bliver den titlen, og tomme linjer efter den fjernes; ellers bruges
    filnavnet uden endelse. Ved delt tekst bliver `EXTRA_SUBJECT` titlen, hvis det findes. Tags kommer ikke med.
    En fil, der hverken giver titel eller tekst, opretter ingen note.
  - *Del* (ikon i editorens topbjælke): noten skrives som Markdown-fil (`# Titel`, tom linje, teksten; kan åbnes
    igen til samme titel og tekst) i `cacheDir/shared/` og sendes med `ACTION_SEND` (`text/markdown`, `EXTRA_STREAM`
    via `FileProvider`, titlen som `EXTRA_SUBJECT`) gennem systemets delemenu, så enhver app, der tager imod filer,
    kan gemme eller sende den (filhåndteringer, Drive, Nextcloud, mail). DavNotes selv udelades af menuen. Tidligere
    delte filer slettes, før en ny skrives. Filnavnet er titlen uden `/\:*?"<>|` og kontroltegn, højst 80 tegn,
    ellers "Note", med endelsen `.md`. Der er ingen *Gem som* med `ACTION_CREATE_DOCUMENT` (systemets gem-dialog viser
    kun lagerudbydere, ikke fx Material Files).
  - Fejl vises roligt: "Kan ikke åbne filen. Den er for stor, tom eller ikke en tekstfil." / "Kan ikke dele noten.
    Prøv igen."
- **Sprog:** engelsk er standard; dansk, tysk, fransk og spansk er oversættelser. Datoer formateres i telefonens sprog.
  Appen hedder "DavNotes" på alle sprog. Valg af sprog pr. app i systemindstillingerne (Android 13+).
- **Tekster (dansk oversættelse, som i webklienten):** "Noter", "Ny note", "Søg i noter", "Titel", "Tilføj tags, adskilt med komma", "Skriv her", "Slet",
  "Gemmer", "Gemt", "Ikke gemt", "Ingen noter endnu. Tryk på Ny note for at skrive den første.", "Ingen noter matcher søgningen.",
  "Noten blev ændret et andet sted. Din version er gemt som en ny note med \"(konflikt)\" i titlen.",
  "Kan ikke nå serveren. Dine ændringer gemmes igen, så snart forbindelsen er tilbage.", "Du er ikke logget ind. Log ind igen for at se og gemme dine noter."

## 8. Test

Kernen testes i `:core` med JUnit mod (a) en in-memory `NoteStore` og (b) en rigtig Radicale. Port scenarierne fra `reference/selftest.py`:

- format: rundtur af komma/semikolon/backslash/linjeskift/tags med komma/æøå/emoji; folding ≤ 75 oktetter
- redigering bevarer `CLASS`, `STATUS`, `X-...`, `CATEGORIES`, `PRODID`, parametre på `DESCRIPTION`, `ATTACH` med kolon i citeret parameter; uændret input giver uændret tekst
- to enheder A/B: A opretter to noter og sender; B henter dem; ny sync gør ingenting; redigering og sletning når den anden
- note skrevet af anden klient læses; redigering af den bevarer dens egenskaber på serveren
- **konflikt:** begge redigerer offline → originalen får den enes version, den andens tekst ligger i en ny note med konfliktmærket, og begge ender ens efter sync
- ens redigering begge steder er ingen konflikt
- redigering vinder over sletning; sletning mod ændret serverversion genskaber noten
- ugyldigt sync-token giver fuld resync, som også opdager slettede noter
- offline giver `NetworkError`, og ændringerne ligger tilbage til næste sync
- forkert adgangskode giver `AuthError`
- en `VEVENT` i samlingen ignoreres
- Markdown (`MarkdownTest`): overskrifter, lister, afkrydsning, citater, kode, indrykning, fed/kursiv inkl. indlejring, falske positiver
  (`snake_case`, `2 * 3`, ufærdige markeringer), escapes, `plain`, værktøjslinjens funktioner med markør/markering
  og fortsættelse/afslutning af lister ved Enter; links og `urlAt`
- filer (`NoteFileTest`): tegnsæt, binære filer, titel fra `# Titel` eller filnavn, linjeskift, rundtur af
  `toMarkdown`, filnavne
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

## 9. Kendte begrænsninger og åbne valg

- `SEQUENCE` øges ikke (jtx Board gør). Afgør senere, om det skal ændres.
- Tags splittes på komma i editoren; et tag kan derfor ikke selv indeholde komma, når man skriver det (formatet kan godt).
- Konfliktkopier er almindelige noter, som brugeren selv rydder op i.
- Thunderbird viser samlingen som en tom kalender, fordi den ikke kan vise `VJOURNAL`. Det er forventet og kan ikke skjules fra serveren.
- Hele samlingen hentes ved første sync; derefter kun ændringer via `sync-collection`.
- Markdown: indrykning på 4 mellemrum foran almindelig tekst er en kodeblok i CommonMark, så andre Markdown-visere
  kan vise den anderledes. Inline-formatering på tværs af linjer understøttes ikke (det gør CommonMark).
  Søgning sker i den rå tekst, så `**` tæller med.
- Filer: åbner man den samme fil to gange, bliver det til to noter. Nogle filhåndteringer melder `.md` som
  `application/octet-stream`, og så tilbydes DavNotes ikke under "Åbn med" (men filen kan åbnes fra appen).
