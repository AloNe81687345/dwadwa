package ru.shchiiborshchi.staff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки телефона снова следим за заявками. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Prefs.token(c).isEmpty()) WatchService.start(c);
    }
}
