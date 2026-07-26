package io.github.skydynamic.quickbackupmulti.schedule;

import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.schedule.impl.ModSchedule;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Properties;

public class ScheduleManager {
    /**
     * The single Quartz scheduler shared by every ModSchedule. Individual schedules
     * must NEVER shut it down (the old per-schedule shutdown killed all sibling
     * jobs — prune/database backups silently died after the first resetTimer);
     * only {@link #clearAllSchedule()} tears it down, and it is lazily rebuilt on
     * the next world load. Daemon threads so a forgotten scheduler can never hold
     * the JVM open on exit.
     */
    private static Scheduler sharedScheduler;

    public static synchronized Scheduler getSharedScheduler() throws SchedulerException {
        if (sharedScheduler == null || sharedScheduler.isShutdown()) {
            Properties props = new Properties();
            props.setProperty("org.quartz.scheduler.instanceName", "QBM-Scheduler");
            props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
            props.setProperty("org.quartz.threadPool.threadCount", "2");
            props.setProperty("org.quartz.threadPool.makeThreadsDaemon", "true");
            props.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
            props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            sharedScheduler = new StdSchedulerFactory(props).getScheduler();
            sharedScheduler.start();
        }
        return sharedScheduler;
    }

    private static synchronized void shutdownSharedScheduler() {
        if (sharedScheduler != null) {
            try {
                sharedScheduler.shutdown(true);
            } catch (SchedulerException e) {
                QuickbackupmultiReforged.logger.error("Failed to shutdown scheduler", e);
            }
            sharedScheduler = null;
        }
    }
    private static void registerSchedule(ModSchedule schedule) {
        if (QuickbackupmultiReforged.getModContainer().getSchedules().contains(schedule)) {
            QuickbackupmultiReforged.logger.warn("Schedule already exists: {}", schedule.getName());
            return;
        }
        QuickbackupmultiReforged.getModContainer().getSchedules().add(schedule);
        QuickbackupmultiReforged.logger.info("Register schedule: {}", schedule.getName());
    }

    public static void registerSchedule(String name, String crontab, Runnable executor) {
        ModSchedule schedule = new ModSchedule(name, crontab).setExecutor(executor);
        registerSchedule(schedule);
    }

    public static void registerSchedule(String name, int interval, Runnable executor) {
        ModSchedule schedule = new ModSchedule(name, interval).setExecutor(executor);
        registerSchedule(schedule);
    }

    public static void startAllSchedule() {
        for (IModSchedule schedule : QuickbackupmultiReforged.getModContainer().getSchedules()) {
            if (!schedule.startSchedule()) {
                QuickbackupmultiReforged.logger.warn("Failed to start schedule: {}", schedule.getName());
            } else {
                QuickbackupmultiReforged.logger.info("Start schedule: {}, next execute time: {}",
                    schedule.getName(),
                    QuickbackupmultiReforged.formatTimestamp(schedule.getNextExecuteTime())
                );
            }
        }
    }

    public static void stopAllSchedule() {
        for (IModSchedule schedule : QuickbackupmultiReforged.getModContainer().getSchedules()) {
            if (schedule.isRunning()) {
                schedule.stopSchedule();
                QuickbackupmultiReforged.logger.info("Stop schedule: {}", schedule.getName());
            }
        }
    }

    public static void clearAllSchedule() {
        stopAllSchedule();
        shutdownSharedScheduler();
        QuickbackupmultiReforged.getModContainer().getSchedules().clear();
    }

    public static boolean resetTimer(String name) {
        for (IModSchedule schedule : QuickbackupmultiReforged.getModContainer().getSchedules()) {
            if (schedule.getName().equals(name) && schedule.resetTimer()) {
                QuickbackupmultiReforged.logger.info("Reset timer: {}", name);
                return true;
            }
        }
        return false;
    }
}
