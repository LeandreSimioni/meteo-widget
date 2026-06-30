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
  BLE pour l'Aranet4, résout la station Météo-France la plus proche, récupère
  la température extérieure, met à jour `Prefs`, la notification et le widget.
- `BleScanService` + `AranetDecoder` — scan BLE foreground, filtre sur le
  Manufacturer ID Aranet (`0x0702`), décode la trame manufacturer data.
- `MeteocielFetcher` — scrape `meteociel.fr/temps-reel/obs_villes.php` pour un
  `code2` (code de station) donné ; retourne le dernier relevé réel.
- `StationLocator` — référentiel d'environ 150 stations officielles
  Météo-France (réseau synop + auxiliaire, métropole + Corse, codes à 5
  chiffres) avec coordonnées ; `nearest(lat, lon)` calcule la plus proche par
  haversine. Le code stocké inclut le zéro initial (ex. `"07156"`) ;
  `MeteocielFetcher` le strip pour construire l'URL (`code2=7156`).
- `TemperatureWidgetProvider` — construit les `RemoteViews` du widget : temp.
  intérieure/extérieure, conseil ouvrir/fermer, et un petit indicateur 📱 de la
  température de la batterie du téléphone (pas un vrai capteur de température
  ambiante — Android n'en expose pas de fiable sur la plupart des téléphones).
- `Prefs` — `SharedPreferences` partagées entre Worker et widget (dernières
  valeurs connues, dernier état, dernière station retenue).
- `WorkScheduler` / `BootReceiver` — (re)programment le cycle périodique, y
  compris après redémarrage du téléphone.

## Géolocalisation de la station extérieure

`TemperatureCheckWorker.resolveStationCode()` utilise la dernière position
connue (`LocationManager`, permission `ACCESS_COARSE_LOCATION`) pour choisir la
station la plus proche via `StationLocator`. Si la position est indisponible,
réutilise la dernière station retenue (`Prefs.KEY_STATION_CODE`), sinon retombe
sur l'ancien code fixe historique (`MeteocielFetcher.FALLBACK_STATION_CODE =
"58304005"`).

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
- Station Météo-France géolocalisée : ajoutée, fusionnée sur `main` (remplace
  le code de station fixe `58304005` par une sélection automatique).
- Les deux fonctionnalités sont en production dans la release "latest".
  Reste à confirmer par l'utilisateur en conditions réelles : permission de
  localisation accordée, logs `[Worker] Position connue → station officielle
  Météo-France ...` visibles dans l'app après réinstallation.
