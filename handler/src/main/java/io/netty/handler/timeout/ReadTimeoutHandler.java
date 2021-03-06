/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelStateHandlerAdapter;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;

import java.nio.channels.Channels;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raises a {@link ReadTimeoutException} when no data was read within a certain
 * period of time.
 *
 * <pre>
 * public class MyPipelineFactory implements {@link ChannelPipelineFactory} {
 *
 *     private final {@link Timer} timer;
 *     private final {@link ChannelHandler} timeoutHandler;
 *
 *     public MyPipelineFactory({@link Timer} timer) {
 *         this.timer = timer;
 *         this.timeoutHandler = <b>new {@link ReadTimeoutHandler}(timer, 30), // timer must be shared.</b>
 *     }
 *
 *     public {@link ChannelPipeline} getPipeline() {
 *         // An example configuration that implements 30-second read timeout:
 *         return {@link Channels}.pipeline(
 *             timeoutHandler,
 *             new MyHandler());
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * {@link Timer} timer = new {@link HashedWheelTimer}();
 * ...
 * bootstrap.setPipelineFactory(new MyPipelineFactory(timer));
 * ...
 * </pre>
 *
 * The {@link Timer} which was specified when the {@link ReadTimeoutHandler} is
 * created should be stopped manually by calling {@link #releaseExternalResources()}
 * or {@link Timer#stop()} when your application shuts down.
 * @see WriteTimeoutHandler
 * @see IdleStateHandler
 *
 * @apiviz.landmark
 * @apiviz.uses io.netty.util.HashedWheelTimer
 * @apiviz.has io.netty.handler.timeout.TimeoutException oneway - - raises
 */
public class ReadTimeoutHandler extends ChannelStateHandlerAdapter {

    private final long timeoutMillis;

    private volatile ScheduledFuture<?> timeout;
    private volatile long lastReadTime;

    private volatile boolean destroyed;

    private boolean closed;

    /**
     * Creates a new instance.
     *
     * @param timeoutSeconds
     *        read timeout in seconds
     */
    public ReadTimeoutHandler(int timeoutSeconds) {
        this(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Creates a new instance.
     *
     * @param timeout
     *        read timeout
     * @param unit
     *        the {@link TimeUnit} of {@code timeout}
     */
    public ReadTimeoutHandler(long timeout, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        if (timeout <= 0) {
            timeoutMillis = 0;
        } else {
            timeoutMillis = Math.max(unit.toMillis(timeout), 1);
        }
    }

    @Override
    public void beforeAdd(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive()) {
            // channelActvie() event has been fired already, which means this.channelActive() will
            // not be invoked. We have to initialize here instead.
            initialize(ctx);
        } else {
            // channelActive() event has not been fired yet.  this.channelOpen() will be invoked
            // and initialization will occur there.
        }
    }

    @Override
    public void beforeRemove(ChannelHandlerContext ctx) throws Exception {
        destroy();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx)
            throws Exception {
        // This method will be invoked only if this handler was added
        // before channelActive() event is fired.  If a user adds this handler
        // after the channelActive() event, initialize() will be called by beforeAdd().
        initialize(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.channelInactive(ctx);
    }

    @Override
    public void inboundBufferUpdated(ChannelHandlerContext ctx) throws Exception {
        lastReadTime = System.currentTimeMillis();
        ctx.fireInboundBufferUpdated();
    }

    private void initialize(ChannelHandlerContext ctx) {
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        if (destroyed) {
            return;
        }

        lastReadTime = System.currentTimeMillis();
        if (timeoutMillis > 0) {
            timeout = ctx.executor().schedule(
                    new ReadTimeoutTask(ctx),
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void destroy() {
        destroyed = true;

        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
    }

    protected void readTimedOut(ChannelHandlerContext ctx) throws Exception {
        if (!closed) {
            ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
            ctx.close();
            closed = true;
        }
    }

    private final class ReadTimeoutTask implements Runnable {

        private final ChannelHandlerContext ctx;

        ReadTimeoutTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            if (!ctx.channel().isOpen()) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            long nextDelay = timeoutMillis - (currentTime - lastReadTime);
            if (nextDelay <= 0) {
                // Read timed out - set a new timeout and notify the callback.
                timeout = ctx.executor().schedule(this, timeoutMillis, TimeUnit.MILLISECONDS);
                try {
                    readTimedOut(ctx);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Read occurred before the timeout - set a new timeout with shorter delay.
                timeout = ctx.executor().schedule(this, nextDelay, TimeUnit.MILLISECONDS);
            }
        }
    }
}
