import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

function totpClampSelection(selected, count) {
    if (count <= 0) { return 0; }
    if (selected < 0) { return 0; }
    if (selected >= count) { return count - 1; }
    return selected;
}

class TotpView extends WatchUi.View {
    private var _selected = 0;
    private var _timer as Toybox.Timer.Timer;
    private var _store as TotpStore;

    public function initialize(timer as Toybox.Timer.Timer, store as TotpStore) {
        View.initialize();
        _timer = timer;
        _store = store;
    }

    public function onShow() {
        _timer.start(method(:tick), 1000, true);
    }

    public function onHide() {
        _timer.stop();
    }

    public function tick() as Void {
        WatchUi.requestUpdate();
    }

    public function move(delta) {
        var count = _store.entries().size();
        if (count == 0) { return; }
        _selected = (_selected + delta + count) % count;
        WatchUi.requestUpdate();
    }

    public function chooseFavorite() {
        var entries = _store.entries();
        if (entries.size() > 0) {
            _selected = totpClampSelection(_selected, entries.size());
            _store.setFavorite(entries[_selected]["i"] as Lang.String);
            WatchUi.requestUpdate();
        }
    }

    public function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var width = dc.getWidth();
        var height = dc.getHeight();
        if (_store.isCorrupt()) {
            dc.drawText(width / 2, height / 2 - 20, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.CorruptStorage), Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }
        var entries = _store.entries() as Lang.Array<Lang.Dictionary<String, Object>>;
        if (entries.size() == 0) {
            dc.drawText(width / 2, height / 2 - 20, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.Empty), Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }

        _selected = totpClampSelection(_selected, entries.size());
        var now = Time.now().value();
        if (now < 1577836800) {
            dc.drawText(width / 2, height / 2 - 20, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.InvalidTime), Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }
        var favorite = _store.favoriteId();
        for (var offset = -1; offset <= 1; offset++) {
            var index = _selected + offset;
            if (index < 0 || index >= entries.size()) { continue; }
            var entry = entries[index];
            var y = height / 2 - 60 + offset * 110;
            var isSelected = offset == 0;
            var isFavorite = totpValuesEqual(entry["i"], favorite);
            dc.setColor(isSelected ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            var rawName = entry["n"] as String;
            var shortName = rawName.length() > 28 ? rawName.substring(0, 27) + "…" : rawName;
            var name = (isFavorite ? "★ " : "") + shortName;
            dc.drawText(width / 2, isSelected ? y - 60 : y - 40, isSelected ? Graphics.FONT_SMALL : Graphics.FONT_XTINY,
                name, Graphics.TEXT_JUSTIFY_CENTER);
            dc.drawText(width / 2, isSelected ? y - 25 : y - 5, isSelected ? Graphics.FONT_NUMBER_MILD : Graphics.FONT_SMALL,
                _store.codeFor(entry, now), Graphics.TEXT_JUSTIFY_CENTER);
        }

        var current = entries[_selected];
        var period = current["p"] as Number;
        var remaining = period - (now % period);
        dc.setColor(remaining <= 5 ? Graphics.COLOR_RED : Graphics.COLOR_DK_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(40, height - 105, ((width - 80) * remaining) / period, 7);
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
