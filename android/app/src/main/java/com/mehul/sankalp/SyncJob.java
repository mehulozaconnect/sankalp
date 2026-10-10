package com.mehul.sankalp;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs every 15 minutes (and once after boot or linking). */
public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                Sync.run(getApplicationContext(), 2);
            } catch (Exception e) {
                Api.p(getApplicationContext()).edit().putString("lastErr", String.valueOf(e.getMessage())).apply();
                retry = params.getJobId() == Sync.JOB_NOW;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
