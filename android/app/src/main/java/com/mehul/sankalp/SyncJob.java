package com.mehul.sankalp;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs every 15 minutes, right after the phone calendar changes, and once after boot or linking. */
public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            boolean cal = params.getJobId() == Sync.JOB_CAL;
            try {
                long last = Api.p(getApplicationContext()).getLong("lastSync", 0);
                if (!cal || System.currentTimeMillis() - last > 45 * 1000L) Sync.run(getApplicationContext(), 2);
            } catch (Exception e) {
                Api.p(getApplicationContext()).edit().putString("lastErr", String.valueOf(e.getMessage())).apply();
                retry = params.getJobId() == Sync.JOB_NOW;
            }
            jobFinished(params, retry);
            if (cal && Api.linked(getApplicationContext())) Sync.watchCalendar(getApplicationContext(), true);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
