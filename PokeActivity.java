package com.mehul.sankalp;

import android.app.Activity;
import android.os.Bundle;

/** Invisible. Sankalp opens sankalp://sync after you add a reminder that is due soon; we fetch it a few seconds later and close at once. */
public class PokeActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Api.linked(this)) Sync.later(getApplicationContext());
        finish();
        overridePendingTransition(0, 0);
    }
}
