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
| 7 | Volledige Have I Read That-import. Gebouwd: schakelaar "Ook boeken die je niet (meer) hebt" bij de import; niet-bezeten boeken als "niet in bezit", met "Geleend" (zelf geleend en teruggebracht) bij de tag `geleend`; formaten E-book en Luisterboek (eigen e-books tellen als bezit); leeslijst-boeken als "wil ik lezen"; bibliotheek toont standaard alleen wat je bezit, met filter Bezit, en zoeken vindt alles | Volledige leesgeschiedenis |
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
| 978-90, 978-94 | Nederlands | KB / data.bibliotheken.nl → Open Library → Wikidata |
| 978-3 | Duits | DNB → Open Library → Wikidata |
| 978-2, 979-10 | Frans | BnF → Open Library → Wikidata |
| 978-84 | Spaans (Spanje) | BNE → Open Library → Wikidata |
| 978-950, 987, 956, 958, 959, 968, 970, 607, 9972, 612, 9974, 980 | Spaans (Latijns-Amerika) | Open Library → BNE → Wikidata |
| 978-88, 979-12 | Italiaans | SBN → Open Library → Wikidata |
| 978-0, 978-1, 979-8 | Engels | Open Library → Wikidata |
| overig | — | Open Library → Wikidata → handmatig formulier |

| Bron | Wat levert het | Sleutel? | Opmerking |
|------|----------------|----------|-----------|
| **Open Library** (openlibrary.org) | Titel, auteurs, uitgever, jaar, pagina's, onderwerpen, soms serie; zoeken op tekst | Nee | Ook de standaard voor de snelle lookup |
| **Open Library Covers** | Coverafbeeldingen per ISBN | Nee | |
| **DNB** (Deutsche Nationalbibliothek, SRU) | Zeer complete Duitse metadata incl. **reeks + deelnummer** | Nee | Metadata is CC0 |
| **BnF** (Bibliothèque nationale de France, SRU) | Franse metadata incl. collectie/serie | Nee | |
| **BNE** (Biblioteca Nacional de España, SRU van de Alma-catalogus) | Spaanse metadata (MARC21) incl. vertaler, oorspronkelijke titel en taal | Nee | Uitgeversreeksen (zelfde naam als de uitgever) tellen niet als serie; de oude SPARQL-dienst (datos.bne.es) geeft 403 |
| **SBN** (Servizio Bibliotecario Nazionale, OPAC-JSON) | Italiaanse metadata: auteurs, vertalers, uitgever, jaar, pagina's, taal | Nee | Eerst zoeken op ISBN, dan het volledige record; "collezione" is meestal een uitgeversreeks en wordt genegeerd |
| **KB / data.bibliotheken.nl** (SPARQL) | Nederlandse Bibliografie | Nee | Ook gebruikt voor zoeken op titel bij cover-herkenning |
| **Wikidata** (SPARQL) | Serie (P179) + volgnummer (P1545), originele titel, vertaler, werk-koppeling | Nee | Vooral sterk voor bekende series |

Google Books wordt bewust **niet** gebruikt (geen open data).

Gegevens van meerdere bronnen worden samengevoegd: per veld wint de eerste bron die het heeft, behalve als jij het veld hebt aangepast. Van elk boek wordt bijgehouden welke bronnen zijn geraadpleegd.

**Cover-modus**: foto → tekstherkenning → grootste tekstregels als titel/auteur → zoeken in de KB en Open Library → kandidatenlijst → jij kiest. Lukt het niet: formulier, vooringevuld met de herkende tekst.

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
│ disposalReason, -To, │        │──────────────────────────│
│   -At (weggedaan)    │        │                          │
│ favorite, owner      │        │                          │
│ shelfPosition, notes │        │                          │
│ enrichStatus         │        │ id, bookId, startedAt,   │
│   NIEUW/SNEL/        │        │ finishedAt, status       │
│   VERRIJKT/MISLUKT/  │        │  (bezig/gelezen/gestopt) │
│   HANDMATIG          │        │ rating (0–5), review     │
│ enrichedSources      │        │ datePrecision (dag/jaar) │
│ hirtId, importRunId  │        │ progressPage, -Percent   │
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
- **Weggedaan** (weggegeven, verkocht, kwijt) zet het boek op "niet in bezit" met aan wie en wanneer; het blijft voor de leesgeschiedenis.
- **Eigenaar** is een naam per boek ("Willem", "Anna", "samen"), voor een collectie die je deelt. Leeg = niet ingevuld.
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

Zoals gebouwd (fase 4):
- Eigen lezers voor .xlsx (alle tabbladen, kies er één) en .csv/.tsv; geen extra bibliotheek.
- Doelvelden o.a. auteur als volledige naam óf voornaam / tussenvoegsel / achternaam, gelezen (ja/nee of datum), waardering, uitgeleend (aan), niet meer in bezit, weggedaan aan/op, favoriet, eigenaar, locatie, tags, notities.
- Een kolom met 1/0 of ja/nee kan de kolomnaam als **tag** of als **eigenaar** opleveren (bijv. kolommen "Bram" en "Anna"); onbekende ja/nee-kolommen worden standaard een tag. Een kolom kan ook een notitie "Kolom: waarde" worden.
- De toewijzing wordt per kopregel onthouden.
- Bestaande boeken: zeker (zelfde ISBN, of titel + auteur als één ISBN ontbreekt) of twijfel (zelfde titel en auteur, ander ISBN; standaard apart toevoegen). Keuze voor alles samen en per boek: aanvullen / bestand gaat voor / overslaan.
- Elke import is ongedaan te maken: de toegevoegde boeken worden verwijderd, aanvullingen op bestaande boeken blijven.

### 6.11 Importeren uit Have I Read That (JSON)

**Historisch:** deze import is in 1.9 uit de app gehaald (§12.5); Goodreads nam de plaats in. De kolom `book.hirtId` en het sync-veld `hirtId` blijven, zodat eerder geïmporteerde boeken en telefoons met een oudere versie blijven werken.

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
| 4 | Import Have I Read That (JSON) en CSV/Excel met kolomtoewijzing, controle per boek en ongedaan maken. Erbij: favoriet, eigenaar (gedeelde collectie), weggedaan (hoe, aan wie, wanneer), leesvoortgang | Bestaande collectie erin |
| 5 | Lezen (sessies, statistiek), series-schermen. Gebouwd: tab Lezen (nu bezig met voortgang, leeslijst, leesdoel, per jaar: maandgrafiek, pagina's, gemiddelde, talen); leessectie op de boekpagina (beginnen, voortgang, uitgelezen/gestopt, halve sterren, recensie, herlezen, sessies van andere edities); series-lijst en -detail (delen op volgorde, edities per deel, ontbrekende nummers tot 100, naam wijzigen/samenvoegen, aantal delen, deelnummer) | Have I Read That-functies |
| 6 | Cover-herkenning, export/back-up, afwerking. Gebouwd: back-up (ZIP) en terugzetten, automatische wekelijkse back-up naar een gekozen map, CSV-export, cover-herkenning bij het scannen (ML Kit-tekst → KB + Open Library), instellingen (alleen wifi, geluid/trillen, herinneringen), filters op eigenaar/gelezen/favorieten, app-icoon | Compleet (v1.0) |
| 7 | Volledige Have I Read That-import: ook boeken die niet (meer) in bezit zijn en e-books/luisterboeken, als "niet in bezit" (niet als "geleend", want dat betekent: moet terug). Bibliotheek standaard alleen boeken in bezit tonen, met filter voor de rest. Details vragen bij de start | Volledige leesgeschiedenis |

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

Geen open vragen meer. ~~Wacht nog op: voorbeeld van het Excel/CSV-bestand met de collectie (fase 4).~~ Ontvangen en verwerkt.

## 10. Nog te doen (na v1.0)

Stand 3 november 2026. Fase 1–7 en de meervoudige selectie zijn af; laatste release v1.2.

### Gebouwd in 1.9 (6 november 2026)
- **JSON-export** (§12.1): knop op het Back-up-scherm, om je gegevens ergens anders te gebruiken.
- **Have I Read That-import weg** (§12.5); Goodreads neemt de plaats in.
- **Hoofdeditie** bovenaan bij het filter "Meerdere edities"; **badges** op de deel-afbeelding van het jaaroverzicht.
- **Review**: synchroniseren en koppelen lopen door als je het scherm draait (was een echte fout), sync-dienst veilig bij snel aan/uit, cloudbestanden niet meer op de main thread, camera-analyse op een eigen thread.

### Gebouwd in 1.8 (6 november 2026)
- **Auteurs samenvoegen** (§12.2): hernoemen en samenvoegen op de auteurspagina, suggesties "Mogelijk dezelfde auteur".
- **Hoofdeditie** (§12.3): zelf een editie als hoofdeditie aanwijzen; cover en titel in series en op de auteurspagina. Database versie 8.
- **Goodreads-import** (§12.4): de CSV-export van Goodreads, met leesgeschiedenis; opnieuw importeren zonder dubbelen (`book.externalIds`).
- **Badges, tweede versie** (§12.7): melding bij uitlezen, badges in het jaaroverzicht, details per badge, voortgang leesdoel, vier nieuwe badges.
- **Review, tweede deel**: één gedeelde bibliotheeklijst, minder werk op de main thread, robuustere import, en kleine UI-reparaties (scrollpositie, opslaan, draaien van het scherm).

### Gebouwd in 1.7 (5 november 2026)
- **Leesstreaks** (§12.6): maanden en weken op rij met een uitgelezen boek, en dagen op rij (één vrije dag per week) uit "Voortgang bijwerken" en de begin- en einddatums van sessies; tabel `reading_log`, database versie 7.
- **Badges** (§12.7): mijlpalen uit je eigen leesgeschiedenis, met datum, ook met terugwerkende kracht; kaart op Lezen en een eigen scherm.
- **Review op bugs, lekken, beveiliging en efficiëntie**: sync dichtgezet (begrensde berichten, sessie pas na controle, covernamen zonder pad), geen Android-cloudback-up meer, atomair terugzetten, back-up eerst in de app, verrijkingswachtrij die niet meer vastloopt, wijzigingen tijdens een sync gaan niet verloren, outbox wordt opgeruimd, indexen op uuid, en diverse crashes en kleine lekken.

### Gebouwd in 1.6 (4 november 2026)
- **Delen** (gebouwd): een boek delen via het deelmenu van Android (titel, auteur, jaar, ISBN en een link naar Open Library), en bij een uitgeleend boek "Navragen…" met een kant-en-klaar berichtje (op de boekpagina en in Uitleen). De app stuurt zelf niets.
- **Ontbrekend deel op je leeslijst** (gebouwd): in het venster van een ontbrekend deel "Op mijn leeslijst". Dat wordt een boek "niet in bezit" in die serie, met de auteur van de andere delen, en verschijnt in Lezen onder "Wil ik lezen".
- **Jaaroverzicht** (gebouwd): op Lezen onder het leesdoel "Jouw {jaar} in boeken" (voor elk jaar met gelezen boeken). Totaal, pagina's en gemiddelden, dikste boek, meest gelezen auteur (vanaf twee boeken), favorieten (hoogste beoordeling, vanaf vier sterren, hooguit drie), drukste maand, snelst uitgelezen (begin- en einddatum bekend), eerste en laatste boek, talen. Delen als afbeelding (1080 × 1350, lichte kleuren: jaartal, aantal, covers, hoogtepunten; via een FileProvider op `cache/gedeeld`) of als platte tekst.
- **Widget "Nu aan het lezen"** (gebouwd): RemoteViews, 4×1 (vergrootbaar), eigen palet met donkere variant. Het boek waar je het langst mee bezig bent (zoals op Lezen) met cover, auteur en voortgang, plus "en nog N andere boeken". Tik opent de boekpagina, of Lezen als je niets leest. Bijgewerkt als leessessies veranderen terwijl de app draait (ook door synchroniseren), bij het verlaten van de app en verder eens per drie uur. Plaatsen via Meer → "Widget op beginscherm" of de widgetlijst van de launcher.

### Gebouwd in 1.5 (4 november 2026)
- **Nieuwe vormgeving** naar de schetsen van 4 november 2026 (lokaal: `samples/bibliotheek_app_schetsen.html`).
  - **Besloten (4 november 2026):**
    - altijd het eigen groen-crème palet, niet de kleuren van de achtergrond (Material You);
    - in donkere modus een donkere variant van dezelfde tinten (volgt de telefoon);
    - de onderbalk wordt Bibliotheek · Lezen · Series · Uitleen · Meer, met Locaties onder Meer.
  - **Stappen**, elk een eigen PR plus een testversie:
    1. **Basis:** palet licht en donker; Literata (OFL, in de app) voor schermtitels en boektitels; kaartstijl (`AppCard`); covers zonder afbeelding met boekrug en serif; Scannen goud; groene markering in de onderbalk. *Gebouwd.*
    2. **Navigatie:** de nieuwe onderbalk en een Meer-scherm (Locaties, Auteurs, Importeren, Back-up, Synchroniseren, Instellingen). Het ⋮-menu van de bibliotheek houdt alleen "Handmatig toevoegen" en "Bibliotheek bijwerken". Kaarten zijn in het donker iets lichter dan de achtergrond. *Gebouwd.*
    3. **Bibliotheek:**
       - afgeronde zoekbalk ("Zoek in N boeken") met weergave, sorteren en menu;
       - chip Filters met het aantal en een paneel van onderaf; actieve filters als groene chips met ✕, zonder filters de snelkeuzes Gelezen en Favorieten;
       - status per boek op één regel met de plek (`BookStatus`: uitgeleend aan … en hoe lang, aan het lezen %, gelezen in jaar, ook via een andere editie);
       - "N worden aangevuld" met een dun voortgangslijntje;
       - rasterweergave (covers 2:3, drie kolommen, badges voor gelezen en uitgeleend, voortgangsbalkje), onthouden in de instellingen.
       - *Gebouwd.*
    4. **Boekpagina:** cover op een kleurvlak (kleur uit de cover), titel in serif gecentreerd, één hoofdknop die met de status meebeweegt, plus Uitlenen; kaarten (Lezen, Plek en eigenaar, Beschrijving ingeklapt met tags als chips, Gegevens en bronnen dicht). Ook het bewerkscherm in dezelfde stijl: velden in kaarten per onderwerp. *Gebouwd:* kleurvlak uit de gemiddelde kleur van de cover (op de telefoon berekend, voor een derde gemengd met de achtergrond); hoofdknop "Beginnen met lezen" / "Voortgang bijwerken" / "Opnieuw lezen", tweede knop "Uitlenen" / "Teruggekregen" / "Teruggebracht…"; die knoppen staan niet nog eens in de kaarten. Bewerkscherm in de kaarten Titel en auteurs · Uitgave · Serie · Collectie. Geselecteerde chips zijn overal groen; goud (`Gold`) alleen voor Scannen en uitgeleend.
    5. **Lezen:** jaarkeuze, leesdoel als ring, "Nu aan het lezen" als covers met voortgang, "Wil ik lezen" als rij covers met "Alles", maandgrafiek in een kaart. *Gebouwd:* de ring zegt ook of je op schema ligt (naar rato van de dag in het jaar), met pagina's en gemiddelde beoordeling; de balken zijn lichtgroen en de huidige maand donkergroen.

- **Ook in 1.5:**
- **Auteurspagina.**
  - **Lijst Auteurs** via ⋮ in de bibliotheek: op achternaam, met een zoekveld en het aantal boeken en werken.
  - **Per auteur:** gegevens uit Wikidata (jaren, land, omschrijving; database versie 6, per telefoon), aantal boeken, werken, gelezen en je gemiddelde beoordeling. De series met "x van y delen", en de boeken per werk (edities bij elkaar, op serievolgorde en jaar) met een vinkje bij gelezen.
  - **Op de boekpagina** is elke auteursnaam aanklikbaar.
- **Automatisch opzoeken uit** (§6.12): alleen nog met "Bibliotheek bijwerken" of "Gegevens opnieuw ophalen". De bibliotheek toont dan "N boeken nog niet opgezocht" met de knop **Nu opzoeken**.

### Gebouwd in 1.4
- **Synchronisatie tussen telefoons:** zie §11.

### Gebouwd na v1.2
- **Filter "Meerdere edities"**: alleen boeken waarvan je (binnen de bezit-filter) minstens twee edities of vertalingen van hetzelfde werk hebt; de edities staan in de lijst bij elkaar.
- **Werken handmatig samenvoegen en splitsen** (§5): op de boekpagina "Koppelen aan andere editie…" en "Loskoppelen van andere edities". Losgekoppelde boeken worden niet meer automatisch gekoppeld (`lockedFields` bevat dan `work`); opnieuw koppelen heft dat op.

- **Ontbrekende delen uit Wikidata** (§6.8). Gebouwd 4 november 2026.
  - **Ophalen:** de delen van een serie (nummer, titel, jaar) komen uit Wikidata, via de serie-id of via de Wikidata-id's van je werken. De serie-id wordt voortaan bewaard bij het aanvullen van gegevens.
  - **Opslag:** de delen staan in `series_part` (database versie 5). Dat is afgeleide data per telefoon, niet gesynchroniseerd.
  - **Wanneer:** bij het openen van de serielijst of een serie, hooguit eens per 30 dagen, en via ⋮ → "Delen opzoeken op Wikidata".
  - **Serie-scherm:** ontbrekende delen tonen titel en jaar.
  - **Serielijst:** het totaal komt uit Wikidata als je zelf geen aantal hebt opgegeven, maar alleen als Wikidata minstens de helft van de nummers kent. Kent Wikidata alleen deel 13 van Poirot, dan is 13 geen totaal.
  - **Uitgeversreeksen** met meer dan 100 nummers worden genegeerd.
  - **In jouw bibliotheek** leverde dit delen op voor 19 van de 52 series (bijv. Department Q 9, Mma Ramotswe 21, Montalbano 35). Titels zijn vaak Engels, als Wikidata geen Nederlandse kent.

- **Ontbrekend deel zelf toevoegen** (§6.8), gebouwd 4 november 2026.
  - Op het serie-scherm via ⋮ → "Ontbrekend deel toevoegen…" (nummer en titel), of door op een ontbrekend deel te tikken (titel invullen, aanpassen of weghalen).
  - Opgeslagen in `Series.manualParts` (JSON) en gedeeld via synchroniseren. Een zelf ingevulde titel gaat voor die van Wikidata, en telt mee in het totaal van de serielijst.
  - Heb je het deel later, dan verdwijnt het vanzelf.
  - **Series zonder één deelnummer** (zoals Orphan X bij jou): de app kan dan niet zien welke nummers ontbreken. Er verschijnen alleen de delen die je zelf hebt toegevoegd, niet langer een rij lege nummers.
- **Taal uit het ISBN** (§6.11), gebouwd 4 november 2026.
  - `IsbnLanguage` leidt de taal af uit de registratiegroep: 978-90/94 Nederlands, 978-0/1 Engels, 978-2 Frans, 978-3 Duits, 978-84 Spaans, 978-88 Italiaans, enzovoort. Talen die in een land ook gewoon voorkomen (Catalaans bij 978-84, Fries bij 978-90) tellen niet als tegenstrijdig.
  - **Aanvullen:** geeft geen bron een taal, dan neemt het boek die van het ISBN.
  - **Importeren:** spreekt de taal in het bestand het ISBN tegen, dan blijft die taal staan, maar krijgt het boek de tag "taal controleren"; de melding na de import noemt het aantal.
  - **Eenmalig voor de bestaande bibliotheek** (bij de eerste start van 1.4, en opnieuw na het terugzetten van een back-up): lege talen invullen en tegenstrijdige taggen. In jouw bibliotheek: 38 talen aangevuld, 1 boek getagd ("A History of Japan to 1334", Engels ISBN met "nl" uit Have I Read That).

- **Instellingen** (§6.12), gebouwd 4 november 2026.
  - **Bronnen aan/uit:** per bron (KB, DNB, BnF, BNE, SBN, Open Library, Wikidata). Uit betekent overal uit: bij het opzoeken, bij cover-herkenning en bij de delen van een serie. Staan alle bronnen voor een ISBN uit, dan krijgt het boek "niet gevonden"; "Bibliotheek bijwerken" pakt het weer op als je een bron aanzet.
  - **Volgorde:** bewust niet instelbaar. De volgorde volgt uit het ISBN (eerst de nationale bibliotheek van het land van de uitgever, dan Open Library). Een vaste volgorde voor alle boeken zou slechter werken.
  - **Plank-modus, een boek dat al op een andere plek staat:**
    - **verplaatsen** (standaard, zoals voorheen, met ongedaan maken);
    - **eerst vragen** (venster met de huidige plek; scannen wacht tot je kiest);
    - **laten staan** (in de lijst "Staat in … — niet verplaatst", met de knop "Hierheen").
    - Een boek dat nog nergens staat, gaat altijd naar de plank.

- **Een boek scannen dat als niet in bezit staat**, gebouwd 4 november 2026. Bijvoorbeeld een boek uit de Have I Read That-import dat je ooit leende en nu gekocht hebt.
  - Er verandert niets vanzelf. In de scanlijst staat "Niet in bezit · Weggegeven aan Jan" (of wat er bekend is), met de knop **In bezit**: weer een eigen boek, en de gegevens over weggedaan vervallen. De leesgeschiedenis blijft.
  - In plank-modus vraagt de app eerst: "Weer in bezit nemen en op deze plank zetten?" De instelling "verplaatsen / eerst vragen / laten staan" geldt voor boeken die je wél hebt.


### Eigenaar voor meerdere boeken tegelijk (gebouwd in 1.2)
Voor een gedeelde collectie: de eigenaar niet per boek hoeven invullen. Mogelijkheden:

| Optie | Hoe | Voor | Tegen |
|-------|-----|------|-------|
| **A. Meervoudige selectie** (voorkeur) | Lang drukken in een boekenlijst start de selectie; daarna acties voor alle gekozen boeken: eigenaar, locatie, favoriet, wil ik lezen, tags, verwijderen. Op de locatiepagina staat "Alles selecteren" | Ook nuttig voor andere velden; "alles op deze plank" is gewoon alles selecteren en dan de uitzonderingen uitvinken | Meeste werk |
| B. Eigenaar per plank | Op de locatiepagina: "Eigenaar voor alle boeken hier", daarna afwijkende boeken los aanpassen | Snel en eenvoudig | Alleen voor eigenaar; met A is dit "alles selecteren" |
| C. Eigenaar-modus bij scannen | Net als plank-modus: kies een eigenaar en scan; elk gescand boek krijgt die eigenaar | Handig bij het fysiek doorlopen van een gemengde kast | Alleen met boeken met barcode; aanvulling op A, geen vervanging |

**Besloten (2 november 2026): optie A.** C komt er alleen bij als het bij het gebruik gemist wordt.

**Gebouwd (3 november 2026):** lang drukken op een boek in de bibliotheek of op een locatiepagina begint de selectie. Acties: eigenaar, verplaatsen (achteraan, in lijstvolgorde), favoriet aan/uit, leeslijst aan/uit, tags toevoegen, verwijderen. "Alles selecteren" pakt alle boeken in de lijst, of alle boeken op die plek.

### Bronnen voor meer talen
- **Roemeens** (besloten 4 november 2026: komt er niet; eerder geparkeerd op 3 november): de Biblioteca Națională a României heeft alleen Z39.50 (UNIMARC) en een Aleph-webcatalogus op aleph.bibnat.ro, die vanaf hier niet bereikbaar was. Tot er een open HTTP-dienst gevonden is: Open Library en Wikidata.

### Later, misschien
- **Eigenaar-modus bij scannen** (optie C hierboven): besloten 4 november 2026, niet nodig; wordt niet gemist.
- **F-Droid-variant zonder ML Kit** (§2): geparkeerd (4 november 2026) tot besloten is of de app echt via F-Droid uitgebracht wordt.

### Losse eindjes
- **Dubbele series samenvoegen** in de app. De serielijst wijst nu zelf waarschijnlijk dubbele series aan (kaartje "Mogelijk dezelfde serie", met Samenvoegen of Negeren), zoals "Arsène Lupin" en "Arsène Lupin literary series". Dezelfde serie in een andere taal herkent de app niet; die voeg je zelf samen via Series → ⋮ → Samenvoegen met…:
  - "In de ban van de ring" met "The Lord of the Rings";
  - "De Grijze Jager" met "Ranger's Apprentice".
- **Pull request #4** toont op GitHub nog de oude namen in de diff. Opruimen kan alleen via GitHub Support ("Remove sensitive data").

## 11. Synchronisatie tussen telefoons (plan)

Stand 4 november 2026: gebouwd en uitgebracht in versie 1.4; zie Voortgang.

### Besloten (3 november 2026)

| Vraag | Keuze |
|-------|-------|
| Transport | **Alleen via de wifi thuis**: de telefoons praten rechtstreeks met elkaar, zonder cloud of server |
| Wat is gedeeld | **De collectie** (boeken, werken, auteurs, series, plekken, eigenaar, uitlenen, covers). **Persoonlijk** en dus niet gesynchroniseerd: leessessies, beoordelingen, recensies, leesdoel, favoriet, "wil ik lezen" |
| Startsituatie | **Beide telefoons hebben al boeken**: bij de eerste keer samenvoegen, met een controlescherm |
| Snelheid | **Binnen enkele minuten** is goed genoeg |
| Op de achtergrond | **Ja**: thuis luistert een achtergronddienst met een stille, vaste melding; uit te zetten in Instellingen |
| Koppelen | **QR-code scannen** met de eigen scanner |

### Doel
Twee telefoons in één huishouden houden dezelfde collectie bij. Iedereen kan scannen, verplaatsen, uitlenen en wijzigen, ook als de ander niet thuis is. Zodra beide telefoons op de wifi thuis zijn, worden de wijzigingen uitgewisseld. Ieder houdt zijn eigen leesgeschiedenis.

### Hoe het werkt

**Koppelen (eenmalig)**
1. Telefoon A: **Instellingen → Synchroniseren → Telefoon koppelen**. A toont een QR-code met daarin zijn telefoon-id, een vingerafdruk van zijn sleutel en een eenmalig koppelgeheim.
2. Telefoon B scant de code. Beide telefoons wisselen hun publieke sleutels uit en bewaren die van de ander: vanaf dan vertrouwen ze alleen elkaar.
3. Daarna volgt het samenvoegen van beide bibliotheken (zie hieronder).

**Elkaar vinden**
- Thuis meldt elke telefoon zich op het netwerk aan als `_bibliotheek._tcp` (Android NSD/mDNS) en luistert op een poort.
- Een gekoppelde telefoon die hem ziet, maakt verbinding.

**Beveiliging**
- De verbinding is versleuteld met TLS. Elke telefoon controleert de sleutel van de ander tegen de bij het koppelen bewaarde vingerafdruk.
- Andere apparaten op het netwerk kunnen de telefoon wel zien, maar niets opvragen.
- Gekoppelde telefoons zijn te ontkoppelen.

**Op de achtergrond**
- Met een gekoppelde telefoon draait een *foreground service*. Android vereist daarvoor een vaste melding: stil en klein, "Klaar om te synchroniseren".
- Alleen op een thuisnetwerk (een wifi-netwerk waar al eens is gesynchroniseerd) luistert de app en meldt hij zich aan. Elders blijft de dienst bestaan, met de melding "wacht op het thuisnetwerk". Android staat niet toe dat de dienst later vanaf de achtergrond opnieuw start.
- In Instellingen is dit uit te zetten. Dan synchroniseert de app alleen als hij open staat (en via **Nu synchroniseren**).

**Wanneer**
- Bij het openen en sluiten van de app, na een scansessie of bulkactie, en elke paar minuten zolang beide telefoons thuis zijn.

### Gegevens
- **Wijzigingenlogboek per telefoon** met oplopende volgnummers. Bij een verbinding vraagt elke telefoon aan de ander: "geef me alles na nummer N". Per gekoppelde telefoon wordt N bijgehouden.
- **Eén wijziging** bevat: soort record, uuid, de gewijzigde velden met hun nieuwe waarde, en een tijdstempel. Een verwijdering wordt een *grafsteen* (uuid + tijdstip).
- **Meer dan twee telefoons:** een telefoon kan met meerdere telefoons gekoppeld zijn (bijvoorbeeld ook die van een kind). Wat binnenkomt en wint, geeft hij door aan zijn andere gekoppelde telefoons. Elke nieuwe telefoon hoeft dus maar met één bestaande gekoppeld te worden. Niets gaat terug naar de telefoon waar het vandaan kwam (de tijdstempel bevat het telefoon-id), en wat ergens al bekend is, wint daar niet en gaat niet verder.
- **Samenvoegen per veld; de laatste wijziging wint.** Per veld onthoudt de app wanneer het voor het laatst gewijzigd is. Wijzig jij de eigenaar en vult de andere telefoon tegelijk de uitgever aan, dan blijven beide staan.
- **Tijdstempels:** een hybride logische klok (tijd + teller + telefoon-id). Een telefoon met een verkeerd ingestelde klok kan zo geen nieuwere wijzigingen overschrijven.
- **Vastleggen:** wijzigingen worden in de database zelf vastgelegd (SQLite-triggers op de gedeelde tabellen), zodat ook bulkacties en de achtergrondverrijking automatisch meegaan. Bij het uitwisselen vergelijkt de app met de laatst uitgewisselde versie (een *schaduwkopie*) en stuurt alleen de velden die echt anders zijn.
- **Id's:** de lokale nummers verschillen per telefoon. Uitwisselen gaat op uuid.
- **Covers:** een eigen cover-foto gaat als bestand mee wanneer de ander hem nog niet heeft. De naam staat al in het boek.

### Wat is persoonlijk

| Gedeeld | Persoonlijk (blijft op de telefoon) |
|---------|-------------------------------------|
| Boek (alle velden behalve favoriet en "wil ik lezen"), werk, auteurs, serie, locaties, eigenaar, personen, uitleningen, geleend van, weggedaan, eigen covers | Leessessies, beoordeling, recensie, voortgang, leesdoel, favoriet, "wil ik lezen", instellingen, imports |

Eén randgeval: de ander verwijdert een boek uit de gedeelde collectie waar jij leesgeschiedenis bij hebt. Dan blijft het boek bij jou als "niet in bezit", zodat je leesgeschiedenis niet verdwijnt.

### Eerste keer: twee bibliotheken samenvoegen
- Na het koppelen stuurt elke telefoon een overzicht van zijn boeken.
- **Herkennen:** zoals bij de import (`ImportMatcher`): eerst ISBN, dan titel + eerste auteur als een van beide geen ISBN heeft. Zelfde titel met een ander ISBN wordt een twijfelgeval.
- **Controlescherm:** "op beide telefoons / alleen bij jou / alleen bij de ander / twijfelgevallen", met per boek de keuze **hetzelfde boek** of **apart houden**. Apart houden is bijvoorbeeld nodig als jullie elk echt een exemplaar hebben.
- **Hetzelfde boek:** de records worden één, met één uuid. Velden die maar op één telefoon zijn ingevuld, worden aangevuld. Verschillen beide waarden, dan wint de meest recente; plek en eigenaar laten zien welke het werd.
- **Locaties:** de locatieboom wordt samengevoegd op naam, met hetzelfde pad. "Woonkamer › Kast 2" op beide telefoons wordt dus één plek.
- **Vooraf** maakt elke telefoon automatisch een back-up.

### Bouwstappen

| Stap | Inhoud |
|------|--------|
| S1 | **Datamodel**: telefoon-id, hybride klok, per-veld-klokken, grafstenen, triggers en schaduwkopie; uuid voor auteurs (of de naam als sleutel); migratie met behoud van alle gegevens |
| S2 | **Wijzigingen** opbouwen, verzenden en samenvoegen per veld, met de scheiding gedeeld/persoonlijk. Uitgebreide unit-tests: gelijktijdige wijzigingen, verwijderingen, klokverschillen, de leesgeschiedenis-regel |
| S3 | **Koppelen en verbinden**: QR-code, sleutels, TLS met vastgepinde sleutel, NSD, het scherm Synchroniseren en de knop "Nu synchroniseren" |
| S4 | **Eerste keer samenvoegen**: overzicht uitwisselen, herkennen, controlescherm, locaties samenvoegen |
| S5 | **Achtergronddienst** met melding (basis al in S3), starten en stoppen op het thuisnetwerk, synchroniseren na wijzigingen, covers meesturen |
| S6 | **Test met twee telefoons** (twee testversies, door je zus), daarna synchroniseren aanzetten in de gewone app en uitbrengen |

Elke stap komt in een eigen branch en pull request. Testen gebeurt eerst met de staging-app en een kopie van de gegevens, net als bij de import.

**Testen in S6:**
- Beide testers krijgen een aparte testversie (eigen app-id, naast de gewone app). Zo komen echte bibliotheken nooit per ongeluk samen.
- Beide telefoons zitten op hetzelfde wifi-netwerk.
- De testversie komt als losse "testversie" op de releasepagina.

### Voortgang
- **S1 (3 november 2026):**
  - nieuwe tabellen `sync_meta`, `sync_dirty`, `sync_shadow`, `sync_field_clock`, `sync_outbox` en `sync_peer`;
  - database versie 4;
  - 23 triggers, die alleen vastleggen als synchroniseren aan staat;
  - de hybride klok.
  - Getest met unit-tests op echte SQLite met het Room-schema, en met een upgrade van een echte back-up (425 boeken) in de staging-build.
- **S2 (3 november 2026):** het hart van de synchronisatie.
  - **`SyncEngine`:**
    - uitgaande wijzigingen klaarzetten; alleen echt gewijzigde velden gaan mee, vergeleken met de schaduwkopie;
    - binnenkomende samenvoegen per veld, met de hybride klok;
    - verwijderen wint, behalve bij eigen leesgeschiedenis (dan lokaal "niet in bezit" en losgekoppeld);
    - niets kaatst terug.
  - **`SyncSchema`:** welke velden gedeeld zijn.
  - **`RoomSyncStore`:** vertaalt tussen tabellen en uitwisselvorm (uuid's, lijsten voor auteurs en werken).
  - **Triggers aangepast:** eerst weghalen, dan invoegen. `INSERT OR REPLACE` in een trigger botst met de `OR ABORT` van Room.
  - **Getest** met twee gesimuleerde telefoons (10 tests) en met twee echte Room-databases via Robolectric (5 tests).
- **S3 (3 november 2026):** koppelen en verbinden.
  - **Doorgeven:** de engine zet wat van een andere telefoon binnenkomt en wint, in de eigen outbox (met de oorspronkelijke tijdstempel). Zo werkt A–B–C zonder dat A en C gekoppeld zijn. Niets gaat terug naar de bron.
  - **`PairingCode`:** de koppelcode (`BIB1:…`) met telefoon-id, naam, vingerafdruk, adres en een eenmalig geheim (10 minuten geldig). De koppelende telefoon bewijst het geheim met een HMAC over zijn id en certificaat.
  - **`SyncSession`:** het gesprek als JSON-regels (hello, wijzigingen heen en terug, done). Alleen gekoppelde telefoons met het bekende certificaat, of met een geldig koppelbewijs, krijgen iets.
  - **`SyncIdentity` en `SyncTls`:** een EC-sleutel in de Android Keystore met eigen certificaat; TLS waarbij de verbinder de vingerafdruk van de ander vastpint. Getest met de TLS van Java en met Conscrypt (die van Android).
  - **`Discovery`:** NSD `_bibliotheek._tcp` met het telefoon-id.
  - **`SyncManager`:** luistert zolang de app in beeld is en er een gekoppelde telefoon is; automatisch synchroniseren zodra de ander gevonden is (alleen de telefoon met het kleinste id begint); één sessie tegelijk.
  - **Uit S5 naar voren gehaald:** de achtergronddienst (`SyncService`, type *connectedDevice*) met stille melding, aan/uit in het scherm. Nodig omdat Android apps op de achtergrond bevriest.
  - **Scherm Synchroniseren:** QR-code tonen, scannen of plakken; gekoppelde telefoons met laatste keer en ontkoppelen; "Nu synchroniseren".
  - Synchroniseren staat alleen in debug- en staging-builds (`BuildConfig.SYNC`), tot S6 af is. `-PstagingVariant=2` bouwt een tweede testapp (`.staging2`, "Bibliotheek test 2").
  - **Getest op de Fairphone met twee testapps:** koppelen via plakken; 425 boeken (913 wijzigingen) overgekomen en identiek; favorieten, leeslijst en leessessies bleven op de eigen telefoon; een gewijzigde eigenaar ging met "Nu synchroniseren" over terwijl de andere app op de achtergrond stond. QR-scannen kan pas met twee telefoons getest worden.
- **S4 (3 november 2026):** eerste keer samenvoegen.
  - **Wie samenvoegt:** de telefoon die de code scant, als die nog met geen enkele telefoon gekoppeld is. Hij voegt zich bij de ander (die mag al in een groep zitten).
    - Is de scannende telefoon al gekoppeld, dan moet het andersom, tenzij de ander leeg en nieuw is.
    - Twee groepen samenvoegen kan niet.
  - **Overzicht:** de ander zet eerst al zijn wijzigingen klaar en stuurt dan een overzicht van alle gedeelde records, met zijn klokstand. Hij slaat de scannende telefoon dan nog niet op als gekoppeld; dat gebeurt pas bij de uitwisseling na het samenvoegen, met hetzelfde koppelbewijs.
  - **`FirstMerge`:**
    - overeenkomsten: boeken met `ImportMatcher` (ISBN, anders titel + auteur; zelfde titel met een ander ISBN is een twijfelgeval), plekken op pad, series en personen op naam, en daarnaast werken en uitleningen van samengevoegde boeken;
    - lokale records krijgen de uuid van de ander, en het bijhouden begint opnieuw;
    - de schaduwkopie per veld bepaalt wie wint: een ingevuld veld wint van een leeg veld, en bij een verschil het record met de laatste wijziging (`updatedAt`). Daarna doet de gewone uitwisseling de rest.
  - **Controlescherm:** twijfelgevallen bovenaan (standaard apart), daaronder de zekere paren (standaard samen), met aantallen.
  - **Kopieën:** vóór het samenvoegen maken beide telefoons een kopie (`voor-samenvoegen.zip`, in de app). Die is net als de kopie van vóór het terugzetten terug te zetten in het Back-up-scherm.
  - **Na terugzetten van een back-up:** nieuw telefoon-id, geen koppelingen, en het bijhouden begint opnieuw. De back-up kan van een andere telefoon komen, of ouder zijn dan wat gekoppelde telefoons al hebben.
  - **Getest:**
    - unit-tests: in het geheugen, waarbij de klok van de samenvoegende telefoon achterloopt, en met twee echte Room-databases;
    - op de Fairphone: "Test" had je back-up (425 boeken), "Test 2" dezelfde bibliotheek met eigen uuid's en verschillen (30 boeken minder, 2 extra, lege eigenaren, nieuwere notities, 5 zonder ISBN, 3 met een ander ISBN);
    - het controlescherm toonde 395 paren, waarvan 3 twijfel;
    - na het samenvoegen 430 boeken op beide, identiek: lege velden aangevuld, nieuwere notities gewonnen, geen dubbele plekken of series, en persoonlijke gegevens bleven per telefoon.
- **S5 (3 november 2026):** achtergrond, thuisnetwerk en covers.
  - **Thuisnetwerk:** herkend zonder locatietoestemming (de wifi-naam vraagt die wel), aan de hand van gateway, subnet en domein, als korte hash. Een netwerk wordt thuis na een geslaagde uitwisseling of koppeling.
  - **Wanneer de app luistert:** tijdens het koppelen, of met een gekoppelde telefoon als de app in beeld is of (met de achtergronddienst) op een thuisnetwerk.
  - **Achtergronddienst:**
    - start bij "Code laten zien" en "Koppelen", en bij het openen van de app (vanaf de achtergrond mag het niet);
    - na het opstarten van de telefoon en na een update van de app start hij weer (`SyncBootReceiver`).
  - **Na een wijziging:** als het 15 seconden stil is, worden de eigen wijzigingen klaargezet. Is er iets nieuws, dan gaan ze naar de gevonden telefoons.
  - **Daarnaast:** elke 15 minuten een ronde, en zodra een telefoon gevonden wordt.
  - **Covers:** aan het eind van elke uitwisseling vraagt elke kant de eigen foto's die bij een boek horen maar hier ontbreken. Alleen gewone bestandsnamen (`[A-Za-z0-9-].jpg`), hooguit 8 MB, eerst naar een tijdelijk bestand.
  - **Getest op de Fairphone:**
    - bij het koppelen kwamen de 6 eigen covers mee;
    - na een update van de app startten beide diensten vanzelf;
    - een gewijzigde eigenaar in "Test 2" stond 15 seconden later in "Test", die op de achtergrond stond.
  - **Gevonden en opgelost:**
    - de dienst mocht niet starten toen de koppeling aan de achtergrondkant rond kwam (start nu bij het tonen van de code);
    - Android geeft een app op de achtergrond geen netwerkgegevens (het netwerk van het tonen van de code geldt);
    - het doorgeven werd afgebroken door de eigen wachtlogica.
- **S6 (3 november 2026, bezig):**
  - **Covers:** een cover die via synchroniseren vervangen wordt, ruimt nu het oude bestand op (als geen ander boek het gebruikt).
  - **Testversie 1.4-test1** voor je zus, als pre-release op GitHub. Het is de staging-build (`nl.wdhertog.library.staging`, "Bibliotheek test"), naast de gewone app, op twee telefoons en met een testinstructie in de release.
  - **Batterijbesparing:** zolang de app niet is uitgezonderd, staat onder "Op de achtergrond" een uitleg met de knop **Niet beperken**. Die opent de systeemvraag. Sommige telefoons (Samsung, Xiaomi) leggen apps anders toch stil.
  - **Eerste test door je zus (4 november):** koppelen, samenvoegen (4 boeken, 1 dubbel) en wijzigingen werkten; daarna stonden beide bibliotheken gelijk. Gevonden:
    - **Dubbel opzoeken:** boeken die via synchroniseren binnenkwamen (status "nog op te zoeken"), gingen op de andere telefoon óók in de opzoekrij. Die rij werd daar niet gestart, dus de teller bleef staan tot vernieuwen. Nu zoekt alleen de telefoon die de status als laatste zette een boek op; zo staat het in de veldklok van `enrichStatus`. Die boeken tellen op de andere telefoon ook niet mee.
    - **Vertraging tijdens importeren:** doorgeven wachtte tot het 15 seconden stil was, en dat werd het tijdens importeren en opzoeken nooit. Nu na 5 seconden stilte, en bij aanhoudende wijzigingen minstens elke minuut.
    - **Eigenaar wijzigen** duurde 10–20 seconden; nu ongeveer 5.
  - **Uitgebracht in 1.4 (4 november 2026):** `BuildConfig.SYNC` staat ook in de gewone app aan (de vlag blijft als noodrem). De waarschuwing "Testversie" staat alleen nog in testversies. Test2 is door je zus nog niet nagelopen; dit is uitgebracht op jouw verzoek.

## 12. Plan na 1.6: zeven ideeën

Stand: 5 november 2026, op basis van `main` (99c3c8a, versie 1.6, database versie 6).

### Overzicht

| # | Idee | Omvang | Database | Sync | Hangt af van |
|---|------|--------|----------|------|--------------|
| 1 | JSON-export bij de back-up | S–M | nee | nee | — |
| 2 | Auteurs samenvoegen | M | nee (v1) | via werk → gaat vanzelf mee | — |
| 3 | Hoofdeditie aanwijzen | M | ja: `work.primaryBookUuid` | nieuw veld `primaryBook` op werk | — |
| 4 | Goodreads-import | M–L | ja: `book.externalIds` (of `goodreadsId`) | veld erbij | — |
| 5 | HIRT-import verwijderen | S | nee (kolom blijft) | nee | 4 (helpers hergebruiken) |
| 6 | Leesstreaks | S (v1) / M (v2) | v2: tabel `reading_log` | nee (persoonlijk) | — |
| 7 | Badges | M | nee | nee (persoonlijk) | 6 (streak-badges) |

**Voorgestelde volgorde, elk een eigen PR:**
1. JSON-export — los, klein, goede opwarmer.
2. Auteurs samenvoegen — patroon ligt er al (`SeriesRepository.mergeInto`).
3. Hoofdeditie — met de eerste schema-wijziging (versie 7).
4. Goodreads-import — dezelfde versie 7 als het kan (één `AutoMigration(6 → 7)`).
5. HIRT verwijderen — pas als Goodreads de gedeelde stukken heeft overgenomen.
6. Streaks, daarna 7. Badges — samen het "Lezen"-pakket voor 1.8.

Vanavond haalbaar: 1 en 2, eventueel 3 beginnen.

### 1. JSON-export bij de back-up

**Gebouwd (6 november 2026, voor 1.9):** `JsonExport` + knop "Exporteren als JSON" op het Back-up-scherm. Besloten: alleen als losse knop, niet in de ZIP.

**Doel:** een open, leesbare export van alles, naast de ZIP (die alleen de SQLite-database bevat) en de CSV (die alleen boeken plat slaat).

**Wat erin:**
- gedeeld: boeken, werken, auteurs, series (met `manualParts`), locaties (boom via `parent`-uuid), personen, uitleningen;
- persoonlijk: leessessies, leesdoelen, favoriet en "wil ik lezen";
- verwijzingen als uuid's, niet als lokale id's.

**Hergebruik:** `RoomSyncStore` zet gedeelde records al om naar veld-maps met uuid-verwijzingen (zie `SyncSchema.fields`). De export kan daar grotendeels op leunen; alleen sessies, doelen en auteur-info komen er los bij.

**Vorm:**
```json
{
  "app": "nl.wdhertog.library", "format": 1, "schemaVersion": 6,
  "exportedAt": "2026-11-05T20:00:00Z",
  "books": [...], "works": [...], "authors": [...], "series": [...],
  "locations": [...], "persons": [...], "loans": [...],
  "readingSessions": [...], "readingGoals": {"2026": 16}
}
```

**Waar:**
- `data/backup/JsonExport.kt` (naast `CsvExport.kt`), puur, met een unit-test zoals `BackupFormatTest`;
- Back-up-scherm: knop "Exporteren als JSON" naast CSV;
- **Keuze:** ook `export.json` in de ZIP stoppen? Kan veilig: `BackupFormat.isKnownEntry` negeert onbekende namen bij terugzetten. Kost wel ruimte in elke wekelijkse back-up.

**Bewust niet:** terugzetten uit JSON. Daarvoor blijft de ZIP; JSON is voor jezelf, scripts en andere apps.

**Open vraag:** alleen als losse knop, of ook automatisch in de (wekelijkse) ZIP?

### 2. Auteurs samenvoegen

**Gebouwd (6 november 2026):** hernoemen en samenvoegen op de auteurspagina, suggesties "Mogelijk dezelfde auteur" in de lijst (`AuthorDuplicates`). Koppelingen worden opnieuw neergezet (verwijderen + invoegen) zodat de synctriggers het werk meesturen; er is geen trigger op UPDATE van `work_author`.

**Doel:** "J.R.R. Tolkien", "J. R. R. Tolkien" en "Tolkien, J.R.R." worden één auteur.

**Hoe (naar het voorbeeld van `SeriesRepository.mergeInto`):**
1. `AuthorRepository.merge(authorId, intoId)` in één transactie:
   - `work_author` van `authorId` omzetten naar `intoId`; staat `intoId` al bij dat werk (co-auteur-dubbel), dan de regel weghalen in plaats van omzetten (primaire sleutel `workId, authorId`);
   - Wikidata-gegevens: die van `into` houden, lege velden aanvullen uit de ander;
   - oude auteur verwijderen;
   - bij alle geraakte boeken `"authors"` in `lockedFields` zetten (anders zet verrijking de oude spelling terug — `BookRepository.kt:261`) en `refreshSearchText`.
2. `AuthorRepository.rename(id, name)`: bestaat de naam al (unieke index op `author.name`), dan samenvoegen — net als bij series.
3. **UI:** auteurspagina → ⋮ → "Naam wijzigen…" en "Samenvoegen met…" (zoeklijst van auteurs).
4. **Suggesties (optioneel, tweede stap):** `AuthorDuplicates` zoals `SeriesDuplicates`: kaartje "Mogelijk dezelfde auteur" in de lijst Auteurs. Vergelijken op genormaliseerde naam: punten en spaties tussen initialen weg, "Achternaam, Voornaam" omdraaien, accenten weg (`TextNormalizer`). Met Samenvoegen / Negeren.

**Sync:** auteurs hebben geen eigen uuid; ze reizen als namenlijst `authors` op het werk. De triggers op `work_author` (`SyncTriggers.kt:30-31`) markeren het werk als gewijzigd, dus de andere telefoon krijgt de nieuwe naam vanzelf. Daar blijft de oude auteur zonder werken achter; die verschijnt niet in de lijst (`observeSummaries` joint op `work_author`). Opruimen van wezen kan bij dezelfde gelegenheid (`deleteOrphanAuthors`).

**Later, als het nodig blijkt:** tabel `author_alias (name → authorId)` zodat nieuw gescande boeken met de oude spelling meteen bij de juiste auteur komen. Eerst kijken of het slot op `authors` genoeg is.

**Tests:** `AuthorRepositoryTest` uitbreiden: samenvoegen met gedeeld werk, hernoemen naar bestaande naam, slot gezet.

### 3. Een editie aanwijzen als hoofdeditie

**Gebouwd (6 november 2026, database versie 8):** `work.primaryBookUuid`, sync-veld `primaryBook`; `Editions.ordered/primaryOf`. Besloten: een zelf gekozen hoofdeditie bepaalt ook de titel in series en op de auteurspagina (alleen weergave). Nog niet: hoofdeditie bovenaan bij de filter "Meerdere edities".

**Doel:** van een werk met meerdere edities bepalen welke "de" editie is: die cover en titel tonen in series, op de auteurspagina en in de lijst bij "Meerdere edities".

**Nu:** overal `books.first()` — `SeriesModels.kt:99`, `SeriesScreens.kt:290`, de volgorde op `AuthorScreens.kt`. Dat is willekeurig.

**Datamodel:** `Work.primaryBookUuid: String?` (uuid, geen id/FK: geen kringverwijzing tussen `book` en `work`, en sync werkt al met uuid's). Database versie 7, `AutoMigration(6 → 7)`.

**Sync:** veld `primaryBook` toevoegen aan `SyncSchema.fields[WORK]` en aan `RoomSyncStore`. Gedeeld, want het hoort bij de collectie.

**Logica:** één functie `primaryOf(work, books)`:
1. de aangewezen editie, als die nog bij het werk hoort;
2. anders: in bezit > niet in bezit, dan met eigen cover, dan taal van de app (nl), dan oudst toegevoegd.
Zo is er ook zonder keuze een betere standaard dan `first()`.

**UI:**
- boekpagina, kaart met andere edities → "Als hoofdeditie instellen" (en ster/markering bij de huidige);
- serie-scherm en auteurspagina gebruiken `primaryOf` voor cover, titel en volgorde (hoofdeditie bovenaan);
- in de lijst bij "Meerdere edities" staat de hoofdeditie bovenaan de groep.

**Randgevallen:**
- hoofdeditie verwijderd, losgekoppeld of naar ander werk → valt terug op regel 2 (uuid wijst nergens heen; bij losmaken het veld leegmaken);
- werken samenvoegen ("Koppelen aan andere editie…"): keuze van het overblijvende werk houden;
- omnibus: geldt per werk, niets bijzonders.

**Open vraag:** moet de hoofdeditie ook de **werktitel** bepalen (bijv. "In de ban van de ring" in plaats van "The Lord of the Rings")? Voorstel: ja als optie, maar `Work.title` niet overschrijven — alleen weergave.

### 4. Goodreads-import

**Gebouwd (6 november 2026):** `GoodreadsParser`, automatisch herkend in de CSV-route, eigen kaart op het importscherm. Besloten: `book.externalIds` (gesynchroniseerd). In bezit: heeft niemand iets als in bezit gemarkeerd, dan alles; anders alleen de gemarkeerde, de rest als "niet (meer) in bezit" (leesgeschiedenis blijft). Gebouwd op de bekende kolommen, nog niet met een echte export getest.

**Doel:** de CSV-export van Goodreads ("Export Library") rechtstreeks inlezen, met leesgeschiedenis, net zo vast als nu Have I Read That.

**Formaat (eerst controleren met een eigen export):** kolommen o.a. `Book Id`, `Title`, `Author`, `Author l-f`, `Additional Authors`, `ISBN`, `ISBN13`, `My Rating`, `Publisher`, `Binding`, `Number of Pages`, `Year Published`, `Original Publication Year`, `Date Read`, `Date Added`, `Bookshelves`, `Exclusive Shelf`, `My Review`, `Private Notes`, `Read Count`, `Owned Copies`.

| Goodreads | Wordt | Opmerking |
|-----------|-------|-----------|
| `Book Id` | extern id | voor opnieuw importeren zonder dubbelen |
| `Title` | titel + serie/deel | zelfde patroon als HIRT: `"Duin (Duin, #1)"` |
| `Author`, `Additional Authors` | auteurs | `Author l-f` niet nodig |
| `ISBN13` / `ISBN` | isbn13 | staan als `="9781234567890"` → `="` en `"` strippen, door `Isbn` halen |
| `Binding` | formaat | Paperback/Hardcover/Kindle Edition/Audible Audio… → e-book/luisterboek |
| `Exclusive Shelf` | status | `read` → sessie gelezen; `currently-reading` → open sessie; `to-read` → wil ik lezen |
| `Date Read`, `Read Count` | sessies | alleen de laatste leesdatum bekend; `Read Count` > 1 → extra sessies zonder datum |
| `My Rating` | beoordeling | 0 = geen beoordeling |
| `My Review`, `Private Notes` | recensie, notities | |
| `Bookshelves` | tags | `read`, `to-read`, `currently-reading`, `owned` niet als tag |
| `Owned Copies` / plank `owned` | in bezit | vaak 0 terwijl je het wél hebt → zelfde schakelaar als bij HIRT: "Ook boeken die je niet (meer) hebt" |
| `Original Publication Year` | werk: eerste jaar | |
| `Date Added` | toegevoegd | |

**Code:**
- `importer/GoodreadsParser.kt`: leest met de bestaande `CsvReader`, geeft `List<ImportedBook>`; hergebruikt `ImportService`, `ImportMatcher` (controle vooraf) en ongedaan maken;
- gedeelde stukken uit `HirtParser` (serie-in-titel-regex, namen splitsen, datumprecisie) naar `importer/ImportText.kt`;
- herkennen: een CSV met kopregel `Book Id,Title,Author,…` → automatisch Goodreads, anders de gewone kolom-wizard;
- tests met een kleine voorbeeld-CSV in `app/src/test/resources/import/`.

**Datamodel — keuze:**
- (a) kolom `goodreadsId` naast `hirtId` — snel, maar bij elke bron een kolom erbij;
- (b) **voorstel:** `externalIds` (tekst, bijv. `goodreads:12345,hirt:678`) en `ImportMatcher` daarop laten zoeken. Dan kan `hirtId` later daarin opgaan (zie 5). Sync: veld toevoegen aan `SyncSchema.fields[BOOK]`.

**Open vraag:** wil je Goodreads-leesdoelen? Die staan niet in de export, dus nee — tenzij handmatig.

### 5. HIRT-import verwijderen

**Gedaan (6 november 2026, voor 1.9):** `HirtParser` en de kaart op het importscherm zijn weg; `ImportText` heeft de gedeelde stukken. `hirtId` en `MatchReason.HIRT_ID` blijven.

**Aanname:** de Have I Read That-import heeft zijn werk gedaan (eenmalige overstap) en mag uit de app. (Bedoel je juist "een gedane HIRT-import terugdraaien": dat kan al, elke import is ongedaan te maken.)

**Weg:**
- `importer/HirtParser.kt` en `HirtParserTest.kt` (na het verhuizen van de helpers, zie 4);
- in `ImportScreen.kt` / `ImportViewModel.kt`: kaart "Have I Read That", `openHirt`, `hirtIncludeAll`;
- `MoreScreen.kt:61`: ondertitel wordt "Goodreads, Excel of CSV";
- README en `ONTWERP.md` §6.11 als "historisch" markeren.

**Blijft (bewust):**
- kolom `book.hirtId` en `MatchReason.HIRT_ID`: bestaande boeken hebben die waarde, en het veld `hirtId` zit in het sync-protocol (`SyncSchema`, `RoomSyncStore`). Een telefoon met 1.6 stuurt het nog mee. Een kolom laten vallen vraagt een handmatige migratie zonder winst.
- Met keuze (b) bij 4: in migratie 6 → 7 `hirtId` kopiëren naar `externalIds` als `hirt:<id>`, en het sync-veld pas weghalen als beide telefoons over zijn.
- Teksten als "bijv. uit de Have I Read That-import" in commentaar mogen blijven.

### 6. Leesstreaks

**Gebouwd (v1, 5 november 2026):** `data/repository/ReadingStreaks.kt` (`Streaks.of`, `monthsReachedOn`), maanden en weken op rij; regel in de leesdoelkaart op Lezen (alleen bij het huidige jaar). De lopende reeks breekt pas als een hele maand voorbij is zonder uitgelezen boek. v2 (dagstreak) gebouwd op 5 november 2026, zie hieronder.

**Probleem:** de app weet per sessie wanneer je begon en klaar was, en de *laatste* voortgang, maar niet op welke dagen je las. Een dagstreak kan dus niet met de huidige gegevens.

**v1 — zonder schemawijziging (voorstel om mee te beginnen):**
- **maanden op rij** met minstens één uitgelezen boek (en de langste ooit);
- **weken op rij** idem;
- alleen sessies met `datePrecision = DAY` tellen; geen gat-vulling voor sessies van alleen het jaar;
- werkt meteen met je hele geïmporteerde geschiedenis.
- Puur rekenwerk in `ReadingStats` (of `ReadingStreaks.kt`), met tests zoals `ReadingStatsTest`.
- Op Lezen: klein regeltje onder de leesdoelring, bijv. "🔥 7 maanden op rij · langste 11".

**v2 — echte dagstreak:**
- tabel `reading_log (date, bookId, page?, percent?)`, één regel per dag per boek; geschreven bij "Voortgang bijwerken", beginnen en uitgelezen;
- persoonlijk, niet gesynchroniseerd (zoals leessessies);
- optioneel: "Vandaag gelezen" als snelle knop in de widget;
- streak = aaneengesloten dagen met een log; één dag "vrij" per week als instelling (anders demotiverend).

**v2 gebouwd (5 november 2026, database versie 7):** tabel `reading_log (date, bookId)`, geschreven bij "Voortgang bijwerken"; begin- en einddatums van sessies tellen ook als leesdag (`Streaks.readingDays`). Eén vrije dag per week (ma–zo), vast, geen instelling. Vandaag nog niet gelezen breekt niets. Badges 7/30/100 dagen op rij. Geen widgetknop (besloten: eerst kijken of voortgang invullen genoeg is).

**Open vraag (beantwoord):** is een dagstreak echt gewenst (dan moet je elke dag iets invullen), of is "maanden op rij" genoeg?

### 7. Badges

**Gebouwd (5 november 2026):** `data/repository/Achievements.kt` met de hele eerste set hieronder (serie: minstens 3 delen); kaart "Badges" op Lezen (laatste drie) en scherm `BadgesScreen` (behaald met datum, nog te halen met voortgang). Material Icons in een gouden cirkel. Versie 2 (6 november 2026): melding bij "Uitgelezen" als je een badge haalt, badges in het jaaroverzicht, tikken op een badge toont wanneer en waarmee, voortgang bij "Doel gehaald" (dit jaar), en Vlotte lezer, Trouwe lezer, Uitlener en Verzamelaar (`BadgeService`).

**Doel:** mijlpalen zichtbaar maken op Lezen, afgeleid uit bestaande gegevens — niets opslaan.

**Opzet:**
- `data/repository/Achievements.kt`: lijst van definities, elk `(id, titel, uitleg, check(sessions, books) → behaald op datum?)`. Puur, goed te testen.
- Behaald-op-datum wordt berekend (bijv. de sessie waarmee je de 100 haalde), dus ook terug in de tijd en na een import of terugzetten meteen goed.
- Persoonlijk (op basis van eigen leessessies); niet gesynchroniseerd.

**Eerste set (ideeën):**
- aantallen: 10 / 50 / 100 / 250 / 500 boeken gelezen;
- pagina's: 10.000 / 50.000 in totaal; een boek van 1.000+ pagina's;
- leesdoel gehaald (per jaar, telt mee als "×3");
- talen: in 3 / 5 talen gelezen (`book.language`);
- series: een serie helemaal uit (alle delen gelezen, met `SeriesModels`);
- auteur: 10 boeken van dezelfde auteur;
- herlezer: een boek 3× gelezen;
- streaks (na 6): 6 / 12 maanden op rij.

**UI:**
- Lezen → kaart "Badges" met de behaalde (laatste drie) en "Alles", naar een scherm met behaald (datum) en nog niet behaald (grijs, met voortgang "73 van 100");
- eventueel een korte melding (snackbar) als je net een badge haalt bij "Uitgelezen";
- jaaroverzicht: badges die dat jaar behaald zijn erbij.

**Open vraag:** pictogrammen zelf tekenen (vector, in het groen-goud palet) of Material Icons? Voorstel: Material Icons in een gouden cirkel, past bij `Gold`.

### Te beslissen vóór het bouwen

1. JSON-export: ook automatisch in de ZIP?
2. Hoofdeditie: ook de werktitel bepalen?
3. Goodreads: `externalIds` (voorstel) of losse `goodreadsId`?
4. HIRT: alleen de import weg, kolom en sync-veld laten staan (voorstel)?
5. Streaks: v1 (maanden/weken) volstaat, of meteen de dagstreak?
6. Badges: welke uit de lijst wel/niet?
