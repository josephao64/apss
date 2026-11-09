package gt.tiempopantalla;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class HistoryJob extends JobService {
    private static final int JOB_ID = 12;
    private JobParameters running;

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) == null) {
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, HistoryJob.class))
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        running = params;
        new Thread(() -> {
            try {
                Usage.read(this);
            } catch (SecurityException ignored) {
                // The user may revoke access while the job is running.
            } finally {
                getMainExecutor().execute(() -> {
                    if (running == params) {
                        running = null;
                        jobFinished(params, false);
                    }
                });
            }
        }, "usage-history").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (running == params) running = null;
        return true;
    }
}
