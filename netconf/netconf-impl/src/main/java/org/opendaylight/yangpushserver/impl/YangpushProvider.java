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
import java.util.HashSet;
import java.util.Set;

import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcProviderService;
import org.opendaylight.controller.sal.core.api.Broker.ProviderSession;
import org.opendaylight.controller.sal.core.api.Provider;
import org.opendaylight.netconf.api.NetconfMessage;
import org.opendaylight.netconf.impl.NetconfServerSession;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.netconf.monitoring.rev101004.NetconfState;
import org.opendaylight.yangpushserver.notification.NotificationEngine;
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
public class YangpushProvider implements Provider, AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(YangpushProvider.class);
	public static final YangInstanceIdentifier ROOT = YangInstanceIdentifier.builder().build();

	private DOMDataBroker globalDomDataBroker;
	private RpcImpl ypServerRpcImpl;
	private SubscriptionEngine subEngine;
	private NotificationEngine notificationEngine;
	private Set<NetconfServerSession> serverSessions;

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
		serverSessions = new HashSet<>();
		// get the DOM versions of MD-SAL services
		this.globalDomDataBroker = session.getService(DOMDataBroker.class);

		this.subEngine = SubscriptionEngine.getInstance();
		this.subEngine.setDataBroker(globalDomDataBroker);
		this.subEngine.createSubscriptionDataStore();

		this.notificationEngine = NotificationEngine.getInstance();
		this.notificationEngine.setDataBroker(globalDomDataBroker);
		this.notificationEngine.setProvider(this);

		final DOMRpcProviderService service = session.getService(DOMRpcProviderService.class);
		ypServerRpcImpl = new RpcImpl(service, this.globalDomDataBroker);

		LOG.info("YangpushProvider is registered.");

		// this.globalDomDataBroker.registerDataChangeListener(LogicalDatastoreType.OPERATIONAL,
		// nodeIid, this,
		// AsyncDataBroker.DataChangeScope.SUBTREE);
	}

	@Override
	public Collection<ProviderFunctionality> getProviderFunctionality() {
		// Deprecated, not using.
		return Collections.emptySet();
	}

	public void pushNotification(NetconfMessage notification) {
		LOG.info("Push notification...");
		for (NetconfServerSession currentSession : serverSessions) {
			currentSession.sendMessage(notification);
			LOG.info("Pushed notification {} on session {}", notification, currentSession);
		}
	}

	public void onSessionsUp(NetconfServerSession serverSession) {
		LOG.info("YangpushProvider server session up: {}", serverSession);
		serverSessions.add(serverSession);
	}
}