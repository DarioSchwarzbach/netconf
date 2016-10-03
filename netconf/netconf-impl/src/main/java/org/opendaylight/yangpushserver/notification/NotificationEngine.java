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
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataReadTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationPublishService;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationService;
import org.opendaylight.netconf.impl.NetconfServerSession;
import org.opendaylight.netconf.util.NetconfUtil;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.Subscriptions;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushChangeUpdate;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.PushUpdate;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yangpushserver.impl.YangpushProvider;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine.operations;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo.SubscriptionStreamStatus;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifierWithPredicates;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.MapNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeWriter;
import org.opendaylight.yangtools.yang.data.impl.codec.xml.XMLStreamNormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNodes;
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
	// Global dom notification service used to register listeners
	private DOMNotificationService globalDomNotificationService = null;
	// Global service for publishing notifications; Necessary with Netconf
	// server?
	private DOMNotificationPublishService globalNotificationPublisher = null;
	// Pointer to the provider to access session information
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
	 * Set global BI data broker, notification service and notification
	 * publisher to notification engine
	 * 
	 */
	public void setDOMBrokers(DOMDataBroker globalDomDataBroker, DOMNotificationService globalDomNotificationService,
			DOMNotificationPublishService globalDomNotificationPublisher) {
		this.globalDomDataBroker = globalDomDataBroker;
		this.globalDomNotificationService = globalDomNotificationService;
		this.globalNotificationPublisher = globalDomNotificationPublisher;
		DOMDataWriteTransaction tx1 = instance.globalDomDataBroker.newWriteOnlyTransaction();
		NodeIdentifier pushupdates = NodeIdentifier.create(NetworkTopology.QNAME);
		NodeIdentifier pushupdate = NodeIdentifier.create(Topology.QNAME);
		YangInstanceIdentifier iid = YangInstanceIdentifier.builder().node(NetworkTopology.QNAME).build();
		// Creates container node in BI way and
		// commit to MD-SAL at the start of the application.
		ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(pushupdates).build();
		YangInstanceIdentifier iid_1 = iid.node(Topology.QNAME);
		MapNode mn = Builders.mapBuilder().withNodeIdentifier(pushupdate).build();
		NodeIdentifier topId = NodeIdentifier.create(QName.create(NetworkTopology.QNAME, "topology-id"));
		YangInstanceIdentifier pid = YangInstanceIdentifier.builder().node(NetworkTopology.QNAME).node(Topology.QNAME)
				.build();

		NodeIdentifierWithPredicates p = new NodeIdentifierWithPredicates(
				QName.create(NetworkTopology.QNAME, "topology"), QName.create(NetworkTopology.QNAME, "topology-id"),
				"TEST");

		MapEntryNode men = ImmutableNodes.mapEntryBuilder().withNodeIdentifier(p)
				.withChild(ImmutableNodes.leafNode(topId, "TEST")).build();

		YangInstanceIdentifier yid = pid
				.node(new NodeIdentifierWithPredicates(Topology.QNAME, men.getIdentifier().getKeyValues()));

		tx1.merge(LogicalDatastoreType.OPERATIONAL, iid, cn);
		tx1.merge(LogicalDatastoreType.OPERATIONAL, iid_1, mn);
		tx1.merge(LogicalDatastoreType.OPERATIONAL, yid, men);
		try {
			tx1.submit().checkedGet();
		} catch (TransactionCommitFailedException e1) {
			e1.printStackTrace();
		}
	}

	/**
	 * Set global BI yang push server provider to notification engine
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
			// TODO Correct type when SubEngine is adapted?
			String stream = underlyingSub.getStream();

			// As alternative two ways to get a instance identifier: First via
			// builder to a generic stream, second via create and key to some
			// specific stream. Requires DataBroker and ReadOnlyTransaction.
			// InstanceIdentifier<Stream> iid =
			// InstanceIdentifier.builder(Netconf.class).child(Streams.class)
			// .child(Stream.class).build();
			// InstanceIdentifier<Stream> iid =
			// InstanceIdentifier.create(Netconf.class).child(Streams.class)
			// .child(Stream.class, new StreamKey("test"));

			// TODO Maybe identify data (as node) with filters before reading to
			// make data store access smaller.
			// YangInstanceIdentifier yid =
			// YangInstanceIdentifier.builder().node(Netconf.QNAME).node(Streams.QNAME)
			// .node(Stream.QNAME).build();

			// Preparations for read from data store TODO transactionChain?
			LOG.info("MARKER1");
			DOMDataReadTransaction readTransaction = this.globalDomDataBroker.newReadOnlyTransaction();
			LOG.info("MARKER2");
			CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> future = null;
			LOG.info("MARKER3");
			Optional<NormalizedNode<?, ?>> optional = Optional.absent();
			LOG.info("MARKER4");
			NormalizedNode<?, ?> data = null;
			// TODO Adapt to correct type of stream.
			switch (stream) {

			case "NETCONF":
				LOG.info("MARKER5");
				future = readTransaction.read(LogicalDatastoreType.OPERATIONAL,
						YangpushProvider.NETCONF_TOPO_YID);
				break;
			case "CONFIGURATION":
				LOG.info("MARKER6");

				future = readTransaction.read(LogicalDatastoreType.CONFIGURATION,
						YangpushProvider.NETCONF_TOPO_YID);
				break;
			case "OPERATIONAL":
				LOG.info("MARKER7");

				future = readTransaction.read(LogicalDatastoreType.OPERATIONAL,
						YangpushProvider.NETCONF_TOPO_YID);
				break;
			default:
				LOG.error("Stream {} not supported.", stream);
				return;
			}
			try {
				LOG.info("MARKER8: {}", future);

				optional = future.checkedGet();
				LOG.info("MARKER9: {}", optional);

				if (optional != null && optional.isPresent()) {
					LOG.info("MARKER10");
					data = optional.get();
					// Applying filters to the retrieved data.
					applyFilter();

					LOG.info("Data for periodic notification of subscription {} read successfully from data store: {}",
							subscriptionID, data);
				}
			} catch (ReadFailedException e) {
				LOG.warn("Reading data for notification failed:", e);
			}
			// As alternative to the PeriodicNotification class extending
			// NetconfMessage class
			// you could use DOMNotification and DOMNotificationPublishService
			// what
			// might be a more generic approach

			// ContainerNode cn =
			// Builders.containerBuilder().withNodeIdentifier(PERIODIC_NOTIFICATION_NI).addChild(data);
			LOG.info("MARKER11");
			DOMResult result = new DOMResult();
			LOG.info("MARKER12");
			result.setNode(XmlUtil.newDocument());
			LOG.info("MARKER13");
			try {
				writeNormalizedNode(data, result);
			} catch (IOException | XMLStreamException e) {
				LOG.warn("Transforming normalized node to dom result failed:", e);
			}
			LOG.info("MARKER14");
			PeriodicNotification notification = new PeriodicNotification((Document) result.getNode(), subscriptionID);
			// TODO Maybe move this part to the provider itself to later manage
			// other transport options
			LOG.info("Sending periodic notification for subscription with ID {}...", subscriptionID);
			provider.pushNotification(notification);
			LOG.info("Periodic notification for subscription with ID {} sent over session {}", subscriptionID);
		} else {
			LOG.info("Not processing periodic notification for subscription {}. Status: {}", subscriptionID,
					underlyingSub.getSubscriptionStreamStatus());
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
			OnChangeNotification notification = new OnChangeNotification((Document) result.getNode(), subscriptionID);
			// TODO Maybe move this part to the provider itself to later manage
			// other transport options.
			// Looking for the session that initialized this subscription and
			// sending notification
			LOG.info("Sending on change notification for subscription with ID {}...", subscriptionID);
//			NetconfServerSession session = (NetconfServerSession) provider.getNetconfSession(subscriptionID);
//			session.sendMessage(notification);
			LOG.info("On change notification for subscription with ID {} sent over session {}", subscriptionID);

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
		// TODO More parameters?
		String subStartTime = underlyingSubscription.getSubscriptionStartTime();
		String subStopTime = underlyingSubscription.getSubscriptionStopTime();
		// TODO change all YIDs?
		Long dampeningPeriod = underlyingSubscription.getDampeningPeriod();
//		boolean noSynchOnStart = underlyingSubscription.getNoSynchOnStart();
		boolean noSynchOnStart = false;
		OnChangeHandler handler = new OnChangeHandler(globalDomDataBroker, stream,
				YangInstanceIdentifier.builder().node(Subscriptions.QNAME).build());
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