import Toybox.Lang;
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
