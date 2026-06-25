# TOTP mixalich7b

Приложение 2FA для Garmin. Отображает TOTP коды, загруженные из companion приложения на Android.

- Android-приложение хранит и управляет записями, импортирует одиночные
  `otpauth://totp` QR и экспорт Google Authenticator, синхронизирует данные с часами;
- Garmin Connect IQ watch app хранит переданный snapshot, вычисляет коды офлайн,
  показывает вертикальный список и glance избранной записи.

Android является единственным источником истины для списка записей. На часах можно
листать коды кнопками или жестами и выбирать favorite для glance. Подтверждаемая
команда очистки удаляет данные только с часов; следующая синхронизация восстановит
их из Android.

Поддерживаются SHA-1 и SHA-256, 6 или 8 цифр и период 5–300 секунд. HOTP, SHA-512
и MD5 не поддерживаются. Один snapshot содержит не более 100 записей.

Android показывает preview QR-импорта, позволяет выбрать записи и обработать
дубликаты: пропустить, заменить или сохранить оба варианта. Синхронизация применяет
snapshot на часах атомарно и считается успешной только после подтверждения часов.

## Документация

- [Сборка, тесты и sideload](docs/BUILDING.md)
- [Архитектура и разработка](docs/DEVELOPMENT.md)
- [Модель безопасности](docs/SECURITY.md)
- [Текущая stable-версия и контрольные суммы](docs/RELEASE.md)
- [Sync protocol v1](protocol/schema.md)

## Быстрый старт

Android:

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Garmin:

```bash
cd garmin
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b.prg -w
```

Android шифрует содержательные поля записей AES-256-GCM с ключом Android Keystore.
На Garmin секреты находятся в `Application.Storage` без дополнительного
прикладного шифрования. Ограничения подробно описаны в
[docs/SECURITY.md](docs/SECURITY.md).
