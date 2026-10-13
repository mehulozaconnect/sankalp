package com.mehul.sankalp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an app update, put alarms back and resume syncing. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context ctx = context.getApplicationContext();
        Alarms.restore(ctx);
        if (Api.linked(ctx)) { Sync.schedule(ctx); Sync.soon(ctx); Coach.schedule(ctx); }
    }
}
