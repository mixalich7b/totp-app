# План реализации «TOTP mixalich7b»

Дата актуализации: 21 июня 2026 года.

## 1. Цель и границы проекта

Нужно создать два клиентских приложения без собственного сервера:

1. Garmin Connect IQ watch app для Garmin fēnix 8 Pro, которое локально хранит TOTP-секреты, вычисляет коды без телефона, показывает их вертикальным списком и предоставляет glance для избранного кода.
2. Android-приложение для создания, импорта, удаления и синхронизации секретов с часами.

Название обоих приложений: **TOTP mixalich7b**.

- Android `applicationId` и `namespace`: `net.mixalich7b.totp`.
- В Garmin manifest приложение идентифицируется UUID, а не Java-подобным package name. Для классов, ресурсов и сопутствующей документации используется префикс `net.mixalich7b`; UUID создаётся один раз и больше не меняется.
- Android является источником истины для набора секретов. Часы могут только читать коды и выбирать локальный избранный код для glance.
- На данном этапе оба приложения распространяются только через sideloading и не публикуются в Google Play или Connect IQ Store.
- Облачная синхронизация, учётные записи, аналитика и crash-reporting в первую версию не входят.

## 2. Проверенные ограничения и исходные версии

На машине разработки обнаружены:

- Garmin Connect IQ SDK **9.2.0** (`2026-06-09`);
- профиль устройства `fenix8pro47mm` для fēnix 8 Pro 47/51 mm, разрешение 454×454, AMOLED, touch + кнопки, Connect IQ **6.0.2**;
- лимит памяти профиля: 64 KiB для glance и 768 KiB для полноэкранного приложения;
- Android SDK platform **36.1**; `targetSdk` задаётся стабильным целым API level 36, а сборка использует последнюю доступную стабильную platform/extension.

Перед созданием каркаса и перед каждым релизом нужно повторно проверить SDK Manager, Android SDK Manager, AGP, Kotlin, Compose BOM и зависимости. В репозитории версии фиксируются version catalog/lock-файлами; preview/RC-версии не используются в production без отдельного решения.

### Особенность Garmin glance

На устройствах с Connect IQ API 4.0+ watch apps могут предоставлять собственный glance. fēnix 8 Pro использует API 6.0.2, поэтому Garmin-часть реализуется одним пакетом типа `watch-app` с:

- `GlanceView` — краткое представление избранного кода;
- полноэкранным `View` — список всех кодов и настройки избранного.

Приложение доступно из launcher, а выбор его glance запускает тот же watch app в полноэкранном режиме. Отдельный legacy widget-пакет не нужен: он потребовал бы дублирования хранилища, синхронизации и идентификаторов.

## 3. Модель хранения и доверия

### 3.1. Защищаемые данные

Секретными считаются:

- исходный TOTP secret;
- issuer, account/label и пользовательское название;
- параметры алгоритма;
- QR payload и все промежуточные сообщения синхронизации.

Прикладное шифрование используется только для Android-хранилища. Garmin storage и транспортные сообщения дополнительным прикладным шифрованием не защищаются.

### 3.2. Android

- При первом запуске приложение генерирует неэкспортируемый AES-256 key с alias приложения в провайдере `AndroidKeyStore`.
- Ключ создаётся с `setUserAuthenticationRequired(false)`: `BiometricPrompt`, PIN приложения и дополнительное подтверждение пользователя не используются.
- Keystore запрещает экспорт ключа, но без user authentication приложение может использовать его автоматически; защита не подтверждает присутствие пользователя и не защищает от компрометации самого процесса приложения.
- На Android 15+ задаётся `setUnlockedDeviceRequired(true)`, чтобы ключ не использовался до первой разблокировки устройства после загрузки. На Android 14 и ниже этот флаг не применяется из-за документированных платформенных ограничений.
- Каждая TOTP-запись целиком шифруется AES-256-GCM с новым случайным 96-bit IV; `id`, версия схемы и revision включаются в AAD. Повтор IV с тем же ключом недопустим.
- Room хранит только технический envelope (`id`, revision, schema version, IV, ciphertext/tag); secret, issuer, account и display name в plaintext БД не записываются.
- Hardware-backed/StrongBox key используется, если доступен, но не является обязательным условием работы. Фактический security level диагностируется через `KeyInfo` без вывода ключевого материала.
- Приложение опирается также на Android application sandbox, системную file-based encryption и блокировку самого устройства.
- Sideload APK с реальными секретами собирается в release-варианте с `debuggable=false`; debug APK позволяет владельцу среды разработки прочитать app-private БД через ADB.
- Android backup/data extraction для БД и настроек отключается; чувствительные экраны защищаются от screenshots/recents (`FLAG_SECURE`).
- Секреты, URI и QR payload никогда не попадают в логи, exception messages, clipboard, analytics или saved state.
- Временные `ByteArray` очищаются после использования настолько, насколько это позволяет JVM.
- При потере/инвалидации Keystore key ciphertext считается невосстановимым: приложение предлагает удалить локальное хранилище и создать его заново, не пытаясь продолжать с повреждёнными данными.

### 3.3. Garmin

- Записи хранятся без прикладного шифрования через `Application.Storage` и доступны приложению после каждого запуска, включая glance.
- Приложение устанавливается sideloading как Developer content и не получает уровень Trusted от Connect IQ Store.
- Хранилище изолировано от других обычных Connect IQ приложений, но для Developer content Garmin не гарантирует шифрование object store и недоступность через developer tools.
- Поэтому реальный уровень защиты ниже исходного требования: пользователь с доступом к часам и среде разработки потенциально может извлечь секреты. Это принимаемое ограничение текущего sideload-only этапа.
- Секреты и коды не выводятся в `System.println`, crash logs и diagnostics; удаление логически удаляет соответствующие записи `Application.Storage`, но гарантированное физическое стирание flash не предполагается.

### 3.4. Синхронизация

- Транспорт — Garmin Connect IQ Mobile SDK; на Android должен быть установлен Garmin Connect и пользователь должен разрешить доступ к сопряжённым часам.
- Дополнительный pairing между приложениями, ECDH, SAS, session keys и прикладное шифрование сообщений не используются.
- Доверие опирается на уже выполненное сопряжение часов с Garmin Connect, разрешённый пользователем список устройств и неизменяемый UUID Garmin-приложения.
- Конфиденциальность и аутентичность payload ограничены гарантиями Garmin Mobile SDK и его BLE-транспорта; прикладной протокол их не усиливает.
- Сообщения содержат protocol version, transfer id, monotonic revision, sequence number и checksum/hash для обнаружения повреждений. Checksum не считается защитой от намеренной подмены.
- Протокол отклоняет неизвестную версию, неполный snapshot, неверную последовательность chunks и устаревшую revision.

Принятые ограничения и модель доверия оформляются отдельным `docs/THREAT_MODEL.md` до реализации постоянного хранилища.

## 4. Структура репозитория

```text
totp/
├── android/                 # Gradle project, Kotlin, Compose/Material 3
├── garmin/                  # Connect IQ/Monkey C watch app + glance
├── protocol/
│   ├── schema.md            # Версионированный wire format
│   └── test-vectors/        # TOTP, Android storage, import и sync fixtures
├── docs/
│   ├── THREAT_MODEL.md
│   ├── SECURITY.md
│   ├── TESTING.md
│   └── RELEASE.md
├── scripts/                 # Только воспроизводимые setup/check scripts
└── IMPLEMENTATION_PLAN.md
```

Garmin Mobile SDK обычно поставляется отдельно и может иметь ограничения на распространение. Бинарный SDK не коммитится без проверки лицензии: setup script/documentation указывает ожидаемую версию, путь и SHA-256.

## 5. Общая доменная модель

Одна TOTP-запись содержит:

- `id`: случайный UUID, назначенный Android-приложением;
- `issuer`;
- `accountName`/label;
- пользовательское `displayName`;
- `secret`: декодированные байты Base32;
- `algorithm`: SHA-1, SHA-256 или SHA-512;
- `digits`: 6 или 8;
- `periodSeconds`: положительное поддерживаемое значение, по умолчанию 30;
- `createdAt`, `updatedAt`, `revision`.

HOTP не поддерживается и не должен молча преобразовываться в TOTP. При импорте HOTP запись показывается пользователю как неподдерживаемая и не сохраняется.

TOTP вычисляется по RFC 6238/4226 из UTC Unix time. Форматирование всегда сохраняет ведущие нули. При недостоверном времени часов коды скрываются и показывается ошибка времени.

## 6. Пошаговая реализация

### Шаг 0. Аппаратный spike и закрытие платформенных рисков

До основной разработки создать минимальные Garmin и Android прототипы и проверить на реальной fēnix 8 Pro:

1. Собрать watch app с glance под `fenix8pro47mm` на SDK 9.2.0.
2. Показать статический glance и проверить, что нажатие открывает полноэкранный экран.
3. Проверить обработку кнопок UP/DOWN/BACK/MENU, tap и swipe up/down.
4. Передать сообщения Android ↔ Garmin через Mobile SDK при открытом и закрытом watch app; зафиксировать фактические ограничения доставки и размера payload.
5. Проверить lifecycle glance и возможность секундного обновления кода без нарушения лимитов батареи/платформы.
6. Измерить память TOTP-вычислений, списка и синхронизации отдельно в полном экране и 64 KiB glance.
7. Проверить чтение/запись/удаление `Application.Storage` в sideload Developer-сборке и документировать доступность данных через developer tools.
8. На Android проверить создание и использование Keystore AES key без UI-подтверждения, отсутствие plaintext в Room и поведение ключа после reboot/device unlock.

Результат: короткий ADR с подтверждённым lifecycle glance, лимитом secrets/chunk size и фактическими свойствами sideload storage. Не переходить к основной реализации до завершения spike.

### Шаг 1. Каркас, версии и автоматические проверки

1. Создать Android Gradle project с Kotlin, Jetpack Compose, Material 3, Room, CameraX/QR scanner и `minSdk 28`.
2. Установить `compileSdk` на последнюю стабильную platform (сейчас 36.1) и `targetSdk 36`; добавить version catalog и dependency verification.
3. Создать Connect IQ watch-app project с glance, UUID, manifest для `fenix8pro47mm`, английские и русские ресурсы.
4. Добавить команды `build`, `test`, `lint` для обеих платформ и CI без секретных signing keys.
5. Добавить `.gitignore` для Garmin developer key, Android signing key, local SDK paths, screenshots с реальными кодами и локальных БД.

### Шаг 2. TOTP core и общие test vectors

1. Реализовать строгий Base32 decoder: пробелы/дефисы нормализуются только на ручном вводе, invalid alphabet/padding отклоняется.
2. Реализовать HOTP dynamic truncation и TOTP для SHA-1/SHA-256/SHA-512, 6/8 digits и настраиваемого period.
3. На Garmin использовать нативный HMAC там, где алгоритм поддерживается SDK; для отсутствующего SHA-512 применить компактную проверенную реализацию либо явно отклонить алгоритм по результату memory spike. Нельзя выдавать код с другим алгоритмом.
4. Создать единый набор RFC vectors и edge cases, который выполняется на Android и Garmin.
5. Добавить тесты границы периода: `t-1`, `t`, `t+period-1`, `t+period`, ведущие нули и даты после 2038 года.

Критерий: обе платформы дают побайтно/посимвольно одинаковые результаты на всех поддерживаемых параметрах.

### Шаг 3. Версионированный sync protocol

1. Описать компактную каноническую схему без JSON overhead, если memory/message spike это подтвердит.
2. Реализовать сообщения `HELLO`, `SNAPSHOT_BEGIN`, `CHUNK`, `COMMIT`, `ACK`, `ERROR`.
3. Android формирует полный snapshot текущей revision. Удаление записи естественно отражается отсутствием её в новом snapshot.
4. Часы пишут входящие chunks во временное хранилище, проверяют count/hash/revision, затем атомарно переключают active snapshot.
5. При ошибке или обрыве старый snapshot остаётся рабочим; временные данные очищаются.
6. Повторный `COMMIT` идемпотентен. Старая revision и неизвестная protocol version отклоняются.
7. После успешной синхронизации Android показывает подтверждённую часами revision, количество записей и время.

### Шаг 4. Android storage и UI

1. Создать Android Keystore AES-256-GCM key с `setUserAuthenticationRequired(false)`; на Android 15+ включить `setUnlockedDeviceRequired(true)`.
2. Создать Room schema, DAO и repository, которые хранят технический envelope и шифруют/расшифровывают чувствительный payload на границе repository.
3. Для каждой записи генерировать уникальный IV через `SecureRandom`, проверять GCM tag до использования plaintext и никогда не переиспользовать IV.
4. Отключить backup/data extraction для БД и проверить, что Room-файл не содержит secret, issuer, account или display name в plaintext.
5. Обработать отсутствие, инвалидирование и неподдерживаемый security level ключа без падения и без потери других настроек.
6. Сделать основной экран стандартными Material 3 компонентами:
   - список названий/issuer/account;
   - состояние часов и последней синхронизации;
   - кнопки `Добавить`, `Сканировать QR`, `Синхронизировать`.
7. Сделать форму ручного добавления: название, Base32 secret, issuer/account, algorithm, digits, period; показать validation до сохранения.
8. Сделать удаление с confirmation dialog, затем увеличить revision.
9. Не показывать secret по умолчанию; явное действие временно раскрывает его и автоматически скрывает без биометрического запроса.
10. Корректно обработать process death, отсутствие Garmin Connect, Bluetooth permission, часы offline и несовместимую версию Garmin-приложения.

### Шаг 5. Импорт QR

1. Использовать CameraX и локальный QR decoder без отправки кадров или payload в сеть.
2. Поддержать одиночный `otpauth://totp/...` URI:
   - percent-decoding ровно один раз;
   - обязательный непустой secret;
   - согласование issuer в label/query;
   - строгая проверка algorithm/digits/period;
   - preview и явное подтверждение перед сохранением.
3. Поддержать `otpauth-migration://offline?data=...` Google Authenticator:
   - URL-safe Base64 decode;
   - protobuf parser только необходимых полей миграционного формата;
   - сбор нескольких QR одной batch migration по `batchId`, `batchIndex`, `batchSize`;
   - preview всех TOTP-записей, выбор записей и отчёт о неподдерживаемых HOTP/повреждённых элементах.
4. Находить дубликаты по нормализованным issuer/account/secret/parameters, но не удалять автоматически: пользователь выбирает skip/replace/keep both.
5. Очищать camera frames, migration chunks и decoded secrets после завершения/отмены.
6. Добавить fixtures для Unicode labels, специальных символов, отсутствующего issuer, duplicate entries, out-of-order batches и malformed protobuf.

### Шаг 6. Garmin storage и inbox

1. Реализовать versioned storage schema и миграции поверх `Application.Storage` без дополнительного шифрования.
2. Разделить индекс и записи так, чтобы glance загружал только избранную запись и укладывался в 64 KiB.
3. Реализовать явное удаление заменённых и удалённых записей из persistent storage.
4. Реализовать staging/verification/atomic commit входящего snapshot.
5. После commit сохранить избранный `id`, если он ещё существует; иначе выбрать первую запись либо показать отсутствие кодов.
6. Никогда не писать secret, код или payload синхронизации в `System.println`, crash logs и diagnostics.
7. Обработать повреждённое хранилище без удаления старых данных: заблокировать вывод, сообщить об ошибке и предложить повторную синхронизацию после подтверждения.

### Шаг 7. Полноэкранный UI Garmin

1. Нарисовать простой вертикальный список под круглый 454×454 экран: название, крупный код, индикатор остатка периода и звезда избранного.
2. Фокус перемещается UP/DOWN и swipe up/down; tap/START открывает действия записи; BACK возвращает стандартным образом.
3. Действие `Показывать в glance` меняет только локальный favorite id и сразу обновляет glance.
4. Список пересчитывает видимые коды на границе периода, а progress — не чаще необходимого; невидимые элементы не вычисляются каждый кадр.
5. Состояния: `Нет кодов`, `Ожидание синхронизации`, `Неверное время`, `Повреждённое хранилище`.
6. Проверить длинные Unicode-названия, 6/8-digit codes, крупный системный шрифт, touch и управление только кнопками.

### Шаг 8. Glance

1. Показать название, актуальный избранный код и компактный индикатор времени.
2. При отсутствии favorite показать `Выберите код`; при удалённом favorite применить fallback из шага 6.
3. Нажатие на glance должно открывать полноэкранное представление watch app.
4. На границе 30-секундного периода код должен обновиться без отображения просроченного значения; фактическая стратегия timer/invalidation определяется spike из шага 0.
5. Проверить memory usage, burn-in-safe layout и потребление батареи на реальных часах.

### Шаг 9. Интеграция Android ↔ Garmin

1. Реализовать выбор разрешённых устройств через Garmin Connect и проверку наличия Garmin-приложения по неизменяемому UUID.
2. Использовать уже сопряжённые в Garmin Connect часы без дополнительного pairing между приложениями.
3. Реализовать ручную синхронизацию и retry при восстановлении соединения.
4. Показать прогресс, cancel, timeout и точную причину ошибки без включения секретных данных.
5. Проверить добавление, массовый импорт, удаление, повторную доставку, обрыв посередине, низкий заряд, смену телефона и сброс часов.
6. Добавить явные операции `Забыть часы` и `Очистить данные часов`; удалённая очистка выполняется только при доступных часах и после подтверждения пользователя.

### Шаг 10. Тестирование и security review

Автоматизировать:

- unit tests TOTP/Base32/URI/protobuf/protocol;
- Android AES-GCM envelope tests: unique IV, AAD, corrupted ciphertext/tag и key invalidation;
- property/fuzz tests parsers и canonical serialization;
- protocol validation tests для checksum, count, sequence и revision;
- sync tests с потерей, повтором, перестановкой и дублированием chunks;
- Room migration и Garmin storage migration tests;
- Android Compose/UI tests и camera permission tests;
- Monkey C unit tests, simulator tests и memory reports.

Проверить вручную на реальных fēnix 8 Pro и минимум двух Android-устройствах:

- корректность кода возле смены периода;
- работу без телефона и без сети;
- кнопки, жесты и glance launch;
- reboot/process kill;
- синхронизацию 1, 50 и целевого максимума записей;
- отсутствие plaintext в Android Room-файле, ожидаемое наличие plaintext в Garmin `Application.Storage` и отсутствие секретов в logs, backups, screenshots и crash artifacts;
- неверное время, повреждённый QR, HOTP import, duplicate migration batch;
- uninstall/reinstall, factory reset и повторный выбор часов через Garmin Connect.

Перед использованием с реальными секретами проверить threat model sideload-сборок. Прикладная криптография ограничена Android storage; Garmin storage и transport остаются без дополнительного шифрования. Возможная реализация SHA-512 для TOTP на Garmin покрывается RFC vectors.

### Шаг 11. Подготовка sideload-сборок

1. Зафиксировать поддерживаемую firmware/Connect IQ version и Android min/target SDK.
2. Создать Garmin developer key и Android signing key вне репозитория, настроить резервное хранение.
3. Подготовить краткое описание приватности: camera используется только локально для QR, сервер отсутствует, синхронизация выполняется через Garmin Mobile SDK.
4. Проверить permissions по принципу минимальных привилегий: camera и необходимые Bluetooth/Garmin integration permissions только в момент функции.
5. Собрать подписанные Android APK и Garmin IQ/PRG артефакты воспроизводимым pipeline и документировать команды sideloading.
6. Проверить, что Android APK имеет `debuggable=false`, а в обоих артефактах отключены debug logs.
7. Выполнить dependency/SBOM/license scan и проверить отсутствие test secrets.
8. Провести проверку на реальных часах и сохранить совместимые версии обоих sideload-артефактов вместе с checksums и protocol version.

## 7. Критерии готовности первой версии

Версия считается готовой, когда:

- Android вручную добавляет и удаляет TOTP-записи;
- одиночный QR и многочастный Google Authenticator migration импортируются с preview;
- чувствительные поля Android зашифрованы AES-256-GCM неэкспортируемым Android Keystore key, а БД исключена из backup;
- часы после синхронизации вычисляют коды полностью офлайн;
- список управляется кнопками и touch-жестами;
- пользователь выбирает избранный код, glance постоянно показывает его и открывает полный экран;
- удаление/повторная синхронизация атомарны и подтверждаются revision/ACK;
- RFC vectors проходят на обеих платформах;
- реальные часы проходят memory, battery, lifecycle и storage checks;
- plaintext secrets отсутствуют в логах, backup, screenshots и crash artifacts;
- ограничения sideload Developer storage явно описаны пользователю и в threat model.

## 8. Основные риски

1. **Sideload Developer storage не гарантирует шифрование.** Секреты потенциально доступны пользователю с доступом к часам и developer tools; без прикладного шифрования этот риск не устраняется.
2. **64 KiB glance memory.** Glance должен читать только favorite record; подтверждается ранним spike.
3. **Доставка Mobile SDK в фоне и message limits.** Проверяется на реальном устройстве до фиксации протокола; snapshot всегда chunked и resumable.
4. **SHA-512 на Garmin.** Нативная поддержка может отсутствовать. Решение принимается по memory/performance spike без подмены алгоритма.
5. **Секундное обновление glance.** Connect IQ может ограничивать lifecycle/update frequency; до подтверждения нельзя обещать постоянное покадровое обновление.
6. **Совместимость двух sideload-артефактов.** Protocol versioning обязателен, потому что Android и Garmin приложения могут обновляться независимо.
7. **Payload синхронизации не имеет прикладной защиты.** Его конфиденциальность и аутентичность полностью зависят от Garmin Mobile SDK, Garmin Connect и BLE-сопряжения.
8. **Android Keystore key может быть потерян или инвалидирован.** Поскольку backup отключён и ключ неэкспортируемый, такие данные восстановить нельзя; потребуется сброс локальной БД и повторное добавление секретов.
9. **Android key не требует подтверждения пользователя.** Любой код, получивший выполнение внутри процесса приложения, сможет запросить расшифрование через Keystore без биометрического диалога.

## 9. Официальные справочные материалы

- [Garmin Connect IQ Glances](https://developer.garmin.com/connect-iq/core-topics/glances/)
- [Garmin Connect IQ Security](https://developer.garmin.com/connect-iq/core-topics/security/)
- [Garmin Connect IQ Mobile SDK for Android](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/)
- [Garmin Toybox.Cryptography](https://developer.garmin.com/connect-iq/api-docs/Toybox/Cryptography.html)
- [Garmin Toybox.Application.Storage](https://developer.garmin.com/connect-iq/api-docs/Toybox/Application/Storage.html)
- [RFC 4226 — HOTP](https://www.rfc-editor.org/rfc/rfc4226)
- [RFC 6238 — TOTP](https://www.rfc-editor.org/rfc/rfc6238)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [Android CameraX](https://developer.android.com/media/camera/camerax)
- [Android Room](https://developer.android.com/training/data-storage/room)
