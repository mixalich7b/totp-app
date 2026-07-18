import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

(:background)
class TotpMixalich7bApp extends Application.AppBase {
    private var _view = null;
    private const _totpCore = new TotpCore();
    private const _store = new TotpStore(_totpCore);

    public function initialize() {
        AppBase.initialize();
    }

    public function onStart(state) {
        registerBackgroundSync();
    }

    public function onAppInstall() {
        registerBackgroundSync();
    }

    private function registerBackgroundSync() {
        if (!Background.getPhoneAppMessageEventRegistered()) {
            System.println("TOTP sync registering background phone message event");
            Background.registerForPhoneAppMessageEvent();
        }
    }

    public function getInitialView() {
        _view = new TotpView(new Timer.Timer(), _store, _totpCore);
        return [_view, new TotpDelegate(_view)];
    }

    public function getServiceDelegate() {
        return [new TotpSyncServiceDelegate(_store)];
    }

    public function onStorageChanged() {
        if (_view != null) {
            WatchUi.requestUpdate();
        }
    }

    (:glance)
    public function getGlanceView() {
        return [new TotpGlanceView(_store, _totpCore)];
    }
}
