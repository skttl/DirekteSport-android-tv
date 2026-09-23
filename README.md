# DirekteSport TV

Android-app til Nvidia Shield og telefon. Den viser DirekteSports katalog og åbner videoer i fuld skærm. TV-grænsefladen styres med fjernbetjeningen; telefonen har et layout til touch.

## Hent APK på Shield eller telefon

Åbn [downloadsiden](https://skttl.github.io/DirekteSport-android-tv/) i browseren, og tryk **Download nyeste APK**. Åbn den hentede fil, og tillad installation fra browseren eller filhåndteringen. Appen vises som **DirekteSport TV** i telefonens appskuffe og på Android TV-startskærmen.

Appen tjekker ved start, om der findes en nyere APK. Brug **Tjek opdatering** for at kontrollere manuelt. Når en opdatering findes, viser appen versionsnoterne og kan selv hente APK-filen. Android åbner derefter installationsskærmen. Første gang skal du give DirekteSport TV tilladelse til at installere apps fra denne kilde. De publicerede APK'er signeres med samme nøgle, så en ny APK kan opdatere en tidligere installation. Den lokale debug-APK i `app/build/outputs/apk/debug/` har en anden signatur og kan ikke installeres som opdatering til den publicerede APK.

## Prøv første milepæl

1. Åbn **Log ind / konto**, og log ind på JFM's egen side. Appen gemmer websessionen på Shield.
2. Vælg **Live** eller **Arkiv**. Vælg en sportsgren, eller søg efter et videonavn.
3. Åbn en video markeret **GRATIS** og derefter en markeret **ABONNEMENT**.
4. På TV åbner op/ned videolisten, venstre/højre spoler 10 sekunder, OK styrer afspilning, og Tilbage går til kataloget. På telefonen åbner knappen **Videoer** videolisten, og den indbyggede videoplayer har touchkontroller. Live kan kun spoles, hvis streamen tillader det. På telefon og tablet kan du trykke på Cast-knappen i kataloget eller afspilleren, vælge en Chromecast og styre pause/afspilning i afspilleren. Når du afbryder forbindelsen, fortsætter videoen på enheden. Telefon og Chromecast skal være på samme netværk, og Google Play-tjenester skal være tilgængelige. Appen starter ikke næste video automatisk.

Appen henter videoens HLS-adresse fra Flowplayers konfiguration ved hjælp af video-id og spiller m3u8-strømmen direkte med Media3. Kommende livestreams kan først afspilles, når strømmen er startet. Chromecast bruger Googles standardmodtager til at hente HLS-adressen direkte. Streams, der kræver telefonens login-cookie eller særlige HTTP-headere, kan derfor fejle på Chromecast; især abonnementsvideoer kræver test med en rigtig enhed og konto.

## Byg selv

Brug JDK 17, Android SDK Platform 35 og Build Tools 35.0.0. Sæt `ANDROID_HOME` til SDK-mappen, og kør `gradlew.bat assembleDebug` i projektmappen. Den resulterende APK ligger i `app/build/outputs/apk/debug/`.

Hvert push til `main` starter `.github/workflows/publish-apk.yml`. Workflowet bygger en signeret APK med stigende Android-versionskode og et nyt filnavn med commit-id. Hver APK arkiveres som et GitHub-download, og GitHub Pages viser de seneste 10 versioner med direkte APK-links og versionsnoter. Nye versionsnoter hentes fra commit-beskeden; ældre releases beholder deres eksisterende beskrivelse. Alle tidligere APK'er ligger fortsat under GitHub Releases. Siden opdaterer listen via en cache-fri forespørgsel. Repository secrets `ANDROID_KEYSTORE_BASE64` og `ANDROID_KEYSTORE_PASSWORD` er nødvendige for signering; nøglen ligger ikke i Git.

Kataloget hentes fra de JSON-endepunkter, som [DirekteSports hjemmeside](https://direktesport.dk/) selv bruger. De er ikke dokumenteret som et offentligt tredjeparts-API og kan ændre sig. Login og betaling styres af DirekteSport/JFM; appen gemmer ikke adgangskoden selv og forsøger ikke at omgå abonnementsadgang.

## Status

Katalog-endepunkterne og HLS-konfigurationen er kontrolleret uden login. Den direkte afspilning og betalt adgang skal afprøves på Shield med den eksisterende JFM-konto.
