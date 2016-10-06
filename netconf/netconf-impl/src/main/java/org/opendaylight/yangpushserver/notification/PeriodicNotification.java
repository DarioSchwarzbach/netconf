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
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushUpdate;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.common.base.Preconditions;

/**
 * Special type of netconf message that wraps a periodic YANG push notification
 * like defined in {@link PushUpdate}.
 * 
 * @author Dario.Schwarzbach
 *
 */
public final class PeriodicNotification extends NetconfMessage {
	private static final Logger LOG = LoggerFactory.getLogger(PeriodicNotification.class);

	public static final String NOTIFICATION = "notification";
	public static final String NOTIFICATION_NAMESPACE = "urn:ietf:params:xml:ns:netconf:notification:1.0";
	public static final String PUSH_UPDATE = PushUpdate.QNAME.getLocalName();
	public static final String PUSH_UPDATE_NAMESPACE = PushUpdate.QNAME.getNamespace() + ":1.0";
	public static final String SUB_ID = "subscription-id";
	public static final String TIME_OF_UPDATE = "time-of-update";
	public static final String CONTENT_XML = "datastore-contents-xml";
	public static final String CONTENT_JSON = "datastore-contents-json";
	public static final String RFC3339_DATE_FORMAT_BLUEPRINT = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	/**
	 * Not the exact YANG DateAndTime format because java time formats do not
	 * support microseconds. Instead incoming DateAndTime String will be cut
	 * off.
	 **/
	public static final String YANG_DATEANDTIME_FORMAT_BLUEPRINT = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'";
	public static final String EVENT_TIME = "eventTime";

	/**
	 * Used for unknown/un-parse-able event-times
	 */
	public static final Date UNKNOWN_EVENT_TIME = new Date(0);

	private final Date eventTime;
	private final String subscriptionID;

	/**
	 * Create new periodic notification and capture the timestamp in the
	 * constructor
	 */
	public PeriodicNotification(final Document notificationContent, final String subscriptionID) {
		this(notificationContent, subscriptionID, new Date());
	}

	/**
	 * Create new notification with provided timestamp
	 */
	private PeriodicNotification(final Document notificationContent, final String subscriptionID,
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

		LOG.info("Start wrapping content {} for periodic notification of subscription with ID {}...",
				XmlUtil.toString(notificationContent), subscriptionID);
		String encoding = SubscriptionEngine.getInstance().getSubscription(subscriptionID).getEncoding();

		if (encoding.equals("encode-json")) {
			LOG.info("Encoding content to JSON...");
			final Document res = XmlUtil.newDocument();
			final Element baseNotification = notificationContent.getDocumentElement();
			final Element entireNotification = res.createElementNS(NOTIFICATION_NAMESPACE, NOTIFICATION);

			final Element eventTimeElement = res.createElement(EVENT_TIME);
			eventTimeElement.setTextContent(getSerializedEventTime(eventTime, RFC3339_DATE_FORMAT_BLUEPRINT));
			entireNotification.appendChild(eventTimeElement);

			final Element pushUpdate = res.createElementNS(PUSH_UPDATE_NAMESPACE, PUSH_UPDATE);
			final Element subID = res.createElement(SUB_ID);
			subID.setTextContent(subscriptionID);
			pushUpdate.appendChild(subID);

			final Element timeOfUpdate = res.createElement(TIME_OF_UPDATE);
			timeOfUpdate.setTextContent(getSerializedEventTime(eventTime, YANG_DATEANDTIME_FORMAT_BLUEPRINT));
			pushUpdate.appendChild(timeOfUpdate);

			final Element datastoreContent = res.createElement(CONTENT_JSON);

			if (baseNotification != null) {
				JSONObject xmlContentToJson = XML.toJSONObject(XmlUtil.toString(baseNotification));
				datastoreContent.setTextContent(xmlContentToJson.toString(4));
			}

			pushUpdate.appendChild(datastoreContent);
			entireNotification.appendChild(pushUpdate);

			res.appendChild(entireNotification);
			LOG.info("Content for periodic notification for subscription {} successfully wrapped: {}", subscriptionID,
					XmlUtil.toString(res));

			return res;
		} else {
			LOG.info("Encoding content to XML...");
			final Element baseNotification = notificationContent.getDocumentElement();
			final Element entireNotification = notificationContent.createElementNS(NOTIFICATION_NAMESPACE,
					NOTIFICATION);

			final Element eventTimeElement = notificationContent.createElement(EVENT_TIME);
			eventTimeElement.setTextContent(getSerializedEventTime(eventTime, RFC3339_DATE_FORMAT_BLUEPRINT));
			entireNotification.appendChild(eventTimeElement);

			final Element pushUpdate = notificationContent.createElementNS(PUSH_UPDATE_NAMESPACE, PUSH_UPDATE);
			final Element subID = notificationContent.createElement(SUB_ID);
			subID.setTextContent(subscriptionID);
			pushUpdate.appendChild(subID);

			final Element timeOfUpdate = notificationContent.createElement(TIME_OF_UPDATE);
			timeOfUpdate.setTextContent(getSerializedEventTime(eventTime, YANG_DATEANDTIME_FORMAT_BLUEPRINT));
			pushUpdate.appendChild(timeOfUpdate);

			final Element datastoreContent;
			if (SubscriptionEngine.getInstance().getSubscription(subscriptionID).getEncoding().equals("encode-json")) {
				datastoreContent = notificationContent.createElement(CONTENT_JSON);

			} else {
				datastoreContent = notificationContent.createElement(CONTENT_XML);
			}
			if (baseNotification != null) {
				datastoreContent.appendChild(baseNotification);
			}
			pushUpdate.appendChild(datastoreContent);
			entireNotification.appendChild(pushUpdate);

			notificationContent.appendChild(entireNotification);
			LOG.info("Content for periodic notification for subscription {} successfully wrapped: {}", subscriptionID,
					XmlUtil.toString(notificationContent));

			return notificationContent;
		}
	}

	private static String getSerializedEventTime(final Date eventTime, String pattern) {
		// SimpleDateFormat is not threadsafe, cannot be in a constant
		return new SimpleDateFormat(pattern).format(eventTime);
	}
	// ##########TEST FOR NOTIFICATION STRUCTURE WITH SELF-BUILT NODE#######
	// public static final NodeIdentifier N_ESTABLISH_SUB_INPUT =
	// NodeIdentifier.create(EstablishSubscriptionInput.QNAME);
	// public static final String NOTIF_BIS =
	// "urn:ietf:params:xml:ns:yang:ietf-event-notifications";
	// public static final String NOTIF_BIS_DATE = "2016-06-15";
	// public static final QName N_ENCODING_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "encoding");
	// public static final QName N_STREAM_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "stream");
	// public static final QName N_START_TIME_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "startTime");
	// public static final QName N_STOP_TIME_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "stopTime");
	// public static final QName N_UPDATE_FILTER_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "update-filter");
	// public static final QName N_UPDATE_TRIGGER_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "update-trigger");
	// public static ContainerNode createNormalizedNode(String sid) {
	// Long period = 30l;
	// final ContainerNode cn =
	// Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_INPUT)
	// .withChild(ImmutableNodes.leafNode(N_ENCODING_NAME, "encode-xml"))
	// .withChild(ImmutableNodes.leafNode(N_STREAM_NAME, "push-update"))
	// .withChild(ImmutableNodes.leafNode(N_UPDATE_FILTER_NAME, ""))
	// .withChild(ImmutableNodes.leafNode(N_START_TIME_NAME, ""))
	// .withChild(ImmutableNodes.leafNode(N_STOP_TIME_NAME, ""))
	// .withChild(ImmutableNodes.leafNode(N_UPDATE_TRIGGER_NAME, period))
	// .build();
	//
	// return cn;
	// }
	// public static void main(String[] args) throws IOException,
	// TransformerException {
	// DOMResult result = new DOMResult();
	// NormalizedNode<?, ?> data = createNormalizedNode("");
	// result.setNode(XmlUtil.newDocument());
	// try {
	// writeNormalizedNode(data, result);
	// } catch (IOException | XMLStreamException e) {
	// // TODO Auto-generated catch block
	// e.printStackTrace();
	// }
	// Document doc = (Document) result.getNode();
	// printDocument(doc, System.out);
	//
	// }
}
