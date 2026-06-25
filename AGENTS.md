# Инструкции для агентов

Обязательный контекст для автоматизированных агентов. Пользовательская документация
находится в `README.md` и `docs/`.

## Границы проекта

- Проект состоит из Android companion app и Garmin Connect IQ watch app/glance
  `TOTP mixalich7b`.
- Распространение только через sideload. Не добавлять store publishing, сервер,
  аккаунты, облачную синхронизацию, аналитику или crash-reporting без запроса.
- Android — единственный источник истины для списка записей. Часы только хранят
  snapshot, вычисляют коды и выбирают локальный favorite.
- Не добавлять биометрию/PIN, отдельный application pairing или дополнительное
  прикладное шифрование Garmin storage/транспорта.
- Не выполнять `git add`, `git commit` и другие изменяющие git-операции.
- Не добавлять в репозиторий signing keys, keystores, реальные TOTP secrets,
  локальные БД, screenshots с кодами или собранные APK/PRG.

## Структура репозитория

```text
.
├── README.md                   краткое описание и ссылки
├── AGENTS.md                   инварианты для агентов
├── android/
│   ├── app/build.gradle.kts    Android config, версии, зависимости, signing
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml permissions, backup и Activity
│   │   ├── kotlin/net/mixalich7b/totp/
│   │   │   ├── MainActivity.kt              lifecycle, wiring и storage reset
│   │   │   ├── MainScreen.kt                platform UI и ListView adapter
│   │   │   ├── EntryCollection.kt           decrypted UI state и secret ownership
│   │   │   ├── LocalEntryStore.kt           repository executor и async API
│   │   │   ├── EntryEditorController.kt      add/delete dialogs
│   │   │   ├── ImportController.kt           QR и import flow
│   │   │   ├── SyncController.kt             sync UI orchestration
│   │   │   ├── TotpCore.kt                  model, Base32, TOTP, otpauth
│   │   │   ├── SecureEntryRepository.kt     SQLite + Android Keystore
│   │   │   ├── MigrationParser.kt           Google Authenticator protobuf
│   │   │   ├── MigrationBatchCollector.kt   multi-batch assembly
│   │   │   ├── ImportPlanner.kt             duplicates/import policy
│   │   │   ├── GarminSyncManager.kt         Mobile SDK state machine
│   │   │   └── SyncProtocol.kt              canonical snapshot hash
│   │   └── res/                 strings, styles, dimensions and icons
│   ├── app/src/test/            JVM unit/property/fuzz tests
│   ├── app/src/androidTest/     SQLite/Keystore instrumentation tests
│   ├── gradle/verification-metadata.xml
│   └── gradlew
├── garmin/
│   ├── manifest.xml             UUID, target, permissions, languages
│   ├── monkey.jungle
│   ├── source/
│   │   ├── TotpMixalich7bApp.mc app lifecycle/background registration
│   │   ├── TotpView.mc          fullscreen list and input
│   │   ├── TotpGlanceView.mc    favorite glance
│   │   ├── TotpCore.mc          TOTP and snapshot hash
│   │   ├── TotpStore.mc         active/staging storage
│   │   ├── TotpSyncServiceDelegate.mc background wire receiver
│   │   └── TotpCoreTest.mc      Connect IQ test PRG
│   ├── resources/               English strings and icon
│   └── resources-rus/           Russian strings
├── protocol/schema.md           canonical sync protocol v1
└── docs/
    ├── BUILDING.md              build, tests and sideload
    ├── DEVELOPMENT.md           architecture and behavior
    ├── SECURITY.md              threat model
    └── RELEASE.md               current stable artifact metadata
```

`android/**/build/`, `android/.gradle/` и `garmin/bin/` — generated output, не
источник истины. `android/local.properties` и IDE-файлы локальны.

## Идентификаторы и toolchain

- Android application ID/namespace: `net.mixalich7b.totp`.
- Garmin UUID: `fa0bbecf-1e62-477b-b9cf-740aca2a4b32`; в manifest без дефисов.
- Android Keystore alias: `net.mixalich7b.totp.entries.v1`.
- Storage AAD v1: UTF-8 `id|schema|revision`.
- Sync protocol: v1; каноническая схема — `protocol/schema.md`.
- Android: AGP 9.2.1, Gradle 9.6.0, compile/target SDK 37, min SDK 28.
- Garmin: Connect IQ SDK 9.2.0, Companion SDK 2.4.0, target
  `fenix8pro47mm`, min API 6.0.0.

Не менять ID, UUID, alias, AAD, storage/wire format или protocol version без явного
решения владельца и одновременного обновления реализации, тестов и документации.

UI и persistence намеренно используют стандартные platform-компоненты. Не вводить
Compose, Room, KSP/kapt, DI или другой framework без необходимости и обоснования.

## Android: данные и безопасность

- SQLite содержит только `id`, revision, schema version, 12-byte IV и ciphertext
  с GCM tag. Display name, issuer, account и secret находятся в encrypted payload.
- Каждая запись шифруется AES-256-GCM с новым IV, созданным provider после
  `Cipher.init(ENCRYPT_MODE, key)`. Caller-provided IV при шифровании запрещён.
- Keystore key неэкспортируемый, randomized encryption включён,
  `setUserAuthenticationRequired(false)`.
- StrongBox используется с корректным fallback. `setUnlockedDeviceRequired(true)`
  применяется только на API 35+.
- Если БД непуста, а key отсутствует/невалиден, не создавать новый key молча.
  Нужен явный reset flow. Перед `resetLocalStorage()` закрыть repository.
- Reset Android storage удаляет только локальную БД и Keystore key, не данные часов.
- Backup/data extraction отключены, Activity сохраняет `FLAG_SECURE`, release
  остаётся `debuggable=false`.
- Persistence и подготовка snapshot не выполняются на UI thread. Repository
  копирует принадлежащие ему `ByteArray`; временные secret arrays очищаются.
- `EntryCollection` — единственный владелец расшифрованного списка для UI. Состав
  списка изменяется только через её API; удалённые, заменённые и очищаемые secret
  arrays зануляются внутри коллекции.
- Полная загрузка списка из repository допустима при старте и после явного reset.
  После успешных add/delete/import UI обновляет `EntryCollection` инкрементально,
  не очищая `ListView` на время повторного чтения БД.
- `LocalEntryStore` последовательно выполняет repository operations вне UI thread
  и не доставляет callbacks после `close()`. Контроллеры import/sync очищают
  принадлежащие им временные данные в `close()`.
- Поле ручного ввода secret отключает suggestions, autofill и сохранение view state;
  содержимое очищается при закрытии диалога.
- Размеры UI задаются в dp/sp resources. Не передавать уже пересчитанные px обратно
  в API, ожидающие sp. Сохранять обработку system bar insets и edge-to-edge.
- Во время storage/sync operation блокировать конфликтующие изменения данных.
- Не помещать secret, QR payload, decrypted records или sync dictionaries в logs,
  clipboard, exceptions, saved state или UI ошибок.

## Импорт

- Одиночный формат — только `otpauth://totp`.
- Google Authenticator export поддерживает protobuf payload versions 1/2 и
  multi-batch по `batchId`, `batchIndex`, `batchSize`; неизвестные поля пропускаются
  по protobuf wire type.
- `otpauth://totp` декодируется ровно один раз; secret обязателен, Base32 должен быть
  валиден, issuer в label/query согласован. Пробелы и дефисы допустимо нормализовать
  только при ручном вводе.
- Ошибка одной записи не отменяет остальные корректные записи пачки. Отчёт содержит
  только безопасные категории и количества.
- Поддерживаются SHA-1/SHA-256, 6/8 digits, period 5–300 и secret 1–1024 bytes.
  HOTP, SHA-512 и MD5 отклоняются.
- Дубликат: NFC/case-insensitive issuer/account + secret + algorithm + digits +
  period. Display name не входит в ключ.
- `REPLACE` сохраняет ID и createdAt. Вся импортируемая пачка увеличивает revision
  один раз.
- Pending import data очищается с занулением secret arrays после import, cancel
  или destroy.
- Google Code Scanner не требует `CAMERA`; не добавлять permission без смены
  scanner implementation.

## Garmin

- `Application.Storage` содержит plaintext — не создавать видимость защищённого
  хранения.
- Целевой экран — круглый AMOLED 454×454 с touch и аппаратными кнопками. Лимиты
  профиля: 768 KiB foreground и 64 KiB glance.
- Active snapshot целиком хранится под `active_snapshot`, staging —
  `staging_snapshot`, favorite — `favorite`. Active переключается одним
  `Storage.setValue`.
- До commit проверять protocol version, transfer ID, sequence, count, revision,
  entry bounds и SHA-256. Максимум: 100 записей, name 128 chars, secret 1024 bytes.
- Staging очищается после terminal error, успешного commit, нового valid BEGIN и
  команды очистки.
- Старые revision отклоняются; повтор commit последнего transfer идемпотентен.
- После commit сохранять существующий favorite, иначе выбирать первую запись или
  очищать favorite.
- Команда `d` удаляет active/staging/favorite и отвечает `z` только после удаления.
- Индекс выбранной записи ограничивать до обращения к массиву. Длинные Unicode-имена
  сокращать без повреждения строки; invalid active snapshot показывает состояние
  повреждённого хранилища.
- Не логировать secret, code, name, checksum, transfer ID или message dictionary.

Glance/background:

- Glance имеет лимит 64 KiB. Нужный ему код отмечается `(:glance)` и остаётся
  минимальным.
- `AppBase.initialize()` работает в glance context и не создаёт `TotpView`/timer.
- Phone messages принимает background `ServiceDelegate`, зарегистрированный через
  `Background.registerForPhoneAppMessageEvent()`.
- `TotpStore` и `TotpCore` используются glance и background и должны сохранять
  `(:glance, :background)`.
- Односимвольный тип сообщения может прийти как `String`, `Char` или `Symbol`.
  Нормализовать через `syncMessageType`; строки сравнивать по значению через
  `.equals()`/`totpValuesEqual`.
- Garmin HMAC-SHA1 реализован через `Cryptography.Hash(HASH_SHA1)` по ipad/opad.
  Не заменять native HMAC с `HASH_SHA1`.

## Sync protocol

- Android отправляет полный snapshot: begin, chunk на запись, commit. ACK принимается
  только после атомарного переключения active storage.
- Любой ACK/error требует точного active transfer ID и active device.
- Проект использует первое подключённое Garmin-устройство и не хранит отдельный
  target-watch pairing.
- Каждый async callback и timeout проверяет active transfer ID; позднее событие
  старой операции не должно влиять на retry.
- Timeout ожидания ACK — 30 секунд.
- Отмена допустима только до commit. Во время commit/ACK отмена блокируется.
- Текст ошибки от часов не отражать напрямую в UI/logcat: использовать фиксированные
  безопасные категории.
- Checksum не является MAC. Не добавлять ECDH, SAS, transport crypto или pairing.

## Обязательная проверка

Android:

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew lintRelease assembleRelease
```

`connectedDebugAndroidTest` очищает БД и key debug-пакета. Запускать только на чистом
эмуляторе/тестовом устройстве без реальных секретов; пользовательское приложение при
конфликте подписи автоматически не удалять.

Garmin:

```bash
cd garmin
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b.prg -w
"$CONNECTIQ_HOME/bin/monkeyc" -f monkey.jungle -d fenix8pro47mm -t \
  -y "$HOME/developer_key" -o bin/TOTP-mixalich7b-tests.prg -w
"$CONNECTIQ_HOME/bin/monkeydo" bin/TOTP-mixalich7b-tests.prg fenix8pro47mm -t
```

`monkeydo` может вернуть non-zero при итоговом `PASSED`; проверять текстовый итог.
После изменения glance/background проверять build stats и лимит heap. После
изменения sync, storage, lifecycle, glance или Garmin UI выполнять Simulator smoke
и ручную проверку на Android 16/Xiaomi 17 и fēnix 8 Pro 47 mm/firmware 22.35.

## Документация

- Пользовательское поведение и краткие возможности — `README.md`.
- Сборка, тесты и установка — `docs/BUILDING.md`.
- Архитектура и эксплуатационные нюансы — `docs/DEVELOPMENT.md`.
- Модель угроз — `docs/SECURITY.md`.
- Текущие артефакты — `docs/RELEASE.md`.
- Wire format — `protocol/schema.md`.

Не превращать документацию в журнал работ: не добавлять завершённые планы, даты
промежуточных проверок, перечни исправленных дефектов или хронологию разработки.
