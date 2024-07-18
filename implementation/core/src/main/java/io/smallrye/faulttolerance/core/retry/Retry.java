package io.smallrye.faulttolerance.core.retry;

import static io.smallrye.faulttolerance.core.retry.RetryLogger.LOG;
import static io.smallrye.faulttolerance.core.util.Preconditions.checkNotNull;
import static io.smallrye.faulttolerance.core.util.SneakyThrow.sneakyThrow;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;

import io.smallrye.faulttolerance.core.FailureContext;
import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.InvocationContext;
import io.smallrye.faulttolerance.core.stopwatch.RunningStopwatch;
import io.smallrye.faulttolerance.core.stopwatch.Stopwatch;
import io.smallrye.faulttolerance.core.util.ExceptionDecision;
import io.smallrye.faulttolerance.core.util.ResultDecision;

public class Retry<V> implements FaultToleranceStrategy<V> {
    final FaultToleranceStrategy<V> delegate;
    final String description;

    private final ResultDecision resultDecision;
    private final ExceptionDecision exceptionDecision;
    final long maxRetries; // this is an `int` in MP FT, but `long` allows easier handling of "infinity"
    final long maxTotalDurationInMillis;
    private final Supplier<SyncDelay> delayBetweenRetries;
    final Stopwatch stopwatch;
    final Consumer<FailureContext> beforeRetry;

    public Retry(FaultToleranceStrategy<V> delegate, String description, ResultDecision resultDecision,
            ExceptionDecision exceptionDecision, long maxRetries, long maxTotalDurationInMillis,
            Supplier<SyncDelay> delayBetweenRetries, Stopwatch stopwatch, Consumer<FailureContext> beforeRetry) {
        this.delegate = checkNotNull(delegate, "Retry delegate must be set");
        this.description = checkNotNull(description, "Retry description must be set");
        this.resultDecision = checkNotNull(resultDecision, "Result decision must be set");
        this.exceptionDecision = checkNotNull(exceptionDecision, "Exception decision must be set");
        this.maxRetries = maxRetries < 0 ? Long.MAX_VALUE : maxRetries;
        this.maxTotalDurationInMillis = maxTotalDurationInMillis <= 0 ? Long.MAX_VALUE : maxTotalDurationInMillis;
        this.delayBetweenRetries = checkNotNull(delayBetweenRetries, "Delay must be set");
        this.stopwatch = checkNotNull(stopwatch, "Stopwatch must be set");
        this.beforeRetry = beforeRetry;
    }

    @Override
    public V apply(InvocationContext<V> ctx) throws Exception {
        LOG.trace("Retry started");
        try {
            return doApply(ctx);
        } finally {
            LOG.trace("Retry finished");
        }
    }

    private V doApply(InvocationContext<V> ctx) throws Exception {
        long counter = 0;
        SyncDelay delay = delayBetweenRetries.get();
        RunningStopwatch runningStopwatch = stopwatch.start();
        Throwable lastFailure = null;
        while (counter <= maxRetries && runningStopwatch.elapsedTimeInMillis() < maxTotalDurationInMillis) {
            if (counter > 0) {
                LOG.debugf("%s invocation failed, retrying (%d/%d)", description, counter, maxRetries);
                ctx.fireEvent(RetryEvents.Retried.INSTANCE);

                try {
                    delay.sleep(lastFailure);
                } catch (InterruptedException e) {
                    ctx.fireEvent(RetryEvents.Finished.EXCEPTION_NOT_RETRYABLE);
                    throw e;
                } catch (Exception e) {
                    ctx.fireEvent(RetryEvents.Finished.EXCEPTION_NOT_RETRYABLE);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }

                    throw e;
                }

                // Previously, we called `delayBetweenRetries.sleep()` _after_ `delegate.apply()`
                // in a single iteration of the loop and had no additional check (it would happen
                // immediately as part of the loop condition). This has one issue: we would sleep
                // after the last retry iteration (per `maxRetries`), which is a waste of resources.
                // Currently, we call `delayBetweenRetries.sleep()` _before_ `delegate.apply()`
                // in a single iteration of the loop. This means we might call `delegate.apply()`
                // after sleeping, even though the time budget (per `maxTotalDurationInMillis`)
                // is possibly already empty. This would probably be OK per the spec (the TCK
                // doesn't care), but it would change our own existing behavior (as codified by
                // `RetryTest`). Hence this second explicit check.
                if (runningStopwatch.elapsedTimeInMillis() >= maxTotalDurationInMillis) {
                    break;
                }

                if (beforeRetry != null) {
                    try {
                        beforeRetry.accept(new FailureContext(lastFailure, ctx));
                    } catch (Exception e) {
                        LOG.warn("Before retry action has thrown an exception", e);
                    }
                }
            }

            try {
                V result = delegate.apply(ctx);
                if (shouldAbortRetryingOnResult(result)) {
                    ctx.fireEvent(RetryEvents.Finished.VALUE_RETURNED);
                    return result;
                }
                lastFailure = null;
            } catch (InterruptedException e) {
                ctx.fireEvent(RetryEvents.Finished.EXCEPTION_NOT_RETRYABLE);
                throw e;
            } catch (Throwable e) {
                if (Thread.interrupted()) {
                    ctx.fireEvent(RetryEvents.Finished.EXCEPTION_NOT_RETRYABLE);
                    throw new InterruptedException();
                }

                if (shouldAbortRetryingOnException(e)) {
                    ctx.fireEvent(RetryEvents.Finished.EXCEPTION_NOT_RETRYABLE);
                    throw e;
                }

                lastFailure = e;
            }

            counter++;
        }

        if (counter > maxRetries) {
            ctx.fireEvent(RetryEvents.Finished.MAX_RETRIES_REACHED);
        } else {
            ctx.fireEvent(RetryEvents.Finished.MAX_DURATION_REACHED);
        }

        if (lastFailure != null) {
            throw sneakyThrow(lastFailure);
        } else {
            throw new FaultToleranceException(description + " reached max retries or max retry duration");
        }
    }

    boolean shouldAbortRetryingOnResult(Object value) {
        return resultDecision.isConsideredExpected(value);
    }

    boolean shouldAbortRetryingOnException(Throwable e) {
        return exceptionDecision.isConsideredExpected(e);
    }
}
