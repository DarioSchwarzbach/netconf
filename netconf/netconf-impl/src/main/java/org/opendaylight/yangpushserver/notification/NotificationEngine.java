/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.notification;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.dom.DOMResult;

import org.opendaylight.controller.config.util.xml.XmlUtil;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataReadTransaction;
import org.opendaylight.netconf.util.NetconfUtil;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushChangeUpdate;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushUpdate;
import org.opendaylight.yangpushserver.impl.YangpushProvider;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo.SubscriptionStreamStatus;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeWriter;
import org.opendaylight.yangtools.yang.data.impl.codec.xml.XMLStreamNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;

/**
 * This is a singleton class handling all notification related processing for
 * YANG-PUSH at MD-SAL.
 * 
 * @author Dario.Schwarzbach
 *
 */
public class NotificationEngine {
	private static final Logger LOG = LoggerFactory.getLogger(NotificationEngine.class);
	// TODO Schema paths and node identifiers for push updates and on change
	// push updates intended to use for storing already sent notifications in
	// MD-SAL data store. Necessary to support replay feature capability of sub
	private static final SchemaPath PERIODIC_NOTIFICATION_PATH = SchemaPath.create(true, PushUpdate.QNAME);
	private static final SchemaPath ON_CHANGE_NOTIFICATION_PATH = SchemaPath.create(true, PushChangeUpdate.QNAME);
	private static final NodeIdentifier PERIODIC_NOTIFICATION_NI = NodeIdentifier.create(PushUpdate.QNAME);
	private static final NodeIdentifier ON_CHANGE_NOTIFICATION_NI = NodeIdentifier.create(PushChangeUpdate.QNAME);
	private static NotificationEngine instance = null;

	// Global data broker
	private DOMDataBroker globalDomDataBroker = null;

	// Pointer to the provider to push notifications
	private YangpushProvider provider = null;

	// TODO Support for one anchor time for multiple periodic subscriptions; to
	// avoid sending redundant notifications
	private Long anchorTime;

	// Map of the schedulers and data tree change listeners for each
	// subscription (key is subscription ID)
	// TODO Need optimization for scale
	private Map<String, PeriodicNotificationScheduler> notificationSchedulerMap = null;
	private Map<String, OnChangeHandler> notificationListenerMap = null;

	/**
	 * Constructor to create singleton instance
	 */
	protected NotificationEngine() {
		super();
		notificationSchedulerMap = new HashMap<String, PeriodicNotificationScheduler>();
		notificationListenerMap = new HashMap<String, OnChangeHandler>();
	}

	/**
	 * Method to return the singleton of {@link NotificationEngine} if already
	 * existing, otherwise creates the singleton and returns it.
	 * 
	 * @return this
	 */
	public static NotificationEngine getInstance() {
		if (instance == null) {
			instance = new NotificationEngine();
		}
		return instance;
	}

	/**
	 * Set global BI data broker to notification engine
	 * 
	 */
	public void setDataBroker(DOMDataBroker globalDomDataBroker) {
		this.globalDomDataBroker = globalDomDataBroker;
	}

	/**
	 * Set global BI yang push provider to notification engine
	 * 
	 */
	public void setProvider(YangpushProvider provider) {
		this.provider = provider;
	}

	/**
	 * This method is called by a {@link PeriodicNotificationScheduler} and
	 * leads to reading data from MD-SAL data store, transforming this data,
	 * composing a periodic notification and finally sending out the
	 * notification to the subscriber.
	 * 
	 * @param subscriptionID
	 *            ID of the subscription used to retrieve related data from
	 *            {@link SubscriptionEngine}.
	 */
	public void periodicNotification(String subscriptionID) {
		SubscriptionInfo underlyingSub = SubscriptionEngine.getInstance().getSubscription(subscriptionID);

		// Dont do anything if suspended, stopped etc.
		if (underlyingSub.getSubscriptionStreamStatus() == SubscriptionStreamStatus.active) {
			LOG.info("Processing periodic notification for active subscription {}...", subscriptionID);
			String stream = underlyingSub.getStream();

			// Preparations for read from data store TODO transactionChain?
			DOMDataReadTransaction readTransaction = this.globalDomDataBroker.newReadOnlyTransaction();
			CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> future = null;
			Optional<NormalizedNode<?, ?>> optional = Optional.absent();
			NormalizedNode<?, ?> data = null;

			switch (stream) {

			case "YANG-PUSH":
				future = readTransaction.read(LogicalDatastoreType.OPERATIONAL, YangpushProvider.ROOT);
				secondPeriodicNotification(subscriptionID);
				break;
			case "CONFIGURATION":
				future = readTransaction.read(LogicalDatastoreType.CONFIGURATION, YangpushProvider.ROOT);
				break;
			case "OPERATIONAL":
				future = readTransaction.read(LogicalDatastoreType.OPERATIONAL, YangpushProvider.ROOT);
				break;
			default:
				LOG.error("Stream {} not supported.", stream);
				return;
			}
			try {
				optional = future.checkedGet();

				if (optional != null && optional.isPresent()) {
					data = optional.get();

					LOG.info("Data for periodic notification of subscription {} read successfully from data store",
							subscriptionID);
				}
			} catch (ReadFailedException e) {
				LOG.warn("Reading data for notification failed:", e);
			}

			DOMResult result = new DOMResult();
			result.setNode(XmlUtil.newDocument());

			try {
				writeNormalizedNode(data, result);
			} catch (IOException | XMLStreamException e) {
				LOG.warn("Transforming normalized node to dom result failed:", e);
			}
			applyFilter(); // TODO
			PeriodicNotification notification = new PeriodicNotification((Document) result.getNode(), subscriptionID);
			// TODO Maybe move this part to the provider itself to later manage
			// other transport options
			LOG.info("Sending periodic notification for subscription with ID {}...", subscriptionID);
			provider.pushNotification(notification);
			LOG.info("Periodic notification for subscription with ID {} sent.", subscriptionID);
		} else {
			LOG.info("Not processing periodic notification for subscription {}. Status: {}", subscriptionID,
					underlyingSub.getSubscriptionStreamStatus());
		}
	}

	/**
	 * The only purpose of this method is to send another periodic notification
	 * for the second part of the data store when the subscribed stream is
	 * YANG-PUSH.
	 * 
	 * @param subscriptionID
	 *            ID of the subscription used to retrieve related data from
	 *            {@link SubscriptionEngine}.
	 */
	private void secondPeriodicNotification(String subscriptionID) {
		SubscriptionInfo underlyingSub = SubscriptionEngine.getInstance().getSubscription(subscriptionID);

		// Dont do anything if suspended, stopped etc.
		if (underlyingSub.getSubscriptionStreamStatus() == SubscriptionStreamStatus.active) {
			LOG.info("Processing second periodic notification (CONFIGURATION) for active subscription {}...",
					subscriptionID);
			String stream = underlyingSub.getStream();

			// Preparations for read from data store TODO transactionChain?
			DOMDataReadTransaction readTransaction = this.globalDomDataBroker.newReadOnlyTransaction();
			CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> future = null;
			Optional<NormalizedNode<?, ?>> optional = Optional.absent();
			NormalizedNode<?, ?> data = null;

			future = readTransaction.read(LogicalDatastoreType.CONFIGURATION, YangpushProvider.ROOT);

			try {
				optional = future.checkedGet();

				if (optional != null && optional.isPresent()) {
					data = optional.get();

					LOG.info(
							"Data for second periodic notification of subscription {} read successfully from data store",
							subscriptionID);
				}
			} catch (ReadFailedException e) {
				LOG.warn("Reading data for notification failed:", e);
			}

			DOMResult result = new DOMResult();
			result.setNode(XmlUtil.newDocument());

			try {
				writeNormalizedNode(data, result);
			} catch (IOException | XMLStreamException e) {
				LOG.warn("Transforming normalized node to dom result failed:", e);
			}
			applyFilter(); // TODO
			PeriodicNotification notification = new PeriodicNotification((Document) result.getNode(), subscriptionID);
			// TODO Maybe move this part to the provider itself to later manage
			// other transport options
			LOG.info("Sending second periodic notification (CONFIGURATION) for subscription with ID {}...",
					subscriptionID);
			provider.pushNotification(notification);
			LOG.info("Second periodic notification for subscription with ID {} sent (CONFIGURATION).", subscriptionID);
		} else {
			LOG.info("Not processing second periodic notification (CONFIGURATION) for subscription {}. Status: {}",
					subscriptionID, underlyingSub.getSubscriptionStreamStatus());
		}
	}

	/**
	 * This method is called by a {@link OnChangeHandler} when any changes to
	 * the MD-SAL data store occur. The given data is then transformed, put
	 * inside a composed notification and finally send out to the subscriber.
	 * 
	 * @param subscriptionID
	 *            ID of the subscription used to retrieve related data from
	 *            {@link SubscriptionEngine}.
	 * @param changedData
	 *            Subscribed data that have changed in data store given by
	 *            {@link OnChangeHandler}.
	 */
	public void onChangeNotification(String subscriptionID, NormalizedNode<?, ?> changedData) {
		SubscriptionInfo underlyingSub = SubscriptionEngine.getInstance().getSubscription(subscriptionID);

		// Dont do anything if suspended, stopped etc.
		if (underlyingSub.getSubscriptionStreamStatus() == SubscriptionStreamStatus.active) {
			LOG.info("Processing on change notification for active subscription {}...", subscriptionID);
			// Empty DOM result serving as container for the transformation of
			// the data
			DOMResult result = new DOMResult();
			result.setNode(XmlUtil.newDocument());
			try {
				writeNormalizedNode(changedData, result);
			} catch (IOException | XMLStreamException e) {
				LOG.warn("Transforming normalized node to dom result failed:", e);
			}
			applyFilter(); // TODO
			OnChangeNotification notification = new OnChangeNotification((Document) result.getNode(), subscriptionID);
			LOG.info("Sending on change notification for subscription with ID {}...", subscriptionID);
			provider.pushNotification(notification);
			LOG.info("On change notification for subscription with ID {} sent.", subscriptionID);

		} else {
			LOG.info("Not processing on change notification for subscription {}. Status: {}", subscriptionID,
					underlyingSub.getSubscriptionStreamStatus());
		}
	}

	/**
	 * Called by {@link SubscriptionEngine} to register for notifications
	 * related to a specific subscription. Used to set up a
	 * {@link PeriodicNotificationScheduler} for this periodic subscription and
	 * stores this scheduler locally.
	 * 
	 * @param subscriptionID
	 *            Underlying subscription ID for the future notifications
	 */
	public void registerPeriodicNotification(String subscriptionID) {
		SubscriptionInfo underlyingSubscription = SubscriptionEngine.getInstance().getSubscription(subscriptionID);
		String subStartTime = underlyingSubscription.getSubscriptionStartTime();
		String subStopTime = underlyingSubscription.getSubscriptionStopTime();
		Long period = underlyingSubscription.getPeriod();
		PeriodicNotificationScheduler scheduler = new PeriodicNotificationScheduler();
		scheduler.schedulePeriodicNotification(subscriptionID, subStartTime, subStopTime, period);
		this.notificationSchedulerMap.put(subscriptionID, scheduler);
		LOG.info("Periodic notification for subscription ID {} successfully registered", subscriptionID);
	}

	/**
	 * Called by {@link SubscriptionEngine} to register for notifications
	 * related to a specific subscription. Used to set up a
	 * {@link OnChangeHandler} for this on change subscription and stores this
	 * handler locally.
	 * 
	 * @param subscriptionID
	 *            Underlying subscription ID for the future notifications
	 */
	public void registerOnChangeNotification(String subscriptionID) {
		SubscriptionInfo underlyingSubscription = SubscriptionEngine.getInstance().getSubscription(subscriptionID);

		String stream = underlyingSubscription.getStream();
		String subStartTime = underlyingSubscription.getSubscriptionStartTime();
		String subStopTime = underlyingSubscription.getSubscriptionStopTime();
		Long dampeningPeriod = underlyingSubscription.getDampeningPeriod();
		boolean noSynchOnStart = underlyingSubscription.getNoSynchOnStart();

		OnChangeHandler handler = new OnChangeHandler(globalDomDataBroker, stream, YangpushProvider.ROOT);
		handler.scheduleNotification(subscriptionID, subStartTime, subStopTime, dampeningPeriod, noSynchOnStart);
		this.notificationListenerMap.put(subscriptionID, handler);
		LOG.info("On change notification for subscription ID {} successfully registered", subscriptionID);
	}

	/**
	 * Used to change the settings for one already existent notification related
	 * to a specific subscription.
	 * 
	 * @param subscriptionID
	 *            ID of the underlying subscription that was modified before by
	 *            e.g. a RPC call
	 */
	public void modifyRegisteredNotification(String subscriptionID) {
		if (notificationSchedulerMap.containsKey(subscriptionID)) {
			unregisterNotification(subscriptionID);
			registerPeriodicNotification(subscriptionID);
		} else if (notificationListenerMap.containsKey(subscriptionID)) {
			unregisterNotification(subscriptionID);
			registerOnChangeNotification(subscriptionID);
		} else {
			LOG.error("No notification registered for subscription with ID {}", subscriptionID);
		}
	}

	/**
	 * Used to stop sending notifications of any type for the given subscription
	 * ID.
	 */
	public void unregisterNotification(String subscriptionID) {
		if (this.notificationSchedulerMap.containsKey(subscriptionID)) {
			this.notificationSchedulerMap.get(subscriptionID).quietClose();
			this.notificationSchedulerMap.remove(subscriptionID);
			LOG.info("Periodic subscription with ID '{}' successfully unregistered", subscriptionID);
		} else if (this.notificationListenerMap.containsKey(subscriptionID)) {
			this.notificationListenerMap.get(subscriptionID).quietClose();
			this.notificationListenerMap.remove(subscriptionID);
			LOG.info("On change subscription with ID '{}' successfully unregistered", subscriptionID);
		} else {
			LOG.warn("Subscription ID '{}' not registered for periodic or on change notifications.", subscriptionID);
		}
	}// TODO changeNotification missing for modify subscription?

	private void applyFilter() {
		// TODO Applies the filters required for the specific subscription to
		// the retrieved data.
	}

	/**
	 * Transforms a data from the data store present as {@link NormalizedNode}
	 * into its XML representation inside a {@link Document}.
	 * 
	 * @param normalized
	 *            Data retrieved from data store
	 * @param result
	 *            A dom result the transformation is written to. Node of this
	 *            result has to be initialized before.
	 * @throws IOException
	 * @throws XMLStreamException
	 */
	private static void writeNormalizedNode(final NormalizedNode<?, ?> normalized, final DOMResult result)
			throws IOException, XMLStreamException {
		final XMLStreamWriter writer = NetconfUtil.XML_FACTORY.createXMLStreamWriter(result);
		try (final NormalizedNodeStreamWriter normalizedNodeStreamWriter = XMLStreamNormalizedNodeStreamWriter
				.createSchemaless(writer);
				final NormalizedNodeWriter normalizedNodeWriter = NormalizedNodeWriter
						.forStreamWriter(normalizedNodeStreamWriter)) {
			normalizedNodeWriter.write(normalized);
			normalizedNodeWriter.flush();
			LOG.info("Data {} successfully transformed to XML", normalized);
		} finally {
			try {
				if (writer != null) {
					writer.close();
				}
			} catch (final Exception e) {
				LOG.warn("Unable to close resource properly", e);
			}
		}
	}
}