# meteo-widget

Widget Android (écran d'accueil) qui affiche la température intérieure (capteur
Aranet4 en BLE) et extérieure (relevé réel d'une station Météo-France, pas une
prévision), avec un conseil "Ouvrir/Fermer les fenêtres" basé sur l'écart entre
les deux.

## Règle importante : toujours fusionner sur `main`

Le workflow `.github/workflows/build.yml` republie automatiquement la release
GitHub **"latest"** (`releases/latest/download/app-debug.apk`) à chaque push sur
`main` — c'est le lien que l'utilisateur réinstalle à chaque fois. Un push sur
une branche de feature ne met PAS à jour ce lien (l'APK reste seulement
disponible en artifact du run Actions, plus contraignant à récupérer).

**Donc : toute modification destinée à être testée par l'utilisateur doit être
fusionnée sur `main`** (PR + merge), pas seulement poussée sur une branche.
Vérifier ensuite que le build GitHub Actions déclenché sur `main` passe
(`status: completed`, `conclusion: success`) avant de dire à l'utilisateur que
c'est prêt.

## Architecture

- `TemperatureCheckWorker` — cycle périodique (WorkManager, 15 min) : scanne le
  BLE pour l'Aranet4, récupère la température extérieure du lieu sélectionné,
  met à jour `Prefs`, la notification et le widget.
- `BleScanService` + `AranetDecoder` — scan BLE foreground, filtre sur le
  Manufacturer ID Aranet (`0x0702`), décode la trame manufacturer data. La
  trame porte son propre âge (`ageSec`), utilisé pour dater la mesure.
- `WeatherLocation` — les lieux disponibles et leur source. `FRANCE` interroge
  Meteociel avec le code de station enregistré ; `CORDOVADO` interroge l'ARPA
  FVG. Le choix est dans `Prefs.KEY_LOCATION`.
- `MeteocielFetcher` — scrape `meteociel.fr/temps-reel/obs_villes.php` pour un
  `code2` donné. `parseLatest()` est pur et testé.
- `FvgFetcher` — XML officiel de l'ARPA FVG, un fichier par station :
  `dev.meteo.fvg.it/xml/stazioni/{SIGLA}.xml`. `parseObservation()` est pur et
  testé. Relevés horaires, publiés ~30 min après l'heure ronde, horodatés UTC.
- `Reading` — une température **et** la date à laquelle elle a été mesurée.
  Une mesure trop vieille (90 min dedans, 3 h dehors) n'alimente plus le
  conseil ; elle reste affichée mais grisée.
- `WindowAdvisor` — logique pure ouvrir/fermer, avec hystérésis (entrée à
  0,5 °C, sortie à 0,2 °C) pour éviter de resonner sur un écart qui oscille.
- `TemperatureWidgetProvider` — construit les `RemoteViews` du widget : temp.
  intérieure/extérieure, conseil ouvrir/fermer, âge de la mesure, et un petit
  indicateur 📱 de la température de la batterie du téléphone (pas un vrai
  capteur ambiant — Android n'en expose pas de fiable).
- `Prefs` — `SharedPreferences` partagées entre Worker et widget (dernières
  valeurs connues et leur date, dernier état, lieu, code de station).
- `WorkScheduler` / `BootReceiver` — (re)programment le cycle périodique, y
  compris après redémarrage du téléphone.

## Choix du lieu extérieur

Le bouton "Lieu" de `MainActivity` bascule entre `FRANCE` et `CORDOVADO`.

- **France** — station Meteociel, code modifiable dans le champ "Station"
  (`Prefs.KEY_STATION_CODE`, défaut `MeteocielFetcher.DEFAULT_STATION_CODE` =
  `7563`, Avignon). Le code stocké peut inclure un zéro initial (`"07156"`) ;
  `MeteocielFetcher` le strip pour l'URL (`code2=7156`).
- **Cordovado** — station ARPA FVG `D101` (Mure, commune de Sesto al Reghena),
  la plus proche à 6,2 km. Le champ "Station" est masqué : rien à régler.
  Attention, la sigle est obligatoire — `101.xml` est une autre station.
  Données © ARPA FVG - OSMER e GRN, CC BY-SA 3.0 IT.

Changer de lieu ou de code de station efface la température extérieure et
l'état ouvrir/fermer stockés : ils ne veulent plus rien dire pour la nouvelle
station.

## Tests

`gradle testDebugUnitTest` — tests JVM sur la logique pure (advisor, les deux
parseurs, décodeur Aranet, fraîcheur des mesures). `MeteocielFetcherTest`
tourne sur une vraie page enregistrée dans `app/src/test/resources/`. La CI les
lance avant de construire l'APK.

## Contraintes d'environnement de dev

Dans les sessions sandboxées (Claude Code on the web), l'egress réseau est
souvent restreint à un allowlist (GitHub, npm, pypi...) — `dl.google.com`
(plugin AGP) et `meteociel.fr` sont généralement bloqués, donc un build Gradle
local échoue avec une 403 du proxy. Dans ce cas, valider via `xmllint --noout`
pour les XML, relecture manuelle du Kotlin, et laisser le workflow GitHub
Actions (`gradle assembleDebug` sur runner GitHub, accès réseau complet) faire
foi sur la compilation réelle.

## État actuel (30 juin 2026)

- Indicateur 📱 température batterie : ajouté, fusionné sur `main`.
- Station Météo-France géolocalisée : ajoutée puis **retirée** (commit
  `9f6e06a`) — la sélection GPS tombait sur des stations sans relevé
  exploitable (ex. 07260). `StationLocator` n'existe plus ; le code de station
  est saisi à la main.

## Mise à jour (4 juillet 2026)

- L'utilisateur a déménagé de Varzy à Avignon. `DEFAULT_STATION_CODE` passe de
  `58304005` à `7563` (station Avignon sur meteociel.fr). Rappel : ce
  changement n'affecte que les installs qui n'ont jamais enregistré de code
  via le champ "Code de station" de `MainActivity` — un utilisateur qui a déjà
  sauvegardé une valeur dans `Prefs.KEY_STATION_CODE` doit la changer
  manuellement dans l'app (ou via `btnSaveStation`).

## Mise à jour (28 juillet 2026)

- **Cordovado** ajouté comme second lieu, via l'ARPA FVG. Séjours en Frioul :
  station Mure (`D101`), commune de Sesto al Reghena.
- **Scraper Meteociel** : la colonne de température est maintenant repérée par
  l'en-tête du tableau (avec repli par motif). Le correctif précédent
  (`b556a10`, détection par suffixe `°C`) est conservé comme repli — il
  échouait si le `°` était mal décodé, la page étant en ISO-8859-1.
- **Fraîcheur des mesures** : chaque relevé est daté de sa mesure, pas de sa
  récupération. Une valeur périmée n'alimente plus le conseil ouvrir/fermer.
  Avant, la dernière température intérieure connue était crue indéfiniment.
- **Hystérésis** sur le conseil, pour arrêter de resonner à chaque cycle quand
  l'écart oscille autour du seuil.
- **Premiers tests du repo** (32 tests JVM), lancés par la CI avant l'APK.
- Contrairement à ce qu'indique la section ci-dessus sur l'environnement de
  dev : dans cette session, `dl.google.com` et `meteociel.fr` étaient
  accessibles et un `gradle assembleDebug` local a fonctionné (SDK Android
  installé à la main). Vérifier au cas par cas plutôt que de le supposer.
