# DavNotes

**English** | [Dansk](README.da.md)

Simple notes app for Android. Notes are stored as `VJOURNAL` objects in a CalDAV collection on your own
[Radicale](https://radicale.org/) server and synced both ways. The app works offline and sends
the changes when the connection is back.

The app can share a collection with other clients, e.g. jtx Board via DAVx5. Properties set by other
clients are preserved unchanged when you edit a note.

## Features

- Create, edit, search and delete notes with title, text and tags.
- Light formatting written as Markdown: headings, bold, italic, lists and indentation, with a
  toolbar above the keyboard. Lists continue on Enter. Notes remain plain text that other
  clients can read.
- Links in the text can be opened in the browser.
- Open text and Markdown files as new notes, also via "Open with" and "Share", and share a note as a file.
- Saves automatically while you type and when you leave a note.
- Works offline. Changes are sent at the next sync.
- Syncs when the app is opened, after every change, on pull-to-refresh and roughly every 15 minutes.
- No note is overwritten blindly. If a note was changed both here and elsewhere, your version is saved
  as a new note with "(conflict)" in the title ("(konflikt)" in Danish etc.). Text is never merged.
- Finds your collections on the server and can create a new notes collection.
- The password is encrypted with a key in the Android Keystore. HTTPS only.
- In English, Danish, German, French and Spanish. Follows the phone's language, and on Android 13+ the language
  can be chosen for the app alone in the system settings.

Not included (on purpose): full Markdown (e.g. tables, images and `[text](url)`), attachments, multiple accounts and shared notes.

## Getting started

Requires Android 7.0 (API 24) or later and a CalDAV server that supports `sync-collection`
(RFC 6578). The app is developed and tested against Radicale 3.8.

1. Build and install the app (see below).
2. Open the settings and enter the address of the collection, e.g. `https://radicale.example.com/user/notes/`,
   plus username and password.
3. If you don't know the path, enter the server's address and tap **Find collections**. If there is no
   notes collection, tap **Create notes collection**.
4. Tap **Save**. The app checks the login and collection and starts syncing.

## Build

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # unsigned release APK
```

The build uses the Gradle wrapper, AGP 9.4 and Kotlin 2.2. Open the project in Android Studio, or
build from the command line with a `local.properties` pointing to the Android SDK.

The debug build has the package name `dk.rpix.noter.debug` and is called "DavNotes debug", so it can sit on
the phone next to the release build. It allows `http://` to `10.0.2.2`, `localhost` and
`127.0.0.1`, so it can use a local test server. The release build requires HTTPS.

## Structure

| Module | Contents |
|---|---|
| `:core` | Pure Kotlin/JVM: the iCalendar layer (`Ical`), the CalDAV client (`DavClient` on OkHttp), the `NoteStore` interface and the sync algorithm (`Syncer`). Can be tested without an emulator. |
| `:app` | Compose and Material 3, Room (implements `NoteStore`), WorkManager for syncing and DataStore for settings. |

The core is a direct port of the reference implementation in `reference/` (Python and JavaScript).
The full specification of the data format, CalDAV requests and sync rules is in
[`docs/SPEC.md`](docs/SPEC.md) (in Danish).

```
app/        The Android app
core/       The core (ical, dav, sync)
docs/       Specification
reference/  Reference implementation (notesync.py, selftest.py, web client)
tools/      Helper scripts (the app icon)
```

## Tests

### Unit tests

```
./gradlew test
```

Runs the iCalendar tests and all sync scenarios against an in-memory CalDAV server. Requires
no server and no phone. The scenarios are ported from `reference/selftest.py`: two devices,
conflicts, edit versus delete, invalid sync token, offline and wrong password.

### Against a real Radicale

The same scenarios can run against a local Radicale. Each test creates its own collection and deletes
it again.

```
python3 -m venv .venv && .venv/bin/pip install radicale
```

Set up and start the server as described in the "Test" section of [`docs/SPEC.md`](docs/SPEC.md). Then run:

```
NOTES_TEST_URL=http://127.0.0.1:5232/anna/noter/ ./gradlew test
```

Username and password are taken from `NOTES_TEST_USER` and `NOTES_TEST_PASSWORD` (default `anna` and
`hemmelig`, as in the specification's test setup). Without `NOTES_TEST_URL` these tests are skipped.

### On the phone

`NewNoteButtonTest` is an instrumented Compose test that checks that the New note button can be read by
TalkBack. The phone must be unlocked.

```
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dk.rpix.noter.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Avoid `./gradlew connectedAndroidTest` on a phone you use. It uninstalls the app afterwards,
and then settings and notes that have not been sent yet are lost.

A local test server can be reached from the phone with `adb reverse tcp:5232 tcp:5232` and the address
`http://localhost:5232/anna/noter/`. From the emulator it is `http://10.0.2.2:5232/anna/noter/`.

## The app icon

The icon is generated by `tools/make_icon.py` (requires Pillow). The script writes the adaptive vectors,
the WebP images for Android 7.x and the store icon for F-Droid from the same geometry:

```
python3 tools/make_icon.py [preview.png]
```

## Releases

The app is released on GitHub and F-Droid with a reproducible build and the same signature in both places. Store texts,
icon and screenshots are in `fastlane/metadata/android/`. The procedure is described in
[`docs/RELEASE.md`](docs/RELEASE.md) (in Danish).

## Known limitations

- `SEQUENCE` is not incremented on edit. Syncing uses etags.
- Tags are written separated by commas, so a tag cannot itself contain a comma when typed in the app.
  The format allows it, and such tags from other clients are shown correctly.
- Conflict copies are ordinary notes that you clean up yourself.
- Thunderbird shows the collection as an empty calendar, because it cannot display `VJOURNAL`.
- The whole collection is fetched on the first sync. After that only changes are fetched.

## License

Developed in Claude by lianergoist. Released under the MIT License, see [`LICENSE`](LICENSE).

The app uses libraries under the Apache License 2.0 (AndroidX, OkHttp, Okio, Kotlin and kotlinx) and
the Public Suffix List under MPL 2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) or
Settings → About DavNotes in the app.

Thanks to [Radicale](https://radicale.org/), [jtx Board](https://jtx.techbee.at/) and
[DAVx⁵](https://www.davx5.com/), which DavNotes is built to work with.
