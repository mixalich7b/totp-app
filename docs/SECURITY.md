# Модель безопасности

Защищаемые данные — TOTP secret и связанная метаинформация. Собственного сервера, аналитики и crash-reporting нет.

Android хранит записи AES-256-GCM под ключом Android Keystore без биометрии/PIN приложения. Это защищает файл БД и backup, но код, получивший выполнение в процессе приложения на разблокированном устройстве, может запросить расшифрование. Debug APK и rooted/скомпрометированное устройство не считаются безопасной средой.

Garmin `Application.Storage` и payload Garmin Mobile SDK не имеют дополнительного прикладного шифрования. Защита зависит от изоляции Connect IQ, Garmin Connect и существующего BLE-сопряжения. Sideload Developer content потенциально доступен владельцу developer tools. Checksum протокола защищает только от случайного повреждения.

Screenshot/recents и Android backup отключены. Секреты не должны попадать в logs, clipboard, exceptions или saved state. Удаление не гарантирует физического стирания flash ни на Android, ни на Garmin.

Android release APK не пишет transport diagnostics в logcat; служебное логирование
включено только в debug build. Garmin оставляет минимальные `System.println` об
известном типе сообщения, безопасной категории результата и revision успешного
commit, но не печатает transfer ID, payload, secret, код, название или checksum.

Android release manifest явно удаляет транзитивные `INTERNET` и
`ACCESS_NETWORK_STATE`: сетевую работу выполняют процессы Garmin Connect и Google
Play services. AndroidX добавляет только внутреннее signature-permission. `CAMERA`,
storage, location и Bluetooth permissions приложение не запрашивает.
Google Code Scanner показывает системный экран сканирования через
Google Play services; приложение получает строку QR и разбирает её локально, без
собственного сервера. Собственная аналитика не настроена, но Google-компоненты
подчиняются условиям ML Kit/Google Play services.

Подтверждаемая команда очистки часов удаляет единые active/staging snapshot objects
и favorite из `Application.Storage`. Это логическое
удаление через Connect IQ API, а не гарантированный secure erase flash.

При потере Android Keystore key локальные данные не восстанавливаются. Приложение не
заменяет потерянный ключ автоматически: после явного предупреждения пользователь может
безвозвратно удалить локальную БД и ключ, а затем импортировать записи заново. Этот сброс
не удаляет уже синхронизированные секреты с часов.

При потере часов или телефона следует отозвать/перевыпустить соответствующие TOTP
credentials у каждого issuer.

Signing keys хранятся вне репозитория и должны иметь права доступа `0600`. Их потеря
не раскрывает сохранённые TOTP secrets, но лишает возможности выпускать совместимые
обновления; поэтому нужны отдельные защищённые резервные копии ключей и паролей.
