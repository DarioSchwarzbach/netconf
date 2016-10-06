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
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataTreeChangeListener;
import org.opendaylight.controller.md.sal.dom.api.DOMDataTreeChangeService;
import org.opendaylight.controller.md.sal.dom.api.DOMDataTreeIdentifier;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine.operations;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo.SubscriptionStreamStatus;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeCandidate;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeCandidateNode;
import org.opendaylight.yangtools.yang.data.api.schema.tree.ModificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class serves as listener for changes in md-sal data store and as
 * scheduler at the same time. Triggering on change notifications in the
 * {@link NotificationEngine} on changes.
 * 
 * @author Dario.Schwarzbach
 *
 */
public class OnChangeHandler implements AutoCloseable, DOMDataTreeChangeListener {
	private static final Logger LOG = LoggerFactory.getLogger(OnChangeHandler.class);

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private ScheduledFuture<?> trigger;

	private String subscriptionID;
	private String stream;
	private YangInstanceIdentifier yid;
	private String startTime;
	private String stopTime;
	private Long timeOfLastUpdate;
	private Long dampeningPeriod;

	private ListenerRegistration<OnChangeHandler> registration;
	private DOMDataTreeChangeService domDataTreeChangeService;

	/**
	 * Constructor for the on change handler that serves as scheduler and
	 * listener at the same time.
	 * 
	 * @param db
	 *            Data broker to retrieve the {@link DOMDataTreeChangeService}
	 *            what is used to register {@link OnChangeHandler} as
	 *            {@link DOMDataTreeChangeListener}
	 * @param stream
	 *            Part of the md-sal data store we are listening on (e.g.
	 *            YANG-PUSH, CONFIGURATION,...)
	 * @param yid
	 *            Identifier for a specific node we want to listen on
	 */
	public OnChangeHandler(DOMDataBroker db, String stream, YangInstanceIdentifier yid) {
		super();
		domDataTreeChangeService = (DOMDataTreeChangeService) db.getSupportedExtensions()
				.get(DOMDataTreeChangeService.class);
		this.stream = stream;
		this.yid = yid;
	}

	@Override
	public void close() throws Exception {
		if (this.registration != null) {
			registration.close();
		}
		if (this.trigger != null) {
			trigger.cancel(true);
			trigger = null;
		}
		if (this.scheduler != null) {
			scheduler.shutdown();
		}
		NotificationEngine.getInstance().unregisterNotification(subscriptionID);
	}

	/**
	 * Used to schedule when the listeners on the data store should be
	 * registered first, when sending notifications for the underlying
	 * subscription should stop and what period should be between every on
	 * change notification.
	 * 
	 * @param subscriptionID
	 *            ID of underlying subscription
	 * @param subStartTime
	 *            Time when listeners for this subscription are registered
	 * @param subStopTime
	 *            Time when sending of notifications stop
	 * @param dampeningPeriod
	 *            Minimum time between every notification
	 */
	public void scheduleNotification(String subscriptionID, String subStartTime, String subStopTime,
			Long dampeningPeriod, boolean noSynchOnStart) {
		DateFormat format = new SimpleDateFormat(PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT);

		this.subscriptionID = subscriptionID;
		this.startTime = subStartTime;
		this.stopTime = subStopTime;
		this.dampeningPeriod = dampeningPeriod;
		this.timeOfLastUpdate = 0l;

		if (!noSynchOnStart) {
			NotificationEngine.getInstance().periodicNotification(subscriptionID);
		}

		final Runnable triggerAction = new Runnable() {
			@Override
			public void run() {
				LOG.info("DOMDataTreeChangeListener for subscription {} registered and subscription set to active",
						subscriptionID);
				if (SubscriptionEngine.getInstance().getSubscription(subscriptionID)
						.getSubscriptionStreamStatus() == SubscriptionStreamStatus.inactive) {
					SubscriptionEngine.getInstance().getSubscription(subscriptionID)
							.setSubscriptionStreamStatus(SubscriptionStreamStatus.active);
				}
				registerListeners();
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
			trigger = scheduler.schedule(triggerAction, deltaTillStart, TimeUnit.MILLISECONDS);
			LOG.info("On change notification for subscription {} scheduled to start in {}ms with dampening period {}",
					subscriptionID, deltaTillStart, dampeningPeriod);
		}
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
							"On change notification for subscription {} reached its stop time and the subscription will be deleted",
							subscriptionID);
					quietClose();
				}
			}, deltaTillStop, TimeUnit.MILLISECONDS);
			LOG.info("On change notification for subscription {} scheduled with stop time {}", subscriptionID,
					stopTime);
		}
	}

	/**
	 * Used to register the listeners on data store for previously set
	 * parameters.
	 */
	private void registerListeners() {
		switch (stream) {
		case "YANG-PUSH":
			this.registration = domDataTreeChangeService.registerDataTreeChangeListener(
					new DOMDataTreeIdentifier(LogicalDatastoreType.OPERATIONAL, yid), this);
			this.registration = domDataTreeChangeService.registerDataTreeChangeListener(
					new DOMDataTreeIdentifier(LogicalDatastoreType.CONFIGURATION, yid), this);
			break;
		case "CONFIGURATION":
			this.registration = domDataTreeChangeService.registerDataTreeChangeListener(
					new DOMDataTreeIdentifier(LogicalDatastoreType.CONFIGURATION, yid), this);
			break;
		case "OPERATIONAL":
			this.registration = domDataTreeChangeService.registerDataTreeChangeListener(
					new DOMDataTreeIdentifier(LogicalDatastoreType.OPERATIONAL, yid), this);
			break;
		default:
			LOG.error("Stream {} not supported.", stream);
		}
	}

	/**
	 * Calls method close() but handles the exception already.
	 */
	public void quietClose() {
		try {
			this.close();
		} catch (Exception e) {
			throw new IllegalStateException("Unable to close registration", e);
		}
	}

	@Override
	public void onDataTreeChanged(Collection<DataTreeCandidate> changes) {
		LOG.info("Noticed changed data for subscription {}", subscriptionID);
		NotificationEngine notificationEngine = NotificationEngine.getInstance();
		Long currentTime = new Date().getTime();
		if (currentTime >= timeOfLastUpdate + dampeningPeriod) {
			LOG.info("Dampening period of {} over...next update will be triggered", dampeningPeriod);
			for (DataTreeCandidate change : changes) {
				DataTreeCandidateNode rootNode = change.getRootNode();
				if (rootNode.getModificationType() == ModificationType.WRITE) {
					LOG.info("Noticed a {} for data", ModificationType.WRITE);

					if (rootNode.getDataAfter() != null && rootNode.getDataAfter().isPresent()) {
						notificationEngine.onChangeNotification(subscriptionID, rootNode.getDataAfter().get());
					}
				} else if (rootNode.getModificationType() == ModificationType.SUBTREE_MODIFIED) {
					LOG.info("Noticed a {} for data", ModificationType.SUBTREE_MODIFIED);

					if (rootNode.getDataAfter() != null && rootNode.getDataAfter().isPresent()) {
						notificationEngine.onChangeNotification(subscriptionID, rootNode.getDataAfter().get());
					}
				} else if (rootNode.getModificationType() == ModificationType.DELETE) {
					LOG.info("Noticed a {} for data", ModificationType.DELETE);

					if (rootNode.getDataAfter() != null && rootNode.getDataAfter().isPresent()) {
						notificationEngine.onChangeNotification(subscriptionID, rootNode.getDataAfter().get());
					}
				}
			}
		} else {
			LOG.info("Dampening period of {} not over yet...no update will be triggered", dampeningPeriod);
		}
	}
}
