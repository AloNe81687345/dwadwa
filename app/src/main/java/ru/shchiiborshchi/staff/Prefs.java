package ru.shchiiborshchi.staff;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static SharedPreferences get(Context c) { return c.getSharedPreferences("shchi", Context.MODE_PRIVATE); }
    static String token(Context c) { return get(c).getString("token", ""); }
}
