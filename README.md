# AA Lyrics — Android Karaoke pro Android Auto

Aplikace v reálném čase rozpoznává hudbu přehrávanou jinou aplikací
(Spotify, YouTube Music, …), stahuje text písně z [LRCLIB](https://lrclib.net)
a zobrazuje jej synchronizovaně jako karaoke. **Plně funguje i v Android Auto**
— včetně vlastní obrazovky s víceřádkovým textem (viz níže).

---

## Build

```bash
# Jednorázově vygenerujte wrapper jar:
gradle wrapper --gradle-version 8.7

# Debug APK:
./gradlew :app:assembleDebug

# Unit testy:
./gradlew :app:testDebugUnitTest

# Instalace na zařízení:
./gradlew :app:installDebug
```

APK najdete v `app/build/outputs/apk/debug/`.

Po prvním spuštění aplikace **vyžaduje povolení čtení notifikací** — bez něj
nedokáže číst MediaSession Spotify / YT Music. Aplikace na obrazovku
*Nastavení* nabízí tlačítko, které otevře přímo systémové menu.

---

## Architektura

```
app/
├── data/
│   ├── local/          Room: CachedLyricsEntity, LyricsDao, AppDatabase
│   ├── remote/         Retrofit: LrcLibApi, LyricsOvhApi
│   └── repository/     LyricsRepository, MediaSessionRepository, SettingsRepository
├── domain/
│   ├── model/          Song, LyricsLine, SyncedLyrics, PlaybackState
│   ├── lrc/            LrcParser, LyricsSyncCalculator
│   ├── usecase/        FetchLyricsUseCase
│   └── LyricsController  ← centrální stavový holder (single source of truth)
├── presentation/
│   ├── MainActivity
│   ├── karaoke/        KaraokeScreen + ViewModel
│   ├── search/         ManualSearchScreen + ViewModel
│   └── settings/       SettingsScreen + ViewModel
├── service/
│   ├── MediaNotificationListener      NotificationListenerService → MEDIA_CONTENT_CONTROL
│   ├── KaraokeMediaService            Media3 MediaLibraryService → standardní AA media UI
│   └── car/
│       ├── LyricsCarAppService        Car App Library service (NEstandardní AA UI)
│       ├── LyricsCarSession
│       └── LyricsCarScreen            PaneTemplate / LongMessageTemplate s víceřádkovým textem
├── di/                 AppModule (Hilt), CarEntryPoint
└── ui/theme/           Material 3
```

### Tok dat

```
            ┌────────────────────┐
            │  Spotify / YT M.   │ (přehrává hudbu)
            └────────┬───────────┘
                     │ MediaSession metadata
                     ▼
   MediaNotificationListener  ──►  MediaSessionRepository  ──►  LyricsController
                                                                    │
                            ┌───────────────────────────────────────┤
                            ▼                                       ▼
                   LyricsRepository                         StateFlow<UiState>
              (LRCLIB → Lyrics.ovh → Room cache)                    │
                            │                  ┌─────────┬──────────┴───────────┐
                            ▼                  ▼         ▼                      ▼
                       SyncedLyrics       KaraokeScreen  KaraokeMediaService    LyricsCarScreen
                                          (Compose)      (MediaSession → AA)    (CarAppLib → AA)
```

Všechny tři "konzumenty" stavu (Compose UI, Media3 session, CarAppService)
sdílejí jednu instanci `LyricsController` přes Hilt — vždy zobrazují
stejný řádek textu.

---

## Android Auto — jak obejít omezení jednoho řádku

Standardní cesta pro media aplikace v Android Auto je
`MediaBrowserService` + `MediaSession` (v této aplikaci
`KaraokeMediaService`). Auto rendruje jeho metadata vlastním fixním layoutem
a zobrazí pouze jeden řádek subtitle/description — víc tam dostat nelze.

**Aplikace proto vedle media service registruje druhý "vchod" do auta:**
`LyricsCarAppService`, postavený na **Car App Library** (`androidx.car.app`).
Tato knihovna je druhý oficiálně podporovaný způsob doručení UI do
Android Auto a umožňuje deklarovat tzv. templates, mezi nimiž jsou
`PaneTemplate` a `LongMessageTemplate` schopné zobrazit větší blok textu.

V Auto pak uživatel uvidí dvě dlaždice:

* **Media** (řízeno `KaraokeMediaService`) — standardní přehrávač
  s tlačítky play/pauza/skip a jedním řádkem textu.
* **AA Lyrics** (řízeno `LyricsCarAppService`) — vlastní obrazovka s
  okolím aktuálního řádku (do 6 řádků zvýrazněných v `PaneTemplate`).
  Při velmi dlouhých textech přepneme na `LongMessageTemplate` který
  v Auto poskytne scrollovatelný blok textu.

`LyricsCarScreen` se přihlásí k `LyricsController.state` a při změně
aktivního řádku zavolá `invalidate()` — host Auto si template znovu
vyžádá. Refresh frekvenci nicméně limituje samo Auto kvůli rozptylování
řidiče; vizuální posuv tedy NENÍ frame-perfect, ale uživatel vidí
~3 sekundové okno před aktivním řádkem a několik za ním (`PaneTemplate`),
což je v karaoke kontextu naprosto použitelné. V parkujícím stavu Auto
dovolí i `LongMessageTemplate`, který zobrazí komplet text.

Manifest deklaruje obě služby:

```xml
<service android:name=".service.KaraokeMediaService" ...>
    <intent-filter>
        <action android:name="androidx.media3.session.MediaLibraryService"/>
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>

<service android:name=".service.car.LyricsCarAppService" ...>
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService"/>
        <category android:name="androidx.car.app.category.IOT"/>
    </intent-filter>
</service>
```

A `automotive_app_desc.xml` deklaruje obě "uses":

```xml
<automotiveApp>
    <uses name="media"/>
    <uses name="template"/>
</automotiveApp>
```

> **Pozn.:** Druhá služba zde běží v kategorii `IOT`, která je ze všech
> kategorií Car App Library nejméně restriktivní a nevyžaduje žádné
> speciální oprávnění od Google před publikací do Play. Pro produkci je
> možné požádat o `category.MEDIA` a/nebo zúžit `HostValidator` z
> `ALLOW_ALL_HOSTS_VALIDATOR` na vrácený šablonový validator.

---

## Použité knihovny

* AndroidX Media3 (ExoPlayer, MediaSession, MediaLibraryService)
* AndroidX Car App Library 1.4 (`androidx.car.app:app`, `app-projected`)
* Hilt 2.51
* Retrofit 2 + Moshi
* Room 2.6
* Jetpack Compose (BOM 2024.06)
* DataStore Preferences (offset + flag preferSynced)
* Coil Compose (album art)

---

## Datové zdroje textů

1. **LRCLIB** — `GET /api/get?artist_name=…&track_name=…&album_name=…&duration=…`
   primárně, plus `GET /api/search?q=…` jako fallback. Bez API klíče.
2. **Lyrics.ovh** — `GET /v1/{artist}/{title}` — fallback čistého textu.
3. **Room cache** — klíč `artist|title|duration_s` (case-insensitive).

Cache lze vyčistit v Nastavení.

---

## LRC parser

`cz.aalyrics.domain.lrc.LrcParser` rozeznává časové značky
`[mm:ss]`, `[mm:ss.xx]` i `[mm:ss.xxx]`, ignoruje metadata
(`[ti:]`, `[ar:]`, `[al:]`, `[by:]`, `[length:]`, …) a respektuje
globální `[offset:±N]`. Více timestampů na jednom řádku rozdělí na samostatné
záznamy. Pokud nenajde žádný platný timestamp, zachová text v plain-text
módu, který `LyricsSyncCalculator` skroluje proporcionálně k délce skladby.

Testy: `app/src/test/java/cz/aalyrics/domain/lrc/`.

---

## Edge cases

| Situace                                | Řešení                                          |
| -------------------------------------- | ----------------------------------------------- |
| Skladba není v LRCLIB                  | spadne na `/api/search`, pak Lyrics.ovh         |
| Není ani plain text                    | UI ukáže "Skladba nenalezena" + tlačítko ručního vyhledání |
| Pauza / seek / skip                    | MediaSession callback → okamžitý přepočet aktivního řádku |
| Změna skladby                          | Reset stavu, nový fetch                         |
| Velmi dlouhé texty                     | `LazyColumn` (mobil), `LongMessageTemplate` (Auto) |
| Offline                                | Servíruje se z Room cache                       |
| Neudělený NotificationListener         | UI v Nastavení zobrazí stav + tlačítko          |

---

## Manuální test scénář

1. Nainstalujte APK, povolte přístup k notifikacím.
2. Spusťte skladbu ve Spotify nebo YT Music.
3. Otevřete AA Lyrics → karta **Karaoke** — text se rozsvítí.
4. Pauza / přetočení v Spotify → karaoke se okamžitě synchronizuje.
5. Připojte Android Auto / DHU:
   * V media surface se objeví dlaždice "AA Lyrics" (přehrávač s 1 řádkem).
   * V "Apps" / "Other apps" se objeví druhá dlaždice "AA Lyrics" s
     víceřádkovým textem (PaneTemplate / LongMessageTemplate).
