# Udgivelse (GitHub, F-Droid og Google Play)

DavNotes udgives med **reproducerbart byg**: F-Droid bygger appen selv fra det taggede commit,
sammenligner resultatet med den signerede APK på GitHub og udgiver vores APK med vores signatur.
Brugere kan derfor skifte frit mellem GitHub og F-Droid. Det kræver, at de to byg er **bit for bit ens**
(bortset fra signaturen).

## Ny version

1. Hæv `versionCode` (heltal, altid større) og `versionName` i `app/build.gradle.kts`.
2. Skriv ændringsloggen `fastlane/metadata/android/<sprog>/changelogs/<versionCode>.txt`
   på alle fem sprog (højst 500 tegn).
3. Kør `./gradlew test assembleDebug`, commit og push.
4. Tag commit'et `v<versionName>` (fx `v1.1`) og push tagget: `git tag v1.1 && git push origin v1.1`.
5. Byg den signerede APK **fra det taggede commit og med ren arbejdsmappe**
   (APK'en indeholder commit-hashen, så et byg fra et andet commit ligner ikke F-Droids):

   ```
   git status            # skal være ren
   ./gradlew clean :app:assembleRelease
   ```

   Signeringen læses fra `~/.gradle/gradle.properties` (`NOTER_KEYSTORE` m.fl.). Brug altid samme nøgle;
   dens SHA-256 står i `AllowedAPKSigningKeys` i F-Droid-metadata.
6. Opret en GitHub-release for tagget, og vedhæft APK'en med **præcis** navnet `davnotes-<versionName>.apk`
   (fx `davnotes-1.1.apk`). F-Droid henter den via `Binaries` i metadata.

F-Droid opdager nye tags af sig selv (`UpdateCheckMode: Tags`) og bygger den nye version. Der går typisk
nogle dage, før den er ude.

## Skærmbilleder

`fastlane/metadata/android/{da-DK,en-US}/images/phoneScreenshots/`, 1080 × 2400: 1 listen, 2 Tur til Lissabon
i editoren med tastatur og værktøjslinje (markøren i linket, så "Åbn link" vises), 3 søgning ("hjem"/"home"),
4 listen i mørkt tema, 5 indstillingerne.

1. Start testserveren fra afsnittet "Test" i `docs/SPEC.md`, og læg demodata ind:
   `python3 -B tools/demo_notes.py http://127.0.0.1:5232 <bruger> <adgangskode>`
   (samlingerne `noter-da` og `noter-en`; datoerne regnes fra i dag).
2. Emulator (1080 × 2400, fx AVD'en `davnotes_shots`) med debug-bygget: samling
   `http://10.0.2.2:5232/<bruger>/noter-da/`. Appens sprog: `adb shell cmd locale set-app-locales
   dk.rpix.noter.debug --locales da-DK`.
3. Rolig statuslinje (demotilstand):

   ```
   adb shell settings put global sysui_demo_allowed 1
   adb shell am broadcast -a com.android.systemui.demo -e command enter
   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1115
   adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
   adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true -e mobile hide
   adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
   ```

   Sæt uret til samme tid som "Synkroniseret" i appen (hver sync opdaterer den).
   Mørkt tema: `adb shell cmd uimode night yes`. Skærmbillede: `adb exec-out screencap -p > 1.png`.
4. Markørens greb i editoren forsvinder, når man taster: skriv et tegn og slet det igen.
5. Engelsk: stop serveren, byt mapperne `noter-da` og `noter-en` i `collections/collection-root/<bruger>/`,
   start serveren igen, og sæt appens sprog til `en-US`. Appen laver en fuld sync og viser de engelske noter.
6. Bagefter: `cmd uimode night no`, demotilstand `-e command exit`.

En langsom emulator kan vise "System UI isn't responding". Kan dialogen ikke lukkes med et tryk:
`adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS` (evt. midlertidigt
`adb shell settings put global hide_error_dialogs 1`, og `0` bagefter).

## Første indsendelse til F-Droid

1. Lav version 1.1 som ovenfor (tag `v1.1` og `davnotes-1.1.apk` på GitHub).
2. Fork https://gitlab.com/fdroid/fdroiddata (kræver GitLab-konto), og opret grenen `dk.rpix.noter`.
3. Kopiér `docs/fdroid/dk.rpix.noter.yml` uændret til `metadata/dk.rpix.noter.yml` i forken
   (filen er uden kommentarer, fordi `fdroid rewritemeta` i CI fjerner dem og så melder formatfejl).
   F-Droids reviewere foretrækker den fulde commit-hash i `commit:` frem for tagget.
4. Commit med beskeden `New App: dk.rpix.noter`, push, og opret en merge request.
   CI-pipelines i forken kører `fdroid lint` og et testbyg; ret det, de klager over.
5. Svar på reviewernes spørgsmål i merge requesten.

## Faldgruber i F-Droid-metadata

- **Citationstegn om versionsnumre:** `fdroid rewritemeta` kræver dem, når nummeret ellers ville blive
  læst som et tal, og afviser dem ellers. To dele: `versionName: '1.1'` og `CurrentVersion: '1.1'`.
  Tre dele: `versionName: 1.1.1` og `CurrentVersion: 1.1.1`. Ret begge steder, både her og i forken.
- **Kopiér filen fra GitHub, ikke fra terminalen**
  (`https://raw.githubusercontent.com/lianergoist/noter/main/docs/fdroid/dk.rpix.noter.yml`),
  så der ikke kommer en prompt eller en dobbelt kopi med. Filen skal slutte med ét linjeskift.
- Ret filen i forken på grenen `dk.rpix.noter`, så den åbne merge request opdateres.

## Google Play

Play kræver en App Bundle (`.aab`), ikke en APK, og bruger altid **Play App Signing**: Google signerer det,
brugerne henter. Vi giver Google **vores egen app-nøgle** (`noter-release.jks`), så Play-, GitHub- og
F-Droid-versionen har samme signatur, og brugerne kan skifte uden at afinstallere (og miste noter, der ikke
er synkroniseret). Lader man Google lave en ny nøgle, kan det ikke laves om bagefter.

### Første gang

1. Opret en udviklerkonto på https://play.google.com/console (engangsgebyr, identitetsbekræftelse).
2. Lav en **upload-nøgle**, adskilt fra app-nøglen, så app-nøglen ikke bruges til daglig:

   ```
   keytool -genkeypair -keystore ~/.android/keystores/noter-upload.jks -alias upload \
       -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=DavNotes upload, O=rpix.dk, C=DK"
   ```

   Gem nøglen og adgangskoderne samme sted som app-nøglen (sikkerhedskopi uden for repoet).
3. Opret appen (navn "DavNotes", standardsprog engelsk, app, gratis). Under *Test and release → App integrity →
   App signing* vælges **Use existing app signing key from Java keystore**. Konsollen giver en krypteringsnøgle
   og en kommando med PEPK-værktøjet (`pepk.jar`); kør den mod `noter-release.jks` med aliaset fra
   `NOTER_KEY_ALIAS`, og upload den krypterede ZIP. Upload derefter upload-nøglens certifikat:
   `keytool -export -rfc -keystore ~/.android/keystores/noter-upload.jks -alias upload -file upload.pem`.
4. Butiksside på alle fem sprog: titel, kort og lang beskrivelse fra `fastlane/metadata/android/<sprog>/`,
   ikon (`en-US/images/icon.png`, 512 × 512), skærmbilleder (`phoneScreenshots/`) og feature graphic
   (`<sprog>/images/featureGraphic.png`, 1024 × 500, laves af `tools/make_feature_graphic.py` ud fra
   `short_description.txt`; kør scriptet igen, hvis teksten ændres).
5. *App content* (alle punkter skal udfyldes):
   - Privatlivspolitik: `https://github.com/lianergoist/noter/blob/main/docs/PRIVACY.md`
   - Reklamer: nej. Adgang til appen: alt kræver en egen CalDAV-server; giv reviewerne en testkonto
     (URL, bruger, adgangskode) i formularen, ikke i repoet.
   - Data safety: ingen data indsamles eller deles med udvikleren eller tredjeparter; data sendes kun krypteret
     (HTTPS) til den server, brugeren selv angiver. Brugeren kan slette sine data. Læs Googles definitioner
     igen, før formularen sendes; de ændres med mellemrum.
   - Indholdsvurdering (spørgeskema), målgruppe 13 år og derover (ikke rettet mod børn; kun 18+ kan begrænse tilgængeligheden), ingen nyheds-, sundheds- eller
     finansfunktioner.
6. Nye personlige konti skal have en **lukket test med mindst 12 testere i 14 dage**, før de kan søge om
   produktion. Opret sporet *Closed testing*, tilføj testerne (Google-konti), og upload den første bundle dér.

### Ny version

Samme `versionCode` og `versionName` som til GitHub og F-Droid (trin 1–3 ovenfor). Byg med upload-nøglen. Den står ved siden af app-nøglen i `~/.gradle/gradle.properties`:

```
NOTER_UPLOAD_KEYSTORE=/home/<bruger>/.android/keystores/noter-upload.jks
NOTER_UPLOAD_KEYSTORE_PASSWORD=...
NOTER_UPLOAD_KEY_ALIAS=upload
NOTER_UPLOAD_KEY_PASSWORD=...
```

```
./gradlew clean :app:bundleRelease -PNOTER_SIGN_WITH=upload
```

Uden `-PNOTER_SIGN_WITH=upload` signeres med app-nøglen som til GitHub. Kontrollér certifikatet med
`keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab`, og upload
`app-release.aab` til sporet. Ændringsloggen kopieres fra `changelogs/<versionCode>.txt` på hvert sprog.
En `versionCode` kan kun bruges én gang på Play, også hvis udgivelsen trækkes tilbage.

## Krav, projektet allerede opfylder

- MIT-licens, offentlig kildekode, kun frie afhængigheder fra Google Maven og Maven Central.
- Ingen Google Play Services, Firebase, sporing eller reklamer, og ingen anti-features.
- Release kan bygges usigneret fra kommandolinjen (uden `NOTER_KEYSTORE`).
- Ingen afhængighedsblok i APK'en (`dependenciesInfo` er slået fra).
- Ingen JDK-download under bygget (ingen foojay, intet `gradle-daemon-jvm.properties`).
  Bygget giver samme APK med JDK 21 og JDK 25 og fra forskellige mapper.
- Butikstekster, ikon og skærmbilleder i `fastlane/metadata/android/`.
  Ikonet laves af `tools/make_icon.py`.
