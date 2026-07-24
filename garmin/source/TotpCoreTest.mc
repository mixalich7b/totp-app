import Toybox.Lang;
import Toybox.Application;
import Toybox.Test;

function clearTestStorage() as Void {
    var keys = ["active_snapshot", "favorite", "staging_snapshot",
        "active", "revision", "staging", "staging_id", "staging_count",
        "staging_revision", "staging_hash", "last_transfer"];
    for (var index = 0; index < keys.size(); index++) {
        Application.Storage.deleteValue(keys[index]);
    }
}

function testEntry(id as String, name as String, secret as Lang.Array) as Lang.Dictionary {
    return {"i" => id, "n" => name, "s" => secret, "a" => 1, "d" => 6, "p" => 30};
}

class CountingTotpCore extends TotpCore {
    private var _generateCalls = 0;

    public function initialize() {
        TotpCore.initialize();
    }

    public function generate(
            entry as Lang.Dictionary<String, Object>,
            unixSeconds as Lang.Number) {
        _generateCalls++;
        return _generateCalls.format("%06d");
    }

    public function generateCalls() {
        return _generateCalls;
    }
}

(:test)
function testRfc6238Sha1(logger as Test.Logger) as Boolean {
    var entry = {
        "s" => [49,50,51,52,53,54,55,56,57,48,49,50,51,52,53,54,55,56,57,48],
        "a" => 1, "d" => 8, "p" => 30
    };
    return (new TotpCore()).generate(entry, 59).equals("94287082");
}

(:test)
function testRfc6238Sha256(logger as Test.Logger) as Boolean {
    var entry = {
        "s" => [49,50,51,52,53,54,55,56,57,48,49,50,51,52,53,54,55,56,57,48,
            49,50,51,52,53,54,55,56,57,48,49,50],
        "a" => 2, "d" => 8, "p" => 30
    };
    return (new TotpCore()).generate(entry, 59).equals("46119246");
}

(:test)
function testSnapshotHash(logger as Test.Logger) as Boolean {
    var entry = {
        "i" => "00000000-0000-0000-0000-000000000001", "n" => "Test",
        "s" => [15,216,65,12,32,247,37,237,25,220], "a" => 1, "d" => 6, "p" => 30
    };
    return (new TotpCore()).snapshotHash([entry])
        .equals("0e5a08fb440ef9e732a896ea987e594250137596428a44fdd4d9b15b58bb327c");
}

(:test)
function testSyncMessageTypeRepresentations(logger as Test.Logger) as Boolean {
    return syncMessageType("b").equals("b")
        && syncMessageType('c').equals("c")
        && syncMessageType(:m).equals("m")
        && syncMessageType('d').equals("d");
}

(:test)
function testSelectionClampAfterSnapshotShrink(logger as Test.Logger) as Boolean {
    return totpClampSelection(4, 2) == 1
        && totpClampSelection(-1, 2) == 0
        && totpClampSelection(1, 2) == 1
        && totpClampSelection(3, 0) == 0;
}

(:test)
function testCorruptActiveStorageIsDetected(logger as Test.Logger) as Boolean {
    clearTestStorage();
    Application.Storage.setValue("active_snapshot", {
        "e" => [testEntry("bad", "Bad", [999])], "r" => 1, "x" => "tx"
    });
    var totpCore = new TotpCore();
    var corrupt = (new TotpStore(totpCore)).isCorrupt();
    clearTestStorage();
    return corrupt;
}

(:test)
function testStorageCacheRefreshesOnlyWhenRequested(logger as Test.Logger) as Boolean {
    clearTestStorage();
    Application.Storage.setValue("active_snapshot", {
        "e" => [testEntry("first", "First", [1])], "r" => 1, "x" => "tx-first"
    });
    Application.Storage.setValue("favorite", "first");
    var store = new TotpStore(new TotpCore());
    var initialEntries = store.entries();
    var initialFavorite = store.favoriteId();

    Application.Storage.setValue("active_snapshot", {
        "e" => [testEntry("second", "Second", [2])], "r" => 2, "x" => "tx-second"
    });
    Application.Storage.setValue("favorite", "second");
    var cachedEntries = store.entries();
    var cachedFavorite = store.favoriteId();
    store.refreshStorage();
    var refreshedEntries = store.entries();
    var refreshedFavorite = store.favoriteId();

    Application.Storage.setValue("active_snapshot", {
        "e" => [testEntry("bad", "Bad", [999])], "r" => 3, "x" => "tx-bad"
    });
    var validUntilRefresh = !store.isCorrupt();
    store.refreshStorage();
    var corruptAfterRefresh = store.isCorrupt();
    Application.Storage.deleteValue("active_snapshot");
    store.refreshStorage();
    var emptyAfterClear = !store.isCorrupt() && store.entries().size() == 0;

    var passed = initialEntries.size() == 1
        && totpValuesEqual(initialEntries[0]["i"], "first")
        && totpValuesEqual(initialFavorite, "first")
        && totpValuesEqual(cachedEntries[0]["i"], "first")
        && totpValuesEqual(cachedFavorite, "first")
        && refreshedEntries.size() == 1
        && totpValuesEqual(refreshedEntries[0]["i"], "second")
        && totpValuesEqual(refreshedFavorite, "second")
        && validUntilRefresh && corruptAfterRefresh && emptyAfterClear;
    clearTestStorage();
    return passed;
}

(:test)
function testCodeCacheUsesTotpTimeStep(logger as Test.Logger) as Boolean {
    var core = new CountingTotpCore();
    var store = new TotpStore(core);
    var entry = testEntry("cached", "Cached", [1]);
    var first = store.codeFor(entry, 30);
    var sameStep = store.codeFor(entry, 59);
    var nextStep = store.codeFor(entry, 60);
    var afterClockRollback = store.codeFor(entry, 59);
    return core.generateCalls() == 3
        && first.equals(sameStep)
        && !nextStep.equals(sameStep)
        && !afterClockRollback.equals(nextStep);
}

(:test)
function testStorageRefreshPreservesOrInvalidatesCodeCache(logger as Test.Logger) as Boolean {
    clearTestStorage();
    var initial = testEntry("cached", "Initial", [1]);
    Application.Storage.setValue("active_snapshot", {
        "e" => [initial], "r" => 1, "x" => "tx-initial"
    });
    var core = new CountingTotpCore();
    var store = new TotpStore(core);
    var loaded = store.entries();
    var firstCode = store.codeFor(loaded[0], 30);

    Application.Storage.setValue("staging_snapshot", {
        "e" => [], "r" => 2, "x" => "tx-staging", "n" => 1, "h" => "hash"
    });
    store.refreshStorage();
    var codeAfterStagingChange = store.codeFor(store.entries()[0], 30);

    var replacement = testEntry("cached", "Replacement", [2]);
    Application.Storage.setValue("active_snapshot", {
        "e" => [replacement], "r" => 2, "x" => "tx-replacement"
    });
    store.refreshStorage();
    var codeAfterCommit = store.codeFor(store.entries()[0], 30);

    var passed = core.generateCalls() == 2
        && firstCode.equals(codeAfterStagingChange)
        && !codeAfterCommit.equals(codeAfterStagingChange)
        && totpValuesEqual(store.entries()[0]["n"], "Replacement");
    clearTestStorage();
    return passed;
}

(:test)
function testProtocolClearRemovesAllStorage(logger as Test.Logger) as Boolean {
    clearTestStorage();
    Application.Storage.setValue("active_snapshot", {"e" => [testEntry("a", "A", [1])],
        "r" => 1, "x" => "tx-active"});
    Application.Storage.setValue("favorite", "a");
    Application.Storage.setValue("staging_snapshot", {"e" => [], "r" => 2,
        "x" => "tx-staged", "n" => 1, "h" => "hash"});

    var totpCore = new TotpCore();
    (new TotpStore(totpCore)).clearAll();
    return Application.Storage.getValue("active_snapshot") == null
        && Application.Storage.getValue("favorite") == null
        && Application.Storage.getValue("staging_snapshot") == null;
}

(:test)
function testLocalStorageWritesUpdateCacheImmediately(logger as Test.Logger) as Boolean {
    clearTestStorage();
    Application.Storage.setValue("active_snapshot", {
        "e" => [testEntry("first", "First", [1]), testEntry("second", "Second", [2])],
        "r" => 1, "x" => "tx-active"
    });
    Application.Storage.setValue("favorite", "first");
    var store = new TotpStore(new TotpCore());
    var loaded = store.entries().size() == 2
        && totpValuesEqual(store.favoriteId(), "first");
    store.setFavorite("second");
    var favoriteUpdated = totpValuesEqual(store.favoriteId(), "second");
    store.clearAll();
    var cacheCleared = store.entries().size() == 0
        && store.favoriteId() == null
        && !store.isCorrupt();
    var storageCleared = Application.Storage.getValue("active_snapshot") == null
        && Application.Storage.getValue("favorite") == null;
    clearTestStorage();
    return loaded && favoriteUpdated && cacheCleared && storageCleared;
}

(:test)
function testProtocolCommitIsAtomicAndIdempotent(logger as Test.Logger) as Boolean {
    clearTestStorage();
    var entry = testEntry("protocol-entry", "Test", [1, 2, 3, 4]);
    var totpCore = new TotpCore();
    var hash = totpCore.snapshotHash([entry]);
    var store = new TotpStore(totpCore);
    var beginResult = store.begin({"v" => 1, "x" => "tx-valid", "r" => 7, "n" => 1, "h" => hash});
    var chunkResult = store.chunk({"x" => "tx-valid", "q" => 0, "e" => entry});
    var commitResult = store.commit({"x" => "tx-valid"});
    var repeatedResult = store.commit({"x" => "tx-valid"});
    var active = Application.Storage.getValue("active_snapshot");
    var passed = true;
    if (beginResult != null) { passed = false; }
    if (chunkResult != null) { passed = false; }
    if (commitResult != 7 || repeatedResult != 7) { passed = false; }
    if (!(active instanceof Lang.Dictionary)) {
        passed = false;
    } else {
        var activeDictionary = active as Lang.Dictionary;
        if (activeDictionary["r"] != 7) { passed = false; }
        if (!totpValuesEqual(activeDictionary["x"], "tx-valid")) { passed = false; }
        if (!((activeDictionary["e"] as Lang.Array).size() == 1)) { passed = false; }
    }
    if (Application.Storage.getValue("staging_snapshot") != null) { passed = false; }
    clearTestStorage();
    return passed;
}

(:test)
function testProtocolErrorsClearStagingAndPreserveActive(logger as Test.Logger) as Boolean {
    clearTestStorage();
    var oldEntry = testEntry("old", "Old", [1]);
    var entry = testEntry("new", "New", [2]);
    var core = new TotpCore();
    var store = new TotpStore(core);
    store.begin({"v" => 1, "x" => "old-tx", "r" => 10, "n" => 1,
        "h" => core.snapshotHash([oldEntry])});
    store.chunk({"x" => "old-tx", "q" => 0, "e" => oldEntry});
    store.commit({"x" => "old-tx"});

    store.begin({"v" => 1, "x" => "bad-sequence", "r" => 11, "n" => 1,
        "h" => core.snapshotHash([entry])});
    var sequenceError = store.chunk({"x" => "bad-sequence", "q" => 1, "e" => entry});
    var clearedAfterSequence = Application.Storage.getValue("staging_snapshot") == null;

    store.begin({"v" => 1, "x" => "bad-hash", "r" => 11, "n" => 1,
        "h" => "0000000000000000000000000000000000000000000000000000000000000000"});
    store.chunk({"x" => "bad-hash", "q" => 0, "e" => entry});
    var checksumError = store.commit({"x" => "bad-hash"});
    var active = Application.Storage.getValue("active_snapshot") as Lang.Dictionary;
    var activeEntries = active["e"] as Lang.Array;
    var activeEntry = activeEntries[0] as Lang.Dictionary;
    var passed = true;
    if (!sequenceError.equals("Wrong sequence")) { passed = false; }
    if (!clearedAfterSequence) { passed = false; }
    if (!checksumError.equals("Checksum mismatch")) { passed = false; }
    if (Application.Storage.getValue("staging_snapshot") != null) { passed = false; }
    if (active["r"] != 10) { passed = false; }
    if (!totpValuesEqual(activeEntry["i"], "old")) { passed = false; }
    clearTestStorage();
    return passed;
}

(:test)
function testProtocolRejectsInvalidBoundsAndByteValues(logger as Test.Logger) as Boolean {
    clearTestStorage();
    var core = new TotpCore();
    var store = new TotpStore(core);
    var hash = "0000000000000000000000000000000000000000000000000000000000000000";
    var tooMany = store.begin({"v" => 1, "x" => "tx-many", "r" => 1, "n" => 101, "h" => hash});
    var invalidHash = store.begin({"v" => 1, "x" => "tx-hash", "r" => 1, "n" => 0, "h" => "short"});
    var begin = store.begin({"v" => 1, "x" => "tx-byte", "r" => 1, "n" => 1, "h" => hash});
    var invalidByte = store.chunk({"x" => "tx-byte", "q" => 0,
        "e" => testEntry("entry", "Test", [256])});
    var passed = true;
    if (!tooMany.equals("Invalid begin")) { passed = false; }
    if (!invalidHash.equals("Invalid begin")) { passed = false; }
    if (begin != null) { passed = false; }
    if (!invalidByte.equals("Invalid record")) { passed = false; }
    if (Application.Storage.getValue("staging_snapshot") != null) { passed = false; }
    clearTestStorage();
    return passed;
}

(:test)
function testNewBeginReplacesAbandonedStaging(logger as Test.Logger) as Boolean {
    clearTestStorage();
    var core = new TotpCore();
    var store = new TotpStore(core);
    var hash = core.snapshotHash([]);
    store.begin({"v" => 1, "x" => "abandoned", "r" => 1, "n" => 0, "h" => hash});
    var result = store.begin({"v" => 1, "x" => "replacement", "r" => 2, "n" => 0, "h" => hash});
    var staging = Application.Storage.getValue("staging_snapshot") as Lang.Dictionary;
    var passed = true;
    if (result != null) { passed = false; }
    if (!totpValuesEqual(staging["x"], "replacement")) { passed = false; }
    if (staging["r"] != 2) { passed = false; }
    clearTestStorage();
    return passed;
}

(:test)
function testProtocolRecoversWithCompleteRetry(logger as Test.Logger) as Boolean {
    clearTestStorage();
    var first = testEntry("first", "First", [2]);
    var second = {"i" => "second", "n" => "Second", "s" => [3], "a" => 2, "d" => 8, "p" => 60};
    var entries = [first, second];
    var core = new TotpCore();
    var store = new TotpStore(core);
    var hash = core.snapshotHash(entries);
    store.begin({"v" => 1, "x" => "partial", "r" => 11, "n" => 2, "h" => hash});
    store.chunk({"x" => "partial", "q" => 0, "e" => first});
    var incomplete = store.commit({"x" => "partial"});
    store.begin({"v" => 1, "x" => "retry", "r" => 11, "n" => 2, "h" => hash});
    var firstResult = store.chunk({"x" => "retry", "q" => 0, "e" => first});
    var secondResult = store.chunk({"x" => "retry", "q" => 1, "e" => second});
    var commit = store.commit({"x" => "retry"});
    var active = store.entries();
    var passed = true;
    if (!incomplete.equals("Incomplete snapshot")) { passed = false; }
    if (firstResult != null || secondResult != null) { passed = false; }
    if (commit != 11 || active.size() != 2) { passed = false; }
    if (!totpValuesEqual(active[1]["i"], "second")) { passed = false; }
    clearTestStorage();
    return passed;
}
