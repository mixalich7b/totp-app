# Инструкции для агентов

Этот файл содержит обязательный контекст для автоматизированных агентов, изменяющих проект. Пользовательская документация находится в `README.md` и `docs/`.

## Границы проекта

- Проект состоит из Android companion app и Garmin Connect IQ watch app/glance с названием `TOTP mixalich7b`.
- Распространение только через sideload. Не добавлять store publishing, сервер, аккаунты, аналитику или crash-reporting без явного запроса.
- Android — единственный источник истины для списка секретов. Часы могут вычислять коды и выбирать локальный favorite, но не редактировать записи.
- Не добавлять дополнительное прикладное шифрование Garmin storage или транспорта и не добавлять отдельный pairing: это сознательно исключено пользователем.
- Не добавлять биометрическое подтверждение или PIN приложения. Android Keystore должен работать без пользовательского подтверждения.
- Не выполнять `git add`, `git commit` и другие изменяющие git-операции: ими управляет пользователь.
- Не добавлять в репозиторий signing keys, keystores, реальные TOTP secrets, локальные БД, screenshots с кодами или собранные артефакты.

## Неизменяемые идентификаторы и версии протокола

- Android `applicationId`/namespace: `net.mixalich7b.totp`.
- Garmin UUID: `fa0bbecf-1e62-477b-b9cf-740aca2a4b32`; manifest использует форму без дефисов.
- Android Keystore alias: `net.mixalich7b.totp.entries.v1`.
- Storage AAD v1: UTF-8 строка `id|schema|revision`.
- Sync protocol: v1, точная схема в `protocol/schema.md`.
- Target Garmin profile: `fenix8pro47mm`, min Connect IQ API 6.0.0.

Не менять UUID, alias, AAD, storage schema или wire format без явного решения
владельца. Переход Garmin storage после `0.1.0` — согласованное исключение: migration
не реализуется, требуется чистая переустановка. При несовместимом изменении wire
format увеличить protocol version и одновременно обновить обе платформы и документацию.

## Структура и toolchain

- `android/`: Kotlin, platform `Activity`/`ListView`/dialogs, `SQLiteOpenHelper`.
- `garmin/`: Monkey C watch app и glance в одном package.
- `protocol/schema.md`: канонический wire format.
- `docs/BUILDING.md`: пользовательские команды сборки и sideload.
- Android: AGP 9.2.1, Gradle 9.6.0, compile/target SDK 37, min SDK 28.
- Garmin: Connect IQ SDK 9.2.0, Companion SDK 2.4.0.

UI и persistence намеренно используют стандартные platform-компоненты. Не вводить Compose, Room, KSP/kapt или новый DI/framework без необходимости и явного обоснования.

## Инварианты Android security

- В SQLite допустимы только `id`, revision, schema version, 12-byte IV и ciphertext с GCM tag.
- `displayName`, issuer, account и secret должны находиться внутри зашифрованного payload.
- Каждая запись шифруется AES-256-GCM с новым IV, который генерирует Android Keystore provider при `Cipher.init(ENCRYPT_MODE, key)` и возвращает через `cipher.iv`. Не передавать caller-provided IV при шифровании: ключ использует `setRandomizedEncryptionRequired(true)`.
- Keystore key неэкспортируемый, `setUserAuthenticationRequired(false)`.
- Сначала можно запросить StrongBox, затем корректно fallback на обычный Android Keystore.
- `setUnlockedDeviceRequired(true)` применять только с API 35+.
- Если БД содержит записи, а key отсутствует/невалиден, не создавать новый key молча. Данные невосстановимы; требуется явный reset flow.
- Reset локального Android-хранилища допустим только после явного подтверждения пользователя. Перед `SecureEntryRepository.resetLocalStorage()` закрыть текущий repository; сброс удаляет Android БД и Keystore key, но не данные на часах.
- Backup/data extraction остаются отключёнными, чувствительная Activity сохраняет `FLAG_SECURE`, release остаётся `debuggable=false`.
- Не помещать secrets, QR payload, decrypted records или sync messages в logs, clipboard, exceptions, saved state, analytics и crash reports.
- Временные `ByteArray` очищать там, где это практически возможно.

QR импорт:

- Одиночный формат: только `otpauth://totp`.
- Google Authenticator migration поддерживает payload versions 1/2 и multi-batch по `batchId`, `batchIndex`, `batchSize`; неизвестные protobuf-поля пропускаются по wire type.
- HOTP, SHA-512 и MD5 отклоняются без преобразования. Поддерживаются SHA-1/SHA-256, 6/8 digits, period 5–300.
- Ошибка отдельной migration-записи не должна отменять корректные записи из той же
  пачки. В отчёте допустимы только безопасные категории и количества, без имён и
  содержимого записей.
- Декодированный secret должен содержать 1–1024 байта. Не увеличивать предел без
  одновременной оценки Android encrypted codec, Garmin storage/mailbox и памяти.
- Google Code Scanner зависит от Google Play services и не требует CAMERA permission. Не добавлять permission без смены scanner implementation.
- Дубликат определяется по NFC-нормализованным case-insensitive issuer/account,
  secret bytes, algorithm, digits и period. Display name не входит в ключ.
- При `REPLACE` сохранять ID и createdAt существующей записи, чтобы синхронизация
  не сбрасывала favorite на часах. Вся выбранная пачка изменяет revision один раз.
- Не оставлять импортированные secrets в Activity после импорта, отмены или destroy;
  `pendingImportEntries` должен очищаться с занулением byte arrays.

## Инварианты Garmin

- `Application.Storage` содержит plaintext — это принятое ограничение. Не создавать видимость защищённого хранения.
- Snapshot принимается только через staging. До переключения active storage проверить protocol version, transfer ID, последовательность chunks, count, revision и SHA-256 snapshot hash.
- Active snapshot хранит entries/revision/last transfer в одном dictionary под
  `active_snapshot` и переключается одним `Storage.setValue`; staging целиком хранится
  под `staging_snapshot`. Не возвращать раздельные metadata keys.
- Staging очищается после terminal error, успешного commit, нового валидного BEGIN
  и команды очистки. Receiver принимает максимум 100 записей, имя до 128 символов и
  secret 1–1024 байта с элементами 0–255.
- Старые revision отклоняются; повторный commit последнего transfer должен быть идемпотентным.
- После commit сохранить favorite, если запись существует; иначе выбрать первую запись или очистить favorite.
- Команда очистки `d` удаляет active, staging, favorite, revision и last transfer,
  отвечает `z` только после удаления и не меняет Android repository. Это логическое
  удаление, не secure erase flash.
- Не логировать secrets, codes, checksum, имена записей и message dictionaries. Для диагностики транспорта допустимы только тип сообщения, transfer ID, revision, число записей, sequence, статусы и безопасные тексты ошибок.

Glance:

- Лимит профиля — 64 KiB. Код, нужный glance, отмечается `(:glance)` и должен оставаться минимальным.
- `AppBase.initialize()` исполняется в glance context: он не должен создавать `TotpView` или timer. Полноэкранный view создаётся только в `getInitialView()`.
- Phone messages обрабатывает background `ServiceDelegate`, зарегистрированный через `Background.registerForPhoneAppMessageEvent()`. Не возвращать foreground-only mailbox callback: синхронизация должна работать при закрытом watch app.
- Glance читает только favorite и вычисляет код при `onUpdate`. Garmin OS управляет частотой обновления; не обещать посекундное обновление glance.
- `TotpStore` и `TotpCore` используются одновременно из glance и background sync, поэтому классы должны сохранять multi-slice annotation `(:glance, :background)`. Одна `:glance` приводит на устройстве к `Class not available to 'Background'`.
- Односимвольные значения Garmin Mobile SDK могут приходить в Monkey C как динамический `String`, `Char` или `Symbol`. Типы сообщений нужно нормализовать через `syncMessageType`, а строки протокола, transfer ID и checksum сравнивать по значению через `.equals()`/`totpValuesEqual`; прямое сравнение ломает обмен ошибками `Unknown message`, `Wrong transfer` или `Checksum mismatch`.
- Текущая build statistics: glance code 5699 bytes/static data 2146 bytes;
  background code 5201 bytes/static data 2074 bytes. После изменения glance или
  background sync повторить build stats и аппаратную проверку heap.

TOTP:

- Garmin `HashBasedMessageAuthenticationCode` на целевом runtime принимает SHA-256, но отклоняет SHA-1.
- HMAC-SHA1 реализован через нативный `Cryptography.Hash(HASH_SHA1)` по ipad/opad-схеме. Не заменять его попыткой передать `HASH_SHA1` в native HMAC.
- Любое изменение TOTP core должно проходить RFC 6238 SHA-1 и SHA-256 tests на Android и Garmin Simulator.

## Sync protocol

- Android отправляет полный snapshot: begin, один chunk на запись, commit; часы отвечают ACK только после валидации и переключения active storage.
- Android показывает успех только после ACK совпадающей revision.
- Любой ACK/error принимается только при наличии точного active transfer ID;
  ответы без `x` больше не поддерживаются.
- Проект рассчитан на одни часы: Android не хранит отдельную target-watch привязку и
  использует первое подключённое Garmin-устройство. Очистка не должна требовать
  предварительной синхронизации; входящие ответы принимаются только от active device.
- Каждый асинхронный send callback и timeout должен проверять активный transfer ID.
  Иначе позднее событие отменённой/истёкшей передачи может повредить следующий retry.
- Отмена допустима только до отправки commit. На commit/ACK UI блокирует отмену,
  потому что активный snapshot на часах уже мог быть переключён.
- Checksum обнаруживает случайное повреждение, но не является MAC и не обеспечивает аутентичность.
- Не добавлять transport crypto, ECDH, SAS или application pairing.
- Garmin сообщения содержат secrets; запрещено печатать объект сообщения даже в debug build.

## Обязательная проверка изменений

Android:

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew lintRelease assembleRelease
```

`connectedDebugAndroidTest` очищает БД и Keystore key debug package. Запускать его
только на чистом эмуляторе/тестовом устройстве без реальных секретов; при конфликте
подписи не удалять пользовательское приложение автоматически.

Garmin — использовать `CONNECTIQ_HOME` и developer key вне репозитория:

```bash
cd garmin
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b.prg -w
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm -t \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b-tests.prg -w
"$CONNECTIQ_HOME/bin/monkeydo" bin/TOTP-mixalich7b-tests.prg fenix8pro47mm -t
```

У Garmin compiler допустимы текущие warnings о динамическом типе контейнеров wire protocol, но новые errors/crashes недопустимы. `monkeydo` может вернуть non-zero при итоговом `PASSED`; проверять текстовый итог tests.

После изменения lifecycle выполнить обычный Simulator smoke-test. После изменения sync, glance, storage или UI обязательна ручная проверка на реальных fēnix 8 Pro. Аппаратная regression-конфигурация подтверждена владельцем проекта 22 июня 2026 года на Android 16/Xiaomi 17 и fēnix 8 Pro 47 mm firmware 22.35, включая немедленную очистку часов; это исходная regression-конфигурация. Stable baseline — версия `0.1.0`, protocol v1, квалифицированная 22 июня 2026 года.

## Документация

- `README.md`, `docs/BUILDING.md`, `docs/SECURITY.md` и `docs/DEVELOPMENT.md` пишутся для людей: назначение, сборка, эксплуатационные ограничения и понятные архитектурные решения.
- Агентские guardrails, команды обязательной проверки и детали внутренних инвариантов поддерживать здесь.
- При изменении пользовательского поведения обновлять README; при изменении сборки — `docs/BUILDING.md`; модели угроз — `docs/SECURITY.md`; wire format — `protocol/schema.md`.

## Официальные справочные материалы

- [Garmin Connect IQ Glances](https://developer.garmin.com/connect-iq/core-topics/glances/)
- [Garmin Connect IQ Security](https://developer.garmin.com/connect-iq/core-topics/security/)
- [Garmin Connect IQ Mobile SDK for Android](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/)
- [Garmin Toybox.Cryptography](https://developer.garmin.com/connect-iq/api-docs/Toybox/Cryptography.html)
- [Garmin Toybox.Application.Storage](https://developer.garmin.com/connect-iq/api-docs/Toybox/Application/Storage.html)
- [RFC 4226 — HOTP](https://www.rfc-editor.org/rfc/rfc4226)
- [RFC 6238 — TOTP](https://www.rfc-editor.org/rfc/rfc6238)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner)
- [Android SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)
