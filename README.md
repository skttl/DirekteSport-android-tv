# DirekteSport TV

Privat Android TV-app til Nvidia Shield. Første milepæl viser DirekteSports katalog i en selvstændig TV-grænseflade og åbner videoer i fuld skærm.

## Hent APK på Shield

Åbn [downloadsiden](https://skttl.github.io/DirekteSport-android-tv/) i Shield-browseren, og tryk **Download APK**. Åbn den hentede fil med en filhåndtering, og tillad installation fra den app, der åbner APK'en.

De publicerede APK'er signeres med samme nøgle, så en ny APK kan opdatere en tidligere installation. Den lokale debug-APK i `app/build/outputs/apk/debug/` har en anden signatur og kan ikke installeres som opdatering til den publicerede APK.

## Prøv første milepæl

1. Åbn **Log ind / konto**, og log ind på JFM's egen side. Appen gemmer websessionen på Shield.
2. Vælg **Live** eller **Arkiv**. Vælg en sportsgren, eller søg efter et videonavn.
3. Åbn en video markeret **GRATIS** og derefter en markeret **ABONNEMENT**.
4. Under afspilning åbner op/ned videolisten, venstre/højre spoler 10 sekunder, OK styrer afspilning, og Tilbage går til kataloget. Live kan kun spoles, hvis streamen tillader det. Appen starter ikke næste video automatisk.

Appen prøver Androids egen videoplayer, hvis webafspilleren giver en direkte HTTPS-kilde til HLS eller MP4. Ellers fortsætter videoen i DirekteSports webafspiller.

## Byg selv

Brug JDK 17, Android SDK Platform 33 og Build Tools 33.0.2. Sæt `ANDROID_HOME` til SDK-mappen, og kør `gradlew.bat assembleDebug` i projektmappen. Den resulterende APK ligger i `app/build/outputs/apk/debug/`.

Hvert push til `main` starter `.github/workflows/publish-apk.yml`. Workflowet bygger en signeret APK med stigende Android-versionskode og et nyt filnavn med commit-id. Hver APK arkiveres som et GitHub-download, og GitHub Pages viser en HTML-liste med alle versioner og direkte links. Siden opdaterer også listen via en cache-fri forespørgsel. Repository secrets `ANDROID_KEYSTORE_BASE64` og `ANDROID_KEYSTORE_PASSWORD` er nødvendige for signering; nøglen ligger ikke i Git.

Kataloget hentes fra de JSON-endepunkter, som [DirekteSports hjemmeside](https://direktesport.dk/) selv bruger. De er ikke dokumenteret som et offentligt tredjeparts-API og kan ændre sig. Login og betaling styres af DirekteSport/JFM; appen gemmer ikke adgangskoden selv og forsøger ikke at omgå abonnementsadgang.

## Status

Katalog-endepunkterne er kontrolleret uden login, og APK'en bygger lokalt. Login, videoafspilning og betalt adgang skal afprøves på Shield med den eksisterende JFM-konto. Der var ingen tilsluttet Android-enhed under udviklingen.

