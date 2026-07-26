package io.github.skydynamic.quickbackupmulti.schedule.impl;

import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.schedule.CronUtils;
import io.github.skydynamic.quickbackupmulti.schedule.IModSchedule;
import io.github.skydynamic.quickbackupmulti.schedule.ModJob;
import io.github.skydynamic.quickbackupmulti.schedule.ScheduleManager;
import org.quartz.*;

import static io.github.skydynamic.quickbackupmulti.schedule.CronUtils.buildTrigger;

public class ModSchedule implements IModSchedule {
    private String identity;

    private String crontab;
    private Integer interval;

    private Runnable executor;

    protected JobDetail jobDetail;
    protected Trigger trigger;
    protected Scheduler scheduler;

    // Quartz
    @SuppressWarnings("unused")
    public ModSchedule() {
    }

    public ModSchedule(String identity, Integer interval) {
        this.identity = identity;
        this.interval = interval;
    }

    public ModSchedule(String identity, String crontab) {
        this.identity = identity;
        this.crontab = crontab;
    }

    @Override
    public String getName() {
        return identity;
    }

    private Trigger rebuildTrigger() {
        if (crontab != null && !crontab.isEmpty()) {
            return buildTrigger(identity, CronUtils.ScheduleMode.CRONTAB, crontab);
        } else if (interval != null && interval > 0) {
            return buildTrigger(identity, CronUtils.ScheduleMode.INTERVAL, interval);
        }
        return null;
    }

    @Override
    public boolean startSchedule() {
        jobDetail = JobBuilder
            .newJob(ModJob.class)
            .withIdentity(identity)
            .build();

        trigger = rebuildTrigger();
        if (trigger == null) {
            return false;
        }

        try {
            scheduler = ScheduleManager.getSharedScheduler();
            scheduler.scheduleJob(jobDetail, trigger);
            return true;
        } catch (SchedulerException e) {
            QuickbackupmultiReforged.logger.error("Failed to get scheduler", e);
            return false;
        }
    }

    @Override
    public void stopSchedule() {
        // Remove ONLY this schedule's job from the shared scheduler. Shutting the
        // scheduler down here (the old behavior) killed every sibling schedule —
        // prune/database backups silently died after the first stop/reset.
        try {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.deleteJob(JobKey.jobKey(identity));
            }
        } catch (SchedulerException e) {
            QuickbackupmultiReforged.logger.error("Failed to stop schedule {}", identity, e);
        }
    }

    @Override
    public ModSchedule setExecutor(Runnable executor) {
        this.executor = executor;
        return this;
    }

    @Override
    public boolean isRunning() {
        try {
            return scheduler != null && !scheduler.isShutdown() && scheduler.checkExists(JobKey.jobKey(identity));
        } catch (SchedulerException e) {
            return false;
        }
    }

    @Override
    public long getNextExecuteTime() {
        return trigger.getNextFireTime().getTime();
    }

    @Override
    public boolean resetTimer() {
        if (scheduler == null) {
            return false;
        }
        Trigger newTrigger = rebuildTrigger();
        if (newTrigger == null) {
            return false;
        }
        try {
            // Atomic in-place reschedule of this trigger only; sibling jobs untouched.
            if (scheduler.rescheduleJob(TriggerKey.triggerKey(identity), newTrigger) == null) {
                return false;
            }
            trigger = newTrigger;
            return true;
        } catch (SchedulerException e) {
            QuickbackupmultiReforged.logger.error("Failed to reset schedule {}", identity, e);
            return false;
        }
    }

    public void execute() {
        QuickbackupmultiReforged.logger.info("Schedule {} execute in {}", identity, QuickbackupmultiReforged.formatTimestamp(System.currentTimeMillis()));
        executor.run();
        QuickbackupmultiReforged.logger.info(
            "Schedule {} execute done, next execute time: {}",
            identity,
            QuickbackupmultiReforged.formatTimestamp(trigger.getNextFireTime().getTime())
        );
    }
}
