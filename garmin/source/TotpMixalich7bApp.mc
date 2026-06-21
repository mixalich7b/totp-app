import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

(:background)
class TotpMixalich7bApp extends Application.AppBase {
    private var _view = null;

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
        _view = new TotpView();
        return [_view, new TotpDelegate(_view)];
    }

    public function getServiceDelegate() {
        return [new TotpSyncServiceDelegate()];
    }

    public function onStorageChanged() {
        if (_view != null) {
            WatchUi.requestUpdate();
        }
    }

    (:glance)
    public function getGlanceView() {
        return [new TotpGlanceView()];
    }
}
