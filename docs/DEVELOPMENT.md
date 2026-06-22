# Архитектура и ограничения

Android-приложение является источником истины для списка TOTP-записей. Часы получают полный snapshot, после чего вычисляют коды полностью офлайн. На часах можно только просматривать коды и выбирать запись для glance.

## Android

Приложение использует Kotlin и стандартные Android-компоненты: `Activity`, `ListView`, диалоги и `SQLiteOpenHelper`. Это сохраняет проект небольшим и уменьшает число build-time зависимостей.

Все содержательные поля записи шифруются AES-256-GCM с ключом Android Keystore. Биометрия и PIN приложения не требуются. QR распознаётся Google Code Scanner, поэтому для этой функции нужны Google Play services; AOSP-эмулятор без Google APIs не подходит.

## Garmin

Watch app и glance находятся в одном Connect IQ package. Полноэкранный экран показывает вертикальный список, а glance — выбранную запись. Поддерживаются SHA-1 и SHA-256; SHA-512 и HOTP не поддерживаются.

Секреты на часах хранятся без дополнительного шифрования в `Application.Storage`. Частоту обновления glance определяет Garmin OS, поэтому постоянное посекундное обновление вне открытого приложения не гарантируется.

## Синхронизация

Android отправляет полный snapshot через Garmin Connect IQ Mobile SDK. Часы проверяют целостность и применяют его только целиком; незавершённая передача не заменяет рабочие данные. Подробный формат находится в [protocol/schema.md](../protocol/schema.md).

Дополнительное прикладное шифрование транспорта и отдельный pairing не используются. Для синхронизации нужен Garmin Connect и уже подключённые к нему часы.

Для диагностики обмена debug-сборка Android пишет стандартный logcat с тегом
`TotpGarminSync`, а Garmin — строки с префиксом `TOTP sync` через `System.println`.
В Android release logging отключён. Garmin-логи содержат только известный тип
сообщения, безопасную категорию результата и revision успешного commit — без
transfer ID, секретов, имён записей, checksum и payload.

```bash
adb logcat -s TotpGarminSync
```

## Статус

Обе части собираются, Android unit-тесты и Garmin RFC-тесты проходят в симуляторах.
Владелец проекта повторил аппаратную regression-проверку 22 июня 2026 года на
связке Xiaomi 17 с Android 16 и fēnix 8 Pro 47 mm с firmware 22.35. Подтверждены
background delegate, полный цикл snapshot/ACK, обновлённый UI синхронизации и
немедленная подтверждаемая очистка данных часов. Эти сценарии нужно повторять после
изменений lifecycle, синхронизации, storage или Garmin UI.

Усиление автоматических тестов, пользовательские сценарии и квалификация стабильной
sideload-версии завершены 22 июня 2026 года. Версия `0.1.0` признана stable после
проверок из этапа 8.4; параметры итоговых артефактов находятся в
[RELEASE.md](RELEASE.md).

Для текущих исходников `0.1.1` завершён автоматический self-review: 54 unit-теста,
7 instrumentation-тестов, Android lint без issues и 12 Garmin Simulator tests.
Из-за замены Garmin storage schema без migration версия остаётся кандидатом до
чистой установки и повторной аппаратной проверки sync/clear/favorite/glance.

Android storage/UI instrumentation-набор из семи тестов успешно выполнен 22 июня
2026 года на эмуляторе Android 16/API 36 (`sdk_gphone64_arm64`). Проверены реальный
Android Keystore и SQLite, уникальность IV, отсутствие plaintext в колонках, потеря
Keystore key, явный reset, повреждение GCM tag, а также атомарная замена записи с
сохранением ID и единственным увеличением revision для всей операции. Preview
позволяет выбрать записи, а политика дубликатов применяется ко всей выбранной пачке.

Расширенный Garmin test PRG успешно выполнен 22 июня 2026 года в Connect IQ Device
Simulator 9.2.0 для `fenix8pro47mm` (runtime API 6.0.2): 12 тестов, 12 PASS. Проверены
RFC 6238 SHA-1/SHA-256, канонический snapshot hash, представления типов сообщений,
атомарный commit, stale revision, checksum, count/sequence/transfer ID, потеря,
перестановка и повтор chunks, а также полная логическая очистка watch storage.
`monkeydo` вернул exit code 1 при итоговой строке
`PASSED (passed=12, failed=0, errors=0)`; результат следует определять по текстовому
итогу. Перед успешным запуском старый процесс Simulator пришлось полностью завершить
и запустить заново; PRG был подписан постоянным developer key проекта.

Android unit-набор содержит 54 теста, включая transport response correlation,
message builder, repository ownership и строгую migration version.
Детерминированные property/fuzz-сценарии
проверяют случайные Base32 secrets, Unicode `otpauth://` URI, произвольные и
усечённые protobuf migration payload и свойства канонического snapshot hash.
Максимальная длина декодированного secret — 1024 байта; это единый предел input
model и storage codec, исключающий создание записи, которую нельзя прочитать после
перезапуска.

Google Authenticator migration parser обрабатывает ошибки на уровне отдельной
записи. HOTP, неподдерживаемые algorithm/digits и повреждённые элементы не
сохраняются и показываются пользователю в сводном отчёте; остальные корректные
TOTP-записи из той же одно- или многочастной миграции можно импортировать.

Синхронизация отображает подготовку, прогресс chunks, commit и ожидание ACK. Кнопка
позволяет отменить передачу до commit и повторить её после ошибки. На commit/ACK
отмена блокируется, так как часы уже могли применить snapshot. Callback отправки
и timeout проверяют активный transfer ID: опоздавшее событие отменённой передачи не
может продолжить старую очередь во время retry. Ответы Garmin сопоставляются с
фиксированными безопасными категориями; произвольный текст часов не отражается в UI
или logcat.

Проект рассчитан на одни часы, поэтому Android использует первое подключённое
устройство Garmin и не хранит отдельную локальную привязку. Подтверждаемая команда
`d` отправляется сразу, без предварительной синхронизации, удаляет единые
active/staging snapshot objects и favorite на часах и получает ACK `z`; Android repository при
этом не меняется.

## Зависимости release

Аудит `releaseRuntimeClasspath` выполнен 22 июня 2026 года. Прямые runtime-зависимости:

- Kotlin stdlib 2.2.10 и AndroidX Core KTX 1.19.0 — Apache License 2.0;
- Garmin Connect IQ Companion SDK 2.4.0 — Garmin Connect IQ SDK License Agreement
  и Connect IQ Application Developer Agreement;
- Google Play services Code Scanner 16.1.0 — ML Kit Terms of Service.

Полное транзитивное дерево формируется без установки дополнительных плагинов:

```bash
cd android
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

В проекте нет настроенных analytics или crash-reporting SDK; Code Scanner при этом
транзитивно включает Google DataTransport для внутренних функций Google-компонентов.
Проверка production sources и release output не выявила встроенных TOTP test
secrets, локальных БД, screenshots или журналов; RFC/test vectors находятся только
в test source sets.
