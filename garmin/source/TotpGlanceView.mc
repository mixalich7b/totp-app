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
        var entry = (new TotpStore()).favorite();
        if (entry == null) {
            dc.drawText(8, height / 2, Graphics.FONT_XTINY, "TOTP: --", Graphics.TEXT_JUSTIFY_LEFT);
            return;
        }
        var now = Time.now().value();
        var code = (new TotpCore()).generate(entry, now);
        var remaining = entry["p"] - (now % entry["p"]);
        dc.drawText(8, 2, Graphics.FONT_XTINY, entry["n"], Graphics.TEXT_JUSTIFY_LEFT);
        dc.drawText(8, height / 2, Graphics.FONT_SMALL, code, Graphics.TEXT_JUSTIFY_LEFT);
        dc.setColor(remaining <= 5 ? Graphics.COLOR_RED : Graphics.COLOR_DK_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(8, height - 7, ((width - 16) * remaining) / entry["p"], 7);
    }
}
