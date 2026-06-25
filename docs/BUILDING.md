# Сборка, тесты и sideload

## Android

Требования:

- JDK 17 или новее;
- Android SDK Platform 37 и Build Tools;
- `adb` из Android Platform Tools;
- Google Play services для QR-сканера;
- Garmin Connect с подключёнными часами для синхронизации.

Gradle Wrapper использует Gradle 9.6.0. SHA-256 distribution и checksums зависимостей
закреплены в wrapper и `gradle/verification-metadata.xml`.

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

Unit-тесты, lint и debug APK:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Instrumentation-тесты:

```bash
./gradlew assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

`connectedDebugAndroidTest` удаляет БД и Android Keystore key debug-пакета. Запускайте
его только на чистом эмуляторе или тестовом устройстве без реальных секретов. При
конфликте подписи не удаляйте пользовательское приложение автоматически.

Если Gradle не может корректно установить APK или остаётся ждать устройство,
собранный instrumentation-набор можно запустить через ADB напрямую:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  net.mixalich7b.totp.test/androidx.test.runner.AndroidJUnitRunner
```

Debug APK предназначен для тестов. Для реальных секретов используйте подписанный
release APK.

### Release key

Один раз создайте keystore вне репозитория:

```bash
mkdir -p "$HOME/.android-keys"
keytool -genkeypair -v \
  -keystore "$HOME/.android-keys/totp-mixalich7b.jks" \
  -alias totp-mixalich7b -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.android-keys/totp-mixalich7b.jks"
```

Добавьте настройки в `~/.gradle/gradle.properties`:

```properties
TOTP_RELEASE_STORE_FILE=/Users/USER/.android-keys/totp-mixalich7b.jks
TOTP_RELEASE_STORE_PASSWORD=CHANGE_ME
TOTP_RELEASE_KEY_ALIAS=totp-mixalich7b
TOTP_RELEASE_KEY_PASSWORD=CHANGE_ME
```

Сборка, проверка подписи и установка:

```bash
./gradlew clean testDebugUnitTest lintRelease assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Если Build Tools имеют другую версию, измените путь к `apksigner`. Без всех четырёх
signing properties создаётся неподписанный `app-release-unsigned.apk`.

## Garmin Connect IQ

Требования:

- Connect IQ SDK 9.2.0;
- профиль `fenix8pro47mm`;
- Connect IQ Device Simulator для локального запуска.

```bash
export CONNECTIQ_HOME="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/connectiq-sdk-mac-9.2.0-2026-06-09-92a1605b2"
```

Если developer key ещё не создан:

```bash
openssl genrsa -out "$HOME/developer_key.pem" 4096
openssl pkcs8 -topk8 -inform PEM -outform DER \
  -in "$HOME/developer_key.pem" -out "$HOME/developer_key" -nocrypt
chmod 600 "$HOME/developer_key.pem" "$HOME/developer_key"
```

Не заменяйте ключ, которым собрано установленное приложение. Храните защищённую
резервную копию ключа вне репозитория.

Сборка PRG:

```bash
cd garmin
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b.prg -w
```

Сборка и запуск тестового PRG в уже запущенном симуляторе:

```bash
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm -t \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b-tests.prg -w
"$CONNECTIQ_HOME/bin/monkeydo" \
  bin/TOTP-mixalich7b-tests.prg fenix8pro47mm -t
```

Оценивайте итоговую строку тестов: `monkeydo` может вернуть non-zero при итоговом
`PASSED`. Если Simulator завис на стартовом экране, полностью перезапустите его.

Запуск обычного приложения:

```bash
"$CONNECTIQ_HOME/bin/monkeydo" bin/TOTP-mixalich7b.prg fenix8pro47mm
```

### Sideload на часы

1. Подключите часы USB-кабелем.
2. Скопируйте `garmin/bin/TOTP-mixalich7b.prg` в `GARMIN/APPS`.
3. Безопасно отключите часы.
4. Добавьте glance `TOTP mixalich7b` стандартным редактором glances.

Для совместимых обновлений сохраняйте developer key и UUID
`fa0bbecf-1e62-477b-b9cf-740aca2a4b32`.

## Проверка артефактов

```bash
shasum -a 256 \
  android/app/build/outputs/apk/release/app-release.apk \
  garmin/bin/TOTP-mixalich7b.prg
```

Параметры текущих stable-артефактов находятся в [RELEASE.md](RELEASE.md).

## Проверка на устройствах

1. Установите Garmin PRG и Android APK.
2. Добавьте тестовую запись вручную и через QR.
3. Проверьте sync при открытом и закрытом watch app.
4. Прервите передачу до commit и проверьте retry.
5. Сравните TOTP около границы периода с независимым authenticator.
6. Проверьте кнопки, жесты, favorite, glance и открытие приложения из glance.
7. Очистите часы и повторно синхронизируйте записи.
