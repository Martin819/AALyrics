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

### Stažení APK z CI (na mobil)

Workflow `.github/workflows/build-apk.yml` po každém pushi do libovolné
větve (a po každém manuálním spuštění) vytvoří debug APK. K dispozici jsou
**dva způsoby stažení**:

1. **Rolling release `latest`** — workflow přesune tag `latest` na poslední
   úspěšný build. Stabilní URL pro mobil:
   ```
   https://github.com/<owner>/<repo>/releases/download/latest/aalyrics-debug-latest.apk
   ```
   Otevřete v mobilním prohlížeči, potvrďte instalaci z neznámého zdroje.
   Release také obsahuje SHA-otagovanou variantu pro dohledatelnost.

2. **Workflow artifact** — v záložce *Actions* běhu workflow je k dispozici
   `aalyrics-debug-<sha>` jako zip s APK uvnitř (retence 30 dní). Vhodné
   pro PR buildy, kde se rolling release neaktualizuje.

PR buildy se buildí a uploadují jen jako artifact (release se přesouvá
jen na opravdové pushe / `workflow_dispatch`), aby `latest` tag držel
mergnutý stav.

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
│   └── KaraokeMediaService            Media3 MediaLibraryService → AA browse-tree lyrics
├── di/                 AppModule (Hilt)
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
                       SyncedLyrics       KaraokeScreen     KaraokeMediaService
                                          (Compose, phone)  (notif. + AA browse tree)
```

Všechny tři "konzumenty" stavu (Compose UI, Media3 session, CarAppService)
sdílejí jednu instanci `LyricsController` přes Hilt — vždy zobrazují
stejný řádek textu.

---

## Android Auto — jak obejít omezení jednoho řádku

Pokoušeli jsme se použít Car App Library (`androidx.car.app`) s
`PaneTemplate` / `LongMessageTemplate` pro víceřádkový text. Sample od
Googlu funguje, ale **konsumer AA 16.x na Pixel 9 Pro + Android 16
sideloaded templated aplikace tiše filtruje** — náš package byl v AA
*Version and permissions* viditelný, ale v *Customize launcher* nikdy
nepřibyl, nezávisle na kategorii (POI, IOT, NAVIGATION), permissions
nebo verzi knihovny. To je gate na straně AA hosta, který bez Play
Store enrollmentu nepřekročíme.

**Místo toho používáme media surface a víceřádkový text doručujeme
přes browse tree:**

`KaraokeMediaService` je `MediaLibraryService` který v `onGetChildren`
vrátí **okno řádků textu** kolem aktuálního (8 položek, 2 nad aktivním
+ aktivní + 5 pod). Každý řádek je `MediaItem` s `isPlayable=true`,
aktivní řádek má prefix "▶ ". AA renderuje children root-u jako
scrollovatelný seznam, takže uživatel reálně vidí víc řádků naráz.

Při změně aktivního řádku zavoláme `session.notifyChildrenChanged(ROOT_ID, …)`
a AA si seznam znovu vyžádá. Současně přepíšeme metadata player-u na
aktivní řádek (`player.replaceMediaItem(0, …)` se stejnou URI — Media3
to optimalizuje na metadata-only update bez znovunabití zdroje), takže
i AA "Now Playing" karta drží aktuální řádek.

### Tap na položku nevolá "Could not load"

Náš ExoPlayer používá vlastní `SilenceOnlyMediaSourceFactory`, která
každý `MediaItem` resolvuje na `SilenceMediaSource` (60 s ticha v
loopu). Konfigurace `setAudioAttributes(AudioAttributes.DEFAULT,
handleAudioFocus = false)` zajišťuje, že náš player **nikdy
neukrade audio focus** od Spotify / YT Music — ten dál hraje hudbu,
my "hrajeme" ticho jen kvůli AA contract. Takže tap v AA proběhne
úspěšně (`STATE_PLAYING`), žádný *Could not load your selection*.

### Manifest

```xml
<service android:name=".service.KaraokeMediaService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaLibraryService"/>
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>
```

```xml
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

### Jak aplikaci v Auto poprvé najít

Android Auto schovává sideloaded aplikace. Aby se *AA Lyrics* v
*Customize launcher* objevila:

1. *Settings → Apps → Android Auto → Open settings → Version* (10× klik)
2. Vpravo nahoře 3 tečky → *Developer settings → Unknown sources* zapnout
3. Force-stop Android Auto, znovu otevřít → v *Customize launcher* se
   objeví "AA Lyrics".

---

## Použité knihovny

* AndroidX Media3 (ExoPlayer + `SilenceMediaSource`, MediaSession, MediaLibraryService)
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
