# Stable release 0.1.1

Wire protocol — `v1`, схема — [protocol/schema.md](../protocol/schema.md).

## Android

- package: `net.mixalich7b.totp`;
- version: `versionCode 2`, `versionName 0.1.1`;
- min/target SDK: 28/37;
- release APK: `android/app/build/outputs/apk/release/app-release.apk`;
- SHA-256 APK: `fadef21cefaf69c09f1ada1ba10afc5760383fe0593a6586564f1821c8e96fa8`;
- SHA-256 signing certificate:
  `2f19079fb8cd4420d2dc7ae8674e4a2a0329c627fb86c99fba32cbee043b8102`.

## Garmin

- UUID: `fa0bbecf-1e62-477b-b9cf-740aca2a4b32`;
- target: `fenix8pro47mm`, min Connect IQ API 6.0.0;
- Connect IQ SDK: 9.2.0;
- PRG: `garmin/bin/TOTP-mixalich7b.prg`;
- SHA-256 PRG: `33d66a7dec9717e9e2e92e6190f13ad512a70d293c3ab4c10250e6358c094ea7`.

APK/PRG и signing keys не хранятся в репозитории. Повторная сборка может изменить
hash артефакта; перед распространением значения нужно сверить командами из
[BUILDING.md](BUILDING.md).
