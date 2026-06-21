# Sync protocol v1

Garmin Mobile SDK передаёт словари со строковыми короткими ключами. Транспорт не шифруется приложением.

Последовательность полного snapshot:

1. Begin: `{"t":"b","v":1,"x":transferId,"r":revision,"n":count,"h":sha256}`.
2. Для каждой записи по порядку chunk: `{"t":"c","x":transferId,"q":sequence,"e":entry}`.
3. Commit: `{"t":"m","x":transferId}`.
4. Часы отвечают ACK `{"t":"a","r":revision}` или error `{"t":"e","m":message}`.

Entry:

- `i` — UUID string;
- `n` — display name;
- `s` — массив unsigned secret bytes;
- `a` — 1/SHA-1 или 2/SHA-256;
- `d` — 6 или 8;
- `p` — period seconds.

SHA-256 считается по записям в порядке chunks. Для каждой строки `i`, `n`, decimal `a`, `d`, `p` добавляются UTF-8 bytes и нулевой байт-разделитель; затем secret bytes и нулевой байт.

Часы не меняют active snapshot до валидного commit. Revision монотонна; меньшая revision отклоняется. Повтор snapshot той же revision допустим.

