package ru.tvshell.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Отдельный пункт «Входы» для домашнего экрана Android TV:
 * открывает штатный селектор источников телевизора и закрывается.
 */
public class InputsActivity extends Activity {

    private static final String TAG = "TVShell";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent i = new Intent("com.android.tv.action.VIEW_INPUTS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "viewInputs", e);
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.mediatek.wwtv.tvcenter",
                        "com.mitv.livetv.input.SelectInputActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e2) {
                Log.w(TAG, "selectInput", e2);
            }
        }
        finish();
    }
}
