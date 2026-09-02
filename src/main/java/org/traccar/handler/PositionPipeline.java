/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler;

import jakarta.inject.Inject;
import org.traccar.database.NotificationManager;
import org.traccar.handler.events.AlarmEventHandler;
import org.traccar.handler.events.BaseEventHandler;
import org.traccar.handler.events.BehaviorEventHandler;
import org.traccar.handler.events.CommandResultEventHandler;
import org.traccar.handler.events.DriverEventHandler;
import org.traccar.handler.events.FuelEventHandler;
import org.traccar.handler.events.GeofenceEventHandler;
import org.traccar.handler.events.IgnitionEventHandler;
import org.traccar.handler.events.MaintenanceEventHandler;
import org.traccar.handler.events.MediaEventHandler;
import org.traccar.handler.events.MotionEventHandler;
import org.traccar.handler.events.OverspeedEventHandler;
import org.traccar.handler.events.ProximityEventHandler;
import org.traccar.model.Position;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;


/** Executes the position processing stages without requiring a Netty context. */
public class PositionPipeline {

    public record Result(boolean persisted, boolean filtered) {}

    public interface Executor {
        boolean inEventLoop();

        void execute(Runnable command);
    }

    private final NotificationManager notificationManager;
    private final List<BasePositionHandler> positionHandlers;
    private final List<BaseEventHandler> eventHandlers;
    private final PostProcessHandler postProcessHandler;

    @Inject
    public PositionPipeline(
            com.google.inject.Injector injector, NotificationManager notificationManager) {
        this.notificationManager = notificationManager;
        positionHandlers = Stream.of(
                ComputedAttributesHandler.Early.class,
                OutdatedHandler.class,
                TimeHandler.class,
                GeolocationHandler.class,
                HemisphereHandler.class,
                MapMatcherHandler.class,
                DistanceHandler.class,
                FilterHandler.class,
                GeofenceHandler.class,
                GeocoderHandler.class,
                SpeedLimitHandler.class,
                MotionHandler.class,
                ComputedAttributesHandler.Late.class,
                DriverHandler.class,
                CopyAttributesHandler.class,
                EngineHoursHandler.class,
                PositionForwardingHandler.class,
                DatabaseHandler.class)
                .map(injector::getInstance)
                .filter(Objects::nonNull)
                .map(handler -> (BasePositionHandler) handler)
                .toList();
        eventHandlers = Stream.of(
                MediaEventHandler.class,
                CommandResultEventHandler.class,
                OverspeedEventHandler.class,
                BehaviorEventHandler.class,
                FuelEventHandler.class,
                MotionEventHandler.class,
                GeofenceEventHandler.class,
                ProximityEventHandler.class,
                AlarmEventHandler.class,
                IgnitionEventHandler.class,
                MaintenanceEventHandler.class,
                DriverEventHandler.class)
                .map(injector::getInstance)
                .filter(Objects::nonNull)
                .map(handler -> (BaseEventHandler) handler)
                .toList();
        postProcessHandler = injector.getInstance(PostProcessHandler.class);
    }

    public CompletionStage<Result> process(Position position) {
        return process(position, new Executor() {
            @Override
            public boolean inEventLoop() {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        }, null);
    }

    public CompletionStage<Result> process(Position position, Executor executor) {
        return process(position, executor, null);
    }

    /**
     * Processes a position using an optional persistence override. The override replaces the
     * {@link DatabaseHandler} persistence step, for example to write the position and a
     * deduplication record in a single transaction.
     */
    public CompletionStage<Result> process(
            Position position, Executor executor, PositionPersistenceHandler persistenceOverride) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        try {
            processPosition(position, executor, 0, true, persistenceOverride, result);
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private void continueProcessing(Executor executor, Runnable continuation) {
        if (executor.inEventLoop()) {
            continuation.run();
        } else {
            executor.execute(continuation);
        }
    }

    private void processPosition(
            Position position, Executor executor, int index, boolean persisted,
            PositionPersistenceHandler persistenceOverride, CompletableFuture<Result> result) {
        if (index == positionHandlers.size()) {
            processEvents(position, executor, persisted, result);
            return;
        }

        BasePositionHandler baseHandler = positionHandlers.get(index);
        // Fase B: bypass FilterHandler para dmj-mqtt (defensa en profundidad).
        // La ingesta móvil ya filtra en app (isPlausibleFix) y el server no debe
        // re-filtrar por maxSpeed/distance; saltamos el handler para no tirar puntos del replay.
        if (baseHandler instanceof FilterHandler && "dmj-mqtt".equals(position.getProtocol())) {
            processPosition(position, executor, index + 1, persisted, persistenceOverride, result);
            return;
        }
        if (baseHandler instanceof PositionPersistenceHandler) {
            PositionPersistenceHandler persistenceHandler = persistenceOverride != null
                    ? persistenceOverride : (PositionPersistenceHandler) baseHandler;
            persistenceHandler.persist(position).whenComplete((success, error) -> {
                try {
                    continueProcessing(executor, () -> processPosition(position, executor, index + 1,
                            persisted && error == null && Boolean.TRUE.equals(success),
                            persistenceOverride, result));
                } catch (Throwable callbackError) {
                    result.completeExceptionally(callbackError);
                }
            });
        } else {
            try {
                baseHandler.handlePosition(position, filtered -> {
                    try {
                        continueProcessing(executor, () -> {
                            if (filtered) {
                                result.complete(new Result(false, true));
                            } else {
                                processPosition(position, executor, index + 1, persisted,
                                        persistenceOverride, result);
                            }
                        });
                    } catch (Throwable callbackError) {
                        result.completeExceptionally(callbackError);
                    }
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }
    }

    private void processEvents(
            Position position, Executor executor, boolean persisted, CompletableFuture<Result> result) {
        try {
            eventHandlers.forEach(handler -> handler.analyzePosition(
                    position, event -> {
                        try {
                            notificationManager.updateEvents(Map.of(event, position));
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                    }));
            postProcessHandler.handlePosition(position, ignored -> result.complete(new Result(persisted, false)));
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

}
