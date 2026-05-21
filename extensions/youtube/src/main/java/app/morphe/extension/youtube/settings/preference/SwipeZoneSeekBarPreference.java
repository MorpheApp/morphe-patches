package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SwipeZoneSeekBarPreference extends SeekBarPreference {

    public SwipeZoneSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SwipeZoneSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SwipeZoneSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SwipeZoneSeekBarPreference(Context context) {
        super(context);
    }

    @Override protected int getMin() { return 5; }
    @Override protected int getMax() { return 50; }
    @Override protected int getStep() { return 1; }
    @Override protected String getUnit() { return "%"; }
    @Override protected int readValue() { return Settings.SWIPE_ZONE_WIDTH.get(); }
    @Override protected void writeValue(int value) { Settings.SWIPE_ZONE_WIDTH.save(value); }
}
