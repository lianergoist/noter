# DavNotes

Enkel noteapp til Android. Noterne gemmes som `VJOURNAL`-objekter i en CalDAV-samling på din egen
[Radicale](https://radicale.org/)-server og synkroniseres begge veje. Appen virker uden net og sender
ændringerne, når forbindelsen er tilbage.

Appen kan dele samling med andre klienter, fx jtx Board via DAVx5. Egenskaber, som andre klienter har
sat, bevares uændret, når du redigerer en note.

## Funktioner

- Opret, redigér, søg i og slet noter med titel, tekst og tags.
- Let formatering skrevet som Markdown: overskrifter, fed, kursiv, lister og indrykning, med en
  værktøjslinje over tastaturet. Lister fortsætter ved Enter. Noterne forbliver ren tekst, som andre
  klienter kan læse.
- Links i teksten kan åbnes i browseren.
- Åbn tekst- og Markdown-filer som nye noter, også via "Åbn med" og "Del", og del en note som fil.
- Gemmer automatisk, mens du skriver, og når du forlader en note.
- Fungerer offline. Ændringer sendes ved næste synkronisering.
- Synkroniserer, når appen åbnes, efter hver ændring, ved træk-for-at-opdatere og cirka hvert kvarter.
- Ingen note overskrives i blinde. Er en note ændret både her og et andet sted, gemmes din version
  som en ny note med "(konflikt)" i titlen ("(conflict)" på engelsk osv.). Tekst flettes aldrig.
- Finder dine samlinger på serveren og kan oprette en ny noter-samling.
- Adgangskoden krypteres med en nøgle i Android Keystore. Kun HTTPS.
- På engelsk, dansk, tysk, fransk og spansk. Følger telefonens sprog, og på Android 13+ kan sproget
  vælges for appen alene i systemindstillingerne.

Ikke med (bevidst): fuld Markdown (fx tabeller, billeder og `[tekst](url)`), vedhæftninger, flere konti og delte noter.

## Kom i gang

Kræver Android 7.0 (API 24) eller nyere og en CalDAV-server, der understøtter `sync-collection`
(RFC 6578). Appen er udviklet og testet mod Radicale 3.8.

1. Byg og installér appen (se nedenfor).
2. Åbn indstillingerne, og skriv adressen til samlingen, fx `https://radicale.example.dk/bruger/noter/`,
   samt bruger og adgangskode.
3. Kender du ikke stien, så skriv serverens adresse og tryk **Find samlinger**. Findes der ingen
   noter-samling, så tryk **Opret noter-samling**.
4. Tryk **Gem**. Appen tjekker login og samling og begynder at synkronisere.

## Byg

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # usigneret release-APK
```

Byggeriet bruger Gradle-wrapperen, AGP 9.4 og Kotlin 2.2. Åbn projektet i Android Studio, eller
byg fra kommandolinjen med `local.properties`, der peger på Android SDK'et.

Debug-bygget har pakkenavnet `dk.rpix.noter.debug` og hedder "DavNotes debug", så det kan ligge på
telefonen ved siden af release-bygget. Det tillader `http://` til `10.0.2.2`, `localhost` og
`127.0.0.1`, så det kan bruge en lokal testserver. Release-bygget kræver HTTPS.

## Opbygning

| Modul | Indhold |
|---|---|
| `:core` | Ren Kotlin/JVM: iCalendar-laget (`Ical`), CalDAV-klienten (`DavClient` på OkHttp), `NoteStore`-interfacet og synkroniseringsalgoritmen (`Syncer`). Kan testes uden emulator. |
| `:app` | Compose og Material 3, Room (implementerer `NoteStore`), WorkManager til synkronisering og DataStore til indstillinger. |

Kernen er en direkte port af referenceimplementeringen i `reference/` (Python og JavaScript).
Den fulde specifikation af dataformat, CalDAV-kald og synkroniseringsregler står i
[`docs/SPEC.md`](docs/SPEC.md).

```
app/        Android-appen
core/       Kernen (ical, dav, sync)
docs/       Specifikation
reference/  Referenceimplementering (notesync.py, selftest.py, webklient)
tools/      Hjælpescripts (app-ikonet)
```

## Test

### Unit tests

```
./gradlew test
```

Kører iCalendar-tests og alle synkroniseringsscenarier mod en CalDAV-server i hukommelsen. Kræver
ingen server og ingen telefon. Scenarierne er porteret fra `reference/selftest.py`: to enheder,
konflikter, redigering mod sletning, ugyldigt sync-token, offline og forkert adgangskode.

### Mod en rigtig Radicale

De samme scenarier kan køre mod en lokal Radicale. Hver test opretter sin egen samling og sletter
den igen.

```
python3 -m venv .venv && .venv/bin/pip install radicale
```

Opsæt og start serveren som beskrevet i afsnittet "Test" i [`docs/SPEC.md`](docs/SPEC.md). Kør så:

```
NOTES_TEST_URL=http://127.0.0.1:5232/anna/noter/ ./gradlew test
```

Bruger og adgangskode tages fra `NOTES_TEST_USER` og `NOTES_TEST_PASSWORD` (standard `anna` og
`hemmelig`, som i specifikationens testopsætning). Uden `NOTES_TEST_URL` springes disse tests over.

### På telefonen

`NewNoteButtonTest` er en instrumenteret Compose-test, der tjekker, at Ny note-knappen kan læses af
TalkBack. Telefonen skal være låst op.

```
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dk.rpix.noter.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Undgå `./gradlew connectedAndroidTest` på en telefon, du bruger. Den afinstallerer appen bagefter,
og så forsvinder indstillinger og noter, der ikke er sendt endnu.

En lokal testserver kan nås fra telefonen med `adb reverse tcp:5232 tcp:5232` og adressen
`http://localhost:5232/anna/noter/`. Fra emulatoren hedder den `http://10.0.2.2:5232/anna/noter/`.

## App-ikonet

Ikonet genereres af `tools/make_icon.py` (kræver Pillow). Scriptet skriver de adaptive vektorer,
WebP-billederne til Android 7.x og butiksikonet til F-Droid ud fra samme geometri:

```
python3 tools/make_icon.py [forhåndsvisning.png]
```

## Udgivelse

Appen udgives på GitHub og F-Droid med reproducerbart byg og samme signatur begge steder. Butikstekster,
ikon og skærmbilleder ligger i `fastlane/metadata/android/`. Fremgangsmåden står i
[`docs/RELEASE.md`](docs/RELEASE.md).

## Kendte begrænsninger

- `SEQUENCE` øges ikke ved redigering. Synkroniseringen bruger etags.
- Tags skrives adskilt med komma, så et tag kan ikke selv indeholde komma, når det skrives i appen.
  Formatet kan godt, og sådanne tags fra andre klienter vises korrekt.
- Konfliktkopier er almindelige noter, som du selv rydder op i.
- Thunderbird viser samlingen som en tom kalender, fordi den ikke kan vise `VJOURNAL`.
- Hele samlingen hentes ved første synkronisering. Derefter hentes kun ændringer.

## Licens

Udviklet i Claude af lianergoist. Udgivet under MIT-licensen, se [`LICENSE`](LICENSE).

Appen bruger biblioteker under Apache License 2.0 (AndroidX, OkHttp, Okio, Kotlin og kotlinx) og
Public Suffix List under MPL 2.0. Se [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) eller
Settings → About DavNotes (Indstillinger → Om DavNotes) i appen.

Tak til [Radicale](https://radicale.org/), [jtx Board](https://jtx.techbee.at/) og
[DAVx⁵](https://www.davx5.com/), som DavNotes er bygget til at arbejde sammen med.
