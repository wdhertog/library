# Bibliotheek

Een Android-app voor je eigen boekencollectie: boeken snel inscannen, bijhouden waar ze staan, aan wie je ze hebt uitgeleend en wat je gelezen hebt. Boekgegevens komen uit open bronnen, zoals nationale bibliotheken, Open Library en Wikidata. Er is geen account nodig en er gaan geen gegevens naar Google.

De app is in het Nederlands en gemaakt voor eigen gebruik, maar iedereen mag hem installeren.

## Wat kan de app

- **Scannen:** scan de barcodes van je boeken achter elkaar. Het boek staat er meteen in; titel, auteur, serie, uitgever en meer worden op de achtergrond opgezocht. Heeft een boek geen (leesbare) barcode, dan fotografeer je de voorkant en leest de app titel en auteur.
- **Waar staat het?** Leg kamers, kasten en planken vast. In plank-modus scan je een hele plank en staat elk boek meteen op zijn plek, in de goede volgorde.
- **Uitlenen:** houd bij aan wie je een boek hebt uitgeleend en welke boeken je zelf hebt geleend, met een herinnering als ze terug moeten.
- **Lezen:**
  - wat je nu leest, met je voortgang;
  - je leeslijst en je leesdoel per jaar;
  - per jaar wat je las, met beoordelingen (in halve sterren) en recensies.
- **Auteurs:** per auteur je boeken (edities bij elkaar), series en gegevens uit Wikidata.
- **Series:** welke delen je hebt, welke je gelezen hebt en welke nog ontbreken, met titels uit Wikidata. Zelf een ontbrekend deel toevoegen kan ook, en de app wijst series aan die dubbel lijken.
- **Edities en vertalingen** van hetzelfde werk horen bij elkaar. Lees je de Engelse editie, dan telt de Nederlandse ook als gelezen.
- **Covers:** fotografeer zelf de cover als er geen in de open bronnen staat; dat geldt voor de meeste Nederlandse boeken.
- **Importeren:**
  - uit Goodreads (CSV-export);
  - uit Excel of CSV, waarbij je per kolom kiest waar die terechtkomt.
  - Vooraf zie je welke boeken er al staan, en elke import is ongedaan te maken.
- **Back-up:** automatisch elke week naar een map die je kiest (bijvoorbeeld in Nextcloud), handmatig als ZIP, terugzetten op een nieuwe telefoon, en exporteren naar CSV voor Excel of naar JSON om je gegevens ergens anders te gebruiken.
- **Delen met huisgenoten:** geef per boek de eigenaar aan en filter daarop.
- **Synchroniseren tussen telefoons:** via de wifi thuis, rechtstreeks en versleuteld van telefoon naar telefoon, zonder server of account. Koppelen gaat met een QR-code. De eerste keer voeg je twee bestaande bibliotheken samen met een controlescherm. De collectie is gedeeld; wat je gelezen hebt, beoordelingen, favorieten en je leeslijst blijven per telefoon.

## Installeren

1. Download de nieuwste `bibliotheek-*.apk` van de [releasepagina](https://github.com/wdhertog/library/releases/latest) op je Android-telefoon (Android 8 of nieuwer).
2. Open het bestand en sta zo nodig toe dat apps uit deze bron worden geïnstalleerd.

Een nieuwe versie installeer je gewoon over de oude heen; je gegevens blijven bewaard. Stel na het installeren de automatische back-up in via **⋮ → Back-up en export…**. De gegevens staan alleen op je telefoon.

## Databronnen

Per ISBN kiest de app eerst de nationale bibliografie van het land van de uitgever, met Open Library als aanvulling. Wikidata vult daarna serie, volgnummer en eerste verschijningsjaar aan.

| ISBN begint met | Taal (meestal) | Eerst |
|-----------------|----------------|-------|
| 978-90, 978-94 | Nederlands | [KB, Nederlandse Bibliografie](https://data.bibliotheken.nl) |
| 978-3 | Duits | [Deutsche Nationalbibliothek](https://www.dnb.de) |
| 978-2, 979-10 | Frans | [Bibliothèque nationale de France](https://catalogue.bnf.fr) |
| 978-84 | Spaans | [Biblioteca Nacional de España](https://www.bne.es) |
| 978-88, 979-12 | Italiaans | [Servizio Bibliotecario Nazionale](https://opac.sbn.it) |
| overig | — | [Open Library](https://openlibrary.org) |

- **Wat er verstuurd wordt:** alleen het ISBN, of bij cover-herkenning de herkende tekst.
- **Op de telefoon zelf:** barcodes en covertekst worden herkend met [ML Kit](https://developers.google.com/ml-kit). Het meesturen van gebruiksgegevens naar Google is uitgezet.
- **Geen Google Books:** dat is geen open data, en daarom bewust niet gebruikt.

## Zelf bouwen

**Nodig:**
- de Android SDK (compileSdk 37);
- een JDK om Gradle te starten. De JDK 21 waarmee wordt gecompileerd, haalt Gradle zelf op.

```sh
./gradlew assembleDebug        # debug-APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit-tests (parsers, import, statistiek, …)
./gradlew assembleStaging      # releasecode (met R8) als aparte app, naast de gewone te installeren
```

De parsertests draaien op echte antwoorden van de bronnen in `app/src/test/resources/lookup/`. Het internet is daarbij niet nodig.

**Een release bouwen** (`./gradlew assembleRelease`) vraagt om een eigen sleutel in `~/.gradle/gradle.properties`:

```properties
libraryReleaseStoreFile=/pad/naar/sleutel.jks
libraryReleaseStorePassword=…
libraryReleaseKeyAlias=…
libraryReleaseKeyPassword=…
```

Zonder deze gegevens wordt de release-APK niet ondertekend. Test een release eerst als staging-build: R8 haalt soms code weg die via reflectie wordt gebruikt (zie `app/proguard-rules.pro`).

### Techniek

- **App:** Kotlin met Jetpack Compose (Material 3), één Gradle-module, ingedeeld per functie.
- **Opslag:** Room, met migraties; de schema's staan in `app/schemas/`.
- **Achtergrond:** WorkManager voor het opzoeken van gegevens, herinneringen en back-ups.
- **Camera:** CameraX met ML Kit (barcode en tekst, volledig op het toestel).
- **Netwerk:** OkHttp en kotlinx.serialization.
- **Letter:** [Literata](https://github.com/googlefonts/literata) voor titels, onder de SIL Open Font License 1.1 (zie `app/src/main/assets/licenses/`).

Het ontwerp staat in [`docs/ONTWERP.md`](docs/ONTWERP.md), met de keuzes, het datamodel, de schermen en de bouwfases. Wat nog op de lijst staat, vind je in [§10 Nog te doen](docs/ONTWERP.md#10-nog-te-doen-na-v10).

## Status

**Versie 1.9** is uitgebracht, met een JSON-export van al je gegevens en reparaties aan synchroniseren en scannen. In 1.8 kwamen auteurs samenvoegen, een hoofdeditie per werk, import uit Goodreads en meer badges. In 1.7 kwamen leesstreaks en badges, en veel reparaties uit een review op bugs, beveiliging en snelheid. In 1.6 kwam een jaaroverzicht ("je jaar in boeken", te delen als afbeelding of tekst), een widget "Nu aan het lezen" op het beginscherm, boeken delen en uitgeleende boeken navragen, en ontbrekende delen van een serie op je leeslijst. In 1.5 kwam de nieuwe vormgeving, de auteurspagina, ontbrekende delen van series, de taal uit het ISBN en instellingen. Synchroniseren tussen telefoons zit er sinds 1.4 in. Wat nog op de lijst staat: zie [§10 van het ontwerp](docs/ONTWERP.md#10-nog-te-doen-na-v10).

Fouten of ideeën zijn welkom als [issue](https://github.com/wdhertog/library/issues).

## Licentie

[MIT](LICENSE). De boekgegevens zelf komen uit de genoemde bronnen en vallen onder hun eigen voorwaarden.
