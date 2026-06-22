import Toybox.Application;
import Toybox.Lang;

(:glance, :background)
function totpValuesEqual(left, right) {
    return left == null ? right == null : left.equals(right);
}

(:glance, :background)
class TotpStore {
    private const ACTIVE = "active";
    private const REVISION = "revision";
    private const FAVORITE = "favorite";
    private const STAGING = "staging";
    private const STAGING_ID = "staging_id";
    private const STAGING_COUNT = "staging_count";
    private const STAGING_REVISION = "staging_revision";
    private const STAGING_HASH = "staging_hash";
    private const LAST_TRANSFER = "last_transfer";

    (:glance)
    public function entries() {
        var value = Application.Storage.getValue(ACTIVE);
        return value == null ? [] : value;
    }

    (:glance, :background)
    public function favoriteId() {
        return Application.Storage.getValue(FAVORITE);
    }

    (:glance)
    public function favorite() {
        var all = entries();
        var id = favoriteId();
        for (var index = 0; index < all.size(); index++) {
            if (totpValuesEqual(all[index]["i"], id)) {
                return all[index];
            }
        }
        return all.size() > 0 ? all[0] : null;
    }

    (:background)
    public function setFavorite(id) {
        Application.Storage.setValue(FAVORITE, id);
    }

    (:background)
    public function clearAll() {
        clearStaging();
        Application.Storage.deleteValue(ACTIVE);
        Application.Storage.deleteValue(FAVORITE);
        Application.Storage.deleteValue(REVISION);
        Application.Storage.deleteValue(LAST_TRANSFER);
    }

    (:background)
    public function begin(message) {
        if (message["v"] != 1) {
            return "Unsupported protocol";
        }
        var revision = message["r"];
        var count = message["n"];
        if (message["x"] == null || message["h"] == null
                || !(revision instanceof Number || revision instanceof Long)
                || !(count instanceof Number || count instanceof Long)) {
            return "Invalid begin";
        }
        if (revision < 0 || count < 0) {
            return "Invalid begin";
        }
        var current = Application.Storage.getValue(REVISION);
        if (current != null && message["r"] < current) {
            return "Stale revision";
        }
        Application.Storage.setValue(STAGING, []);
        Application.Storage.setValue(STAGING_ID, message["x"]);
        Application.Storage.setValue(STAGING_COUNT, message["n"]);
        Application.Storage.setValue(STAGING_REVISION, message["r"]);
        Application.Storage.setValue(STAGING_HASH, message["h"]);
        return null;
    }

    (:background)
    public function chunk(message) {
        if (!totpValuesEqual(message["x"], Application.Storage.getValue(STAGING_ID))) {
            return "Wrong transfer";
        }
        var staging = Application.Storage.getValue(STAGING);
        if (staging == null || message["q"] != staging.size()) {
            return "Wrong sequence";
        }
        var entry = message["e"];
        if (entry == null || entry["i"] == null || entry["n"] == null || entry["s"] == null
                || !(entry["s"] instanceof Lang.Array) || entry["s"].size() == 0) {
            return "Invalid record";
        }
        var period = entry["p"];
        if (!(period instanceof Number || period instanceof Long)) {
            return "Unsupported TOTP";
        }
        if ((entry["a"] != 1 && entry["a"] != 2) || (entry["d"] != 6 && entry["d"] != 8)
                || period < 5 || period > 300) {
            return "Unsupported TOTP";
        }
        staging.add(entry);
        Application.Storage.setValue(STAGING, staging);
        return null;
    }

    (:background)
    public function commit(message) {
        if (!totpValuesEqual(message["x"], Application.Storage.getValue(STAGING_ID))) {
            if (totpValuesEqual(message["x"], Application.Storage.getValue(LAST_TRANSFER))) {
                return Application.Storage.getValue(REVISION);
            }
            return "Wrong transfer";
        }
        var staging = Application.Storage.getValue(STAGING);
        if (staging == null || staging.size() != Application.Storage.getValue(STAGING_COUNT)) {
            return "Incomplete snapshot";
        }
        if (!totpValuesEqual((new TotpCore()).snapshotHash(staging), Application.Storage.getValue(STAGING_HASH))) {
            return "Checksum mismatch";
        }
        var revision = Application.Storage.getValue(STAGING_REVISION);
        Application.Storage.setValue(ACTIVE, staging);
        Application.Storage.setValue(REVISION, revision);
        Application.Storage.setValue(LAST_TRANSFER, message["x"]);

        var favorite = favoriteId();
        var found = false;
        for (var index = 0; index < staging.size(); index++) {
            var record = staging[index] as Lang.Dictionary;
            found = found || totpValuesEqual(record["i"], favorite);
        }
        if (!found) {
            if (staging.size() > 0) {
                var first = staging[0] as Lang.Dictionary;
                setFavorite(first["i"]);
            } else {
                Application.Storage.deleteValue(FAVORITE);
            }
        }
        clearStaging();
        return revision;
    }

    (:background)
    private function clearStaging() {
        Application.Storage.deleteValue(STAGING);
        Application.Storage.deleteValue(STAGING_ID);
        Application.Storage.deleteValue(STAGING_COUNT);
        Application.Storage.deleteValue(STAGING_REVISION);
        Application.Storage.deleteValue(STAGING_HASH);
    }
}
