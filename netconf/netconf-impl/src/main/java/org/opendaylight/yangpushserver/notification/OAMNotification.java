/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.notification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.opendaylight.controller.config.util.xml.XmlUtil;
import org.opendaylight.netconf.api.NetconfMessage;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf._5277.netmod.rev160615.NotificationComplete;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf._5277.netmod.rev160615.ReplayComplete;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.AddedToSubscription;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.RemovedFromSubscription;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.SubscriptionModified;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.SubscriptionResumed;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.SubscriptionStarted;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.SubscriptionSuspended;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.SubscriptionTerminated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.common.base.Preconditions;

/**
 * Special type of netconf message that wraps an OAM notification like defined
 * in {@link ReplayComplete}, {@link NotificationComplete},
 * {@link SubscriptionStarted}, {@link SubscriptionSuspended},
 * {@link SubscriptionResumed}, {@link SubscriptionModified},
 * {@link SubscriptionTerminated}, {@link AddedToSubscription} or
 * {@link RemovedFromSubscription}.
 * 
 * @author Dario.Schwarzbach
 *
 */
public final class OAMNotification extends NetconfMessage {
	private static final Logger LOG = LoggerFactory.getLogger(OAMNotification.class);
	private static final Map<OAMStatus, String> statusToName;
	private static final Map<OAMStatus, String> statusToNamespace;

	public static final String REPLAY_COMPLETE = ReplayComplete.QNAME.getLocalName();
	public static final String REPLAY_COMPLETE_NAMESPACE = ReplayComplete.QNAME.getNamespace().toString();
	public static final String NOTIFICATION_COMPLETE = NotificationComplete.QNAME.getLocalName();
	public static final String NOTIFICATION_COMPLETE_NAMESPACE = NotificationComplete.QNAME.getNamespace().toString();
	public static final String SUBSCRIPTION_STARTED = SubscriptionStarted.QNAME.getLocalName();
	public static final String SUBSCRIPTION_STARTED_NAMESPACE = SubscriptionStarted.QNAME.getNamespace().toString();
	public static final String SUBSCRIPTION_SUSPENDED = SubscriptionSuspended.QNAME.getLocalName();
	public static final String SUBSCRIPTION_SUSPENDED_NAMESPACE = SubscriptionSuspended.QNAME.getNamespace().toString();
	public static final String SUBSCRIPTION_RESUMED = SubscriptionResumed.QNAME.getLocalName();
	public static final String SUBSCRIPTION_RESUMED_NAMESPACE = SubscriptionResumed.QNAME.getNamespace().toString();
	public static final String SUBSCRIPTION_MODIFIED = SubscriptionModified.QNAME.getLocalName();
	public static final String SUBSCRIPTION_MODIFIED_NAMESPACE = SubscriptionModified.QNAME.getNamespace().toString();
	public static final String SUBSCRIPTION_TERMINATED = SubscriptionTerminated.QNAME.getLocalName();
	public static final String SUBSCRIPTION_TERMINATED_NAMESPACE = SubscriptionTerminated.QNAME.getNamespace()
			.toString();
	public static final String ADDED_TO_SUBSCRIPTION = AddedToSubscription.QNAME.getLocalName();
	public static final String ADDED_TO_SUBSCRIPTION_NAMESPACE = AddedToSubscription.QNAME.getNamespace().toString();
	public static final String REMOVED_FROM_SUBSCRIPTION = RemovedFromSubscription.QNAME.getLocalName();
	public static final String REMOVED_FROM_SUBSCRIPTION_NAMESPACE = RemovedFromSubscription.QNAME.getNamespace()
			.toString();

	static {
		statusToName = new HashMap<>();
		statusToName.put(OAMStatus.added_to_subscription, ADDED_TO_SUBSCRIPTION);
		statusToName.put(OAMStatus.notificationComplete, NOTIFICATION_COMPLETE);
		statusToName.put(OAMStatus.removed_from_subscription, REMOVED_FROM_SUBSCRIPTION);
		statusToName.put(OAMStatus.replayComplete, REPLAY_COMPLETE);
		statusToName.put(OAMStatus.subscription_modified, SUBSCRIPTION_MODIFIED);
		statusToName.put(OAMStatus.subscription_resumed, SUBSCRIPTION_RESUMED);
		statusToName.put(OAMStatus.subscription_started, SUBSCRIPTION_STARTED);
		statusToName.put(OAMStatus.subscription_suspended, SUBSCRIPTION_SUSPENDED);
		statusToName.put(OAMStatus.subscription_terminated, SUBSCRIPTION_TERMINATED);

		statusToNamespace = new HashMap<>();
		statusToNamespace.put(OAMStatus.added_to_subscription, ADDED_TO_SUBSCRIPTION_NAMESPACE);
		statusToNamespace.put(OAMStatus.notificationComplete, NOTIFICATION_COMPLETE_NAMESPACE);
		statusToNamespace.put(OAMStatus.removed_from_subscription, REMOVED_FROM_SUBSCRIPTION_NAMESPACE);
		statusToNamespace.put(OAMStatus.replayComplete, REPLAY_COMPLETE_NAMESPACE);
		statusToNamespace.put(OAMStatus.subscription_modified, SUBSCRIPTION_MODIFIED_NAMESPACE);
		statusToNamespace.put(OAMStatus.subscription_resumed, SUBSCRIPTION_RESUMED_NAMESPACE);
		statusToNamespace.put(OAMStatus.subscription_started, SUBSCRIPTION_STARTED_NAMESPACE);
		statusToNamespace.put(OAMStatus.subscription_suspended, SUBSCRIPTION_SUSPENDED_NAMESPACE);
		statusToNamespace.put(OAMStatus.subscription_terminated, SUBSCRIPTION_TERMINATED_NAMESPACE);
	}

	private final Date eventTime;
	private final String subscriptionID;

	/**
	 * Create new on change notification and capture the timestamp in the
	 * constructor
	 */
	/**
	 * Create a new OAM notification related to the given {@link OAMStatus} and
	 * capture the timestamp in the constructor
	 * 
	 * @param base
	 *            Empty {@link Document} used to compose the notification
	 * @param subscriptionID
	 *            Related ID the OAM notification was triggered for
	 * @param status
	 *            The {@link OAMStatus} this notification will represent
	 */
	public OAMNotification(final Document base, final String subscriptionID, OAMStatus status) {
		this(base, subscriptionID, status, new Date());
	}

	/**
	 * Create new notification with provided timestamp
	 */
	private OAMNotification(final Document base, final String subscriptionID, OAMStatus status, final Date eventTime) {
		super(wrapNotification(base, subscriptionID, eventTime, status));
		this.subscriptionID = subscriptionID;
		this.eventTime = eventTime;
	}

	/**
	 * @return Notification event time
	 */
	public Date getEventTime() {
		return eventTime;
	}

	/**
	 * @return Underlying subscription ID
	 */
	public String getSubscrptionID() {
		return subscriptionID;
	}

	/**
	 * Wraps the previously to a XML {@link Document} transformed data into the
	 * related netconf notification.
	 * 
	 * @param notificationContent
	 *            Previously transformed data
	 * @param subscriptionID
	 *            Underlying subscription ID
	 * @param eventTime
	 *            Time when this notification is send
	 * @return
	 */

	private static Document wrapNotification(final Document notificationContent, final String subscriptionID,
			final Date eventTime, final OAMStatus status) {
		Preconditions.checkNotNull(notificationContent);
		Preconditions.checkNotNull(eventTime);

		LOG.info("Start composing '{}' notification of subscription with ID {}...", status, subscriptionID);

		final Element entireNotification = notificationContent
				.createElementNS(PeriodicNotification.NOTIFICATION_NAMESPACE, PeriodicNotification.NOTIFICATION);

		final Element eventTimeElement = notificationContent.createElement(PeriodicNotification.EVENT_TIME);
		eventTimeElement
				.setTextContent(getSerializedEventTime(eventTime, PeriodicNotification.RFC3339_DATE_FORMAT_BLUEPRINT));
		entireNotification.appendChild(eventTimeElement);

		final Element notifiationType = notificationContent.createElementNS(statusToNamespace.get(status),
				statusToName.get(status));
		final Element subID = notificationContent.createElement(PeriodicNotification.SUB_ID);
		subID.setTextContent(subscriptionID);
		notifiationType.appendChild(subID);

		entireNotification.appendChild(notifiationType);

		notificationContent.appendChild(entireNotification);
		LOG.info("Content for {} notification for subscription {} successfully composed: {}", status, subscriptionID,
				XmlUtil.toString(notificationContent));

		return notificationContent;
	}

	private static String getSerializedEventTime(final Date eventTime, String pattern) {
		// SimpleDateFormat is not threadsafe, cannot be in a constant
		return new SimpleDateFormat(pattern).format(eventTime);
	}

	public static enum OAMStatus {
		replayComplete, notificationComplete, subscription_started, subscription_suspended, subscription_resumed, subscription_modified, subscription_terminated, added_to_subscription, removed_from_subscription
	}
}