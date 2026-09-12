package com.darood.app;

/** Stored as a stable, untranslated value in each existing reminder JSON object. */
public enum ReminderAlertMode {
    RING, VIBRATE, SILENT;

    public static ReminderAlertMode parse(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return RING; }
    }

    public int label() {
        switch (this) {
            case VIBRATE: return R.string.reminder_vibrate;
            case SILENT: return R.string.reminder_silent;
            default: return R.string.reminder_ring;
        }
    }
}
