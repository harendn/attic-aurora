/**
 * Copyright 2013 Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Atomics;
import com.twitter.common.application.Lifecycle;
import com.twitter.common.base.Closure;
import com.twitter.common.base.Closures;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.StatImpl;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.Clock;
import com.twitter.common.util.StateMachine;
import com.twitter.common.util.StateMachine.Transition;
import com.twitter.common.zookeeper.Group.JoinException;
import com.twitter.common.zookeeper.ServerSet;
import com.twitter.common.zookeeper.SingletonService.LeaderControl;

import org.apache.aurora.scheduler.events.EventSink;
import org.apache.aurora.scheduler.events.PubsubEvent.DriverRegistered;
import org.apache.aurora.scheduler.events.PubsubEvent.EventSubscriber;
import org.apache.aurora.scheduler.events.PubsubEvent.SchedulerActive;
import org.apache.aurora.scheduler.storage.Storage.MutableStoreProvider;
import org.apache.aurora.scheduler.storage.Storage.MutateWork;
import org.apache.aurora.scheduler.storage.Storage.NonVolatileStorage;
import org.apache.aurora.scheduler.storage.Storage.StoreProvider;
import org.apache.aurora.scheduler.storage.Storage.Work;
import org.apache.aurora.scheduler.storage.StorageBackfill;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.twitter.common.zookeeper.SingletonService.LeadershipListener;

/**
 * The central driver of the scheduler runtime lifecycle.  Handles the transitions from startup and
 * initialization through acting as a standby scheduler / log replica and finally to becoming the
 * scheduler leader.
 * <p>
 * The (enforced) call order to be used with this class is:
 * <ol>
 *   <li>{@link #prepare()}, to initialize the storage system.</li>
 *   <li>{@link LeadershipListener#onLeading(LeaderControl) onLeading()} on the
 *       {@link LeadershipListener LeadershipListener}
 *       returned from {@link #prepare()}, signaling that this process has exclusive control of the
 *       cluster.</li>
 *   <li>{@link #registered(DriverRegistered) registered()},
 *       indicating that registration with the mesos master has succeeded.
 *       At this point, the scheduler's presence will be announced via
 *       {@link LeaderControl#advertise() advertise()}.</li>
 * </ol>
 * If this call order is broken, calls will fail by throwing
 * {@link java.lang.IllegalStateException}.
 * <p>
 * At any point in the lifecycle, the scheduler will respond to
 * {@link LeadershipListener#onDefeated(com.twitter.common.zookeeper.ServerSet.EndpointStatus)
 * onDefeated()} by initiating a clean shutdown using {@link Lifecycle#shutdown() shutdown()}.
 * A clean shutdown will also be initiated if control actions fail during normal state transitions.
 */
public class SchedulerLifecycle implements EventSubscriber {

  private static final Logger LOG = Logger.getLogger(SchedulerLifecycle.class.getName());

  private enum State {
    IDLE,
    PREPARING_STORAGE,
    STORAGE_PREPARED,
    LEADER_AWAITING_REGISTRATION,
    REGISTERED_LEADER,
    ACTIVE,
    DEAD
  }

  private static final Predicate<Transition<State>> IS_DEAD = new Predicate<Transition<State>>() {
    @Override public boolean apply(Transition<State> state) {
      return state.getTo() == State.DEAD;
    }
  };

  private static final Predicate<Transition<State>> NOT_DEAD = Predicates.not(IS_DEAD);

  private final LeadershipListener leadershipListener;
  private final AtomicBoolean registrationAcked = new AtomicBoolean(false);
  private final AtomicReference<LeaderControl> leaderControl = Atomics.newReference();
  private final StateMachine<State> stateMachine;

  @Inject
  SchedulerLifecycle(
      DriverFactory driverFactory,
      NonVolatileStorage storage,
      Lifecycle lifecycle,
      Driver driver,
      DriverReference driverRef,
      final LeadingOptions leadingOptions,
      final ScheduledExecutorService executorService,
      Clock clock,
      EventSink eventSink) {

    this(
        driverFactory,
        storage,
        lifecycle,
        driver,
        driverRef,
        new DelayedActions() {
          @Override public void blockingDriverJoin(Runnable runnable) {
            executorService.execute(runnable);
          }

          @Override public void onAutoFailover(Runnable runnable) {
            executorService.schedule(
                runnable,
                leadingOptions.leadingTimeLimit.getValue(),
                leadingOptions.leadingTimeLimit.getUnit().getTimeUnit());
          }

          @Override public void onRegistrationTimeout(Runnable runnable) {
            LOG.info(
                "Giving up on registration in " + leadingOptions.registrationDelayLimit);
            executorService.schedule(
                runnable,
                leadingOptions.registrationDelayLimit.getValue(),
                leadingOptions.registrationDelayLimit.getUnit().getTimeUnit());
          }
        },
        clock,
        eventSink);
  }

  @VisibleForTesting
  SchedulerLifecycle(
      final DriverFactory driverFactory,
      final NonVolatileStorage storage,
      final Lifecycle lifecycle,
      // TODO(wfarner): The presence of Driver and DriverReference is quite confusing.  Figure out
      //                a clean way to collapse the duties of DriverReference into DriverImpl.
      final Driver driver,
      final DriverReference driverRef,
      final DelayedActions delayedActions,
      final Clock clock,
      final EventSink eventSink) {

    Stats.export(new StatImpl<Integer>("framework_registered") {
      @Override public Integer read() {
        return registrationAcked.get() ? 1 : 0;
      }
    });
    for (final State state : State.values()) {
      Stats.export(new StatImpl<Integer>("scheduler_lifecycle_" + state) {
        @Override public Integer read() {
          return (state == stateMachine.getState()) ? 1 : 0;
        }
      });
    }

    final Closure<Transition<State>> prepareStorage = new Closure<Transition<State>>() {
      @Override public void execute(Transition<State> transition) {
        try {
          storage.prepare();
          stateMachine.transition(State.STORAGE_PREPARED);
        } catch (RuntimeException e) {
          stateMachine.transition(State.DEAD);
          throw e;
        }
      }
    };

    final Closure<Transition<State>> handleLeading = new Closure<Transition<State>>() {
      @Override public void execute(Transition<State> transition) {
        LOG.info("Elected as leading scheduler!");
        storage.start(new MutateWork.NoResult.Quiet() {
          @Override protected void execute(MutableStoreProvider storeProvider) {
            StorageBackfill.backfill(storeProvider, clock);
          }
        });

        @Nullable final String frameworkId = storage.consistentRead(
            new Work.Quiet<String>() {
              @Override public String apply(StoreProvider storeProvider) {
                return storeProvider.getSchedulerStore().fetchFrameworkId();
              }
            });
        driverRef.set(driverFactory.apply(frameworkId));

        delayedActions.onRegistrationTimeout(
            new Runnable() {
              @Override public void run() {
                if (!registrationAcked.get()) {
                  LOG.severe(
                      "Framework has not been registered within the tolerated delay.");
                  stateMachine.transition(State.DEAD);
                }
              }
            });

        delayedActions.onAutoFailover(
            new Runnable() {
              @Override public void run() {
                LOG.info("Triggering automatic failover.");
                stateMachine.transition(State.DEAD);
              }
            });

        Protos.Status status = driver.start();
        LOG.info("Driver started with code " + status);
        delayedActions.blockingDriverJoin(new Runnable() {
          @Override public void run() {
            // Blocks until driver exits.
            driver.join();
            stateMachine.transition(State.DEAD);
          }
        });
      }
    };

    final Closure<Transition<State>> handleRegistered = new Closure<Transition<State>>() {
      @Override public void execute(Transition<State> transition) {
        registrationAcked.set(true);
        eventSink.post(new SchedulerActive());
        try {
          leaderControl.get().advertise();
        } catch (JoinException e) {
          LOG.log(Level.SEVERE, "Failed to advertise leader, shutting down.", e);
          stateMachine.transition(State.DEAD);
        } catch (InterruptedException e) {
          LOG.log(Level.SEVERE, "Interrupted while advertising leader, shutting down.", e);
          stateMachine.transition(State.DEAD);
          Thread.currentThread().interrupt();
        }
      }
    };

    final Closure<Transition<State>> shutDown = new Closure<Transition<State>>() {
      private final AtomicBoolean invoked = new AtomicBoolean(false);
      @Override public void execute(Transition<State> transition) {
        if (!invoked.compareAndSet(false, true)) {
          LOG.info("Shutdown already invoked, ignoring extra call.");
          return;
        }

        // TODO(wfarner): Consider using something like guava's Closer to abstractly tear down
        // resources here.
        try {
          LeaderControl control = leaderControl.get();
          if (control != null) {
            try {
              control.leave();
            } catch (JoinException e) {
              LOG.log(Level.WARNING, "Failed to leave leadership: " + e, e);
            } catch (ServerSet.UpdateException e) {
              LOG.log(Level.WARNING, "Failed to leave server set: " + e, e);
            }
          }

          // TODO(wfarner): Re-evaluate tear-down ordering here.  Should the top-level shutdown
          // be invoked first, or the underlying critical components?
          driver.stop();
          storage.stop();
        } finally {
          lifecycle.shutdown();
        }
      }
    };

    stateMachine = StateMachine.<State>builder("SchedulerLifecycle")
        .initialState(State.IDLE)
        .logTransitions()
        .addState(
            Closures.filter(NOT_DEAD, prepareStorage),
            State.IDLE,
            State.PREPARING_STORAGE, State.DEAD)
        .addState(
            State.PREPARING_STORAGE,
            State.STORAGE_PREPARED, State.DEAD)
        .addState(
            Closures.filter(NOT_DEAD, handleLeading),
            State.STORAGE_PREPARED,
            State.LEADER_AWAITING_REGISTRATION, State.DEAD)
        .addState(
            Closures.filter(NOT_DEAD, handleRegistered),
            State.LEADER_AWAITING_REGISTRATION,
            State.REGISTERED_LEADER, State.DEAD)
        .addState(
            State.REGISTERED_LEADER,
            State.ACTIVE, State.DEAD)
        .addState(
            State.ACTIVE,
            State.DEAD)
        .addState(
            State.DEAD,
            // Allow cycles in DEAD to prevent throwing and avoid the need for call-site checking.
            State.DEAD
        )
        .onAnyTransition(
            Closures.filter(IS_DEAD, shutDown))
        .build();

    this.leadershipListener = new SchedulerCandidateImpl(stateMachine, leaderControl);
  }

  /**
   * Prepares a scheduler to offer itself as a leader candidate.  After this call the scheduler will
   * host a live log replica and start syncing data from the leader via the log until it gets called
   * upon to lead.
   *
   * @return A listener that can be offered for leadership of a distributed election.
   */
  public LeadershipListener prepare() {
    stateMachine.transition(State.PREPARING_STORAGE);
    return leadershipListener;
  }

  @Subscribe
  public void registered(DriverRegistered event) {
    stateMachine.transition(State.REGISTERED_LEADER);
  }

  /**
   * Maintains a reference to the driver.
   */
  static class DriverReference implements Supplier<Optional<SchedulerDriver>> {
    private final AtomicReference<SchedulerDriver> driver = Atomics.newReference();

    @Override public Optional<SchedulerDriver> get() {
      return Optional.fromNullable(driver.get());
    }

    private void set(SchedulerDriver ref) {
      driver.set(ref);
    }
  }

  private static class SchedulerCandidateImpl implements LeadershipListener {
    private final StateMachine<State> stateMachine;
    private final AtomicReference<LeaderControl> leaderControl;

    SchedulerCandidateImpl(
        StateMachine<State> stateMachine,
        AtomicReference<LeaderControl> leaderControl) {

      this.stateMachine = stateMachine;
      this.leaderControl = leaderControl;
    }

    @Override public void onLeading(LeaderControl control) {
      leaderControl.set(control);
      stateMachine.transition(State.LEADER_AWAITING_REGISTRATION);
    }

    @Override public void onDefeated(@Nullable ServerSet.EndpointStatus status) {
      LOG.severe("Lost leadership, committing suicide.");
      stateMachine.transition(State.DEAD);
    }
  }

  public static class LeadingOptions {
    private final Amount<Long, Time> registrationDelayLimit;
    private final Amount<Long, Time> leadingTimeLimit;

    /**
     * Creates a new collection of options for tuning leadership behavior.
     *
     * @param registrationDelayLimit Maximum amount of time to wait for framework registration to
     *                               complete.
     * @param leadingTimeLimit Maximum amount of time to serve as leader before abdicating.
     */
    public LeadingOptions(
        Amount<Long, Time> registrationDelayLimit,
        Amount<Long, Time> leadingTimeLimit) {

      Preconditions.checkArgument(
          registrationDelayLimit.getValue() >= 0,
          "Registration delay limit must be positive.");
      Preconditions.checkArgument(
          leadingTimeLimit.getValue() >= 0,
          "Leading time limit must be positive.");

      this.registrationDelayLimit = checkNotNull(registrationDelayLimit);
      this.leadingTimeLimit = checkNotNull(leadingTimeLimit);
    }
  }

  @VisibleForTesting
  interface DelayedActions {
    void blockingDriverJoin(Runnable runnable);

    void onAutoFailover(Runnable runnable);

    void onRegistrationTimeout(Runnable runnable);
  }
}
