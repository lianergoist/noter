# DavNotes privacy policy

*Effective date: 27 October 2026. Dansk version nedenfor.*

DavNotes (package `dk.rpix.noter`) is a notes app that stores your notes on a CalDAV server that **you**
choose, for example your own Radicale server. The app is open source under the MIT licence:
https://github.com/lianergoist/noter

## What the app does not do

- The developer does not collect, receive or store any of your data. There is no account with the developer
  and no server run by the developer.
- No analytics, crash reporting, advertising, tracking or third-party SDKs (including Google Play Services
  and Firebase).
- Nothing is sold or shared with third parties.

## Data the app handles

| Data | Where it is stored | Where it is sent |
|---|---|---|
| Notes (title, text, tags) | In the app's private database on your device | Only to the server address you enter in the settings |
| Server address and user name | In the app's private storage on your device | To that server, when signing in and syncing |
| Password | On your device, encrypted with a key in the Android Keystore | To that server, as HTTP Basic authentication over HTTPS |

The app connects only to the server you set up. What happens to your notes on that server is decided by
whoever runs it (often you), not by this app. In release builds the app refuses unencrypted `http://` addresses.

Notes, settings and the password are excluded from Android's cloud backup and device transfer. After restoring
a phone you sign in again and the notes are fetched from your server.

## Files

When you open a text or Markdown file, or share text to the app, its content becomes a new note. When you
share a note, the app writes a temporary Markdown copy in its cache and passes it to the app you choose in
Android's share menu. The app asks for no storage permissions.

## Permissions

- **Internet** and **network state**, to sync with your server and to sync again when a connection is back.

## Deleting your data

Deleting a note in the app deletes it on your server at the next sync. Uninstalling the app removes all data
on the device. Data on your server is under the control of whoever runs that server.

## Children

The app is not aimed at children and collects no data from anyone.

## Changes and contact

Changes to this policy are published in this file, and its history is visible on GitHub.
Questions: open an issue at https://github.com/lianergoist/noter/issues

---

# Privatlivspolitik for DavNotes

*Gælder fra 27. oktober 2026.*

DavNotes (pakkenavn `dk.rpix.noter`) er en noteapp, der gemmer dine noter på en CalDAV-server, som **du**
vælger, fx din egen Radicale-server. Appen er open source under MIT-licensen:
https://github.com/lianergoist/noter

## Hvad appen ikke gør

- Udvikleren indsamler, modtager og gemmer ingen af dine data. Der er ingen konto hos udvikleren og ingen
  server, som udvikleren driver.
- Ingen statistik, nedbrudsrapporter, reklamer, sporing eller tredjeparts-SDK'er (heller ikke Google Play
  Services eller Firebase).
- Intet sælges eller deles med tredjeparter.

## Data, appen håndterer

| Data | Hvor de gemmes | Hvor de sendes hen |
|---|---|---|
| Noter (titel, tekst, tags) | I appens private database på din enhed | Kun til den serveradresse, du angiver i indstillingerne |
| Serveradresse og brugernavn | I appens private lager på din enhed | Til den server, når du logger ind og synkroniserer |
| Adgangskode | På din enhed, krypteret med en nøgle i Android Keystore | Til den server, som HTTP Basic-godkendelse over HTTPS |

Appen forbinder kun til den server, du selv har sat op. Hvad der sker med noterne på serveren, bestemmes af
den, der driver den (ofte dig selv), ikke af appen. I release-bygget afviser appen ukrypterede `http://`-adresser.

Noter, indstillinger og adgangskode er udeladt af Androids sikkerhedskopi i skyen og af overførsel til en ny
enhed. Efter en gendannelse logger du ind igen, og noterne hentes fra din server.

## Filer

Når du åbner en tekst- eller Markdown-fil eller deler tekst til appen, bliver indholdet en ny note. Når du
deler en note, skriver appen en midlertidig Markdown-kopi i sin cache og giver den videre til den app, du
vælger i Androids delemenu. Appen beder ikke om adgang til lageret.

## Tilladelser

- **Internet** og **netværkstilstand**, for at synkronisere med din server og synkronisere igen, når der er
  forbindelse.

## Sletning af data

Sletter du en note i appen, slettes den på din server ved næste synkronisering. Afinstallerer du appen,
fjernes alle data på enheden. Data på din server styres af den, der driver serveren.

## Børn

Appen er ikke rettet mod børn og indsamler ingen data fra nogen.

## Ændringer og kontakt

Ændringer af politikken offentliggøres i denne fil, og historikken kan ses på GitHub.
Spørgsmål: opret en issue på https://github.com/lianergoist/noter/issues
