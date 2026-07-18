import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

(:background)
function syncMessageType(rawType as Null or Object) as Null or String {
    if (rawType == null) {
        return null;
    }
    var value = rawType.toString();
    if (value.equals("b") || value.equals(":b")) {
        return "b";
    }
    if (value.equals("c") || value.equals(":c")) {
        return "c";
    }
    if (value.equals("m") || value.equals(":m")) {
        return "m";
    }
    if (value.equals("d") || value.equals(":d")) {
        return "d";
    }
    return value;
}

(:background)
class TotpSyncServiceDelegate extends System.ServiceDelegate {
    private var _store as TotpStore;

    public function initialize(store as TotpStore) {
        ServiceDelegate.initialize();
        _store = store;
    }

    public function onPhoneAppMessage(phoneMessage as PhoneAppMessage) as Void {
        if (phoneMessage.data == null || !(phoneMessage.data instanceof Lang.Dictionary)) {
            System.println("TOTP sync received empty message");
            Background.exit(null);
            return;
        }
        var data = phoneMessage.data as Lang.Dictionary<String, Object>;
        var messageType = syncMessageType(data["t"]);
        var transferId = data["x"] as String;
        var safeType = safeMessageType(messageType);
        System.println("TOTP sync received type=" + safeType);
        var response;
        try {
            response = handle(data);
        } catch (error) {
            System.println("TOTP sync rejected: internal validation error");
            response = {"t" => "e", "m" => "Invalid message"};
        }
        if (response == null) {
            System.println("TOTP sync message accepted without response type=" + safeType);
            Background.exit(null);
            return;
        }
        if (validTransferId(transferId)) {
            response["x"] = transferId;
        }
        var responseType = syncMessageType(response["t"]);
        System.println("TOTP sync transmitting response type=" + safeMessageType(responseType));
        try {
            Communications.transmit(response, null, new TotpSyncConnectionListener(responseType));
        } catch (error) {
            System.println("TOTP sync response transmit failed before callback");
            Background.exit(null);
        }
    }

    private function handle(message as Lang.Dictionary) as Null or Lang.Dictionary {
        if (message == null || message["t"] == null) {
            System.println("TOTP sync rejected: missing message type");
            return {"t" => "e", "m" => "Invalid message"};
        }
        var messageType = syncMessageType(message["t"]);
        var transferId = message["x"];
        var error = null;
        if (messageType.equals("b")) {
            System.println("TOTP sync begin");
            error = _store.begin(message);
        } else if (messageType.equals("c")) {
            System.println("TOTP sync chunk");
            error = _store.chunk(message);
        } else if (messageType.equals("m")) {
            System.println("TOTP sync commit");
            var revision = _store.commit(message);
            if (revision instanceof Number || revision instanceof Long) {
                System.println("TOTP sync committed revision=" + revision);
                return {"t" => "a", "r" => revision};
            }
            error = revision;
        } else if (messageType.equals("d")) {
            if (message["v"] != 1 || !validTransferId(transferId)) {
                error = "Invalid clear";
            } else {
                System.println("TOTP sync clearing watch data");
                _store.clearAll();
                return {"t" => "z", "x" => transferId};
            }
        } else {
            error = "Unknown message";
        }
        if (error != null) {
            System.println("TOTP sync rejected type=" + safeMessageType(messageType) + " category=" + safeErrorCategory(error));
        }
        return error == null ? null : {"t" => "e", "m" => error};
    }

    private function validTransferId(value) {
        return value instanceof String && value.length() >= 1 && value.length() <= 64;
    }

    private function safeMessageType(value) {
        if (value == null) { return "missing"; }
        if (value.equals("b") || value.equals("c") || value.equals("m") || value.equals("d")
                || value.equals("a") || value.equals("z") || value.equals("e")) {
            return value;
        }
        return "unknown";
    }

    private function safeErrorCategory(error) {
        if (error.equals("Unsupported protocol") || error.equals("Invalid message")
                || error.equals("Invalid begin") || error.equals("Invalid clear")) { return "invalid"; }
        if (error.equals("Stale revision")) { return "stale"; }
        if (error.equals("Wrong transfer") || error.equals("Wrong sequence")
                || error.equals("Incomplete snapshot")) { return "incomplete"; }
        if (error.equals("Invalid record") || error.equals("Unsupported TOTP")) { return "record"; }
        if (error.equals("Checksum mismatch")) { return "checksum"; }
        return "unknown";
    }
}

(:background)
class TotpSyncConnectionListener extends Communications.ConnectionListener {
    private var _responseType as String;

    public function initialize(responseType as String) {
        ConnectionListener.initialize();
        _responseType = responseType;
    }

    public function onComplete() {
        System.println("TOTP sync response delivered type=" + _responseType);
        Background.exit(null);
    }

    public function onError() {
        System.println("TOTP sync response delivery failed type=" + _responseType);
        Background.exit(null);
    }
}
