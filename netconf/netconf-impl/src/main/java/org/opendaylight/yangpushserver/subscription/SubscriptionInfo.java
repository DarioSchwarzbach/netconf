/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.subscription;

import javax.xml.transform.dom.DOMSource;

import org.opendaylight.yangpushserver.rpc.Errors;
import org.opendaylight.yangpushserver.rpc.RpcImpl;

/**
 * This is skeleton represents subscriptions with all the information gathered
 * from parsing in {@link RpcImpl}.
 * 
 * @author Philipp Konegen
 *
 */
public final class SubscriptionInfo {
	/**
	 * 
	 * SubscriptionStreamStatus shows the current state of the subscription.
	 *
	 */
	public enum SubscriptionStreamStatus {
		active, inactive, suspend, in_error,
	}

	public String node_name, encoding, stream, startTime, stopTime, filterType, subscriptionStartTime,
			subscriptionStopTime, dscp, subscriptionPriority, subscriptionDependency, updateTrigger, status,
			subscriptionId;
	Long period, dampeningPeriod;
	private Errors.errors error;
	private DOMSource filter;
	private Boolean noSynchOnStart;
	public SubscriptionStreamStatus subscriptionStreamStatus;
	private String excludedChange;
	private static SubscriptionInfo instance = null;

	public SubscriptionInfo() {
	}

	public SubscriptionStreamStatus getSubscriptionStreamStatus() {
		return subscriptionStreamStatus;
	}

	public void setSubscriptionStreamStatus(SubscriptionStreamStatus subscriptionStreamStatus) {
		this.subscriptionStreamStatus = subscriptionStreamStatus;
	}

	public String getSubscriptionDependency() {
		return subscriptionDependency;
	}

	public void setSubscriptionDependency(String subscriptionDependency) {
		this.subscriptionDependency = subscriptionDependency;
	}

	public String getSubscriptionPriority() {
		return subscriptionPriority;
	}

	public void setSubscriptionPriority(String subscriptionPriority) {
		this.subscriptionPriority = subscriptionPriority;
	}

	public Boolean getNoSynchOnStart() {
		return noSynchOnStart;
	}

	public void setNoSynchOnStart(Boolean noSynchOnStart) {
		this.noSynchOnStart = noSynchOnStart;
	}

	public String getExcludedChange() {
		return excludedChange;
	}

	public void setExcludedChange(String excludedChange) {
		this.excludedChange = excludedChange;
	}

	public String getDscp() {
		return dscp;
	}

	public void setDscp(String dscp) {
		this.dscp = dscp;
	}

	public String getSubscriptionStopTime() {
		return subscriptionStopTime;
	}

	public void setSubscriptionStopTime(String subscriptionStopTime) {
		this.subscriptionStopTime = subscriptionStopTime;
	}

	public String getSubscriptionStartTime() {
		return subscriptionStartTime;
	}

	public void setSubscriptionStarTime(String subscriptionStartTime) {
		this.subscriptionStartTime = subscriptionStartTime;
	}

	public Long getDampeningPeriod() {
		return dampeningPeriod;
	}

	public void setDampeningPeriod(Long dampeningPeriod) {
		this.dampeningPeriod = dampeningPeriod;
	}

	public String getUpdateTrigger() {
		return updateTrigger;
	}

	public void setUpdateTrigger(String updateTrigger) {
		this.updateTrigger = updateTrigger;
	}

	// public String getEncodingValue() {
	// return encoding.split("\\)")[1];
	// }

	public String getEncoding() {
		return encoding;
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	public Long getPeriod() {
		return period;
	}

	public void setPeriod(Long period) {
		this.period = period;
	}

	public String getNode_name() {
		return node_name;
	}

	public void setNode_name(String node_name) {
		this.node_name = node_name;
	}

	public String getStream() {
		return stream;
	}

	public void setStream(String stream) {
		this.stream = stream;
	}

	public String getSubscriptionId() {
		return subscriptionId;
	}

	public void setSubscription_id(String subscriptionId) {
		this.subscriptionId = subscriptionId;
	}

	public DOMSource getFilter() {
		return filter;
	}

	public void setFilter(DOMSource filter) {
		this.filter = filter;
	}

	public String getError() {
		return Errors.printError(this.error);
	}

	public void setError(Errors.errors error) {
		this.error = error;
	}

	public String getStartTime() {
		return startTime;
	}

	public void setStartTime(String startTime) {
		this.startTime = startTime;
	}

	public String getStopTime() {
		return stopTime;
	}

	public void setStopTime(String stopTime) {
		this.stopTime = startTime;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
	// @Override
	// // The output format may be better structured with e.g. line breaks
	// public String toString() {
	// return "Node Name: '" + node_name.toString() + "' Encoding: '" +
	// encoding.toString() + "' Stream : '"
	// + stream.toString() + "' Start Time: '" + startTime.toString() + "' Stop
	// Time: '" + stopTime.toString()
	// + "' Subscription Start Time: '" + subscriptionStartTime.toString() +
	// "'"+"\n"+"Subscription Stop Time: '"
	// + subscriptionStopTime.toString() + "' DSCP: '" + dscp.toString() + "'
	// Subscription Priority: '"
	// + subscriptionPriority.toString() + "' Subscription Dependency: '" +
	// subscriptionDependency.toString()
	// + "' Update Trigger: '" + updateTrigger.toString()+"' Period: '"+
	// period.toString()+"\n" ;
	// }

	// public static SubscriptionInfo getInstance() {
	// if (instance == null) {
	// instance = new SubscriptionInfo();
	// }
	// return instance;
	// }
}