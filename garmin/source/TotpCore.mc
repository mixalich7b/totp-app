import Toybox.Cryptography;
import Toybox.Lang;

(:glance, :background)
class TotpCore {
    (:glance)
    public function generate(entry, unixSeconds) {
        var secret = toBytes(entry["s"]);
        var period = entry["p"];
        var counter = (unixSeconds / period).toLong();
        var message = []b;
        for (var shift = 56; shift >= 0; shift -= 8) {
            message.add(((counter >> shift) & 0xff).toNumber());
        }

        var digest;
        if (entry["a"] == 2) {
            var hmac = new Cryptography.HashBasedMessageAuthenticationCode({
                :algorithm => Cryptography.HASH_SHA256,
                :key => secret
            });
            hmac.update(message);
            digest = hmac.digest();
        } else {
            digest = hmacSha1(secret, message);
        }
        var offset = digest[digest.size() - 1] & 0x0f;
        var binary = ((digest[offset] & 0x7f) << 24)
            | ((digest[offset + 1] & 0xff) << 16)
            | ((digest[offset + 2] & 0xff) << 8)
            | (digest[offset + 3] & 0xff);
        var digits = entry["d"];
        var modulus = digits == 8 ? 100000000 : 1000000;
        return (binary % modulus).format(digits == 8 ? "%08d" : "%06d");
    }

    private function hmacSha1(key, message) {
        if (key.size() > 64) {
            var keyHash = new Cryptography.Hash({:algorithm => Cryptography.HASH_SHA1});
            keyHash.update(key);
            key = keyHash.digest();
        }
        var innerPad = []b;
        var outerPad = []b;
        for (var index = 0; index < 64; index++) {
            var keyByte = index < key.size() ? key[index] : 0;
            innerPad.add(keyByte ^ 0x36);
            outerPad.add(keyByte ^ 0x5c);
        }
        var inner = new Cryptography.Hash({:algorithm => Cryptography.HASH_SHA1});
        inner.update(innerPad);
        inner.update(message);
        var innerDigest = inner.digest();

        var outer = new Cryptography.Hash({:algorithm => Cryptography.HASH_SHA1});
        outer.update(outerPad);
        outer.update(innerDigest);
        return outer.digest();
    }

    (:glance, :background)
    public function toBytes(values) {
        var bytes = []b;
        for (var index = 0; index < values.size(); index++) {
            bytes.add(values[index] & 0xff);
        }
        return bytes;
    }

    (:background)
    public function snapshotHash(entries) {
        var hash = new Cryptography.Hash({:algorithm => Cryptography.HASH_SHA256});
        for (var index = 0; index < entries.size(); index++) {
            var entry = entries[index];
            updateText(hash, entry["i"]);
            updateText(hash, entry["n"]);
            updateText(hash, entry["a"].toString());
            updateText(hash, entry["d"].toString());
            updateText(hash, entry["p"].toString());
            hash.update(toBytes(entry["s"]));
            hash.update([0]b);
        }
        var digest = hash.digest();
        var result = "";
        for (var byteIndex = 0; byteIndex < digest.size(); byteIndex++) {
            result += digest[byteIndex].format("%02x");
        }
        return result;
    }

    (:background)
    private function updateText(hash, value) {
        hash.update(toBytes(value.toString().toUtf8Array()));
        hash.update([0]b);
    }
}
