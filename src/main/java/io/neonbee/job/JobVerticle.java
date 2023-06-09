package io.neonbee.job;

import static io.vertx.core.Future.succeededFuture;
import static java.lang.Math.max;
import static java.time.Instant.now;
import static java.time.ZoneOffset.UTC;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
import static java.time.temporal.ChronoUnit.MILLIS;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import com.google.common.annotations.VisibleForTesting;

import io.neonbee.NeonBee;
import io.neonbee.data.DataContext;
import io.neonbee.data.internal.DataContextImpl;
import io.neonbee.logging.LoggingFacade;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public abstract class JobVerticle extends AbstractVerticle {
    @VisibleForTesting
    static final long MINIMUM_DELAY = 100L;

    @VisibleForTesting
    static final long FINALIZE_DELAY = 50L;

    private static final LoggingFacade LOGGER = LoggingFacade.create();

    private static final long NO_SCHEDULE = -1;

    private static final long STOPPED = -2;

    private static final long FINALIZED = -3;

    private static final int SCHEDULE_TEST_EXECUTIONS = 10;

    private JobSchedule schedule;

    private Instant lastExecution;

    private boolean undeployWhenDone;

    private long currentTimerId = NO_SCHEDULE;

    /**
     * Create a new job verticle with a given job schedule.
     *
     * @param schedule the schedule to use when starting this job verticle
     */
    public JobVerticle(JobSchedule schedule) {
        this(schedule, true);
    }

    /**
     * Create a new job verticle with a given job schedule. Optionally undeploy the verticle when the job execution
     * ended (hit the end instant or one time execution)
     *
     * @param schedule         the schedule to use when starting this job verticle
     * @param undeployWhenDone if true, undeploy the verticle when done
     */
    public JobVerticle(JobSchedule schedule, boolean undeployWhenDone) {
        super();
        this.schedule = schedule;
        this.undeployWhenDone = undeployWhenDone;
    }

    /**
     * Returns the job schedule of this verticle.
     *
     * @return the job schedule
     */
    public JobSchedule getSchedule() {
        return schedule;
    }

    /**
     * Returns the name of this job (as it appears in the log / monitors), defaults to the simple name of the class.
     *
     * @return the name of this job
     */
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public void start() {
        if (!NeonBee.get(getVertx()).getOptions().shouldDisableJobScheduling()) {
            if (isScheduleValid()) {
                scheduleJob();
            } else {
                throw new IllegalStateException("The period of a periodic JobSchedule can't be zero");
            }
        } else {
            finalizeJob();
        }
    }

    @Override
    public void stop() {
        // stop without finalizing
        if (currentTimerId >= 0) {
            getVertx().cancelTimer(currentTimerId);
            currentTimerId = STOPPED;
        }
    }

    private boolean isScheduleValid() {
        if (schedule.isPeriodic()) {
            Instant before = now();
            for (int x = 0; x < SCHEDULE_TEST_EXECUTIONS; x++) {
                Instant after = before.with(schedule);
                if (after.compareTo(before) <= 0) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Schedule the next job execution.
     */
    private void scheduleJob() {
        boolean periodicSchedule = schedule.isPeriodic();
        Instant now = now();
        Instant scheduledStart = schedule.getStart();
        Instant scheduledEnd = schedule.getEnd();

        // before even scheduling, check if the scheduled start / end lies in the past
        if ((scheduledStart != null && !periodicSchedule && scheduledStart.isBefore(now))
                || (scheduledEnd != null && scheduledEnd.isBefore(now))) {
            finalizeJob();
            return;
        }

        // the next schedule is based either on the last schedule (if this is a periodic job) or the scheduled start. In
        // case there was no scheduled start defined, start the job now
        Instant nextExecution = lastExecution != null ? lastExecution : (scheduledStart != null ? scheduledStart : now);

        // in case the next schedule lies in the past, find the time for the next execution: iterate the time forward,
        // until it is in the future using the schedule provided
        while (nextExecution.isBefore(now)) {
            nextExecution = periodicSchedule ? nextExecution.with(schedule) : now;
        }

        // in case the next execution time lies after the scheduled end date, finalize the job and do not schedule the
        // next job execution
        if (scheduledEnd != null && nextExecution.isAfter(scheduledEnd)) {
            finalizeJob();
            return;
        }

        // schedule the job for the delay defined and remember the last execution time use a minimum delay to not run
        // jobs in a immediate succession
        long nextDelay = max(MINIMUM_DELAY, now.until(lastExecution = nextExecution, MILLIS));
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Scheduling job execution of {} in {}ms ({})", getName(), nextDelay,
                    ISO_LOCAL_DATE_TIME.format(ZonedDateTime.now(UTC).plus(nextDelay, MILLIS)));
        }
        currentTimerId = getVertx().setTimer(nextDelay, timerID -> {
            // initialize the data context for the job execution
            DataContext context = new DataContextImpl(UUID.randomUUID().toString(),
                    "internal-" + UUID.randomUUID().toString(), getUser());

            // execute the job and wait for the execution to finish, before starting the next execution
            LOGGER.correlateWith(context).info("Job execution of {} started", getClass().getSimpleName());
            Optional.ofNullable(execute(context)).orElse(succeededFuture()).onComplete(result -> {
                // handle the result by logging
                if (result.succeeded()) {
                    LOGGER.correlateWith(context).info("Job execution of {} ended successfully", getName());
                } else {
                    LOGGER.correlateWith(context).warn("Job execution of {} ended with failure", getName(),
                            result.cause());
                }

                // if it is a periodic schedule, schedule the next job run, otherwise finalize and end the execution
                if (periodicSchedule) {
                    scheduleJob();
                } else {
                    finalizeJob();
                }
            });
        });
    }

    /**
     * Finalize the job execution by eventually undeploying the own verticle.
     */
    protected void finalizeJob() {
        String name = getName();
        LOGGER.info("Finalizing job {}", name);

        // indicate that the job was finalized (there is no need to cancel any timer
        // here, because finalizeJob will only
        // be called after a timer completed, or in case no timer was ever started
        currentTimerId = FINALIZED;

        if (undeployWhenDone) {
            // this delay accounts for the edge case, that we are undeploying / finalizing
            // when starting
            getVertx().setTimer(FINALIZE_DELAY, timerId -> {
                LOGGER.info("Undeploying job {}, as processing finished", name);
                getVertx().undeploy(deploymentID()).onFailure(throwable -> {
                    LOGGER.error("Failed to undeploy job {}", name, throwable);
                });
            });
        }
    }

    /**
     * True if the job was finalized.
     *
     * @return true if the job was finalized
     */
    public boolean isFinalized() {
        return currentTimerId == FINALIZED;
    }

    /**
     * Execute the job.
     *
     * @param context the data context for this job execution
     * @return any future signaling the end of the job execution. The next execution will start, as soon as this future
     *         returns. To support consecutive executions, return a succeeded future
     */
    public abstract Future<?> execute(DataContext context);

    /**
     * Override this method in case another user principal should be used for the job execution.
     *
     * @return the user principal to use to execute this job
     */
    protected JsonObject getUser() {
        return new JsonObject().put("user_name", String.format("job_%s", getClass().getSimpleName()));
    }
}
