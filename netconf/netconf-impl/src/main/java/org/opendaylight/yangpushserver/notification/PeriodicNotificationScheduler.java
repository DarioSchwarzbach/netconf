/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.notification;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushUpdate;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine.operations;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo.SubscriptionStreamStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The periodic notification scheduler is used to trigger the {@link PushUpdate}
 * notification (can be extended for all type of notifications, e.g. periodic
 * event notifications) periodically.
 * 
 * TODO Extend this class to support the anchor time of the
 * {@link NotificationEngine}.
 * 
 * @author Dario.Schwarzbach
 *
 */
public class PeriodicNotificationScheduler implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(PeriodicNotificationScheduler.class);
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private ScheduledFuture<?> trigger;
	private String startTime;
	private String stopTime;

	/**
	 * Used to schedule when a periodic notification should be send first, when
	 * sending should stop and what time in milliseconds should be between each
	 * notification.
	 * 
	 * @param subscriptionID
	 *            ID of underlying subscription
	 * @param subStartTime
	 *            Time when notifications are send for the first time
	 * @param subStopTime
	 *            Time when sending stops
	 * @param period
	 *            Time between every notification
	 */
	public void schedulePeriodicNotification(String subscriptionID, String subStartTime, String subStopTime,
			Long period) {
		DateFormat format = new SimpleDateFormat(PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT);

		this.startTime = ensureYangDateAndTimeFormat(subStartTime);
		this.stopTime = ensureYangDateAndTimeFormat(subStopTime);

		final Runnable triggerAction = new Runnable() {
			@Override
			public void run() {
				// Calls method periodicNotification in NotificationEngine
				// to compose and send a periodic notification for the
				// underlying subscription
				if (SubscriptionEngine.getInstance().getSubscription(subscriptionID)
						.getSubscriptionStreamStatus() == SubscriptionStreamStatus.inactive) {
					SubscriptionEngine.getInstance().getSubscription(subscriptionID)
							.setSubscriptionStreamStatus(SubscriptionStreamStatus.active);
				}
				LOG.info("Periodic notification for subscription {} is triggered", subscriptionID);
				NotificationEngine.getInstance().periodicNotification(subscriptionID);
			}
		};
		Long deltaTillStart = 0l;
		if (startTime != null) {
			try {
				deltaTillStart = Math.max(0, format.parse(startTime).getTime() - (new Date().getTime()));
			} catch (ParseException e) {
				LOG.warn("Subscription start time not in correct format for {} instead start time is {}",
						PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT, startTime);
			}
		}
		trigger = scheduler.scheduleAtFixedRate(triggerAction, deltaTillStart, period, TimeUnit.MILLISECONDS);
		LOG.info("Periodic notification for subscription {} scheduled to start in {}ms with period {}", subscriptionID,
				deltaTillStart, period);

		Long deltaTillStop = 0l;
		if (stopTime != null) {
			try {
				deltaTillStop = Math.max(0, format.parse(stopTime).getTime() - (new Date().getTime()));
			} catch (ParseException e) {
				LOG.warn("Subscription stop time not in correct format for {} instead stop time is {}",
						PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT, stopTime);
			}
		}
		if (deltaTillStop > 0) {
			scheduler.schedule(new Runnable() {

				@Override
				public void run() {
					SubscriptionInfo subscription = SubscriptionEngine.getInstance().getSubscription(subscriptionID);
					SubscriptionEngine.getInstance().updateMdSal(subscription, operations.delete);
					LOG.info(
							"Periodic notification for subscription {} reached its stop time and the subscription will be deleted",
							subscriptionID);
					NotificationEngine.getInstance().unregisterNotification(subscriptionID);
				}
			}, deltaTillStop, TimeUnit.MILLISECONDS);
			LOG.info("Periodic notification for subscription {} scheduled with stop time {}", subscriptionID, stopTime);
		}
	}

	@Override
	public void close() throws Exception {
		if (this.trigger != null) {
			trigger.cancel(true);
			trigger = null;
		}
		if (this.scheduler != null) {
			scheduler.shutdown();
		}
	}

	/**
	 * Calls method close() but handles the exception already.
	 */
	public void quietClose() {
		try {
			this.close();
		} catch (Exception e) {
			throw new IllegalStateException("Unable to close ressources", e);
		}
	}

	public static String ensureYangDateAndTimeFormat(String yangDateAndTime) {
		if (yangDateAndTime != null) {
			String newTime = yangDateAndTime;
			if (yangDateAndTime.contains(".")) {
				String newEnd = ".000";
				newEnd += yangDateAndTime.substring(yangDateAndTime.indexOf(".") + 1, yangDateAndTime.indexOf(".") + 4);
				newEnd += "Z";

				newTime = yangDateAndTime.substring(0, yangDateAndTime.indexOf("."));
				newTime += newEnd;
			} else {
				newTime = yangDateAndTime.substring(0, yangDateAndTime.indexOf("Z"));
				newTime += ".000000Z";
			}
			return newTime;
		}
		return null;
	}
}