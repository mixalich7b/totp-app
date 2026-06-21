import Toybox.Lang;
import Toybox.Application;
import Toybox.Test;

(:test)
function testRfc6238Sha1(logger as Test.Logger) as Boolean {
    var entry = {
        "s" => [49,50,51,52,53,54,55,56,57,48,49,50,51,52,53,54,55,56,57,48],
        "a" => 1,
        "d" => 8,
        "p" => 30
    };
    var actual = (new TotpCore()).generate(entry, 59);
    logger.debug("SHA1 vector result: " + actual);
    return actual.equals("94287082");
}

(:test)
function testRfc6238Sha256(logger as Test.Logger) as Boolean {
    var entry = {
        "s" => [49,50,51,52,53,54,55,56,57,48,49,50,51,52,53,54,55,56,57,48,49,50,51,52,53,54,55,56,57,48,49,50],
        "a" => 2,
        "d" => 8,
        "p" => 30
    };
    var actual = (new TotpCore()).generate(entry, 59);
    logger.debug("SHA256 vector result: " + actual);
    return actual.equals("46119246");
}

(:test)
function testSnapshotHash(logger as Test.Logger) as Boolean {
    var entry = {
        "i" => "00000000-0000-0000-0000-000000000001",
        "n" => "Test",
        "s" => [15,216,65,12,32,247,37,237,25,220],
        "a" => 1,
        "d" => 6,
        "p" => 30
    };
    var entries = [entry];
    var expectedHash = "0e5a08fb440ef9e732a896ea987e594250137596428a44fdd4d9b15b58bb327c";
    var actualHash = (new TotpCore()).snapshotHash(entries);
    logger.debug("Snapshot hash: " + actualHash);
    return actualHash.equals(expectedHash);
}

(:test)
function testSyncMessageTypeRepresentations(logger as Test.Logger) as Boolean {
    return syncMessageType("b").equals("b")
        && syncMessageType('c').equals("c")
        && syncMessageType(:m).equals("m");
}

(:test)
function testProtocolCommitAndIdempotence(logger as Test.Logger) as Boolean {
    var keys = ["active", "revision", "favorite", "staging", "staging_id", "staging_count",
        "staging_revision", "staging_hash", "last_transfer"];
    for (var keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
        Application.Storage.deleteValue(keys[keyIndex]);
    }
    var entry = {
        "i" => "protocol-entry",
        "n" => "Test",
        "s" => [1, 2, 3, 4],
        "a" => 1,
        "d" => 6,
        "p" => 30
    };
    var store = new TotpStore();
    var hash = (new TotpCore()).snapshotHash([entry]);
    var beginResult = store.begin({"v" => 1, "x" => "tx-valid", "r" => 7, "n" => 1, "h" => hash});
    var chunkResult = store.chunk({"x" => "tx-valid", "q" => 0, "e" => entry});
    var commitResult = store.commit({"x" => "tx-valid"});
    var repeatedResult = store.commit({"x" => "tx-valid"});
    var active = Application.Storage.getValue("active");
    var passed = true;
    if (beginResult != null) { passed = false; }
    if (chunkResult != null) { passed = false; }
    if (commitResult != 7) { passed = false; }
    if (repeatedResult != 7) { passed = false; }
    if (active == null) {
        passed = false;
    } else if ((active as Lang.Array).size() != 1) {
        passed = false;
    }
    for (var cleanupIndex = 0; cleanupIndex < keys.size(); cleanupIndex++) {
        Application.Storage.deleteValue(keys[cleanupIndex]);
    }
    return passed;
}

(:test)
function testProtocolRejectsSequenceTransferAndCount(logger as Test.Logger) as Boolean {
    var keys = ["active", "revision", "favorite", "staging", "staging_id", "staging_count",
        "staging_revision", "staging_hash", "last_transfer"];
    for (var keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
        Application.Storage.deleteValue(keys[keyIndex]);
    }
    var entry = {"i" => "entry", "n" => "Test", "s" => [1], "a" => 1, "d" => 6, "p" => 30};
    var store = new TotpStore();
    var hash = (new TotpCore()).snapshotHash([entry]);
    store.begin({"v" => 1, "x" => "tx-count", "r" => 1, "n" => 2, "h" => hash});
    var wrongSequence = store.chunk({"x" => "tx-count", "q" => 1, "e" => entry});
    var wrongTransfer = store.chunk({"x" => "other", "q" => 0, "e" => entry});
    var validChunk = store.chunk({"x" => "tx-count", "q" => 0, "e" => entry});
    var incomplete = store.commit({"x" => "tx-count"});
    var passed = true;
    if (!wrongSequence.equals("Wrong sequence")) { passed = false; }
    if (!wrongTransfer.equals("Wrong transfer")) { passed = false; }
    if (validChunk != null) { passed = false; }
    if (!incomplete.equals("Incomplete snapshot")) { passed = false; }
    if (Application.Storage.getValue("active") != null) { passed = false; }
    for (var cleanupIndex = 0; cleanupIndex < keys.size(); cleanupIndex++) {
        Application.Storage.deleteValue(keys[cleanupIndex]);
    }
    return passed;
}

(:test)
function testProtocolRejectsChecksumStaleRevisionAndInvalidEntry(logger as Test.Logger) as Boolean {
    var keys = ["active", "revision", "favorite", "staging", "staging_id", "staging_count",
        "staging_revision", "staging_hash", "last_transfer"];
    for (var keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
        Application.Storage.deleteValue(keys[keyIndex]);
    }
    var entry = {"i" => "entry", "n" => "Test", "s" => [1], "a" => 1, "d" => 6, "p" => 30};
    var invalidEntry = {"i" => "bad", "n" => "Bad", "s" => [], "a" => 1, "d" => 6, "p" => 30};
    var store = new TotpStore();
    store.begin({"v" => 1, "x" => "tx-hash", "r" => 5, "n" => 1, "h" => "bad"});
    var invalidRecord = store.chunk({"x" => "tx-hash", "q" => 0, "e" => invalidEntry});
    var validChunk = store.chunk({"x" => "tx-hash", "q" => 0, "e" => entry});
    var checksum = store.commit({"x" => "tx-hash"});
    Application.Storage.setValue("revision", 5);
    var stale = store.begin({"v" => 1, "x" => "tx-stale", "r" => 4, "n" => 0,
        "h" => (new TotpCore()).snapshotHash([])});
    var passed = true;
    if (!invalidRecord.equals("Invalid record")) { passed = false; }
    if (validChunk != null) { passed = false; }
    if (!checksum.equals("Checksum mismatch")) { passed = false; }
    if (!stale.equals("Stale revision")) { passed = false; }
    if (Application.Storage.getValue("active") != null) { passed = false; }
    for (var cleanupIndex = 0; cleanupIndex < keys.size(); cleanupIndex++) {
        Application.Storage.deleteValue(keys[cleanupIndex]);
    }
    return passed;
}
