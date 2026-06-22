import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

(:background)
function syncMessageType(rawType) {
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
    public function initialize() {
        ServiceDelegate.initialize();
    }

    public function onPhoneAppMessage(phoneMessage as PhoneAppMessage) as Void {
        var messageType = phoneMessage.data == null ? null : syncMessageType(phoneMessage.data["t"]);
        var transferId = phoneMessage.data == null ? null : phoneMessage.data["x"];
        System.println("TOTP sync received type=" + (messageType == null ? "missing" : messageType) + " transfer=" + (transferId == null ? "missing" : transferId));
        var response = handle(phoneMessage.data);
        if (response == null) {
            System.println("TOTP sync message accepted without response type=" + (messageType == null ? "missing" : messageType) + " transfer=" + (transferId == null ? "missing" : transferId));
            Background.exit(null);
            return;
        }
        if (transferId != null) {
            response["x"] = transferId;
        }
        var responseType = syncMessageType(response["t"]);
        System.println("TOTP sync transmitting response type=" + responseType + " transfer=" + (transferId == null ? "missing" : transferId));
        Communications.transmit(response, null, new TotpSyncConnectionListener(responseType, transferId));
    }

    private function handle(message) {
        if (message == null || message["t"] == null) {
            System.println("TOTP sync rejected: missing message type");
            return {"t" => "e", "m" => "Invalid message"};
        }
        var messageType = syncMessageType(message["t"]);
        var transferId = message["x"];
        var store = new TotpStore();
        var error = null;
        if (messageType.equals("b")) {
            System.println("TOTP sync begin transfer=" + transferId + " revision=" + message["r"] + " entries=" + message["n"]);
            error = store.begin(message);
        } else if (messageType.equals("c")) {
            System.println("TOTP sync chunk transfer=" + transferId + " sequence=" + message["q"]);
            error = store.chunk(message);
        } else if (messageType.equals("m")) {
            System.println("TOTP sync commit transfer=" + transferId);
            var revision = store.commit(message);
            if (revision instanceof Number || revision instanceof Long) {
                System.println("TOTP sync committed transfer=" + transferId + " revision=" + revision);
                return {"t" => "a", "r" => revision};
            }
            error = revision;
        } else if (messageType.equals("d")) {
            if (message["v"] != 1 || transferId == null) {
                error = "Invalid clear";
            } else {
                System.println("TOTP sync clearing watch data transfer=" + transferId);
                store.clearAll();
                return {"t" => "z", "x" => transferId};
            }
        } else {
            error = "Unknown message type: " + messageType;
        }
        if (error != null) {
            System.println("TOTP sync rejected type=" + messageType + " transfer=" + (transferId == null ? "missing" : transferId) + " error=" + error);
        }
        return error == null ? null : {"t" => "e", "m" => error};
    }
}

(:background)
class TotpSyncConnectionListener extends Communications.ConnectionListener {
    private var _responseType;
    private var _transferId;

    public function initialize(responseType, transferId) {
        ConnectionListener.initialize();
        _responseType = responseType;
        _transferId = transferId;
    }

    public function onComplete() {
        System.println("TOTP sync response delivered type=" + _responseType + " transfer=" + (_transferId == null ? "missing" : _transferId));
        Background.exit(null);
    }

    public function onError() {
        System.println("TOTP sync response delivery failed type=" + _responseType + " transfer=" + (_transferId == null ? "missing" : _transferId));
        Background.exit(null);
    }
}
