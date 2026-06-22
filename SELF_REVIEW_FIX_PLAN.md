# План исправлений по итогам self-review

Дата: 22 июня 2026 года.

Цель — устранить найденные риски корректности, безопасности, lifecycle и
тестируемости перед следующей stable-сборкой. Garmin-приложение будет
переустановлено с очисткой данных, поэтому обратная миграция прежней внутренней
схемы `Application.Storage` намеренно не реализуется. Wire protocol остаётся v1.

## Статус выполнения

- [x] Android security, repository ownership, strict migration parsing и transport
  correlation исправлены и покрыты regression-тестами.
- [x] Garmin active/staging storage заменены без миграции; validation, cleanup,
  corrupt state, favorite bounds и background transmit проверены test PRG.
- [x] Persistence и подготовка sync snapshot вынесены с Android UI thread;
  конфликтующие действия блокируются на время передачи.
- [x] Исправлены SP/PX, adaptive/monochrome icon, edge-to-edge compatibility и
  проверены UI bounds при density 480/font 1.0 и density 420/font 1.3.
- [x] AndroidX Core и Gradle обновлены; distribution/dependency checksums закреплены.
- [x] Пройдены 54 Android unit, 7 instrumentation и 12 Garmin Simulator tests;
  Android lint не содержит issues, Garmin release/test PRG собираются.
- [ ] Выполнить ручную regression-проверку `0.1.1` на Xiaomi 17 и fēnix 8 Pro после
  чистой установки: QR, sync при закрытом watch app, interrupted transfer, clear,
  favorite и glance. Только после неё обновить stable hashes в `docs/RELEASE.md`.

## 1. Regression-тесты

- Добавить Android-тесты отклонения migration payload без версии и с version 0,
  слишком длинного protobuf varint и владения массивом secret в repository.
- Расширить Garmin test PRG проверками строгой валидации входных данных,
  очистки staging после ошибок, атомарного active snapshot и безопасного выбора
  favorite после уменьшения списка.
- Добавить тестируемую модель Android sync state machine и сценарии ACK, timeout,
  cancel, send error, stale callback, wrong transfer/revision и clear.

## 2. Android: безопасность и корректность данных

- Защитить ручной ввод секрета от suggestions, autofill и сохранения view state;
  очищать содержимое поля после закрытия диалога.
- Исправить владение `ByteArray`: repository копирует данные, которыми владеет,
  и не изменяет массивы вызывающего кода; временные копии очищаются.
- Принимать только Google Authenticator migration payload versions 1/2 и усилить
  protobuf validation.
- Вынести операции Keystore/SQLite и подготовку snapshot с UI thread в один
  последовательный executor с lifecycle-safe завершением.

## 3. Garmin: storage и transport validation

- Заменить раздельные active-ключи единым объектом snapshot с entries, revision и
  last transfer ID; переключать его одной операцией `Storage.setValue`.
- Заменить раздельные staging-ключи единым staging-объектом. Очищать его при
  новом BEGIN, terminal error, успешном commit и явной очистке часов.
- Обратную миграцию старых ключей не добавлять: новая схема рассчитана на чистую
  переустановку Garmin-приложения.
- Строго проверять protocol version, типы и длины transfer ID/hash/id/name/secret,
  count, sequence, algorithm, digits, period и диапазон байтов secret до записи.
- Обработать исключения `Communications.transmit`, гарантировать завершение
  background service и не выводить непроверенные значения в logs.

## 4. UI и пользовательские состояния

- Исправить повторную интерпретацию Android размеров из px как sp, подобрать
  стандартные размеры и проверить разные density/font scale.
- Ограничить индекс выбранной Garmin-записи до обращения к списку.
- Безопасно сокращать длинные Unicode-названия и показывать состояние
  повреждённого Garmin storage.
- Уточнить документацию времени: приложение доверяет системному времени часов;
  без внешнего источника определить небольшое расхождение невозможно.
- Добавить monochrome adaptive icon и убрать актуальные lint/deprecation warnings,
  где это возможно без нестандартного UI framework.

## 5. Toolchain и supply chain

- Обновить AndroidX Core KTX до 1.19.0.
- Добавить официальный SHA-256 для Gradle distribution и dependency verification.
- Отдельно проверить совместимость Gradle 9.6 с AGP 9.2.1; не обновлять wrapper,
  если полный набор тестов не проходит.
- Проверить возможность удалить INTERNET permission. R8 рассматривать отдельным
  изменением только после аппаратной проверки Garmin Companion SDK.

## 6. Финальная проверка

- Android: unit tests, debug/release lint, debug/release assembly и androidTest APK.
- Запустить `connectedDebugAndroidTest` только на доступном чистом Android-эмуляторе.
- Garmin: release/test PRG, полный test PRG в Device Simulator и build statistics.
- После изменений storage/sync/UI повторить ручную проверку на Xiaomi 17 и
  fēnix 8 Pro: чистая установка, sync, interrupted transfer, clear, favorite,
  glance и работа без открытого watch app.
- После подтверждения обновить версию, документацию и контрольные суммы stable
  артефактов.
