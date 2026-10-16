# DavNotes - Android-klient til VJOURNAL i Radicale

Simpel noteapp til Android. Noterne er `VJOURNAL`-objekter i en CalDAV-samling på ejerens egen
Radicale-server og synkroniseres to-vejs. Der findes allerede en færdig og testet referenceimplementering
i Python og JavaScript. Opgaven er at lave den samme klient i Kotlin, ikke at finde på en ny model.

Læs hele specifikationen, før du skriver kode: @docs/SPEC.md
(Kan filen ikke indlæses automatisk, så åbn `docs/SPEC.md` selv.)

## Reference (læs før du porterer)

- `reference/notesync.py` - hele kernen: iCalendar-laget, CalDAV-klienten, lokalt lager og sync-algoritmen. **Port den funktion for funktion.**
- `reference/selftest.py` - testscenarierne (to enheder mod en rigtig Radicale). Port dem til JUnit.
- `reference/ical.js`, `reference/dav.js`, `reference/app.js` - samme logik i JavaScript plus brugerfladens opførsel (autosave, konflikter, offline).

## Faste beslutninger (ændr dem ikke uden at spørge)

- Kun `VJOURNAL`. Ingen vedhæftninger, én konto. Teksten er ren tekst med let Markdown som ren visning (SPEC afsnit 6); data og sync er uberørt.
- Serveren er sandheden. Appen skal virke offline og sende ændringerne, når der er net.
- Rå `.ics` gemmes ved siden af de udtrukne felter. Ved redigering ændres kun `SUMMARY`, `DESCRIPTION`,
  `CATEGORIES`, `DTSTAMP` og `LAST-MODIFIED`. Alt andet fra andre klienter (jtx Board, DAVx5) skal bevares uændret.
- Ingen note overskrives i blinde: `If-Match`/`If-None-Match` på alle skrivninger. Ved konflikt gemmes brugerens tekst som en ny note med et konfliktmærke i titlen (`conflict_label`, fx " (conflict)" / " (konflikt)", følger appens sprog; referencen bruger altid " (konflikt)"). Der flettes aldrig tekst.
- Eget lille iCalendar-lag, ikke ical4j (for tungt på Android).
- Basic auth kun over HTTPS. Adgangskoden gemmes ikke i klartekst.

## Arkitektur

- Gradle-modul `:core` i ren Kotlin/JVM (ical, dav, sync-motor, et `NoteStore`-interface). Kan testes uden emulator.
- Modul `:app` med Compose (Material 3), Room (implementerer `NoteStore`), WorkManager og OkHttp.
- Manuel afhængighedsstyring er fint; ingen Hilt/Dagger.
- Følg pakkenavn, minSdk og versioner, som projektet allerede har. Foreslå ændringer, ret dem ikke selv.

## Arbejdsregler

- Kør `./gradlew test` før du siger, at noget er færdigt, og `./gradlew assembleDebug` efter ændringer i `:app`.
- Skriv testen først for sync-reglerne (de er det, der er svært at få rigtigt), og hold dem ækvivalente med `selftest.py`.
- Spørg før du tilføjer eller opgraderer afhængigheder.
- Aldrig adgangskoder, URL'er med login eller testservere i commits. Log aldrig `Authorization`-headeren eller notetekst i release-bygget.
- Appen hedder "DavNotes" på alle sprog (pakkenavnet forbliver `dk.rpix.noter`).
- UI-tekster: engelsk er standard i `res/values/strings.xml`; oversættelser i `values-da`, `values-de`, `values-fr` og `values-es`. En ny tekst tilføjes på alle fem sprog.
  Sentence case, korte aktive verber ("New note", "Delete", "Save" / "Ny note", "Slet", "Gem"). Fejl forklarer, hvad der skete og hvad man kan gøre, uden at undskylde.
- Kommunikér med ejeren på dansk. Identifikatorer på engelsk, kommentarer på dansk som i referencekoden.

## Server (ejerens egen)

- Radicale 3.x bag Apache: `https://radicale.rpix.dk/` (webklienten ligger på `https://notes.rpix.dk/notes/`).
- Samlingen hedder typisk `/<bruger>/noter/`. URL, bruger og adgangskode indtastes i appens indstillinger; intet hardcodes.
- Lokal testserver: se afsnittet "Test" i `docs/SPEC.md`.
