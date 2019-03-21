/*
 * Copyright 2009-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.integration.monitor;

import java.util.concurrent.atomic.AtomicLong;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.integration.Message;
import org.springframework.integration.MessageChannel;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.util.StopWatch;

/**
 * Registers all message channels, and accumulates statistics about their performance. The statistics are then published
 * locally for other components to consume and publish remotely.
 *
 * @author Dave Syer
 * @author Helena Edelson
 * @since 2.0
 */
@ManagedResource
public class DirectChannelMetrics implements MethodInterceptor, MessageChannelMetrics {

	protected final Log logger = LogFactory.getLog(getClass());

	public static final long ONE_SECOND_SECONDS = 1;

	public static final long ONE_MINUTE_SECONDS = 60;

	public static final int DEFAULT_MOVING_AVERAGE_WINDOW = 10;


	private final ExponentialMovingAverage sendDuration = new ExponentialMovingAverage(
			DEFAULT_MOVING_AVERAGE_WINDOW);

	private final ExponentialMovingAverageRate sendErrorRate = new ExponentialMovingAverageRate(
			ONE_SECOND_SECONDS, ONE_MINUTE_SECONDS, DEFAULT_MOVING_AVERAGE_WINDOW);

	private final ExponentialMovingAverageRatio sendSuccessRatio = new ExponentialMovingAverageRatio(
			ONE_MINUTE_SECONDS, DEFAULT_MOVING_AVERAGE_WINDOW);

	private final ExponentialMovingAverageRate sendRate = new ExponentialMovingAverageRate(
			ONE_SECOND_SECONDS, ONE_MINUTE_SECONDS, DEFAULT_MOVING_AVERAGE_WINDOW);

	private final AtomicLong sendCount = new AtomicLong();

	private final AtomicLong sendErrorCount = new AtomicLong();

	private final String name;

	private final MessageChannel messageChannel;


	public DirectChannelMetrics(MessageChannel messageChannel, String name) {
		this.messageChannel = messageChannel;
		this.name = name;
	}


	public void destroy() {
		if (logger.isDebugEnabled()) {
			logger.debug(sendDuration);
		}
	}

	public MessageChannel getMessageChannel() {
		return messageChannel;
	}

	public String getName() {
		return name;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		String method = invocation.getMethod().getName();
		MessageChannel channel = (MessageChannel) invocation.getThis();
		return doInvoke(invocation, method, channel);
	}

	protected Object doInvoke(MethodInvocation invocation, String method, MessageChannel channel) throws Throwable {
		if ("send".equals(method)) {
			Message<?> message = (Message<?>) invocation.getArguments()[0];
			return monitorSend(invocation, channel, message);
		}
		return invocation.proceed();
	}

	private Object monitorSend(MethodInvocation invocation, MessageChannel channel, Message<?> message) throws Throwable {
		if (logger.isTraceEnabled()) {
			logger.trace("Recording send on channel(" + channel + ") : message(" + message + ")");
		}
		final StopWatch timer = new StopWatch(channel + ".send:execution");
		try {
			timer.start();

			sendCount.incrementAndGet();
			sendRate.increment();

			Object result = invocation.proceed();

			timer.stop();
			if ((Boolean)result) {
				sendSuccessRatio.success();
				sendDuration.append(timer.getTotalTimeMillis());
			}
			else {
				sendSuccessRatio.failure();
				sendErrorCount.incrementAndGet();
				sendErrorRate.increment();
			}
			return result;
		}
		catch (Throwable e) {
			sendSuccessRatio.failure();
			sendErrorCount.incrementAndGet();
			sendErrorRate.increment();
			throw e;
		}
		finally {
			if (logger.isTraceEnabled()) {
				logger.trace(timer);
			}
		}
	}

	@Override
	public synchronized void reset() {
		sendDuration.reset();
		sendErrorRate.reset();
		sendSuccessRatio.reset();
		sendRate.reset();
		sendCount.set(0);
		sendErrorCount.set(0);
	}

	@Override
	public int getSendCount() {
		return (int) sendCount.get();
	}

	@Override
	public long getSendCountLong() {
		return sendCount.get();
	}

	@Override
	public int getSendErrorCount() {
		return (int) sendErrorCount.get();
	}

	@Override
	public long getSendErrorCountLong() {
		return sendErrorCount.get();
	}

	@Override
	public double getTimeSinceLastSend() {
		return sendRate.getTimeSinceLastMeasurement();
	}

	@Override
	public double getMeanSendRate() {
		return sendRate.getMean();
	}

	@Override
	public double getMeanErrorRate() {
		return sendErrorRate.getMean();
	}

	@Override
	public double getMeanErrorRatio() {
		return 1 - sendSuccessRatio.getMean();
	}

	@Override
	public double getMeanSendDuration() {
		return sendDuration.getMean();
	}

	@Override
	public double getMinSendDuration() {
		return sendDuration.getMin();
	}

	@Override
	public double getMaxSendDuration() {
		return sendDuration.getMax();
	}

	@Override
	public double getStandardDeviationSendDuration() {
		return sendDuration.getStandardDeviation();
	}

	@Override
	public Statistics getSendDuration() {
		return sendDuration.getStatistics();
	}

	@Override
	public Statistics getSendRate() {
		return sendRate.getStatistics();
	}

	@Override
	public Statistics getErrorRate() {
		return sendErrorRate.getStatistics();
	}

	@Override
	public String toString() {
		return String.format("MessageChannelMonitor: [name=%s, sends=%d]", name, sendCount.get());
	}

}
