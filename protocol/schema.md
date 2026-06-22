# Sync protocol v1

Garmin Mobile SDK передаёт словари со строковыми короткими ключами. Транспорт не шифруется приложением.

Последовательность полного snapshot:

1. Begin: `{"t":"b","v":1,"x":transferId,"r":revision,"n":count,"h":sha256}`.
2. Для каждой записи по порядку chunk: `{"t":"c","x":transferId,"q":sequence,"e":entry}`.
3. Commit: `{"t":"m","x":transferId}`.
4. Часы отвечают ACK `{"t":"a","r":revision}` или error `{"t":"e","m":message}`.

Каждый ответ содержит `x` исходного transfer ID. Android принимает ответ только
при точном совпадении `x` с активной операцией; ответ без `x` игнорируется.

Подтверждаемая пользователем очистка данных часов использует отдельную команду
`{"t":"d","v":1,"x":transferId}`. Часы удаляют active snapshot (включая
revision и metadata последней передачи), favorite и незавершённый staging, затем отвечают
`{"t":"z","x":transferId}`. Android-записи команда не изменяет. Следующая обычная
синхронизация снова заполнит часы полным snapshot.

Entry:

- `i` — UUID string;
- `n` — display name;
- `s` — массив unsigned secret bytes;
- `a` — 1/SHA-1 или 2/SHA-256;
- `d` — 6 или 8;
- `p` — period seconds.

Ограничения watch receiver: не более 100 записей; transfer ID и UUID имеют длину
1–64 символа; display name — 1–128 символов; secret — 1–1024 байта, каждый элемент
в диапазоне 0–255. SHA-256 передаётся как строка из 64 символов. Нарушение границ
отклоняется до записи в staging.

SHA-256 считается по записям в порядке chunks. Для каждой строки `i`, `n`, decimal `a`, `d`, `p` добавляются UTF-8 bytes и нулевой байт-разделитель; затем secret bytes и нулевой байт.

Часы не меняют active snapshot до валидного commit. Entries, revision и transfer ID
находятся в одном объекте `Application.Storage` и переключаются одной записью.
Staging также хранится одним объектом и удаляется после terminal error, успешного
commit, нового валидного BEGIN или команды очистки. Revision монотонна; меньшая
revision отклоняется. Повтор snapshot той же revision допустим.
