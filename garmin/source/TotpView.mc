import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

class TotpView extends WatchUi.View {
    private var _selected = 0;
    private var _timer;

    public function initialize() {
        View.initialize();
        _timer = new Timer.Timer();
    }

    public function onShow() {
        _timer.start(method(:tick), 1000, true);
    }

    public function onHide() {
        _timer.stop();
    }

    public function tick() {
        WatchUi.requestUpdate();
    }

    public function move(delta) {
        var count = (new TotpStore()).entries().size();
        if (count == 0) { return; }
        _selected = (_selected + delta + count) % count;
        WatchUi.requestUpdate();
    }

    public function chooseFavorite() {
        var entries = (new TotpStore()).entries();
        if (entries.size() > 0) {
            (new TotpStore()).setFavorite(entries[_selected]["i"]);
            WatchUi.requestUpdate();
        }
    }

    public function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var width = dc.getWidth();
        var height = dc.getHeight();
        var entries = (new TotpStore()).entries();
        if (entries.size() == 0) {
            dc.drawText(width / 2, height / 2 - 20, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.Empty), Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }

        if (_selected >= entries.size()) { _selected = entries.size() - 1; }
        var now = Time.now().value();
        var core = new TotpCore();
        for (var offset = -1; offset <= 1; offset++) {
            var index = _selected + offset;
            if (index < 0 || index >= entries.size()) { continue; }
            var entry = entries[index];
            var y = height / 2 - 60 + offset * 110;
            var isSelected = offset == 0;
            var isFavorite = totpValuesEqual(entry["i"], (new TotpStore()).favoriteId());
            dc.setColor(isSelected ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            var name = (isFavorite ? "★ " : "") + entry["n"];
            dc.drawText(width / 2, isSelected ? y - 60 : y - 40, isSelected ? Graphics.FONT_SMALL : Graphics.FONT_XTINY,
                name, Graphics.TEXT_JUSTIFY_CENTER);
            dc.drawText(width / 2, isSelected ? y - 25 : y - 5, isSelected ? Graphics.FONT_NUMBER_MILD : Graphics.FONT_SMALL,
                core.generate(entry, now), Graphics.TEXT_JUSTIFY_CENTER);
        }

        var current = entries[_selected];
        var remaining = current["p"] - (now % current["p"]);
        dc.setColor(remaining <= 5 ? Graphics.COLOR_RED : Graphics.COLOR_DK_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(40, height - 105, ((width - 80) * remaining) / current["p"], 7);
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, height - 90, Graphics.FONT_XTINY,
            WatchUi.loadResource(Rez.Strings.FavoriteHint), Graphics.TEXT_JUSTIFY_CENTER);
    }
}

class TotpDelegate extends WatchUi.BehaviorDelegate {
    private var _view;
    public function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }
    public function onNextPage() { _view.move(1); return true; }
    public function onPreviousPage() { _view.move(-1); return true; }
    public function onSelect() { _view.chooseFavorite(); return true; }
    public function onMenu() { _view.chooseFavorite(); return true; }
    public function onBack() { return false; }
}
