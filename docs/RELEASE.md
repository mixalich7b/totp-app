# Stable release 0.1.0

Стабильная sideload-версия квалифицирована 22 июня 2026 года. Wire protocol — `v1`,
схема — [protocol/schema.md](../protocol/schema.md).

## Android

- package: `net.mixalich7b.totp`;
- version: `versionCode 1`, `versionName 0.1.0`;
- min/target SDK: 28/37;
- release APK подписан RSA-4096 ключом проекта и имеет `debuggable=false`;
- SHA-256 сертификата: `2f19079fb8cd4420d2dc7ae8674e4a2a0329c627fb86c99fba32cbee043b8102`;
- файл: `android/app/build/outputs/apk/release/app-release.apk`;
- SHA-256 файла: `d144964752c9d99ca5b779abe2bd17215e5cfbac8d95ca9aa4715c7f786e4bff`.

## Garmin

- UUID: `fa0bbecf-1e62-477b-b9cf-740aca2a4b32`;
- target: `fenix8pro47mm`, min Connect IQ API 6.0.0;
- Connect IQ SDK: 9.2.0;
- файл: `garmin/bin/TOTP-mixalich7b.prg`;
- SHA-256 файла: `f0c7fc4042d099a9a26dbcb6b47683a7f54121c37fddd6e0e0ec6d5766ce6d2a`.

APK/PRG являются локальными build artifacts и не хранятся в репозитории. Повторная
сборка может дать другой hash; перед распространением значения в этом файле нужно
обновить из окончательных артефактов. Команды сборки, проверки подписи и checksum
находятся в [BUILDING.md](BUILDING.md).

Полная stable-квалификация из
[IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md#84-подготовка-стабильной-sideload-версии)
подтверждена владельцем проекта. Основная аппаратная regression-конфигурация —
Xiaomi 17/Android 16 и fēnix 8 Pro 47 mm/firmware 22.35.
