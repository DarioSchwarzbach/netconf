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

import org.json.JSONObject;
import org.json.XML;
import org.opendaylight.controller.config.util.xml.XmlUtil;
import org.opendaylight.netconf.api.NetconfMessage;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushChangeUpdate;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.common.base.Preconditions;

/**
 * Special type of netconf message that wraps an on change YANG push
 * notification like defined in {@link PushChangeUpdate}.
 * 
 * @author Dario.Schwarzbach
 *
 */
public final class OnChangeNotification extends NetconfMessage {
	private static final Logger LOG = LoggerFactory.getLogger(OnChangeNotification.class);

	public static final String PUSH_CHANGE_UPDATE = PushChangeUpdate.QNAME.getLocalName();
	public static final String PUSH_CHANGE_UPDATE_NAMESPACE = PushChangeUpdate.QNAME.getNamespace() + ":1.0";
	public static final String CHANGES_XML = "datastore-changes-xml";
	public static final String CHANGES_JSON = "datastore-changes-json";

	private final Date eventTime;
	private final String subscriptionID;

	/**
	 * Create new on change notification and capture the timestamp in the
	 * constructor
	 */
	public OnChangeNotification(final Document notificationContent, final String subscriptionID) {
		this(notificationContent, subscriptionID, new Date());
	}

	/**
	 * Create new notification with provided timestamp
	 */
	private OnChangeNotification(final Document notificationContent, final String subscriptionID,
			final Date eventTime) {
		super(wrapNotification(notificationContent, subscriptionID, eventTime));
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
	 * related netconf notification while supporting other encodings for the
	 * content (XML, JSON).
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
			final Date eventTime) {
		Preconditions.checkNotNull(notificationContent);
		Preconditions.checkNotNull(eventTime);

		LOG.info("Start wrapping content {} for on change notification of subscription with ID {}...",
				XmlUtil.toString(notificationContent), subscriptionID);
		String encoding = SubscriptionEngine.getInstance().getSubscription(subscriptionID).getEncoding();

		if (encoding.equals("encode-json")) {
			LOG.info("Encoding content to JSON...");
			final Document res = XmlUtil.newDocument();
			final Element baseNotification = notificationContent.getDocumentElement();
			final Element entireNotification = res.createElementNS(PeriodicNotification.NOTIFICATION_NAMESPACE,
					PeriodicNotification.NOTIFICATION);

			final Element eventTimeElement = res.createElement(PeriodicNotification.EVENT_TIME);
			eventTimeElement.setTextContent(
					getSerializedEventTime(eventTime, PeriodicNotification.RFC3339_DATE_FORMAT_BLUEPRINT));
			entireNotification.appendChild(eventTimeElement);

			final Element pushChangeUpdate = res.createElementNS(PUSH_CHANGE_UPDATE_NAMESPACE, PUSH_CHANGE_UPDATE);
			final Element subID = res.createElement(PeriodicNotification.SUB_ID);
			subID.setTextContent(subscriptionID);
			pushChangeUpdate.appendChild(subID);

			final Element timeOfUpdate = res.createElement(PeriodicNotification.TIME_OF_UPDATE);
			timeOfUpdate.setTextContent(
					getSerializedEventTime(eventTime, PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT));
			pushChangeUpdate.appendChild(timeOfUpdate);

			final Element datastoreChange = res.createElement(CHANGES_JSON);

			if (baseNotification != null) {
				JSONObject xmlContentToJson = XML.toJSONObject(XmlUtil.toString(baseNotification));
				datastoreChange.setTextContent(xmlContentToJson.toString(4));
			}

			pushChangeUpdate.appendChild(datastoreChange);
			entireNotification.appendChild(pushChangeUpdate);

			res.appendChild(entireNotification);
			LOG.info("Content for on change notification for subscription {} successfully wrapped: {}", subscriptionID,
					XmlUtil.toString(res));

			return res;
		} else {

			final Element baseNotification = notificationContent.getDocumentElement();
			final Element entireNotification = notificationContent
					.createElementNS(PeriodicNotification.NOTIFICATION_NAMESPACE, PeriodicNotification.NOTIFICATION);

			final Element eventTimeElement = notificationContent.createElement(PeriodicNotification.EVENT_TIME);
			eventTimeElement.setTextContent(
					getSerializedEventTime(eventTime, PeriodicNotification.RFC3339_DATE_FORMAT_BLUEPRINT));
			entireNotification.appendChild(eventTimeElement);

			final Element pushChangeUpdate = notificationContent.createElementNS(PUSH_CHANGE_UPDATE_NAMESPACE,
					PUSH_CHANGE_UPDATE);
			final Element subID = notificationContent.createElement(PeriodicNotification.SUB_ID);
			subID.setTextContent(subscriptionID);
			pushChangeUpdate.appendChild(subID);

			final Element timeOfUpdate = notificationContent.createElement(PeriodicNotification.TIME_OF_UPDATE);
			timeOfUpdate.setTextContent(
					getSerializedEventTime(eventTime, PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT));
			pushChangeUpdate.appendChild(timeOfUpdate);

			final Element datastoreChange = notificationContent.createElement(CHANGES_XML);

			if (baseNotification != null) {
				datastoreChange.appendChild(baseNotification);
			}
			pushChangeUpdate.appendChild(datastoreChange);
			entireNotification.appendChild(pushChangeUpdate);

			notificationContent.appendChild(entireNotification);
			LOG.info("Content for on change notification for subscription {} successfully wrapped: {}", subscriptionID,
					XmlUtil.toString(notificationContent));
			return notificationContent;
		}
	}

	private static String getSerializedEventTime(final Date eventTime, String pattern) {
		// SimpleDateFormat is not threadsafe, cannot be in a constant
		return new SimpleDateFormat(pattern).format(eventTime);
	}
}
