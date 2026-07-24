import Toybox.Application;
import Toybox.Lang;

(:glance, :background)
function totpValuesEqual(left, right) as Lang.Boolean {
    return left == null ? right == null : left.equals(right);
}

(:glance, :background)
class TotpStore {
    private const ACTIVE_SNAPSHOT = "active_snapshot";
    private const FAVORITE = "favorite";
    private const STAGING_SNAPSHOT = "staging_snapshot";
    private const MAX_ENTRIES = 100;
    private const MAX_TRANSFER_ID_LENGTH = 64;
    private const MAX_ENTRY_ID_LENGTH = 64;
    private const MAX_NAME_LENGTH = 128;
    private const MAX_SECRET_LENGTH = 1024;
    private var _totpCore as TotpCore;
    private var _activeLoaded = false;
    private var _activeSnapshot as Null or Lang.Dictionary = null;
    private var _entries as Lang.Array<Lang.Dictionary<String, Object>> = [];
    private var _corrupt = false;
    private var _favoriteLoaded = false;
    private var _favoriteId as Null or String = null;
    private var _codeCache as Lang.Dictionary<String, Lang.Dictionary> = {};

    public function initialize(totpCore as TotpCore) {
        _totpCore = totpCore;
    }

    (:glance, :background)
    private function activeSnapshot() as Null or Lang.Dictionary {
        ensureActiveLoaded();
        return _activeSnapshot;
    }

    (:glance)
    public function entries() as Lang.Array<Lang.Dictionary<String, Object>> {
        ensureActiveLoaded();
        return _entries;
    }

    (:glance)
    public function isCorrupt() {
        ensureActiveLoaded();
        return _corrupt;
    }

    (:glance, :background)
    public function favoriteId() as Null or String {
        ensureFavoriteLoaded();
        return _favoriteId;
    }

    (:glance)
    public function favorite() as Null or Lang.Dictionary<String, Object> {
        var id = favoriteId();
        var all = entries();
        for (var index = 0; index < all.size(); index++) {
            if (totpValuesEqual(all[index]["i"], id)) {
                return all[index];
            }
        }
        return all.size() > 0 ? all[0] : null;
    }

    (:background)
    public function setFavorite(id as String) {
        Application.Storage.setValue(FAVORITE, id);
        _favoriteId = id;
        _favoriteLoaded = true;
    }

    (:background)
    public function clearAll() {
        clearStaging();
        Application.Storage.deleteValue(ACTIVE_SNAPSHOT);
        Application.Storage.deleteValue(FAVORITE);
        adoptActive(null, false);
        _favoriteId = null;
        _favoriteLoaded = true;
    }

    (:glance, :background)
    public function refreshStorage() {
        adoptActive(Application.Storage.getValue(ACTIVE_SNAPSHOT), true);
        adoptFavorite(Application.Storage.getValue(FAVORITE));
    }

    (:glance)
    public function codeFor(entry as Lang.Dictionary<String, Object>, unixSeconds as Lang.Number) {
        var period = entry["p"] as Number;
        var counter = (unixSeconds / period).toLong();
        var id = entry["i"] as String;
        var cached = _codeCache[id];
        if (cached instanceof Lang.Dictionary
                && cached["c"] == counter
                && cached["v"] instanceof Lang.String) {
            return cached["v"];
        }
        var code = _totpCore.generate(entry, unixSeconds);
        _codeCache[id] = {"c" => counter, "v" => code};
        return code;
    }

    (:background)
    public function begin(message as Lang.Dictionary) {
        if (message == null || message["v"] != 1) {
            return "Unsupported protocol";
        }
        var transferId = message["x"];
        var revision = message["r"];
        var count = message["n"];
        var hash = message["h"];
        if (!validString(transferId, 1, MAX_TRANSFER_ID_LENGTH)
                || !validString(hash, 64, 64)
                || !(revision instanceof Number || revision instanceof Long)
                || !(count instanceof Number || count instanceof Long)
                || revision < 0 || count < 0 || count > MAX_ENTRIES) {
            return "Invalid begin";
        }
        var active = activeSnapshot();
        if (active != null && (active["r"] instanceof Number || active["r"] instanceof Long)
                && revision < active["r"]) {
            return "Stale revision";
        }
        clearStaging();
        Application.Storage.setValue(STAGING_SNAPSHOT, {
            "x" => transferId,
            "r" => revision,
            "n" => count,
            "h" => hash,
            "e" => []
        });
        return null;
    }

    (:background)
    public function chunk(message as Lang.Dictionary) {
        var staging = Application.Storage.getValue(STAGING_SNAPSHOT);
        if (!(staging instanceof Lang.Dictionary)
                || !totpValuesEqual(message == null ? null : message["x"], staging["x"])) {
            return "Wrong transfer";
        }
        var entries = staging["e"];
        var sequence = message["q"];
        if (!(entries instanceof Lang.Array)
                || !(sequence instanceof Number || sequence instanceof Long)
                || sequence != entries.size() || sequence >= staging["n"]) {
            return failStaging("Wrong sequence");
        }
        var entry = message["e"] as Lang.Dictionary;
        if (!validEntry(entry)) {
            return failStaging("Invalid record");
        }
        var period = entry["p"] as Number;
        if ((entry["a"] != 1 && entry["a"] != 2)
                || (entry["d"] != 6 && entry["d"] != 8)
                || period < 5 || period > 300) {
            return failStaging("Unsupported TOTP");
        }
        entries.add(entry);
        staging["e"] = entries;
        Application.Storage.setValue(STAGING_SNAPSHOT, staging);
        return null;
    }

    (:background)
    public function commit(message as Lang.Dictionary) {
        var transferId = message == null ? null : message["x"];
        var staging = Application.Storage.getValue(STAGING_SNAPSHOT);
        if (!(staging instanceof Lang.Dictionary) || !totpValuesEqual(transferId, staging["x"])) {
            var active = activeSnapshot();
            if (active != null && totpValuesEqual(transferId, active["x"])) {
                return active["r"];
            }
            return "Wrong transfer";
        }
        var entries = staging["e"];
        if (!(entries instanceof Lang.Array) || entries.size() != staging["n"]) {
            return failStaging("Incomplete snapshot");
        }
        if (!totpValuesEqual(_totpCore.snapshotHash(entries), staging["h"])) {
            return failStaging("Checksum mismatch");
        }

        var revision = staging["r"];
        // entries, revision and last transfer become visible with one storage write.
        var active = {
            "e" => entries,
            "r" => revision,
            "x" => transferId
        };
        Application.Storage.setValue(ACTIVE_SNAPSHOT, active);
        adoptActive(active, false);

        var favorite = favoriteId();
        var found = false;
        for (var index = 0; index < entries.size(); index++) {
            var record = entries[index] as Lang.Dictionary;
            found = found || totpValuesEqual(record["i"], favorite);
        }
        if (!found) {
            if (entries.size() > 0) {
                var first = entries[0] as Lang.Dictionary;
                setFavorite(first["i"]);
            } else {
                Application.Storage.deleteValue(FAVORITE);
                _favoriteId = null;
                _favoriteLoaded = true;
            }
        }
        clearStaging();
        return revision;
    }

    (:glance, :background)
    private function ensureActiveLoaded() {
        if (!_activeLoaded) {
            adoptActive(Application.Storage.getValue(ACTIVE_SNAPSHOT), false);
        }
    }

    (:glance, :background)
    private function ensureFavoriteLoaded() {
        if (!_favoriteLoaded) {
            adoptFavorite(Application.Storage.getValue(FAVORITE));
        }
    }

    (:glance, :background)
    private function adoptActive(raw, preserveIfSame) {
        var nextSnapshot = null;
        var nextEntries = [];
        var nextCorrupt = false;
        if (raw != null) {
            if (validSnapshot(raw)) {
                nextSnapshot = raw as Lang.Dictionary;
                nextEntries = nextSnapshot["e"] as Lang.Array;
            } else {
                nextCorrupt = true;
            }
        }

        var same = preserveIfSame && _activeLoaded
            && _corrupt == nextCorrupt
            && sameSnapshotIdentity(_activeSnapshot, nextSnapshot);
        if (!same) {
            _activeSnapshot = nextSnapshot;
            _entries = nextEntries;
            _corrupt = nextCorrupt;
            _codeCache = {};
        }
        _activeLoaded = true;
    }

    (:glance, :background)
    private function adoptFavorite(raw) {
        _favoriteId = raw instanceof Lang.String ? raw : null;
        _favoriteLoaded = true;
    }

    (:glance, :background)
    private function sameSnapshotIdentity(
            left as Null or Lang.Dictionary,
            right as Null or Lang.Dictionary) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return totpValuesEqual(left["r"], right["r"])
            && totpValuesEqual(left["x"], right["x"]);
    }

    (:glance, :background)
    private function validSnapshot(raw) {
        if (!(raw instanceof Lang.Dictionary)
                || !(raw["e"] instanceof Lang.Array)
                || !(raw["r"] instanceof Number || raw["r"] instanceof Long)
                || !(raw["x"] instanceof String)) {
            return false;
        }
        var storedEntries = raw["e"] as Lang.Array;
        for (var index = 0; index < storedEntries.size(); index++) {
            if (!validEntry(storedEntries[index])) { return false; }
        }
        return true;
    }

    (:glance, :background)
    private function validEntry(entry) {
        if (!(entry instanceof Lang.Dictionary)
                || !validString(entry["i"], 1, MAX_ENTRY_ID_LENGTH)
                || !validString(entry["n"], 1, MAX_NAME_LENGTH)
                || !(entry["s"] instanceof Lang.Array)
                || entry["s"].size() < 1 || entry["s"].size() > MAX_SECRET_LENGTH
                || !(entry["a"] instanceof Number || entry["a"] instanceof Long)
                || !(entry["d"] instanceof Number || entry["d"] instanceof Long)
                || !(entry["p"] instanceof Number || entry["p"] instanceof Long)) {
            return false;
        }
        var secret = entry["s"] as Lang.Array;
        for (var index = 0; index < secret.size(); index++) {
            var value = secret[index];
            if (!(value instanceof Number || value instanceof Long) || value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    (:glance, :background)
    private function validString(value, minimum, maximum) {
        return value instanceof String && value.length() >= minimum && value.length() <= maximum;
    }

    (:background)
    private function failStaging(error) {
        clearStaging();
        return error;
    }

    (:background)
    private function clearStaging() {
        Application.Storage.deleteValue(STAGING_SNAPSHOT);
    }
}
