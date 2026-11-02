# Ontwerp — Boekenbibliotheek (Android)

Status: **concept ter review** — nog geen code geschreven.

## 1. Doelen

| # | Wens | Hoe het ontwerp het oplost |
|---|------|----------------------------|
| 1 | Hele collectie inlezen uit CSV / Excel | Import-wizard met kolomtoewijzing (formaat is nog onbekend, dus flexibel) |
| 2 | Boeken scannen via barcode of cover | Doorlopende barcodescanner; cover-modus met tekstherkenning op het toestel |
| 3 | Zo veel mogelijk info per boek | Meerdere open bronnen, gekozen op basis van taal (ISBN-prefix) |
| 4 | **Scannen moet snel** | Scan = direct lokaal opslaan. Opzoeken gebeurt los daarvan, op de achtergrond |
| 5 | Leesactiviteit bijhouden (à la *Have I Read That*) | Leesstatus, leesperiodes (ook herlezen), beoordeling, jaaroverzicht |
| 6 | Locatie opslaan en opzoeken | Boomstructuur Kamer › Kast › Plank; "plank-modus" bij scannen |
| 7 | Uitlenen + zelf geleende boeken terugbrengen | Uitleenregistratie in twee richtingen, met herinneringen |
| 8 | Series | Automatisch uit bronnen, handmatig aan te vullen; overzicht met ontbrekende delen |
| 9 | NL / EN / DE / FR + enkele andere talen | Bron per taal; handmatig formulier voor de rest |
| 10 | Open data, geen API-sleutels | Alle standaardbronnen werken zonder sleutel (zie §4) |

## 2. Techniek

| Onderdeel | Keuze | Waarom |
|-----------|-------|--------|
| Taal / UI | Kotlin + Jetpack Compose (Material 3) | Huidige Android-standaard |
| Min. Android | 8.0 (API 26) | Dekt vrijwel alle toestellen; voorstel |
| Database | Room (SQLite) + FTS4 voor zoeken | Alles offline beschikbaar, snel zoeken op titel/auteur |
| Barcode | CameraX + ML Kit Barcode Scanning (gebundeld model) | Werkt offline, geen sleutel, zeer snel. EAN-13 is genoeg |
| Cover-herkenning | ML Kit Text Recognition (op toestel) | Leest titel/auteur van de cover, daarna zoeken op tekst |
| Netwerk | Ktor client + kotlinx.serialization | Lichtgewicht; XML (SRU) via XmlPullParser |
| Achtergrondwerk | WorkManager | Verrijken, herinneringen, overleeft herstart van de app |
| Afbeeldingen | Coil | Covers laden en cachen |
| DI | Hilt | Standaard |
| CSV | kotlin-csv | Klein, detecteert scheidingsteken niet zelf → eigen detectie `;` / `,` / tab |
| Excel (.xlsx) | Eigen kleine lezer (ZIP + XmlPullParser) | Apache POI is te zwaar voor Android; we hoeven alleen celwaarden te lezen |

> ML Kit is van Google, maar draait volledig op het toestel: geen account, geen sleutel, geen data die het toestel verlaat.
>
> De app is in eerste instantie voor eigen gebruik (APK). Scanner en tekstherkenning zitten achter een eigen interface (`BarcodeScanner`, `TextRecognizer`), zodat later een F-Droid-variant (Gradle product flavor) met ZXing kan worden toegevoegd zonder de rest te raken. In die variant vervalt cover-herkenning of wordt die vervangen door Tesseract.

## 3. Scannen: snelheid eerst

Het principe: **de scan is klaar zodra het ISBN lokaal is opgeslagen.** Alles daarna is bijzaak.

```
 Camera ziet barcode
      │
      ▼
 ISBN valideren (checksum) ──ongeldig──► negeren, doorscannen
      │
      ▼
 Al in collectie? ──ja──► trilsignaal ×2 + "Al aanwezig: Woonkamer › Kast 2"
      │nee
      ▼
 Boek opslaan met status NIEUW   (+ locatie als plank-modus aan staat)
 Piep/tril ×1, direct klaar voor volgende scan          ← < 0,2 s
      │
      ├──► Snelle lookup (parallel, max. 3 s, 1 bron): titel, auteur, cover-URL
      │      → status SNEL
      │
      └──► WorkManager-wachtrij: volledige verrijking (alle bronnen, serie, beschrijving)
             → status VERRIJKT  (of MISLUKT → later opnieuw / handmatig)
```

- De snelle lookup blokkeert nooit de camera; het resultaat verschijnt "vanzelf" in de lijst onderaan het scanscherm.
- Volledige verrijking draait op de achtergrond (instelbaar: altijd / alleen wifi / alleen handmatig).
- **"Bibliotheek bijwerken"** (in menu en instellingen) zet alle boeken met status NIEUW/SNEL/MISLUKT in de wachtrij; optioneel ook "alles opnieuw".
- Velden die je zelf hebt aangepast worden **nooit** overschreven door verrijking (per veld bijgehouden).

## 4. Databronnen (zonder API-sleutel)

De bronvolgorde wordt bepaald door het ISBN-prefix, dat de taal/regio van de uitgever aangeeft:

| ISBN-prefix | Taal (meestal) | Bronvolgorde |
|-------------|----------------|--------------|
| 978-90, 978-94 | Nederlands | Open Library → KB / data.bibliotheken.nl → Wikidata |
| 978-3 | Duits | DNB → Open Library → Wikidata |
| 978-2, 979-10 | Frans | BnF → Open Library → Wikidata |
| 978-0, 978-1, 979-8 | Engels | Open Library → Wikidata |
| overig | — | Open Library → Wikidata → handmatig formulier |

| Bron | Wat levert het | Sleutel? | Opmerking |
|------|----------------|----------|-----------|
| **Open Library** (openlibrary.org) | Titel, auteurs, uitgever, jaar, pagina's, onderwerpen, soms serie; zoeken op tekst | Nee | Ook de standaard voor de snelle lookup |
| **Open Library Covers** | Coverafbeeldingen per ISBN | Nee | |
| **DNB** (Deutsche Nationalbibliothek, SRU) | Zeer complete Duitse metadata incl. **reeks + deelnummer** | Nee | Metadata is CC0 |
| **BnF** (Bibliothèque nationale de France, SRU) | Franse metadata incl. collectie/serie | Nee | |
| **KB / data.bibliotheken.nl** (SPARQL) | Nederlandse Bibliografie | Nee | Dekking nog te valideren in fase 2 |
| **Wikidata** (SPARQL) | Serie (P179) + volgnummer (P1545), originele titel, vertaler, werk-koppeling | Nee | Vooral sterk voor bekende series |

Google Books wordt bewust **niet** gebruikt (geen open data).

Gegevens van meerdere bronnen worden samengevoegd: per veld wint de eerste bron die het heeft, behalve als jij het veld hebt aangepast. Van elk boek wordt bijgehouden welke bronnen zijn geraadpleegd.

**Cover-modus**: foto → tekstherkenning → grootste tekstregels als titel/auteur → zoeken in Open Library (en DNB/BnF afhankelijk van gedetecteerde taal) → kandidatenlijst → jij kiest. Lukt het niet: formulier, vooringevuld met de herkende tekst.

## 5. Datamodel

Twee niveaus: een **Work** (het verhaal/werk, los van taal en uitgave) en een **Book** (het fysieke exemplaar in jouw kast, een bepaalde editie).

```
┌──────────────────────┐        ┌──────────────┐      ┌─────────────┐
│ Work (werk)          │*──────*│ Author       │      │ Series      │
│──────────────────────│        │──────────────│      │─────────────│
│ id                   │        │ id, name     │      │ id, name    │
│ title (oorspronkelijk│        └──────────────┘      │ totalVolumes│
│   of zelf gekozen)   │*──────────────────────────────1│ wikidataId  │
│ originalLanguage     │   seriesId, seriesIndex      └─────────────┘
│ firstPublishedYear   │      ("3", "3.5", "II")
│ openLibraryWorkId    │
│ wikidataId           │
└──────────────────────┘
           *
           │  BookWork (bookId, workId, position)
           │  meestal 1 werk per boek; een omnibus bevat er meerdere
           *
┌──────────────────────┐        ┌──────────────────────────┐
│ Book (exemplaar)     │*──────1│ Location (boom)          │
│──────────────────────│        │──────────────────────────│
│ id                   │        │ id, parentId, name, type │
│ isbn13, isbn10       │        │   (kamer/kast/plank/doos)│
│ title, subtitle      │        │ sortOrder                │
│   (zoals op dit boek)│        └──────────────────────────┘
│ language             │
│ translators          │        ┌──────────────────────────┐
│ publisher, year      │1──────*│ Loan (uitgeleend)        │
│ pages, format        │        │──────────────────────────│
│ description          │        │ id, bookId, personId     │
│ coverUrl, coverFile  │        │ lentAt, dueAt, returnedAt│
│ subjects/tags        │        │ note                     │
│ ownership            │        └──────────────────────────┘
│   OWNED / BORROWED / │
│   NOT_OWNED          │        ┌──────────────────────────┐
│ borrowedFrom, dueAt  │1──────*│ ReadingSession           │
│ shelfPosition, notes │        │──────────────────────────│
│ enrichStatus         │        │ id, bookId, startedAt,   │
│   NIEUW/SNEL/        │        │ finishedAt, status       │
│   VERRIJKT/MISLUKT/  │        │  (bezig/gelezen/gestopt) │
│   HANDMATIG          │        │ rating (0–5), review     │
│ enrichedSources      │        │ datePrecision (dag/jaar) │
│                      │        └──────────────────────────┘
│ lockedFields         │
│ openLibraryEditionId │        ┌──────────────┐  ┌──────────────────────────┐
│ uuid, addedAt,       │        │ Person       │  │ ImportProfile            │
│   updatedAt          │        │ id, name,    │  │ naam, kolom→veld mapping │
└──────────────────────┘        │ contactUri?  │  └──────────────────────────┘
                                └──────────────┘
```

Toelichting:
- **Work vs. Book.** *The Lord of the Rings* is één werk (of drie, per deel). Jouw Engelse paperback, de Nederlandse vertaling *In de ban van de ring* en een Duitse uitgave zijn drie Books die naar hetzelfde werk wijzen.
  - Auteurs en serie horen bij het **werk**; taal, vertaler, uitgever, cover, ISBN en locatie bij het **boek**.
  - **Omnibus**: één fysiek boek met alle drie de delen wijst naar drie werken (met volgorde). Zo klopt het serie-overzicht ook als je de delen zowel los als in één band hebt.
  - Koppelen gaat automatisch via het Open Library-werk-ID en Wikidata ("edition of"). Lukt dat niet, dan stelt de app een koppeling voor (zelfde auteur + gelijkende of oorspronkelijke titel) en bevestig je die. Handmatig samenvoegen of splitsen kan altijd.
  - Een nieuw boek zonder herkenbaar werk krijgt automatisch een eigen werk. Voor de gebruiker is dat onzichtbaar tot er een tweede editie bij komt.
- **Book = één fysiek exemplaar.** Twee exemplaren van hetzelfde ISBN mag (met waarschuwing).
- **Datumprecisie**: oude leesdatums zijn vaak alleen op het jaar bekend. Die worden getoond als "2010" en tellen in de jaarstatistiek mee, maar niet in de maandgrafiek.
- **Lezen** wordt per boek vastgelegd (je weet dus welke editie/taal je las), maar "gelezen" geldt voor het hele werk: heb je de Engelse versie gelezen, dan staat de Nederlandse ook als gelezen.
- **Zelf geleend** is een eigenschap van het boek (`ownership = BORROWED`, van wie, terugbrengen vóór). Na terugbrengen kies je: verwijderen of bewaren als "gelezen, niet in bezit" (handig voor de leeshistorie).
- **Uitgeleend** is een aparte `Loan`-regel, zodat de uitleengeschiedenis bewaard blijft.
- **Leesstatus** wordt afgeleid: geen sessie = ongelezen; plus een losse vlag "wil ik lezen".
- `seriesIndex` is tekst, want series nummeren ook als "0.5" of "II".

## 6. Navigatie en schermen

```
 ┌───────────────────────────────────────────────────────┐
 │  Bibliotheek   Lezen    [ SCAN ]   Uitleen    Meer    │   ← onderbalk
 └───────────────────────────────────────────────────────┘
 Meer: Series · Locaties · Importeren · Exporteren/back-up · Instellingen
```

### 6.1 Bibliotheek (startscherm)

```
┌──────────────────────────────────┐
│ Bibliotheek            ⟳  ▦/☰  ⋮ │  ⟳ = Bibliotheek bijwerken
│ ┌──────────────────────────────┐ │
│ │ 🔍 Zoek titel, auteur, ISBN  │ │
│ └──────────────────────────────┘ │
│ [Taal ▾][Locatie ▾][Status ▾][⇅] │  filterchips
│ 1.284 boeken · 37 nog bijwerken  │
│ ┌────┐ De avonden                │
│ │cov │ Gerard Reve · 1947        │
│ │    │ 📍 Woonkamer › Kast 2 › P3│
│ └────┘ ✓ gelezen                 │
│ ┌────┐ Der Zauberberg            │
│ │cov │ Thomas Mann · DE          │
│ └────┘ ↗ uitgeleend aan Anna     │
│ ┌────┐ 9789023456781     ◌       │  ◌ = wacht op gegevens
│ │ ?  │ (nog niet opgezocht)      │
│ └────┘                           │
└──────────────────────────────────┘
```
- Lijst- of rasterweergave (covers).
- Schakelaar **"Groepeer edities"**: één regel per werk met badges `EN · NL · DE ×3`, uitklapbaar naar de losse exemplaren. Uit = elk fysiek boek een eigen regel.
- Filters: taal, locatie, leesstatus, uitgeleend/geleend, serie, verrijkingsstatus.
- Sorteren: titel, auteur, toegevoegd, jaar, locatie (= volgorde op de plank).
- Zoeken is volledig offline (FTS) en werkt tijdens typen.

### 6.2 Scannen

```
┌──────────────────────────────────┐
│ ✕   Scannen        [Barcode|Cover]│
│ ┌──────────────────────────────┐ │
│ │                              │ │
│ │      ┌──────────────┐        │ │
│ │      │ ║│║║│║│║║│║  │        │ │  camerabeeld
│ │      └──────────────┘        │ │
│ │                        🔦    │ │
│ └──────────────────────────────┘ │
│ 📍 Plank-modus: Kast 2 › Plank 3 ▾│  aan/uit + kiezen
│ ──────── Deze sessie (12) ────── │
│ ✓ De avonden — Reve              │
│ ◌ 9783596294312  zoeken…         │
│ ⚠ Max Havelaar — al aanwezig     │
│                          [Klaar] │
└──────────────────────────────────┘
```
- Scant **doorlopend**: geen bevestiging per boek; geluid/trilling als feedback.
- Dezelfde barcode wordt binnen enkele seconden niet dubbel geteld.
- **Plank-modus**: kies eerst een plank, scan dan alles wat erop staat → locatie + volgorde op de plank worden direct vastgelegd. Ook te gebruiken om bestaande boeken te *verplaatsen* (scan van een aanwezig boek = "verplaatsen naar deze plank?" of automatisch, instelbaar).
- Tik op een regel in de sessielijst → boekdetail.
- **Cover-tab**: foto maken → kandidaten (6.4).
- Knop "handmatig invoeren" voor boeken zonder barcode.

### 6.3 Boekdetail

```
┌──────────────────────────────────┐
│ ←                         ✎  ⋮   │
│ ┌──────┐ De Donkere Toren        │
│ │      │ Deel 3: Het Verloren Land│
│ │cover │ Stephen King            │
│ │      │ Luitingh · 2004 · 512 p │
│ └──────┘ NL · Paperback          │
│ 📚 Serie: De Donkere Toren (3/7) ›│
│ 📍 Slaapkamer › Kast 1 › Plank 2 ›│
│ ─────────── Lezen ───────────────│
│ ✓ Gelezen 12-03 → 02-04-2024 ★★★★│
│ [+ Opnieuw lezen]                │
│ ─────────── Uitlenen ────────────│
│ [Uitlenen aan…]                  │
│ ─────────── Details ─────────────│
│ Beschrijving…                    │
│ ISBN 9789024543668               │
│ Bronnen: Open Library, Wikidata  │
│ [Gegevens opnieuw ophalen]       │
└──────────────────────────────────┘
```
- Sectie **"Andere edities in je collectie"**: bijv. *In de ban van de ring* (NL, Kast 2) en *Der Herr der Ringe* (DE, Zolder › Doos 3), met locatie, zodat je ziet welke versie waar staat.
- Bij een omnibus: lijst van de werken die erin zitten.
- Bij een zelf geleend boek staat bovenaan een balk: "Geleend van Piet — terug vóór 15-11 [Teruggebracht]".
- ⋮-menu: verwijderen, dupliceren (2e exemplaar), koppelen aan ander werk / losmaken, werken toevoegen (omnibus).

### 6.4 Kandidaten kiezen (cover-scan / zoeken op titel)

```
┌──────────────────────────────────┐
│ ←  Welk boek is het?             │
│ Herkend: "HET DIEPE WATER  ..."  │
│ ┌────┐ Het diepe water           │
│ │    │ Patricia Highsmith · 1957 │
│ └────┘ Open Library              │
│ ┌────┐ Deep Water                │
│ │    │ Patricia Highsmith · 1957 │
│ └────┘ Open Library              │
│ [Niet gevonden → handmatig]      │
└──────────────────────────────────┘
```

### 6.5 Boek toevoegen / bewerken (formulier)

Velden: titel, ondertitel, auteur(s) (met suggesties uit bestaande auteurs), originele titel, uitgever, jaar, taal (kiezer met NL/EN/DE/FR bovenaan), ISBN, pagina's, formaat, serie + deel (met suggesties), locatie, eigendom (eigen / geleend van…), tags, notities, cover (foto maken of URL).
Knop **"Zoek online"** vult het formulier vanuit de bronnen; alles blijft aanpasbaar. Aangepaste velden worden "vergrendeld" tegen automatisch overschrijven.

### 6.6 Lezen (vergelijkbaar met Have I Read That)

```
┌──────────────────────────────────┐
│ Lezen                    2026 ▾  │
│ ── Nu aan het lezen (2) ──────── │
│ [cover] [cover]                  │
│ ── Wil ik lezen (14) ──────── ›  │
│ ── Gelezen in 2026: 23 ───────── │
│  ▁▃▅▂▇▃▁▄▆▂  per maand           │
│  7.840 pagina's · gem. ★3,8      │
│  NL 11 · EN 8 · DE 3 · FR 1      │
│ ┌────┐ Stoner — John Williams    │
│ │    │ 02-10 → 19-10 · ★★★★★     │
│ └────┘                           │
└──────────────────────────────────┘
```
- Starten/afronden/stoppen met datum; herlezen = nieuwe sessie.
- Beoordeling als decimaal getal (0–5, invoer in halve sterren; geïmporteerde waarden als 3,25 blijven behouden) + korte recensie.
- Leesdoel per jaar (bijv. "16 boeken in 2026") met voortgangsbalk.
- Ook boeken die je niet (meer) bezit kunnen in de leeshistorie staan.

### 6.7 Uitleen

```
┌──────────────────────────────────┐
│ Uitleen                          │
│ [ Uitgeleend (5) | Geleend (2) ] │
│ ⚠ Der Zauberberg                 │
│   aan Anna · sinds 3 mnd · TE LAAT│
│   [Teruggekregen]                │
│   De ontdekking van de hemel     │
│   aan Jan · sinds 2 wk           │
│ ── Geschiedenis ─────────────── ›│
└──────────────────────────────────┘
```
- Personen kiezen uit eerder gebruikte namen of uit Android-contacten (optioneel, met toestemming).
- Herinneringsmelding bij vervaldatum (beide richtingen).
- Per persoon: wat heeft die nu en wat eerder geleend.

### 6.8 Series

- **Lijst**: naam, aantal in bezit / totaal, aantal gelezen.
- **Detail**: genummerde delen (werken, niet exemplaren), per deel welke edities/talen je hebt; ontbrekende delen grijs (uit Wikidata, of door jou toegevoegd als "ontbrekend deel").
- Boeken handmatig aan een serie koppelen, volgnummer aanpassen, series samenvoegen (bij dubbele namen uit verschillende bronnen/talen).

### 6.9 Locaties

```
┌──────────────────────────────────┐
│ Locaties                     +   │
│ ▾ Woonkamer               (612)  │
│   ▾ Kast 1                (340)  │
│       Plank 1              (48)  │
│       Plank 2              (51)  │
│   ▸ Kast 2                (272)  │
│ ▸ Slaapkamer              (180)  │
│ ▸ Zolder — dozen          (492)  │
└──────────────────────────────────┘
```
- Willekeurig diep (kamer › kast › plank, of zolder › doos 7).
- Tik op een plank → boeken in plankvolgorde; vanaf hier direct "scan deze plank".
- Optioneel later: QR-labels printen per plank; label scannen zet de plank-modus aan.

### 6.10 Importeren (CSV / Excel)

Wizard in 4 stappen — omdat het formaat van jouw bestand nog onbekend is, is de kolomtoewijzing volledig instelbaar:

1. **Bestand kiezen** (.csv, .tsv, .xlsx; bij Excel: tabblad kiezen).
2. **Voorbeeld** van de eerste 10 rijen; scheidingsteken, tekencodering (UTF-8 / Windows-1252) en kopregel automatisch herkend, aan te passen.
3. **Kolommen koppelen**: per kolom een doelveld (titel, auteur, ISBN, locatie, serie, deel, gelezen-datum, uitgeleend-aan, …) — automatisch geraden op kolomnaam (NL/EN). Opslaan als importprofiel voor de volgende keer.
4. **Controle**: X nieuw, Y mogelijk dubbel (zelfde ISBN of titel+auteur) → per groep kiezen: overslaan / samenvoegen / toch toevoegen. Daarna importeren; boeken met ISBN gaan in de verrijkingswachtrij.

Locatieteksten zoals "Woonkamer/Kast 2/Plank 3" worden automatisch omgezet naar de locatieboom.

### 6.11 Importeren uit Have I Read That (JSON)

Vaste importeur (geen kolomtoewijzing nodig), want het formaat is bekend. Analyse van de export van 01-11-2026: 700 boeken. Geïmporteerd wordt elk boek met `owned: true` **of** de tag `eigen-boeken`, behalve ebooks en luisterboeken → **336 boeken**.

| JSON-veld | Wordt | Opmerking |
|-----------|-------|-----------|
| `owned` / tag `eigen-boeken` | filter | `owned: true` of tag aanwezig |
| `title` | Book.title | Opschonen: `"Duin (Duin, #1)"` → titel "Duin" + serie/deel; afsluitende `:` weg |
| `author` | Work → Author(s) | Splitsen op `,`; dubbele namen weg ("Jason Matthews, Jason Matthews"). Naam die in `contributors` als *Translator* staat → vertaler i.p.v. auteur |
| `contributors` | vertaler / illustrator | `"Max Schuchart (Translator), Pauline Baynes (Illustrator)"` → per rol |
| `isbn` | Book.isbn13 | 290 van 328 hebben er een; de rest wordt verrijkt op titel + auteur |
| `format` | Book.format | paperback / hardcover / leeg; `ebook` en `audio` worden overgeslagen |
| `language`, `original_language`, `original_title` | Book.language, Work | Taal van de export is niet altijd juist (bijv. Engels ISBN met `nl`); bij conflict met het ISBN-prefix wordt het boek gemarkeerd voor controle |
| `publisher`, `published_year`, `pages`, `description`, `first_published` | Book / Work | |
| `series`, `series_number` | Work → Series | 22 boeken; Wikidata vult later aan |
| `tags`, `genre` | tags | `eigen-boeken` telt als "in bezit" en hoeft daarna niet als losse tag bewaard te worden |
| tag `geleend` | Loan (uitgeleend) | 6 boeken; open uitlening aan "onbekend", zonder datum — naam en datum vul je later in. Verschijnen dus direct in het Uitleen-scherm |
| `status` + `reads[]` | ReadingSession(s) | `read` → sessie "gelezen" (ook zonder datum); `reading` → open sessie; `dnf` → "gestopt"; `to-read` → vlag "wil ik lezen"; meerdere `reads` → meerdere sessies. Datum op 1 januari → precisie "alleen jaar" |
| `rating`, `review` | laatste ReadingSession | rating als decimaal |
| `date_added` | Book.addedAt | |
| `storygraph_id` | extern ID | voor latere koppeling |
| `goals` | leesdoel per jaar | `{"2026": 16}` |
| `awards` | Work.awards | |

- Geïmporteerde waarden gaan vóór online bronnen; verrijking vult alleen lege velden aan.
- Opnieuw importeren (nieuwere export) werkt bij op basis van het Have I Read That-`id`, dus geen dubbele boeken.
- Bij de latere Excel-import worden dubbelen met deze boeken herkend op ISBN of titel + auteur.

### 6.12 Instellingen

- Databronnen: aan/uit en volgorde.
- Achtergrond-verrijking: automatisch / alleen wifi / alleen handmatig.
- Scan-feedback: geluid, trillen; gedrag bij al aanwezig boek in plank-modus.
- Export: CSV (alles), volledige back-up (database + covers als ZIP) en terugzetten. Back-up opslaan via de Android-bestandskiezer, dus ook rechtstreeks naar een cloudmap als die app dat aanbiedt.
- Geen synchronisatie tussen toestellen (mogelijk later). Om dat later makkelijker te maken krijgt elk record een UUID en een `updatedAt`.
- Herinneringen voor uitleen aan/uit.

## 7. Projectstructuur

Eén Gradle-module, ingedeeld per functie. Voor een app van deze omvang is opsplitsen in modules vooral overhead; de pakketstructuur maakt latere opsplitsing makkelijk.

```
app/src/main/java/nl/wdhertog/library/
├── LibraryApp.kt                 Application + Hilt + WorkManager-config
├── MainActivity.kt               Compose host + navigatie
├── data/
│   ├── db/                       Room: entities, DAO's, migraties, FTS
│   ├── repository/               BookRepository, LoanRepository, …
│   └── backup/                   Export/back-up/terugzetten
├── lookup/                       Alles rond online gegevens
│   ├── BookSource.kt             interface: byIsbn(), search()
│   ├── sources/                  OpenLibrary, Dnb, Bnf, Kb, Wikidata
│   ├── SourceRouter.kt           ISBN-prefix → bronvolgorde
│   ├── MetadataMerger.kt         samenvoegen, respecteert lockedFields
│   └── EnrichWorker.kt           WorkManager-taak
├── importer/
│   ├── CsvReader.kt
│   ├── XlsxReader.kt
│   ├── ColumnGuesser.kt
│   └── ImportService.kt
├── scan/
│   ├── BarcodeAnalyzer.kt        CameraX + ML Kit
│   ├── CoverRecognizer.kt        ML Kit tekstherkenning
│   └── Isbn.kt                   validatie, ISBN-10↔13
├── ui/
│   ├── theme/
│   ├── components/               BookRow, CoverImage, LocationPicker, …
│   ├── library/  bookdetail/  bookedit/  scan/  candidates/
│   ├── reading/  loans/  series/  locations/  importer/  settings/
│   └── navigation/
└── notifications/                Herinneringen uitleen
```

Patroon: Compose-scherm → ViewModel (StateFlow) → Repository → Room / lookup. Room is de enige bron van waarheid: schermen kijken naar de database, dus wanneer verrijking op de achtergrond een boek bijwerkt, ververst de lijst vanzelf.

Tests: unittests voor ISBN-logica, bron-parsers (met opgeslagen voorbeeldantwoorden), samenvoegen en CSV/XLSX-import.

## 8. Bouwfases

| Fase | Inhoud | Resultaat |
|------|--------|-----------|
| 1 | Projectopzet, database, bibliotheeklijst, detail, handmatig toevoegen/bewerken | Bruikbare catalogus zonder scanner |
| 2 | Barcodescanner (doorlopend), snelle lookup, achtergrond-verrijking, "Bibliotheek bijwerken", bronnen OL/DNB/BnF/Wikidata | Snel boeken scannen |
| 3 | Locaties + plank-modus, uitleen in beide richtingen + herinneringen, cover fotograferen (direct na scan en vanuit boekdetail) | Weten waar elk boek is; covers voor Nederlandse boeken (geen open bron heeft die) |
| 4 | Import CSV/Excel (zodra het voorbeeldbestand er is). Details vragen aan het begin van deze fase | Bestaande collectie erin |
| 5 | Lezen (sessies, statistiek), series-schermen | Have I Read That-functies |
| 6 | Cover-herkenning, export/back-up, afwerking | Compleet |

## 9. Open vragen

Besloten:
- ~~Back-up / sync~~ → alleen back-up (ZIP); sync misschien later.
- ~~Google Books~~ → niet gebruiken.
- ~~Distributie~~ → eigen gebruik (APK) met ML Kit; F-Droid-variant eventueel later (zie §2).
- ~~Edities~~ → ja, groeperen per werk, inclusief omnibussen (zie §5).
- ~~Minimale Android-versie~~ → 8.0 (API 26).

- ~~Have I Read That~~ → JSON-export; `owned: true` of tag `eigen-boeken`, met leesgegevens (zie §6.11).

- ~~Tag `geleend`~~ → betekent uitgeleend; wordt een open uitlening.
- ~~Ebooks / luisterboeken~~ → niet importeren.
- ~~Datums op 1 januari~~ → precisie "alleen jaar".

Geen open vragen meer. Wacht nog op: voorbeeld van het Excel/CSV-bestand met de collectie (fase 4).
