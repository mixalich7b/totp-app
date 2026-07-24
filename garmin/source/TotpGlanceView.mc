import Toybox.Graphics;
import Toybox.Time;
import Toybox.WatchUi;
import Toybox.Lang;

(:glance)
class TotpGlanceView extends WatchUi.GlanceView {
    private var _store as TotpStore;
    private var _timer as Toybox.Timer.Timer;

    (:glance)
    public function initialize(timer as Toybox.Timer.Timer, store as TotpStore) {
        GlanceView.initialize();
        _timer = timer;
        _store = store;
    }

    public function onShow() {
        _timer.start(method(:tick), 2000, true);
    }

    public function onHide() {
        _timer.stop();
    }

    public function tick() as Void {
        WatchUi.requestUpdate();
    }

    (:glance)
    public function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var width = dc.getWidth();
        var height = dc.getHeight();
        if (_store.isCorrupt()) {
            dc.drawText(8, height / 2, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.CorruptStorage), Graphics.TEXT_JUSTIFY_LEFT);
            return;
        }
        var entry = _store.favorite();
        if (entry == null) {
            dc.drawText(8, height / 2, Graphics.FONT_XTINY, "TOTP: --", Graphics.TEXT_JUSTIFY_LEFT);
            return;
        }
        var now = Time.now().value();
        if (now < 1577836800) {
            dc.drawText(8, height / 2, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.InvalidTime), Graphics.TEXT_JUSTIFY_LEFT);
            return;
        }
        var entrySafe = entry;
        var code = _store.codeFor(entrySafe, now);
        var period = entrySafe["p"] as Number;
        var remaining = period - (now % period);
        var rawName = entrySafe["n"] as String;
        var shortName = rawName.length() > 24 ? rawName.substring(0, 23) + "…" : rawName;
        dc.drawText(8, 2, Graphics.FONT_XTINY, shortName, Graphics.TEXT_JUSTIFY_LEFT);
        dc.drawText(8, height / 2, Graphics.FONT_SMALL, code, Graphics.TEXT_JUSTIFY_LEFT);
        dc.setColor(remaining <= 5 ? Graphics.COLOR_RED : Graphics.COLOR_DK_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(8, height - 7, ((width - 16) * remaining) / period, 7);
    }
}
