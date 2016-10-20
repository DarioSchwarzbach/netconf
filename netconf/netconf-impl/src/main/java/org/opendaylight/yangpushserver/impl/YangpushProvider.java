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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcProviderService;
import org.opendaylight.controller.sal.core.api.Broker.ProviderSession;
import org.opendaylight.controller.sal.core.api.Provider;
import org.opendaylight.netconf.api.NetconfMessage;
import org.opendaylight.netconf.impl.NetconfServerSession;
import org.opendaylight.yangpushserver.notification.NotificationEngine;
import org.opendaylight.yangpushserver.rpc.RpcImpl;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine.operations;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * This class is a Binding Independent provider and provides the implementation
 * of yangpush producer application used for subscriptions to MD-SAL data store
 * and YANG push notifications.
 * 
 * @author Dario.Schwarzbach
 *
 */
public class YangpushProvider implements Provider, AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(YangpushProvider.class);
	public static final YangInstanceIdentifier ROOT = YangInstanceIdentifier.builder().build();
	/**
	 * Used whenever notifications are scheduled to ensure that a rpc reply is
	 * sent out before the notifications.
	 */
	public static final Long DELAY_TO_ENSURE_RPC_REPLY = 100l;

	private DOMDataBroker globalDomDataBroker;
	private RpcImpl ypServerRpcImpl;
	private SubscriptionEngine subEngine;
	private NotificationEngine notificationEngine;
	private String latestEstablishedSubscriptionID = "0";
	private String priorEstablishedSubscriptionID = "0";
	private Map<NetconfServerSession, Set<String>> serverSessionToSubIds;

	/**
	 * Method called when the blueprint container is destroyed.
	 */
	public void close() {
		this.globalDomDataBroker = null;
		LOG.info("YangpushserverProvider Closed");
	}

	/**
	 * This method initializes {@link DOMDataBroker}, {@link NotificationEngine}
	 * and {@link SubscriptionEngine}. These services are needed throughout the
	 * lifetime of the yangpush application and registers its RPC implementation
	 * with the MD-SAL.
	 */
	@Override
	public void onSessionInitiated(ProviderSession session) {
		serverSessionToSubIds = new HashMap<>();
		// get the DOM version of MD-SAL services
		this.globalDomDataBroker = session.getService(DOMDataBroker.class);

		this.subEngine = SubscriptionEngine.getInstance();
		this.subEngine.setDataBroker(globalDomDataBroker);
		this.subEngine.createSubscriptionDataStore();

		this.notificationEngine = NotificationEngine.getInstance();
		this.notificationEngine.setDataBroker(globalDomDataBroker);
		this.notificationEngine.setProvider(this);

		final DOMRpcProviderService service = session.getService(DOMRpcProviderService.class);
		ypServerRpcImpl = new RpcImpl(service, this.globalDomDataBroker, this);

		LOG.info("YangpushProvider is registered.");
	}

	/**
	 * @deprecated not using
	 */
	@Override
	public Collection<ProviderFunctionality> getProviderFunctionality() {
		return Collections.emptySet();
	}

	/**
	 * Checks which subscriptions fit to what {@link NetconfServerSession} and
	 * send the notifications related to this subscription over the session.
	 * 
	 * @param notification
	 *            Notification to be send out
	 * @param subscriptionID
	 *            The ID of the subscription the notification is send for
	 */
	public void pushNotification(NetconfMessage notification, String subscriptionID) {
		LOG.info("Push notification...");
		for (NetconfServerSession sessionKey : serverSessionToSubIds.keySet()) {
			for (String subIDValue : serverSessionToSubIds.get(sessionKey)) {
				if (subIDValue.equals(subscriptionID)) {
					sessionKey.sendMessage(notification);
					LOG.info("Pushed notification {} on session {}", notification, sessionKey);
				}
			}
		}
	}

	/**
	 * Notifies the provider if a {@link NetconfServerSession} successfully
	 * received a RPC and provides the related session.
	 * 
	 * @param serverSession
	 *            Netconf server session that received the RPC
	 */
	public void onIncomingRpcSuccess(NetconfServerSession serverSession) {
		LOG.info("New successful RPC on netconf server session");
		if (!latestEstablishedSubscriptionID.equals(priorEstablishedSubscriptionID)) {
			if (serverSessionToSubIds.containsKey(serverSession)) {
				if (serverSessionToSubIds.get(serverSession) != null) {
					Set<String> relatedSetofSubIds = serverSessionToSubIds.get(serverSession);
					relatedSetofSubIds.add(latestEstablishedSubscriptionID);
					serverSessionToSubIds.put(serverSession, relatedSetofSubIds);
				}
			} else {
				Set<String> subIdValues = new HashSet<>();
				subIdValues.add(latestEstablishedSubscriptionID);
				serverSessionToSubIds.put(serverSession, subIdValues);
			}
			priorEstablishedSubscriptionID = latestEstablishedSubscriptionID;
		}
	}

	/**
	 * Notifies the provider if a new subscription was established.
	 * 
	 * @param subscriptionId
	 *            ID of the new subscription
	 */
	public void onEstablishedSubscription(String subscriptionId) {
		LOG.info("Subscription with ID {} established.", subscriptionId);

		if (latestEstablishedSubscriptionID.equals(priorEstablishedSubscriptionID)) {
			latestEstablishedSubscriptionID = subscriptionId;
		} else {
			priorEstablishedSubscriptionID = latestEstablishedSubscriptionID;
			latestEstablishedSubscriptionID = subscriptionId;
		}
	}

	/**
	 * Notifies the provider if an existing subscription was deleted.
	 * 
	 * @param subscriptionId
	 *            ID of the deleted subscription
	 */
	public void onDeletedSubscription(String subscriptionId) {
		LOG.info("Subscription with ID {} deleted. Deleting from related session", subscriptionId);
		for (NetconfServerSession sessionKey : serverSessionToSubIds.keySet()) {
			Set<String> toRemove = new HashSet<>();
			for (String subIDValue : serverSessionToSubIds.get(sessionKey)) {
				if (subIDValue.equals(subscriptionId)) {
					toRemove.add(subscriptionId);
				}
			}
			serverSessionToSubIds.get(sessionKey).removeAll(toRemove);
		}
	}

	/**
	 * Notifies the provider if an existing netconf server session goes down for
	 * whatever reason
	 * 
	 * @param netconfServerSession
	 *            Session that is down
	 */
	public void onSessionDown(NetconfServerSession netconfServerSession) {
		LOG.info("Session {} down. Deleting all related subscriptions", netconfServerSession);
		for (String subID : serverSessionToSubIds.get(netconfServerSession)) {
			this.notificationEngine.unregisterNotification(subID);
		}
		for (String subID : serverSessionToSubIds.get(netconfServerSession)) {
			this.subEngine.updateMdSal(this.subEngine.getSubscription(subID), operations.delete);
		}
		serverSessionToSubIds.remove(netconfServerSession);
	}
}