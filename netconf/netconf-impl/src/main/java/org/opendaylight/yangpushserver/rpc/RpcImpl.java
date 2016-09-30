/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.rpc;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.xml.transform.dom.DOMSource;

import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcException;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcIdentifier;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcImplementation;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcProviderService;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcResult;
import org.opendaylight.controller.md.sal.dom.spi.DefaultDOMRpcResult;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf._5277.netconf.rev160615.base.filter.FilterType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.DeleteSubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.EstablishSubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.EstablishSubscriptionOutput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.ModifySubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.establish.subscription.input.filter.type.UpdateFilter;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.update.filter.update.filter.Subtree;
import org.opendaylight.yangpushserver.notification.NotificationEngine;
import org.opendaylight.yangpushserver.rpc.Errors.errors;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine.operations;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo.SubscriptionStreamStatus;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.AugmentationIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.PathArgument;
import org.opendaylight.yangtools.yang.data.api.schema.AnyXmlNode;
import org.opendaylight.yangtools.yang.data.api.schema.AugmentationNode;
import org.opendaylight.yangtools.yang.data.api.schema.ChoiceNode;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.LeafNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNodeContainer;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNodes;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;

public class RpcImpl implements DOMRpcImplementation {

	private static final Logger LOG = LoggerFactory.getLogger(RpcImpl.class);
	// NOTIFICATION_NS should be now up-to-date with "1.1"
	public static final String NOTIFICATION_NS = "urn:ietf:params:xml:ns:netconf:notification:1.1";
	public static final String YP_NS = "urn:ietf:params:xml:ns:yang:ietf-yang-push";
	public static final String YP_NS_DATE = "2016-06-15";
	public static final String I_DS_PUSH_NS = "urn:ietf:params:xml:ns:yang:ietf-datastore-push";
	public static final String I_DS_PUSH_NS_DATE = "2015-10-15";
	public static final String NOTIF_BIS = "urn:ietf:params:xml:ns:yang:ietf-event-notifications";
	public static final String NOTIF_BIS_DATE = "2016-06-15";

	// QNames used to construct augment leafs present in
	// ietf-event-notifications.yang
	public static final QName N_ENCODING_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "encoding");
	public static final QName N_STREAM_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "stream");
	public static final QName N_START_TIME_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "startTime");
	public static final QName N_STOP_TIME_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "stopTime");
	public static final QName N_UPDATE_FILTER_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "update-filter");
	public static final QName N_FILTER_TYPE_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter-type");
	public static final QName N_UPDATE_TRIGGER_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "update-trigger");
	public static final QName N_DSCP_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "dscp");
	public static final QName N_SUB_ID_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subsciption-id");
	public static final QName N_SUB_PRIORITY_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subscription-priority");
	public static final QName N_SUB_DEPENDENCY_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE,
			"subscription-dependency");
	public static final QName N_SUB_RESULT_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subscription-result");
	public static final QName N_SUB_START_TIME_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE,
			"subscription-start-time");
	public static final QName N_SUB_STOP_TIME_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subscription-stop-time");

	// QNames used to construct augment leafs present in
	// ietf-yang-push
	public static final QName Y_DAMPENING_PERIOD_NAME = QName.create(YP_NS, YP_NS_DATE, "dampening-period");
	public static final QName Y_PERIOD_NAME = QName.create(YP_NS, YP_NS_DATE, "period");
	public static final QName Y_UPDATE_TRIGGER_NAME = QName.create(YP_NS, YP_NS_DATE, "update-trigger");
	public static final QName Y_DSCP_NAME = QName.create(YP_NS, YP_NS_DATE, "dscp");

	// QNames used to construct establish RPC input & output present in
	// ietf-event-notifications.yang
	public static final NodeIdentifier N_ESTABLISH_SUB_OUTPUT = NodeIdentifier
			.create(EstablishSubscriptionOutput.QNAME);
	public static final NodeIdentifier N_ESTABLISH_SUB_INPUT = NodeIdentifier.create(EstablishSubscriptionInput.QNAME);

	// QNames used to construct establish filter present in
	// ietf-event-notifications.yang
	public static final NodeIdentifier N_FILTER_TYPE = NodeIdentifier.create(FilterType.QNAME);
	public static final NodeIdentifier N_UPDATE_FILTER = NodeIdentifier.create(UpdateFilter.QNAME);
	public static final NodeIdentifier N_SUBTREE = NodeIdentifier.create(Subtree.QNAME);
	// public static final QName N_FILTER_TYPE_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "filter-type");

	// QNames used to construct input args defined in ietf-datatsore-push.yang
	public static final QName I_PUSH_UPDATE_TRIGGER = QName.create(I_DS_PUSH_NS, I_DS_PUSH_NS_DATE, "update-trigger");
	public static final QName I_PUSH_PERIOD_NAME = QName.create(I_DS_PUSH_NS, I_DS_PUSH_NS_DATE, "period");
	public static final QName I_PUSH_TARGET_DATASTORE = QName.create(I_DS_PUSH_NS, I_DS_PUSH_NS_DATE,
			"target-datastore");
	public static final QName I_PUSH_SUBTREE_FILTERSPEC = QName.create(I_DS_PUSH_NS, I_DS_PUSH_NS_DATE, "filterspec");
	public static final QName I_PUSH_SUBTREE_FILTER_TYPE = QName.create(I_DS_PUSH_NS, I_DS_PUSH_NS_DATE, "filter-type");
	public static final QName I_PUSH_SUBTREE_FILTER = QName.create(I_DS_PUSH_NS, I_DS_PUSH_NS_DATE, "subtree-filter");

	public static final DOMRpcIdentifier ESTABLISH_SUBSCRIPTION_RPC = DOMRpcIdentifier
			.create(SchemaPath.create(true, QName.create(EstablishSubscriptionInput.QNAME, "establish-subscription")));
	public static final DOMRpcIdentifier MODIFY_SUBSCRIPTION_RPC = DOMRpcIdentifier
			.create(SchemaPath.create(true, QName.create(ModifySubscriptionInput.QNAME, "modify-subscription")));
	public static final DOMRpcIdentifier DELETE_SUBSCRIPTION_RPC = DOMRpcIdentifier
			.create(SchemaPath.create(true, QName.create(DeleteSubscriptionInput.QNAME, "delete-subscription")));


	// TODO REMOVE Test_Sub!
	public static final String RFC3339_DATE_FORMAT_BLUEPRINT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

	private DOMRpcProviderService service;
	private DOMDataBroker globalDomDataBroker;
	private SubscriptionEngine subscriptionEngine = null;
	private NotificationEngine notificationEngine = null;
	private SubscriptionInfo subscriptionInfo = null;
	String date = String.valueOf(new Date().getTime());

	public RpcImpl(DOMRpcProviderService service, DOMDataBroker globalDomDataBroker) {
		super();
		this.service = service;
		this.globalDomDataBroker = globalDomDataBroker;
		this.subscriptionEngine = SubscriptionEngine.getInstance();
		this.notificationEngine = NotificationEngine.getInstance();
		this.subscriptionInfo = SubscriptionInfo.getInstance();
		// TODO Register?
		registerRPCs();
	}

	/**
	 * Registers RPC present in ietf-datastore-push module.
	 */
	private void registerRPCs() {
		// Register RPC to DOMRpcProviderService, LOGs proofed this.
		// TODO REMOVE Test_Sub! GET_SUBSCRIPTION_RPC, Test_Sub,
		service.registerRpcImplementation(this, ESTABLISH_SUBSCRIPTION_RPC, MODIFY_SUBSCRIPTION_RPC,
				DELETE_SUBSCRIPTION_RPC);
	}

	/**
	 * This method is invoked on RPC invocation of the registered method.
	 * rpc(localname) is used to invoke the correct requested method.
	 */
	@Override
	public CheckedFuture<DOMRpcResult, DOMRpcException> invokeRpc(DOMRpcIdentifier rpc, NormalizedNode<?, ?> input) {
		LOG.info("sub-RPC invoked");
		if (rpc.equals(ESTABLISH_SUBSCRIPTION_RPC)) {
			LOG.debug("This is a establish subscription RPC");
			return establishSubscriptionRpcHandler(input);
		} else if (rpc.equals(MODIFY_SUBSCRIPTION_RPC)) {
			LOG.info("This is a modify subscrition RPC. Not supported yet...");
			return modifySubscriptionRpcHandler(input);
		} else if (rpc.equals(DELETE_SUBSCRIPTION_RPC)) {
			LOG.info("This is a delete subscrition RPC");
			return deleteSubscriptionRpcHandler(input);
//		} else if (rpc.equals(GET_SUBSCRIPTION_RPC)) {
//			LOG.info("This is a get subscrition RPC");
//			return getSubscriptionRpcHandler(input);
		}
		LOG.info("Unknown RPC...");
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult());
	}

	/****************************************
	 * TODO REMOVE Section for TEST-METHODS *
	 ****************************************/
	public ContainerNode createNormalizedNode() {
		Long period = 30l;

		// ContainerNode choice3 =
		// Builders.containerBuilder().withNodeIdentifier(N_UPDATE_FILTER)
		// .withChild(ImmutableNodes.leafNode(N_UPDATE_FILTER_NAME,
		// "subtree")).build();
		// ContainerNode choice2 = (ContainerNode)
		// Builders.containerBuilder().withNodeIdentifier(N_UPDATE_FILTER)
		// .withChild(choice3);
		// ContainerNode choice1 = Builders.containerBuilder(N_FILTER_TYPE,
		// choice2);
		ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_INPUT)
				.withChild(ImmutableNodes.leafNode(N_ENCODING_NAME, "encode-xml"))
				.withChild(ImmutableNodes.leafNode(N_STREAM_NAME, "push-update"))
				.withChild(ImmutableNodes.leafNode(N_START_TIME_NAME, ""))
				.withChild(ImmutableNodes.leafNode(N_STOP_TIME_NAME, ""))
				.withChild(ImmutableNodes.leafNode(N_UPDATE_TRIGGER_NAME, "periodic"))
				.withChild(ImmutableNodes.leafNode(N_FILTER_TYPE, N_UPDATE_FILTER_NAME)).build();
		return cn;
	}

	/********************************
	 * END Section for TEST-METHODS *
	 ********************************/
	/***********************************
	 * Section for DELETE-SUBSCRIPTION *
	 ***********************************/
	private CheckedFuture<DOMRpcResult, DOMRpcException> deleteSubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		String sid = "";
		if (input.equals(null)) {
			sid = "-1";
			LOG.error(Errors.printError(errors.input_error));
			return null;
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseDeleteSubExternalRpcInput(input, error);
		// parsing should have been 'ok'
		LOG.trace(inputData.toString());
		LOG.info("Parsing complete");
		if(subscriptionEngine.checkIfSubscriptionExists(inputData.getSubscriptionId())){
		subscriptionEngine.updateMdSal(subscriptionInfo, operations.delete);	
		}

		// Because it's deleteSubscription, there will be no RPC output.
		return null;
	}

	/**
	 * Parses the input received from user for a delete subscription RPC. The
	 * input should container node_name and subscriptionId from the user
	 *
	 * @param input
	 * @param error
	 * @return
	 */
	private SubscriptionInfo parseDeleteSubExternalRpcInput(NormalizedNode<?, ?> input, String error) {
		SubscriptionInfo dsri = new SubscriptionInfo();
		ContainerNode conNode = null;
		error = "";
		if (input == null) {
			error = Errors.printError(errors.input_error);
			dsri = null;
			return dsri;
		}

		if (input instanceof ContainerNode) {
			conNode = (ContainerNode) input;
			try {
				Set<QName> childNames = new HashSet<>();
				AugmentationIdentifier ai = new AugmentationIdentifier(childNames);
				Optional<DataContainerChild<? extends PathArgument, ?>> t = conNode.getChild(ai);

				// Decode subscription-id
				DataContainerChild<? extends PathArgument, ?> subIdNode = null;
				NodeIdentifier sub_id = new NodeIdentifier(N_SUB_ID_NAME);
				t = conNode.getChild(sub_id);
				if (t.isPresent()) {
					subIdNode = t.get();
					if (subIdNode.getValue() != null) {
						String subscription_id = subIdNode.getValue().toString();
						dsri.setSubscription_id(subscription_id);
					} else {
						error = Errors.printError(errors.input_sub_id_error);
					}
				} else {
					error = Errors.printError(errors.input_sub_id_error);
				}
				// Check for Errors
				if (!error.equals("")) {
					dsri = null;
					LOG.error(error);
					return dsri;
				}
			} catch (Exception e) {
				LOG.error(e.toString());
			}
		} else {
			error = Errors.printError(errors.input_not_instance_of);
			dsri = null;
		}
		// Checks if there is a subscription with spezified subscription id
		if (!correctDeleteInput(dsri)) {
			dsri = null;
		}
		return dsri;
	}

	private boolean correctDeleteInput(SubscriptionInfo dsri) {
		if (dsri.getSubscriptionId() != null) {
			return true;
		}
		return false;
	}

	/**
	 * Structure to hold input arguments for delete subscription
	 *
	 */
	final class DeleteSubscriptionRpcInput {

		public DeleteSubscriptionRpcInput() {
			this.subscription_id = "";
		}

		public String getSubscription_id() {
			return subscription_id;
		}

		public void setSubscription_id(String subscription_id) {
			this.subscription_id = subscription_id;
		}

		public String getError() {
			return Errors.printError(this.error);
		}

		public void setError(Errors.errors error) {
			this.error = error;
		}

		private String subscription_id;
		private Errors.errors error;

		public String toString() {
			return ("Delete Subscription Input paramters-> sub_id: " + subscription_id);
		}
	}

	/**************************************
	 * Section for ESTABLISH-SUBSCRIPTION *
	 **************************************/
	private CheckedFuture<DOMRpcResult, DOMRpcException> establishSubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		String sid = "";
		// Is this mandatory?
		if (input == null) {
			sid = "-1";
			LOG.error(Errors.printError(errors.input_error));
			ContainerNode output = createEstablishSubOutput(sid);
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
		}
		if (input.equals(null)) {
			sid = "-1";
			LOG.error(Errors.printError(errors.input_error));
			ContainerNode output = createEstablishSubOutput(sid);
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseEstablishAndModifySubExternalRpcInput(input, error, true);
		if (inputData == null) {
			LOG.error("Parsing failed");
			return null;
		}
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		// get subscription id from subscription engine.
		sid = this.subscriptionEngine.generateSubscriptionId();
		inputData.setSubscription_id(sid);
		// Saving the Subscription Information locally & on MDSAL datastore
		this.subscriptionEngine.updateMdSal(subscriptionInfo, operations.establish);

		// Check if on-Change-Notifications or periodic-Notifications should be
		// sent
		if (inputData.getDampeningPeriod() != null) {
			LOG.info("Register on-Change-Notifications");
			notificationEngine.registerOnChangeNotification(inputData.getSubscriptionId());
			
		} else if (inputData.getPeriod() != null) {
			LOG.info("Register periodic-Notifications");
			notificationEngine.registerPeriodicNotification(inputData.getSubscriptionId());
			
		} else {
			LOG.error("Wrong Subscription exists, neither on-Change nor periodic Subscription");
			return null;
		}
		ContainerNode output = createEstablishSubOutput(inputData.getSubscriptionId());
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	/**
	 * Creates a container node for CreateSubscription RPC output.
	 *
	 * @param sid
	 * @return containerNode for Create Subscription Output
	 */
	private ContainerNode createEstablishSubOutput(String sid) {
		if (sid.equals("-1")){
		final ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
				.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, "ok"))
				.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, sid)).build();
		return cn;
		}
		return null;
	}

	// Parsing the whole RPC, part by part
	private SubscriptionInfo parseEstablishAndModifySubExternalRpcInput(NormalizedNode<?, ?> input, String error,
			Boolean isEstablish) {
		SubscriptionInfo esri = SubscriptionInfo.getInstance();
		ContainerNode conNode = null;
		error = "";
		// Checks if input is missing/null
		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			esri = null;
			return esri;
		}
		if (input instanceof ContainerNode) {
			try {
				// General variables before separate parsing starts..
				conNode = (ContainerNode) input;
				Set<QName> childNames = new HashSet<>();
				AugmentationNode an = (AugmentationNode) conNode.getValue().iterator().next();
				
				// Whole example node

				// ImmutableContainerNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)input,
				// value=[ImmutableAugmentationNode{nodeIdentifier=AugmentationIdentifier{childNames=[(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-dependency,
				// (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-priority,
				// (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)update-trigger,
				// (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)dscp,
				// (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-start-time,
				// (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-stop-time]},
				// value=[ImmutableChoiceNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)update-trigger,
				// value=[ImmutableLeafNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)period,
				// value=500, attributes={}}]}]},
				// ImmutableLeafNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)encoding,
				// value=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)encode-xml,
				// attributes={}},
				// ImmutableLeafNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)stream,
				// value=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)NETCONF,
				// attributes={}}], attributes={}}

				// I Parse encoding - NO AUGMENTATION
				DataContainerChild<? extends PathArgument, ?> encodingNode = null;
				childNames.add(N_ENCODING_NAME);
				NodeIdentifier ni = new NodeIdentifier(N_ENCODING_NAME);
				Optional<DataContainerChild<? extends PathArgument, ?>> t = conNode.getChild(ni);
				if (t.isPresent()) {
					encodingNode = (LeafNode<?>) t.get();
					if (encodingNode.getValue() != null) {
						esri.setEncoding(encodingNode.getValue().toString().split("\\)")[1]);
					} else {
						esri.setEncoding("encode-xml");
					}
				} else {
					esri.setEncoding("encode-xml");
				}
				LOG.info("Parsing encode complete : "+esri.getEncoding());
				// II Parse stream - NO AUGMENTATION
				DataContainerChild<? extends PathArgument, ?> streamNode = null;
				NodeIdentifier stream = new NodeIdentifier(N_STREAM_NAME);
				t = conNode.getChild(stream);
				if (t.isPresent()) {
					streamNode = (LeafNode<?>) t.get();
					if (streamNode.getValue() != null) {
						esri.setStream(streamNode.getValue().toString().split("\\)")[1]);
					} else {
						esri.setStream("NETCONF");
					}
				} else {
					esri.setStream("NETCONF");
				}
				LOG.info("Parsing stream complete : "+esri.getStream());
				// III Parse sub-start-time
				DataContainerChild<? extends PathArgument, ?> subStartTimeNode = null;
				NodeIdentifier subStartTime = new NodeIdentifier(N_SUB_START_TIME_NAME);
				t = an.getChild(subStartTime);
				if (t.isPresent()) {
					subStartTimeNode = (LeafNode<?>) t.get();
					if (subStartTimeNode.getValue() != null) {
						esri.setSubscriptionStarTime(subStartTimeNode.getValue().toString());
					} else {
						esri.setSubscriptionStarTime("-1");
					}
				} else {
//					esri.setSubscriptionStarTime(
//							new SimpleDateFormat(RFC3339_DATE_FORMAT_BLUEPRINT).format(new Date()));
					esri.setSubscriptionStarTime("-1");
				}
				LOG.info("Parsing sub-start-time complete : "+esri.getSubscriptionStartTime());
				// IV Parse sub-stop-time
				DataContainerChild<? extends PathArgument, ?> subStopTimeNode = null;
				NodeIdentifier subStopTime = new NodeIdentifier(N_SUB_STOP_TIME_NAME);
				t = an.getChild(subStopTime);
				if (t.isPresent()) {
					subStopTimeNode = (LeafNode<?>) t.get();
					if (subStopTimeNode.getValue() != null) {
						esri.setSubscriptionStopTime(subStopTimeNode.getValue().toString());
					} else {
						esri.setSubscriptionStopTime("-1");
					}
				} else {
					esri.setSubscriptionStopTime("-1");
				}
				LOG.info("Parsing sub-stop-time complete : "+esri.getSubscriptionStopTime());
				// V Parse start-time
				DataContainerChild<? extends PathArgument, ?> startTimeNode = null;
				NodeIdentifier startTime = new NodeIdentifier(N_START_TIME_NAME);
				t = an.getChild(startTime);
				if (t.isPresent()) {
					startTimeNode = (LeafNode<?>) t.get();
					if (startTimeNode.getValue() != null) {
						esri.setStartTime(startTimeNode.getValue().toString());
					} else {
						esri.setSubscriptionStarTime("-1");
					}
				} else {
					esri.setSubscriptionStarTime("-1");
				}
				LOG.info("Parsing start-time complete : "+esri.getStartTime());
				// VI Parse stop-time
				DataContainerChild<? extends PathArgument, ?> stopTimeNode = null;
				NodeIdentifier stopTime = new NodeIdentifier(N_STOP_TIME_NAME);
				t = an.getChild(stopTime);
				if (t.isPresent()) {
					stopTimeNode = (LeafNode<?>) t.get();
					if (stopTimeNode.getValue() != null) {
						esri.setStopTime(stopTimeNode.getValue().toString());
					} else {
						esri.setStopTime("-1");
					}
				} else {
					esri.setStopTime("-1");
				}

				LOG.info("Parsing stop-time complete : "+esri.getStopTime());
				// VII Parsing update-trigger

				NodeIdentifier updateTrigger = new NodeIdentifier(Y_UPDATE_TRIGGER_NAME);
				NodeIdentifier period = new NodeIdentifier(Y_PERIOD_NAME);
				NodeIdentifier dampeningPeriod = new NodeIdentifier(Y_DAMPENING_PERIOD_NAME);
				// ChoiceNode cn = (ChoiceNode) an.getValue().iterator().next();
				// LOG.info("cn "+cn);
				// LeafNode per = (LeafNode) cn.getValue().iterator().next();
				// LOG.info("per "+per);

				// Set<QName> childTest = new HashSet<>();
				// childTest.add(Y_UPDATE_TRIGGER_NAME);
				// AugmentationIdentifier ai = new
				// AugmentationIdentifier(childTest);
				// LOG.info("Augment Ident " + ai);
				// Augment Ident
				// AugmentationIdentifier{childNames=[(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)update-trigger]}

				ChoiceNode c1 = (ChoiceNode) an.getChild(updateTrigger).get();
				if (c1.getChild(period).isPresent()) {
					DataContainerChild<? extends PathArgument, ?> c2 = c1.getChild(period).get();
					if (c2.getValue() != null) {
						esri.setPeriod((Long) c2.getValue());
						LOG.info("Periode auf " + esri.getPeriod() + " gesetzt");
					}
				} else if (c1.getChild(dampeningPeriod).isPresent()) {
					DataContainerChild<? extends PathArgument, ?> c2 = c1.getChild(dampeningPeriod).get();
					if (c2.getValue() != null) {
						esri.setDampeningPeriod((Long) c2.getValue());
						LOG.info("Dampening Periode auf " + esri.getDampeningPeriod() + " gesetzt");
					}
				} else {
					error = Errors.printError(errors.input_period_error);
				}
				// Check for Errors
				if (!error.equals("")) {
					LOG.error(error);
					esri = null;
					return esri;
				}

				LOG.info("Parsing update-trigger complete "+"P: "+esri.getPeriod()+" DP: "+esri.getDampeningPeriod());
				// VIII Parsing dscp FUNKTIONIERT
				DataContainerChild<? extends PathArgument, ?> dscpNode = null;
				NodeIdentifier dscp = new NodeIdentifier(Y_DSCP_NAME);
				t = an.getChild(dscp);
				if (t.isPresent()) {
					dscpNode = (LeafNode<?>) t.get();
					if (dscpNode.getValue() != null) {
						esri.setDscp(String.valueOf(dscpNode.getValue()));
					} else {
						esri.setDscp("0");
					}
				} else {
					esri.setDscp("0");
				}
				LOG.info("Parsing dscp complete : "+esri.getDscp());
				// IX Parsing sub-priority
				DataContainerChild<? extends PathArgument, ?> subPriorityNode = null;
				NodeIdentifier subPriority = new NodeIdentifier(N_SUB_PRIORITY_NAME);
				t = an.getChild(subPriority);
				if (t.isPresent()) {
					subPriorityNode = (LeafNode<?>) t.get();
					if (subPriorityNode.getValue() != null) {
						esri.setSubscriptionPriority((String) subPriorityNode.getValue());
					} else {
						esri.setSubscriptionPriority("");
					}
				} else {
					esri.setSubscriptionPriority("");
				}
				LOG.info("Parsing sub-priority complete : "+esri.getSubscriptionPriority());
				// IX Parsing sub-dependency
				DataContainerChild<? extends PathArgument, ?> subDependencyNode = null;
				NodeIdentifier subDependency = new NodeIdentifier(N_SUB_DEPENDENCY_NAME);
				t = an.getChild(subDependency);
				if (t.isPresent()) {
					subDependencyNode = (LeafNode<?>) t.get();
					if (subDependencyNode.getValue() != null) {
						esri.setSubscriptionDependency((String) subDependencyNode.getValue());
					} else {
						esri.setSubscriptionDependency("");
					}
				} else {
					esri.setSubscriptionDependency("");
				}
				LOG.info("Parsing sub-dependency complete : "+esri.getSubscriptionDependency());
				// TODO Need to be checked!
				// XI Parse filter-type
				// NodeIdentifier filterspec = new
				// NodeIdentifier(I_PUSH_SUBTREE_FILTERSPEC);
				// NodeIdentifier filtertype = new
				// NodeIdentifier(I_PUSH_SUBTREE_FILTER_TYPE);
				// NodeIdentifier subtreeFilter = new
				// NodeIdentifier(I_PUSH_SUBTREE_FILTER);
				// System.out.println("test1");
				// DataContainerChild<? extends PathArgument, ?> i =
				// conNode.getChild(filterspec).get();
				// ChoiceNode t1 = (ChoiceNode) i;
				// System.out.println("test2");
				// DataContainerChild<? extends PathArgument, ?> t2 =
				// t1.getChild(filtertype).get();
				// ChoiceNode t3 = (ChoiceNode) t2;
				// System.out.println("test3");
				// DataContainerChild<? extends PathArgument, ?> t4 =
				// t3.getChild(subtreeFilter).get();
				// if (t4 != null) {
				// System.out.println("test4");
				// AnyXmlNode anyXmlFilter = (AnyXmlNode) t4;
				// org.w3c.dom.Node nodeFilter =
				// anyXmlFilter.getValue().getNode();
				// org.w3c.dom.Document document =
				// nodeFilter.getOwnerDocument();
				// document.renameNode(nodeFilter, NOTIFICATION_NS, "filter");
				// DOMSource domSource = anyXmlFilter.getValue();
				// esri.setFilter(domSource);
				// }
				LOG.info("Parsing filter-type complete : "+esri.getFilter());
				// Set the SubscriptionStreamStatus to inactive, as long as no
				// Notification is sent to client
				subscriptionInfo.setSubscriptionStreamStatus(SubscriptionStreamStatus.inactive);
				// Check if it is a modify subscription
				if (!isEstablish) {
					// Parse Subscription ID
					DataContainerChild<? extends PathArgument, ?> subscriptionIdNode = null;
					NodeIdentifier subscriptionId = new NodeIdentifier(N_SUB_ID_NAME);
					t = conNode.getChild(subscriptionId);
					if (t.isPresent()) {
						subscriptionIdNode = (LeafNode<?>) t.get();
						if (subscriptionIdNode.getValue() != null) {
							esri.setSubscription_id((String) subscriptionIdNode.getValue());
						} else {
							error = Errors.printError(errors.input_subscription_id_error);
						}
					} else {
						error = Errors.printError(errors.input_subscription_id_error);
					}
					LOG.info("Parsing subscription-id complete");
				}
			} catch (Exception e) {
				LOG.error(e.toString());
			}
		} else {
			error = Errors.printError(errors.input_not_instance_of);
			esri = null;
		}
		if (correctEstablishOrModifyInput(esri)) {
			return esri;
		}
		return esri;
	}

	// TODO check the input
	private Boolean correctEstablishOrModifyInput(SubscriptionInfo esri) {
		Boolean result = false;
		if (!esri.getEncoding().equals("encode-xml")) {
			LOG.error("Only encode-xml supported");
			return false;
		} else {
			LOG.info("Correct encoding");
		}
		// Compare if start-time is before system time is prooved in
		// NotificationEngine

		// TODO: Compare if startTime is before stopTime.
		
		// Compare if stream is either NETCONF, OPERATIONAL or CONFIGURATION
		switch (esri.getStream()) {

		case "NETCONF":

			break;
		case "OPERATIONAL":

			break;
		case "CONFIGURATION":

			break;
		default:
			return result;
		}
		// Compare if Filter is correct, atm just subtree is supported
//		if (true) {
//			if (true) {
//				if (esri.getFilter() != "subtree") {
//					System.out.println(String.valueOf(date));
//					LOG.error("Only subtree supported");
//					return result;
//				}
//			}
//		}
		// Compare qos paramters: dscp, sub-dependecy and sub-priority
		// Input should be checked now
		result = true;
		LOG.info("Input is now checked");
		return result;
	}

	/***********************************
	 * Section for MODIFY-SUBSCRIPTION *
	 ***********************************/
	private CheckedFuture<DOMRpcResult, DOMRpcException> modifySubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		String sid = "";

		// Is this mandatory?
		if (input == null) {
			sid = "-1";
			LOG.error(Errors.printError(errors.input_error));
			ContainerNode output = createModifySubOutput(sid, "Error");
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
		}
		if (input.equals(null)) {
			sid = "-1";
			LOG.error(Errors.printError(errors.input_error));
			ContainerNode output = createModifySubOutput(sid, "Error");
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseEstablishAndModifySubExternalRpcInput(input, error, false);
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		// get subscription id from subscription engine.
		sid = this.subscriptionEngine.generateSubscriptionId();
		inputData.setSubscription_id(sid);

		// Saving the Subscription Information locally & on MDSAL datastore
		this.subscriptionEngine.updateMdSal(subscriptionInfo, operations.modify);

		// The novel Notifications will be registered
		if (!inputData.getDampeningPeriod().equals(null)) {
			notificationEngine.registerOnChangeNotification(inputData.getSubscriptionId());
			LOG.info("Register on-Change-Notifications");
		} else if (!inputData.getPeriod().equals(null)) {
			notificationEngine.registerPeriodicNotification(inputData.getSubscriptionId());
			LOG.info("Register periodic-Notifications");
		} else {
			LOG.error("Wrong Subscription exists, neither on-Change nor periodic Subscription");
			return null;
		}
		ContainerNode output = createModifySubOutput(sid, "Accepted");
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	private ContainerNode createModifySubOutput(String sid, String outputStatus) {
		ContainerNode cn = null;
		switch (outputStatus) {
		case "Error":
			cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
					.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, "internal error"))
					.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, sid)).build();
			break;
		case "Accepted":
			cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
					.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, "ok"))
					.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, sid)).build();
			break;
		case "Rejected":
			cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
					.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, "error-no-such-option"))
					.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, sid)).build();
			break;
		default:
			break;
		}
		return cn;
	}

	/********************************
	 * Section for GET-SUBSCRIPTION *
	 ********************************/

	private CheckedFuture<DOMRpcResult, DOMRpcException> getSubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return null;
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		String subId = parseGetSubRpcInput(input, error);
		// parsing should have been 'ok'
		// LOG.trace(inputData.toString());
		LOG.info("Parsing complete");
		ContainerNode output = createGetSubOutput(subId);
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	private String parseGetSubRpcInput(NormalizedNode<?, ?> input, String error) {
		ContainerNode conNode = null;
		error = "";
		if (input == null) {
			error = Errors.printError(errors.input_error);
			return null;
		}
		if (input instanceof ContainerNode) {
			conNode = (ContainerNode) input;
			try {
				Set<QName> childNames = new HashSet<>();
				AugmentationIdentifier ai = new AugmentationIdentifier(childNames);
				Optional<DataContainerChild<? extends PathArgument, ?>> t = conNode.getChild(ai);
				// Decode subscription-id
				DataContainerChild<? extends PathArgument, ?> subIdNode = null;
				NodeIdentifier sub_id = new NodeIdentifier(N_SUB_ID_NAME);
				t = conNode.getChild(sub_id);
				if (t.isPresent()) {
					subIdNode = t.get();
					if (subIdNode.getValue().equals(null) && subIdNode.getValue().toString() != "-1") {
						return subIdNode.getValue().toString();
					} else {
						error = Errors.printError(errors.input_sub_id_error);
					}
				} else {
					error = Errors.printError(errors.input_sub_id_error);
				}
				// Check for Errors
				if (!error.equals("")) {
					LOG.error(error);
					return null;
				}
			} catch (Exception e) {
				LOG.error(e.toString());
			}
		} else {
			error = Errors.printError(errors.input_not_instance_of);
			return null;
		}
		return null;
	}

	private ContainerNode createGetSubOutput(String subscriptionId) {
		ContainerNode cn = null;
		SubscriptionInfo subInfo = subscriptionEngine.getSubscription(subscriptionId);
		if (!subInfo.getPeriod().equals(null)) {
			cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
					.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, subInfo.getSubscriptionId()))
					.withChild(ImmutableNodes.leafNode(N_ENCODING_NAME, subInfo.getEncoding()))
					.withChild(ImmutableNodes.leafNode(N_STREAM_NAME, subInfo.getStream()))
					.withChild(ImmutableNodes.leafNode(N_SUB_START_TIME_NAME, subInfo.getSubscriptionStartTime()))
					.withChild(ImmutableNodes.leafNode(N_SUB_STOP_TIME_NAME, subInfo.getSubscriptionStopTime()))
					.withChild(ImmutableNodes.leafNode(N_START_TIME_NAME, subInfo.getStartTime()))
					.withChild(ImmutableNodes.leafNode(N_STOP_TIME_NAME, subInfo.getStopTime()))
					.withChild(ImmutableNodes.leafNode(N_UPDATE_FILTER_NAME, subInfo.getFilter()))
					.withChild(ImmutableNodes.leafNode(N_DSCP_NAME, subInfo.getDscp()))
					.withChild(ImmutableNodes.leafNode(N_SUB_PRIORITY_NAME, subInfo.getSubscriptionPriority()))
					.withChild(ImmutableNodes.leafNode(N_SUB_DEPENDENCY_NAME, subInfo.getSubscriptionDependency()))
					.withChild(ImmutableNodes.leafNode(Y_PERIOD_NAME, subInfo.getPeriod())).build();
		} else {
			cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
					.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, subInfo.getSubscriptionId()))
					.withChild(ImmutableNodes.leafNode(N_ENCODING_NAME, subInfo.getEncoding()))
					.withChild(ImmutableNodes.leafNode(N_STREAM_NAME, subInfo.getStream()))
					.withChild(ImmutableNodes.leafNode(N_SUB_START_TIME_NAME, subInfo.getSubscriptionStartTime()))
					.withChild(ImmutableNodes.leafNode(N_SUB_STOP_TIME_NAME, subInfo.getSubscriptionStopTime()))
					.withChild(ImmutableNodes.leafNode(N_START_TIME_NAME, subInfo.getStartTime()))
					.withChild(ImmutableNodes.leafNode(N_STOP_TIME_NAME, subInfo.getStopTime()))
					.withChild(ImmutableNodes.leafNode(N_UPDATE_FILTER_NAME, subInfo.getFilter()))
					.withChild(ImmutableNodes.leafNode(N_DSCP_NAME, subInfo.getDscp()))
					.withChild(ImmutableNodes.leafNode(N_SUB_PRIORITY_NAME, subInfo.getSubscriptionPriority()))
					.withChild(ImmutableNodes.leafNode(N_SUB_DEPENDENCY_NAME, subInfo.getSubscriptionDependency()))
					.withChild(ImmutableNodes.leafNode(Y_DAMPENING_PERIOD_NAME, subInfo.getDampeningPeriod())).build();
		}
		return cn;
	}
}
