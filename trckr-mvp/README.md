# trckr

MVP Android-трекер бігу: GPS + BLE пульсометр (Magene H64/H303 та будь-який нагрудний датчик зі стандартним Heart Rate Service).

## Встановлення
APK збирає GitHub Actions на кожен push: **Releases → `latest` → `trckr.apk`**. Debug-ключ закомічено (`app/debug.keystore`), тож нові збірки ставляться поверх старих без втрати даних.

## Як користуватись
1. Дай дозволи (геолокація, Bluetooth, сповіщення).
2. Одягни датчик, натисни «Пульсометр: вибрати…» і вибери пристрій (позначений ♥).
3. Зачекай, поки GPS покаже `±N м ✓`, натисни **СТАРТ**. Запис іде у foreground-сервісі — екран можна вимкнути.
4. **СТОП** → сесія зберігається в історії. Тап по сесії → «Експорт GPX» (у `Download/trckr`, з пульсом — імпортується в Strava/Garmin).

## Дані
`files/sessions/<yyyyMMdd_HHmmss>/`:
- `track.csv` — `t_ms,lat,lon,alt,acc,speed,hr,dist_m`, 1 рядок/сек, пишеться наживо (переживає краш);
- `summary.json` — підсумки;
- `track.gpx` — GPX 1.1 з `gpxtpx:hr`.
