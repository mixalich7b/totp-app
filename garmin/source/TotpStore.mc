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

    public function initialize(totpCore as TotpCore) {
        _totpCore = totpCore;
    }

    (:glance, :background)
    private function activeSnapshot() as Null or Lang.Dictionary {
        var value = Application.Storage.getValue(ACTIVE_SNAPSHOT);
        return value instanceof Lang.Dictionary ? value : null;
    }

    (:glance)
    public function entries() as Lang.Array<Lang.Dictionary<String, Object>> {
        var snapshot = activeSnapshot();
        if (snapshot == null || !(snapshot["e"] instanceof Lang.Array)) {
            return [];
        }
        var storedEntries = snapshot["e"] as Lang.Array;
        for (var index = 0; index < storedEntries.size(); index++) {
            if (!validEntry(storedEntries[index])) { return []; }
        }
        return storedEntries;
    }

    (:glance)
    public function isCorrupt() {
        var raw = Application.Storage.getValue(ACTIVE_SNAPSHOT);
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof Lang.Dictionary)) {
            return true;
        }
        if (!(raw["e"] instanceof Lang.Array)
                || !(raw["r"] instanceof Number || raw["r"] instanceof Long)
                || !(raw["x"] instanceof String)) {
            return true;
        }
        var storedEntries = raw["e"] as Lang.Array;
        for (var index = 0; index < storedEntries.size(); index++) {
            if (!validEntry(storedEntries[index])) { return true; }
        }
        return false;
    }

    (:glance, :background)
    public function favoriteId() as Null or String {
        var favouriteId = Application.Storage.getValue(FAVORITE);
        if (favouriteId == null || !(favouriteId instanceof Lang.String)) {
            return null;
        }
        return favouriteId;
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
    }

    (:background)
    public function clearAll() {
        clearStaging();
        Application.Storage.deleteValue(ACTIVE_SNAPSHOT);
        Application.Storage.deleteValue(FAVORITE);
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
        Application.Storage.setValue(ACTIVE_SNAPSHOT, {
            "e" => entries,
            "r" => revision,
            "x" => transferId
        });

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
            }
        }
        clearStaging();
        return revision;
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
