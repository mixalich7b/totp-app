# TOTP mixalich7b

Два sideload-приложения для хранения и использования TOTP без собственного сервера:

- Android-приложение управляет записями: ручное добавление, удаление, импорт одиночного `otpauth://totp` QR и пакетного экспорта Google Authenticator, синхронизация с часами;
- Garmin Connect IQ watch app хранит переданные записи, вычисляет коды офлайн, показывает вертикальный список и glance избранного кода.

Android является источником истины. Нажатие на запись в Android открывает подтверждение удаления. На часах `UP/DOWN` и вертикальные жесты меняют выбранную запись, `START` или `MENU` делают её избранной для glance.

Поддерживаются TOTP SHA-1/SHA-256, 6 или 8 цифр и период 5–300 секунд. HOTP, SHA-512 и MD5 отклоняются без преобразования.

## Быстрый старт

Полные требования, команды сборки и sideload находятся в [docs/BUILDING.md](docs/BUILDING.md).

Android:

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Garmin (путь к SDK и ключу нужно подставить свой):

```bash
cd garmin
"$CONNECTIQ_HOME/bin/monkeyc" \
  -f monkey.jungle -d fenix8pro47mm \
  -y "$HOME/.connectiq/developer_key.der" \
  -o bin/TOTP-mixalich7b.prg -w
```

## Безопасность

На Android чувствительная запись целиком шифруется AES-256-GCM. Неэкспортируемый ключ создаётся в Android Keystore без биометрического подтверждения; на Android 15+ ключ доступен только после разблокировки устройства после загрузки. Backup и screenshots отключены.

Если Keystore key потерян или зашифрованные данные повреждены, приложение не создаёт новый ключ поверх существующей БД. Оно предупреждает, что восстановление невозможно, и предлагает явно удалить локальные записи, после чего их можно импортировать и синхронизировать заново.

На Garmin секреты находятся в `Application.Storage` без дополнительного прикладного шифрования. Транспорт Garmin Mobile SDK также не имеет дополнительного прикладного шифрования или pairing. Это сознательные ограничения текущей sideload-версии; подробнее — [docs/SECURITY.md](docs/SECURITY.md).

Signing keys в репозиторий не входят и создаются владельцем проекта локально.
