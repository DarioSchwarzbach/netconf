/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.impl;

import java.util.Collection;
import java.util.Collections;

import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationPublishService;
import org.opendaylight.controller.md.sal.dom.api.DOMNotificationService;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcProviderService;
import org.opendaylight.controller.sal.core.api.Broker.ProviderSession;
import org.opendaylight.controller.sal.core.api.Provider;
import org.opendaylight.netconf.api.monitoring.NetconfMonitoringService;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.netconf.monitoring.rev101004.netconf.state.sessions.Session;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yangpushserver.notification.NotificationEngine;
import org.opendaylight.yangpushserver.notification.PeriodicNotification;
import org.opendaylight.yangpushserver.rpc.RpcImpl;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.InstanceIdentifierBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * This class is a Binding Independent provider and provide the implementation
 * of yangpush producer application used for subscriptions to MD-SAL data store
 * and YANG push notifications.
 * 
 * @author Dario.Schwarzbach
 *
 */
public class YangpushProvider implements Provider, AutoCloseable, NetconfMonitoringService.SessionsListener {
	private static final Logger LOG = LoggerFactory.getLogger(YangpushProvider.class);
	public static final YangInstanceIdentifier NETCONF_TOPO_YID;
	// TODO Tried BaseNetconfNotificationListener,
	// netconf.api.monitoring.SessionListener,
	// org.apache.sshd.common.SessionListener,
	// NetconfSessionListener<NetconfServerSession> already to retrieve session
	// info
	static {
		final InstanceIdentifierBuilder builder = org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier
				.builder();
		builder.node(NetworkTopology.QNAME);

		NETCONF_TOPO_YID = builder.build();
	}

	private NetconfMonitoringService monitoringService;
	private DOMDataBroker globalDomDataBroker;
	private DOMNotificationPublishService globalDomNotificationPublisher;
	private DOMNotificationService globalDomNotificationService;
	private RpcImpl ypServerRpcImpl;
	private SubscriptionEngine subEngine;
	private NotificationEngine notificationEngine;

	/**
	 * Method called when the blueprint container is destroyed.
	 */
	public void close() {
		this.globalDomDataBroker = null;
		LOG.info("YangpushserverProvider Closed");
	}

	/**
	 * This method initializes DomDataBroker and Mountpoint service. This
	 * services needed throughout the lifetime of the yangpush application and
	 * registers its RPC implementation and Data change Listener with the
	 * MD-SAL.
	 */
	@Override
	public void onSessionInitiated(ProviderSession session) {
		LOG.info("YangpushServerProvider is registered first marker");
		// get the DOM versions of MD-SAL services
		this.globalDomDataBroker = session.getService(DOMDataBroker.class);

		this.subEngine = SubscriptionEngine.getInstance();
		this.subEngine.setDataBroker(globalDomDataBroker);
		// TODO this needs to be created
		this.subEngine.createSubscriptionDataStore();

		this.notificationEngine = NotificationEngine.getInstance();
		this.notificationEngine.setDOMBrokers(globalDomDataBroker, globalDomNotificationService,
				globalDomNotificationPublisher);
		this.notificationEngine.setProvider(this);

		final DOMRpcProviderService service = session.getService(DOMRpcProviderService.class);
		ypServerRpcImpl = new RpcImpl(service, this.globalDomDataBroker);

		LOG.info("YangpushServerProvider is registered second marker.");

		// this.globalDomDataBroker.registerDataChangeListener(LogicalDatastoreType.OPERATIONAL,
		// nodeIid, this,
		// AsyncDataBroker.DataChangeScope.SUBTREE);
	}

	@Override
	public Collection<ProviderFunctionality> getProviderFunctionality() {
		// Deprecated, not using.
		return Collections.emptySet();
	}

	public void setMonitoringService(NetconfMonitoringService monitoringService) {
		this.monitoringService = monitoringService;
		this.monitoringService.registerSessionsListener(this);
	}

	@Override
	public void onSessionStarted(Session session) {
		LOG.info("YangpushServerProvider {}.", session.getClass());
	}

	@Override
	public void onSessionEnded(Session session) {
		LOG.info("YangpushServerProvider is registered second marker {}.", session);

	}

	@Override
	public void onSessionsUpdated(Collection<Session> sessions) {
		LOG.info("YangpushServerProvider is registered second marker.{}", sessions);

	}

	public void pushNotification(PeriodicNotification notification) {
		monitoringService.getSessions().getSession().iterator().next();
	}
}