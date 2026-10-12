# Udgivelse (GitHub og F-Droid)

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

## Første indsendelse til F-Droid

1. Lav version 1.1 som ovenfor (tag `v1.1` og `davnotes-1.1.apk` på GitHub).
2. Fork https://gitlab.com/fdroid/fdroiddata (kræver GitLab-konto), og opret grenen `dk.rpix.noter`.
3. Kopiér `docs/fdroid/dk.rpix.noter.yml` til `metadata/dk.rpix.noter.yml` i forken.
   F-Droids reviewere foretrækker den fulde commit-hash i `commit:` frem for tagget.
4. Commit med beskeden `New App: dk.rpix.noter`, push, og opret en merge request.
   CI-pipelines i forken kører `fdroid lint` og et testbyg; ret det, de klager over.
5. Svar på reviewernes spørgsmål i merge requesten.

## Krav, projektet allerede opfylder

- MIT-licens, offentlig kildekode, kun frie afhængigheder fra Google Maven og Maven Central.
- Ingen Google Play Services, Firebase, sporing eller reklamer, og ingen anti-features.
- Release kan bygges usigneret fra kommandolinjen (uden `NOTER_KEYSTORE`).
- Ingen afhængighedsblok i APK'en (`dependenciesInfo` er slået fra).
- Ingen JDK-download under bygget (ingen foojay, intet `gradle-daemon-jvm.properties`).
  Bygget giver samme APK med JDK 21 og JDK 25 og fra forskellige mapper.
- Butikstekster, ikon og skærmbilleder i `fastlane/metadata/android/`.
  Ikonet laves af `tools/make_icon.py`.
