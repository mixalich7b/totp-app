# Sync protocol v1

Garmin Mobile SDK передаёт словари со строковыми короткими ключами. Транспорт не шифруется приложением.

Последовательность полного snapshot:

1. Begin: `{"t":"b","v":1,"x":transferId,"r":revision,"n":count,"h":sha256}`.
2. Для каждой записи по порядку chunk: `{"t":"c","x":transferId,"q":sequence,"e":entry}`.
3. Commit: `{"t":"m","x":transferId}`.
4. Часы отвечают ACK `{"t":"a","r":revision}` или error `{"t":"e","m":message}`.

Ответы новой реализации также содержат `x` исходного transfer ID. Android принимает
ответ без `x` для совместимости со старой v1 watch app, но игнорирует ответ с `x`,
который не совпадает с активной операцией.

Подтверждаемая пользователем очистка данных часов использует отдельную команду
`{"t":"d","v":1,"x":transferId}`. Часы удаляют active snapshot, favorite,
revision, незавершённый staging и метаданные последней передачи, затем отвечают
`{"t":"z","x":transferId}`. Android-записи команда не изменяет. Следующая обычная
синхронизация снова заполнит часы полным snapshot.

Entry:

- `i` — UUID string;
- `n` — display name;
- `s` — массив unsigned secret bytes;
- `a` — 1/SHA-1 или 2/SHA-256;
- `d` — 6 или 8;
- `p` — period seconds.

SHA-256 считается по записям в порядке chunks. Для каждой строки `i`, `n`, decimal `a`, `d`, `p` добавляются UTF-8 bytes и нулевой байт-разделитель; затем secret bytes и нулевой байт.

Часы не меняют active snapshot до валидного commit. Revision монотонна; меньшая revision отклоняется. Повтор snapshot той же revision допустим.
