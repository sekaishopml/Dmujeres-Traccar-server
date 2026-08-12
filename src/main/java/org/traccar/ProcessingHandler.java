/*
 * Copyright 2024 - 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.database.BufferingManager;
import org.traccar.handler.PositionPipeline;
import org.traccar.handler.network.AcknowledgementHandler;
import org.traccar.helper.PositionLogger;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

@Singleton
@ChannelHandler.Sharable
public class ProcessingHandler extends ChannelInboundHandlerAdapter implements BufferingManager.Callback {

    private final CacheManager cacheManager;
    private final PositionLogger positionLogger;
    private final BufferingManager bufferingManager;
    private final PositionPipeline positionPipeline;

    private record QueuedPosition(ChannelHandlerContext ctx, Position position) {}

    private final Map<Long, Queue<QueuedPosition>> queues = new HashMap<>();

    private synchronized Queue<QueuedPosition> getQueue(long deviceId) {
        return queues.computeIfAbsent(deviceId, k -> new LinkedList<>());
    }

    @Inject
    public ProcessingHandler(
            Config config,
            CacheManager cacheManager, PositionLogger positionLogger, PositionPipeline positionPipeline) {
        this.cacheManager = cacheManager;
        this.positionLogger = positionLogger;
        bufferingManager = new BufferingManager(config, this);
        this.positionPipeline = positionPipeline;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Position position) {
            cacheManager.addDevice(position.getDeviceId(), position);
            bufferingManager.accept(ctx, position);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void onReleased(ChannelHandlerContext context, Position position) {
        Queue<QueuedPosition> queue = getQueue(position.getDeviceId());
        boolean queued;
        synchronized (queue) {
            queued = !queue.isEmpty();
            queue.offer(new QueuedPosition(context, position));
        }
        if (!queued) {
            processPositionHandlers(context, position);
        }
    }

    private void processPositionHandlers(ChannelHandlerContext ctx, Position position) {
        positionPipeline.process(position, new PositionPipeline.Executor() {
            @Override
            public boolean inEventLoop() {
                return ctx.executor().inEventLoop();
            }

            @Override
            public void execute(Runnable command) {
                ctx.executor().execute(command);
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                try {
                    ctx.fireExceptionCaught(error);
                } finally {
                    finishedProcessing(ctx, position, true);
                }
            } else {
                finishedProcessing(ctx, position, result.filtered());
            }
        });
    }

    private void finishedProcessing(ChannelHandlerContext ctx, Position position, boolean filtered) {
        if (!filtered) {
            positionLogger.log(ctx, position);
        }
        ctx.writeAndFlush(new AcknowledgementHandler.EventHandled(position));
        processNextPosition(position.getDeviceId());
        cacheManager.removeDevice(position.getDeviceId(), position);
    }

    private void processNextPosition(long deviceId) {
        Queue<QueuedPosition> queue = getQueue(deviceId);
        QueuedPosition next;
        synchronized (queue) {
            queue.poll(); // remove current position
            next = queue.peek();
        }
        if (next != null) {
            next.ctx().executor().execute(() -> processPositionHandlers(next.ctx(), next.position()));
        }
    }

}
