# Сборка и sideload

## Android

Требования:

- JDK 17 или новее (проверено с JDK 21);
- Android SDK Platform 37 и Build Tools 36+;
- `adb` из Android Platform Tools;
- для QR-сканера — устройство или эмулятор с Google Play services;
- для синхронизации — установленный Garmin Connect с подключёнными часами.

Gradle Wrapper сам загрузит Gradle 9.6.0 и зависимости. SHA-256 Gradle distribution
и checksums Maven-артефактов закреплены в wrapper и
`gradle/verification-metadata.xml`; неожиданная подмена зависимости останавливает
сборку. Android SDK можно указать переменной или локальным файлом, который игнорируется git:

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
# Альтернатива: printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

Проверка и debug APK:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Интеграционные тесты SQLite и Android Keystore на подключённом устройстве или
запущенном эмуляторе (тесты очищают только данные debug-приложения):

```bash
./gradlew connectedDebugAndroidTest
```

Тесты удаляют локальную БД и Keystore key пакета `net.mixalich7b.totp`, поэтому
их следует запускать на чистом эмуляторе или тестовом устройстве без реальных
секретов. При `INSTALL_FAILED_UPDATE_INCOMPATIBLE` используйте чистый эмулятор:
установленное приложение подписано другим ключом и не должно удаляться ради теста.

Если Gradle ожидает устройство после сборки test APK, тот же набор можно запустить
через ADB напрямую:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  net.mixalich7b.totp.test/androidx.test.runner.AndroidJUnitRunner
```

Debug APK предназначен для тестов: он `debuggable=true`. Для реальных секретов используйте подписанный release APK.

Один раз создать release keystore вне репозитория:

```bash
mkdir -p "$HOME/.android-keys"
keytool -genkeypair -v \
  -keystore "$HOME/.android-keys/totp-mixalich7b.jks" \
  -alias totp-mixalich7b -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.android-keys/totp-mixalich7b.jks"
```

Добавить секреты подписи в `~/.gradle/gradle.properties` (не в репозиторий):

```properties
TOTP_RELEASE_STORE_FILE=/Users/USER/.android-keys/totp-mixalich7b.jks
TOTP_RELEASE_STORE_PASSWORD=CHANGE_ME
TOTP_RELEASE_KEY_ALIAS=totp-mixalich7b
TOTP_RELEASE_KEY_PASSWORD=CHANGE_ME
```

Собрать, проверить подпись и установить release APK:

```bash
./gradlew clean testDebugUnitTest lintRelease assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose \
  app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Если Build Tools установлены в другой версии, замените `37.0.0` в пути. Без четырёх signing properties Gradle создаст неподписанный `app-release-unsigned.apk`, который напрямую не устанавливается.

## Garmin Connect IQ

Требования:

- Connect IQ SDK 9.2.0;
- профиль `fenix8pro47mm` в SDK Manager;
- Connect IQ Device Simulator для запуска на компьютере.

Задать путь к активному SDK:

```bash
export CONNECTIQ_HOME="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/connectiq-sdk-mac-9.2.0-2026-06-09-92a1605b2"
```

Если постоянного developer key ещё нет, один раз создать его вне репозитория:

```bash
openssl genrsa -out "$HOME/developer_key.pem" 4096
openssl pkcs8 -topk8 -inform PEM -outform DER \
  -in "$HOME/developer_key.pem" \
  -out "$HOME/developer_key" -nocrypt
chmod 600 "$HOME/developer_key.pem" "$HOME/developer_key"
```

Не перезаписывайте существующий `$HOME/developer_key`: другим ключом нельзя собрать
совместимое обновление уже установленного Garmin-приложения. Сделайте защищённые
резервные копии обоих signing keys и паролей отдельно от исходного кода.

Сборка PRG:

```bash
cd garmin
"$CONNECTIQ_HOME/bin/monkeyc" \
  -f monkey.jungle -d fenix8pro47mm \
  -y "$HOME/developer_key" \
  -o bin/TOTP-mixalich7b.prg -w
```

Сборка и запуск RFC unit-тестов в уже запущенном симуляторе:

```bash
"$CONNECTIQ_HOME/bin/monkeyc" \
  -f monkey.jungle -d fenix8pro47mm -t \
  -y "$HOME/developer_key" \
  -o bin/TOTP-mixalich7b-tests.prg -w
"$CONNECTIQ_HOME/bin/monkeydo" \
  bin/TOTP-mixalich7b-tests.prg fenix8pro47mm -t
```

Ориентируйтесь на итоговую строку тестов: `monkeydo` может вернуть exit code 1 даже
при `PASSED`. Если Simulator остаётся на синем треугольнике и не печатает результаты,
полностью завершите старый процесс Simulator, запустите `ConnectIQ.app` заново и
повторите команду. Для воспроизводимой проверки используйте постоянный developer key,
которым собирается основное Garmin-приложение.

Запуск в симуляторе: сначала запустите `ConnectIQ.app`, выберите fēnix 8 Pro 47 mm, затем:

```bash
"$CONNECTIQ_HOME/bin/monkeydo" bin/TOTP-mixalich7b.prg fenix8pro47mm
```

Sideload на часы:

Текущая версия `0.1.1` несовместима с внутренней Garmin storage schema версии
`0.1.0` и намеренно не содержит миграции. Перед установкой удалите прежнюю версию
watch app с часов вместе с её данными. После установки заново синхронизируйте все
записи с Android.

1. Подключить часы USB-кабелем и открыть файловое хранилище через MTP/поддерживаемый Garmin файловый клиент.
2. Скопировать `garmin/bin/TOTP-mixalich7b.prg` в каталог `GARMIN/APPS`.
3. Безопасно отключить часы. Приложение появится в списке приложений.
4. Добавить glance `TOTP mixalich7b` стандартным редактором списка glances на часах.

Developer key должен оставаться тем же при последующих обновлениях. UUID приложения также нельзя менять: `fa0bbecf-1e62-477b-b9cf-740aca2a4b32`.

## Проверка release-артефактов

Соберите обе части из одной ревизии исходного кода, затем сохраните protocol version
и SHA-256 рядом с описанием выпуска:

```bash
shasum -a 256 \
  android/app/build/outputs/apk/release/app-release.apk \
  garmin/bin/TOTP-mixalich7b.prg
```

Параметры текущей стабильной подписанной версии приведены в
[RELEASE.md](RELEASE.md). Собранные APK/PRG и signing keys в репозиторий не входят.

## Порядок первой проверки

1. Установить Garmin PRG, затем Android APK.
2. В Android добавить тестовую запись или импортировать QR.
3. Убедиться, что Garmin Connect видит часы как подключённые, и нажать `Синхр.`.
4. Открыть часовое приложение, сравнить код с исходным authenticator около начала и конца 30-секундного периода.
5. Выбрать запись кнопкой `START`, затем проверить glance и открытие приложения из glance.
