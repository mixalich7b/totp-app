import Toybox.Graphics;
import Toybox.Time;
import Toybox.WatchUi;

(:glance)
class TotpGlanceView extends WatchUi.GlanceView {
    (:glance)
    public function initialize() {
        GlanceView.initialize();
    }

    (:glance)
    public function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var width = dc.getWidth();
        var height = dc.getHeight();
        var store = new TotpStore();
        if (store.isCorrupt()) {
            dc.drawText(8, height / 2, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.CorruptStorage), Graphics.TEXT_JUSTIFY_LEFT);
            return;
        }
        var entry = store.favorite();
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
        var code = (new TotpCore()).generate(entry, now);
        var remaining = entry["p"] - (now % entry["p"]);
        var rawName = entry["n"];
        var shortName = rawName.length() > 24 ? rawName.substring(0, 23) + "…" : rawName;
        dc.drawText(8, 2, Graphics.FONT_XTINY, shortName, Graphics.TEXT_JUSTIFY_LEFT);
        dc.drawText(8, height / 2, Graphics.FONT_SMALL, code, Graphics.TEXT_JUSTIFY_LEFT);
        dc.setColor(remaining <= 5 ? Graphics.COLOR_RED : Graphics.COLOR_DK_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(8, height - 7, ((width - 16) * remaining) / entry["p"], 7);
    }
}
