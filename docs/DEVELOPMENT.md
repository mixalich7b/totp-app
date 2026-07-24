# Архитектура и разработка

## Общая модель

Android-приложение — источник истины для TOTP-записей. Часы получают полный snapshot,
хранят его локально и вычисляют коды без телефона. Редактирование записей на часах
не поддерживается; локально выбирается только favorite для glance.

Запись содержит UUID, display name, issuer, account, secret, алгоритм, число цифр,
период и служебные timestamps. Revision относится ко всему локальному snapshot,
а не к отдельной `TotpEntry`. Допустимы SHA-1/SHA-256, 6/8 цифр, период 5–300
секунд, secret длиной 1–1024 декодированных байта и название длиной до 128
символов.

TOTP рассчитывается по RFC 4226/6238 из системного UTC Unix time. Приложение не
имеет внешнего источника времени и не может определить небольшое расхождение часов.

## Android

Приложение написано на Kotlin и использует стандартные `Activity`, `ListView`,
диалоги и `SQLiteOpenHelper`.

- `MainActivity` связывает компоненты, выполняет initial load, управляет lifecycle
  и показывает явный reset flow при недоступном хранилище.
- `MainScreen` строит platform UI, владеет `ListView` adapter, swipe-actions строк
  и состоянием controls.
- `EntryCollection` владеет расшифрованным списком для UI. Она предоставляет
  read-only view и зануляет secret при удалении, замене или очистке записи.
- `LocalEntryStore` владеет repository и последовательным executor. Его
  типизированные операции доставляют результат на UI thread и отбрасывают его
  после `close()`, предварительно очищая расшифрованные snapshot.
- `EntryEditorController` управляет диалогами ручного добавления, редактирования
  и удаления.
- `ImportController` управляет scanner, multi-batch collector, preview, политикой
  дубликатов и временем жизни pending import.
- `SyncController` получает snapshot из `LocalEntryStore` и координирует UI с
  `GarminSyncManager`.
- `SecureEntryRepository` хранит зашифрованные записи и атомарно изменяет revision.
- `TotpCore` содержит модель записи, Base32, TOTP и парсер `otpauth://totp`.
- `MigrationParser` и `MigrationBatchCollector` разбирают одно- и многочастный
  экспорт Google Authenticator.
- `ImportPlanner` определяет дубликаты и строит атомарный план импорта.
- `GarminSyncManager` управляет Garmin Mobile SDK, состояниями передачи,
  timeout/cancel и очисткой часов.
- `SyncProtocol` формирует канонический SHA-256 snapshot.

Полная загрузка decrypted records выполняется при старте приложения и после
явного reset локального storage. После успешных add, edit, delete и import
`EntryCollection` изменяется инкрементально, поэтому `ListView` не очищается на
время повторного чтения БД. Persistence при этом завершается до изменения UI.

Свайп строки списка влево открывает красное действие удаления и бледно-зелёное
действие редактирования. Удаление использует общий диалог подтверждения.
Редактирование позволяет изменить display name, issuer, account, алгоритм, число
цифр и период. Secret в форму не передаётся и не редактируется; update сохраняет
ID, createdAt, secret и позицию строки, увеличивая revision snapshot один раз.

Google Code Scanner требует Google Play services и не требует `CAMERA` permission.
Перед импортом показывается preview. Ошибка отдельной записи экспорта Google
Authenticator не блокирует остальные корректные TOTP-записи.

Дубликат определяется по NFC-нормализованным без учёта регистра issuer/account,
secret и параметрам TOTP. При замене сохраняются ID и время создания существующей
записи, а revision всей выбранной пачки увеличивается один раз.

## Garmin

Watch app и glance входят в один Connect IQ package.
Целевой профиль имеет круглый экран 454×454, touch, аппаратные кнопки, лимит
768 KiB для foreground app и 64 KiB для glance.

- `TotpMixalich7bApp` регистрирует background mailbox и создаёт foreground/glance UI.
- `TotpView` показывает список и обрабатывает кнопки и touch-жесты.
- `TotpGlanceView` показывает локальный favorite и его текущий код.
- `TotpStore` валидирует и атомарно переключает active snapshot через staging.
- `TotpSyncServiceDelegate` принимает сообщения телефона в background context.
- `TotpCore` реализует TOTP и канонический snapshot hash.

В каждом runtime context `TotpStore` лениво читает active snapshot и favorite один
раз, после чего обслуживает UI из памяти. Изменения из другого context обновляют
кэш через `AppBase.onStorageChanged()`, а собственные commit, clear и смена favorite
обновляют его непосредственно. Изменения только staging не сбрасывают active cache.

Рассчитанный код кэшируется по ID записи и номеру TOTP time-step. Повторные
посекундные обновления UI внутри одного периода не выполняют HMAC; пересчёт
происходит при смене time-step или active snapshot. Секундный foreground timer
остаётся нужен для индикатора оставшегося времени.

На устройствах с Live UI открытый glance запрашивает обновление раз в две секунды.
На устройствах с background UI вызовы `WatchUi.requestUpdate()` могут
игнорироваться, и фактическую частоту обновления выбирает Garmin OS. Нажатие на
glance открывает watch app.

HMAC-SHA1 реализован через `Cryptography.Hash(HASH_SHA1)` по ipad/opad-схеме:
целевой runtime принимает SHA-256 в native HMAC, но отклоняет SHA-1.

## Синхронизация

Android отправляет `BEGIN`, по одному `CHUNK` на запись и `COMMIT`. Часы проверяют
protocol version, transfer ID, sequence, count, revision и SHA-256, после чего одной
операцией заменяют active snapshot. Незавершённая или повреждённая передача не
заменяет рабочие данные. Точный wire format описан в
[protocol/schema.md](../protocol/schema.md).

Android принимает ответы только от активного устройства и для текущего transfer ID.
Поздние callbacks и timeout старой операции игнорируются. Отмена доступна до
отправки commit; после commit UI ждёт ACK, поскольку snapshot уже мог быть применён.

Проект рассчитан на одни часы и использует первое подключённое Garmin-устройство.
Для обмена необходимы Garmin Connect, существующее сопряжение и установленное
Garmin-приложение с тем же UUID.

## Диагностика

Debug-сборка Android пишет безопасные события обмена с тегом `TotpGarminSync`:

```bash
adb logcat -s TotpGarminSync
```

Garmin использует `System.println` с префиксом `TOTP sync`. Логи не должны содержать
секреты, коды, имена записей, checksum, transfer ID или словари сообщений.

## Проверки при изменениях

TOTP core должен проходить RFC 6238 SHA-1/SHA-256 тесты на обеих платформах.
Android persistence проверяется unit- и instrumentation-тестами с реальными SQLite
и Android Keystore. Garmin test PRG проверяет storage, protocol validation,
атомарность commit, retry и очистку.

После изменений sync, storage, glance, lifecycle или Garmin UI нужна ручная
проверка на целевых устройствах: синхронизация при закрытом watch app, прерванная
передача и retry, очистка, favorite, glance и открытие watch app из glance.

Команды приведены в [BUILDING.md](BUILDING.md).

## Runtime-зависимости

- AndroidX Core KTX 1.19.0;
- Garmin Connect IQ Companion SDK 2.4.0;
- Google Play services Code Scanner 16.1.0.

Analytics и crash-reporting SDK в проекте отсутствуют.
