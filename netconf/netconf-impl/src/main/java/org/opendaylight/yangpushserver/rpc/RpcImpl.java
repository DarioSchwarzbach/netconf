/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.rpc;

import java.io.StringWriter;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcException;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcIdentifier;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcImplementation;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcProviderService;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcResult;
import org.opendaylight.controller.md.sal.dom.spi.DefaultDOMRpcResult;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.DeleteSubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.DeleteSubscriptionOutput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.EstablishSubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.EstablishSubscriptionOutput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.ModifySubscriptionInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.ModifySubscriptionOutput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.establish.subscription.input.filter.type.UpdateFilter;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.update.filter.update.filter.Subtree;
import org.opendaylight.yangpushserver.notification.NotificationEngine;
import org.opendaylight.yangpushserver.notification.PeriodicNotification;
import org.opendaylight.yangpushserver.rpc.Errors.errors;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;
import org.opendaylight.yangpushserver.subscription.SubscriptionEngine.operations;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo;
import org.opendaylight.yangpushserver.subscription.SubscriptionInfo.SubscriptionStreamStatus;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
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

	public static final String YANG_DATEANDTIME_FORMAT_BLUEPRINT = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'";

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
	public static final QName N_SUB_ID_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subscription-id");
	public static final QName N_SUBTREE_FILTER_TYPE_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter-type");
	public static final QName N_SUBTREE_FILTER_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "filter");

	// public static final QName N_SUB_PRIORITY_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "subscription-priority");
	// public static final QName N_SUB_DEPENDENCY_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE,
	// "subscription-dependency");
	public static final QName N_ES_OUTPUT_RESULT = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "result");
	public static final QName N_RESULT_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "result");
	public static final QName N_SUB_RESULT_NAME = QName.create(NOTIF_BIS, NOTIF_BIS_DATE, "subscription-result");

	// QNames used to construct augment leafs present in
	// ietf-yang-push

	public static final QName Y_NO_SYNCH_ON_START_NAME = QName.create(YP_NS, YP_NS_DATE, "no-synch-on-start");
	public static final QName Y_EXCLUDED_CHANGE_NAME = QName.create(YP_NS, YP_NS_DATE, "excluded-change");
	public static final QName Y_DAMPENING_PERIOD_NAME = QName.create(YP_NS, YP_NS_DATE, "dampening-period");
	public static final QName Y_PERIOD_NAME = QName.create(YP_NS, YP_NS_DATE, "period");
	public static final QName Y_UPDATE_TRIGGER_NAME = QName.create(YP_NS, YP_NS_DATE, "update-trigger");
	public static final QName Y_DSCP_NAME = QName.create(YP_NS, YP_NS_DATE, "dscp");
	public static final QName Y_SUB_START_TIME_NAME = QName.create(YP_NS, YP_NS_DATE, "subscription-start-time");
	public static final QName Y_SUB_STOP_TIME_NAME = QName.create(YP_NS, YP_NS_DATE, "subscription-stop-time");
	public static final QName Y_SUB_DEPENDENCY_NAME = QName.create(YP_NS, YP_NS_DATE, "subscription-dependency");
	public static final QName Y_SUB_PRIORITY_NAME = QName.create(YP_NS, YP_NS_DATE, "subscription-priority");
	public static final QName Y_PUSH_SUBTREE_FILTERSPEC = QName.create(YP_NS, YP_NS_DATE, "filterspec");
	public static final QName Y_PUSH_SUBTREE_FILTER_TYPE = QName.create(YP_NS, YP_NS_DATE, "filter-type");
	public static final QName Y_PUSH_SUBTREE_FILTER = QName.create(YP_NS, YP_NS_DATE, "subtree-filter");

	// QNames used to construct establish RPC input & output present in
	// ietf-event-notifications.yang
	public static final NodeIdentifier N_ESTABLISH_SUB_OUTPUT = NodeIdentifier
			.create(EstablishSubscriptionOutput.QNAME);
	public static final NodeIdentifier N_ESTABLISH_SUB_INPUT = NodeIdentifier.create(EstablishSubscriptionInput.QNAME);
	public static final NodeIdentifier N_DELETE_SUB_OUTPUT = NodeIdentifier.create(DeleteSubscriptionOutput.QNAME);
	public static final NodeIdentifier N_DELETE_SUB_INPUT = NodeIdentifier.create(DeleteSubscriptionInput.QNAME);
	public static final NodeIdentifier N_MODIFY_SUB_OUTPUT = NodeIdentifier.create(ModifySubscriptionOutput.QNAME);
	public static final NodeIdentifier N_MODIFY_SUB_INPUT = NodeIdentifier.create(ModifySubscriptionInput.QNAME);

	// QNames used to construct establish filter present in
	// ietf-event-notifications.yang
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
	private SubscriptionInfo subscriptionInfo = new SubscriptionInfo();
	String date = String.valueOf(new Date().getTime());

	public RpcImpl(DOMRpcProviderService service, DOMDataBroker globalDomDataBroker) {
		super();
		this.service = service;
		this.globalDomDataBroker = globalDomDataBroker;
		this.subscriptionEngine = SubscriptionEngine.getInstance();
		this.notificationEngine = NotificationEngine.getInstance();
		// TODO Register?
		registerRPCs();
	}

	/**
	 * Registers RPC present in ietf-datastore-push module.
	 */
	private void registerRPCs() {
		// Register RPC to DOMRpcProviderService.
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
			LOG.info("This is a establish subscription RPC");
			return establishSubscriptionRpcHandler(input);
		} else if (rpc.equals(MODIFY_SUBSCRIPTION_RPC)) {
			LOG.info("This is a modify subscrition RPC. Not supported yet...");
			return modifySubscriptionRpcHandler(input);
		} else if (rpc.equals(DELETE_SUBSCRIPTION_RPC)) {
			LOG.info("This is a delete subscrition RPC");
			return deleteSubscriptionRpcHandler(input);
		}
		LOG.info("Unknown RPC...");
		return Futures.immediateFailedCheckedFuture(createDOMRpcException("RPC invocation not supported!"));
	}

	private DOMRpcException createDOMRpcException(String string) {
		return new DOMRpcException(string) {
		};
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
				.withChild(ImmutableNodes.leafNode(N_UPDATE_TRIGGER_NAME, "periodic")).build();
		// .withChild(ImmutableNodes.leafNode(N_SUBTREE_FILTER_NAME,
		// N_UPDATE_FILTER_NAME)).build();
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
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateFailedCheckedFuture(createDOMRpcException("Input missing or null"));
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseDeleteSubExternalRpcInput(input, error);
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		LOG.info("Delete Subscription parsed Input: " + inputData.toString());
		notificationEngine.unregisterNotification(inputData.getSubscriptionId());
		if (subscriptionEngine.checkIfSubscriptionExists(inputData.getSubscriptionId())) {
			subscriptionEngine.updateMdSal(inputData, operations.delete);
		} else {
			LOG.error("No such subscription with ID:" + inputData.getSubscriptionId());
			// TODO remove this part!
			// RpcError.ErrorType test = new RpcErrors();
			// RpcError output =
			// RpcResultBuilder.newError(RpcError.ErrorType.RPC, null, "test");
			// LOG.info(output.toString());
			return Futures.immediateFailedCheckedFuture(
					createDOMRpcException("No such subscription with ID:" + inputData.getSubscriptionId()));
		}
		ContainerNode output = createDeleteSubOutput(inputData);
		// TODO Here should OAM message with 'subscription delete' be sent
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	private ContainerNode createDeleteSubOutput(SubscriptionInfo inputData) {
		// ContainerNode subResult =
		// Builders.containerBuilder().withNodeIdentifier(NodeIdentifier.create(Y_SUB_RESULT_NAME)).build();
		final ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
				.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, "ok")).build();
		LOG.info("output node: " + cn);
		return cn;
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
		// NodeIdentifier result = new NodeIdentifier(N_RESULT_NAME);
		// NodeIdentifier subid = new NodeIdentifier(N_SUB_ID_NAME);
		// ChoiceNode c1 = Builders.choiceBuilder().withNodeIdentifier(result)
		// .withChild(ImmutableNodes.leafNode(subid, sidValue)).build();

		if (input instanceof ContainerNode) {
			// AugmentationNode an = (AugmentationNode)
			// conNode.getValue().iterator().next();
			try {
				conNode = (ContainerNode) input;
				LOG.info("Whole node: " + conNode);
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
				LOG.info("Parsed subscription ID is: " + dsri.getSubscriptionId());
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

	/**************************************
	 * Section for ESTABLISH-SUBSCRIPTION *
	 **************************************/
	private CheckedFuture<DOMRpcResult, DOMRpcException> establishSubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		String sid = "";

		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateFailedCheckedFuture(createDOMRpcException("Input missing or null"));
		}
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateFailedCheckedFuture(createDOMRpcException("Input missing or null"));
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseEstablishAndModifySubExternalRpcInput(input, error, true);
		if (inputData == null) {
			LOG.error("Parsing failed");
			return Futures.immediateFailedCheckedFuture(createDOMRpcException("Parsing failed"));
		}
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		// get subscription id from subscription engine.
		sid = this.subscriptionEngine.generateSubscriptionId();
		inputData.setSubscription_id(sid);
		// Saving the Subscription Information locally & on MDSAL datastore
		this.subscriptionEngine.updateMdSal(inputData, operations.establish);

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
			return Futures.immediateFailedCheckedFuture((DOMRpcException) new DOMRpcException(
					"Wrong Subscription exists, neither on-Change nor periodic Subscription") {
			});
		}
		ContainerNode output = createEstablishSubOutput(inputData.getSubscriptionId());
		// TODO Here should OAM message with 'subscription established' be sent
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	/**
	 * Creates a container node for CreateSubscription RPC output.
	 *
	 * @param sid
	 * @return containerNode for Create Subscription Output
	 */
	private ContainerNode createEstablishSubOutput(String sid) {
		SubscriptionInfo subscriptionInfo = subscriptionEngine.getSubscription(sid);

		NodeIdentifier result = new NodeIdentifier(N_RESULT_NAME);
		NodeIdentifier subid = new NodeIdentifier(N_SUB_ID_NAME);
		NodeIdentifier updateTrigger = new NodeIdentifier(Y_UPDATE_TRIGGER_NAME);
		NodeIdentifier period = new NodeIdentifier(Y_PERIOD_NAME);
		NodeIdentifier dampeningPeriod = new NodeIdentifier(Y_DAMPENING_PERIOD_NAME);
		NodeIdentifier noSynchOnStart = new NodeIdentifier(Y_NO_SYNCH_ON_START_NAME);
		NodeIdentifier exlcludedChange = new NodeIdentifier(Y_EXCLUDED_CHANGE_NAME);

		Long sidValue = Long.valueOf(sid);
		Short subPriorityValue = Short.valueOf(subscriptionInfo.getSubscriptionPriority());
		// Short dscpValue = Short.valueOf(subscriptionInfo.getDscp());
		ChoiceNode c1 = Builders.choiceBuilder().withNodeIdentifier(result)
				.withChild(ImmutableNodes.leafNode(subid, sidValue)).build();
		ChoiceNode c2 = null;

		// Whether its periodic or on-Change the node must be built differently
		if (!(subscriptionInfo.getPeriod() == null)) {
			LOG.info("Period" + subscriptionInfo.getPeriod().toString());
			c2 = Builders.choiceBuilder().withNodeIdentifier(updateTrigger)
					.withChild(ImmutableNodes.leafNode(period, subscriptionInfo.getPeriod())).build();
		} else {
			LOG.info("DP" + subscriptionInfo.getDampeningPeriod().toString());
			c2 = Builders.choiceBuilder().withNodeIdentifier(updateTrigger)
					.withChild(ImmutableNodes.leafNode(noSynchOnStart, null))
					.withChild(ImmutableNodes.leafNode(dampeningPeriod, subscriptionInfo.getDampeningPeriod())).build();
		}
		// Creating final output node if sid is valid
		if (!sid.equals("-1")) {
			final ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
					.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, "ok")).withChild(c2).withChild(c1)
					// .withChild(ImmutableNodes.leafNode(Y_DSCP_NAME,
					// dscpValue))
					.withChild(ImmutableNodes.leafNode(Y_SUB_PRIORITY_NAME, subPriorityValue))
					.withChild(ImmutableNodes.leafNode(Y_SUB_DEPENDENCY_NAME,
							subscriptionInfo.getSubscriptionDependency()))
					.withChild(
							ImmutableNodes.leafNode(Y_SUB_STOP_TIME_NAME, subscriptionInfo.getSubscriptionStopTime()))
					.withChild(
							ImmutableNodes.leafNode(Y_SUB_START_TIME_NAME, subscriptionInfo.getSubscriptionStartTime()))
					.build();
			LOG.info("output node: " + cn);
			return cn;
		}
		return null;
	}

	// Parsing the whole RPC, part by part
	private SubscriptionInfo parseEstablishAndModifySubExternalRpcInput(NormalizedNode<?, ?> input, String error,
			Boolean isEstablish) {
		SubscriptionInfo esri = new SubscriptionInfo();
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
				LOG.info("Whole node: " + conNode);
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
				LOG.info("Parsing encode complete : " + esri.getEncoding());
				// II Parse stream - NO AUGMENTATION
				DataContainerChild<? extends PathArgument, ?> streamNode = null;
				NodeIdentifier stream = new NodeIdentifier(N_STREAM_NAME);
				t = conNode.getChild(stream);
				if (t.isPresent()) {
					streamNode = (LeafNode<?>) t.get();
					if (streamNode.getValue() != null) {
						esri.setStream(streamNode.getValue().toString().split("\\)")[1]);
					} else {
						esri.setStream("YANG-PUSH");
					}
				} else {
					esri.setStream("YANG-PUSH");
				}
				LOG.info("Parsing stream complete : " + esri.getStream());
				// III Parse sub-start-time
				DataContainerChild<? extends PathArgument, ?> subStartTimeNode = null;
				NodeIdentifier subStartTime = new NodeIdentifier(Y_SUB_START_TIME_NAME);
				t = an.getChild(subStartTime);
				// TODO Use date instead of dateFuture for further work.
				// DateFuture was needed for a special version of ncclient.
				Date date = new Date();
//				Long timeFuture = date.getTime() + 5000;
//				Date dateFuture = new Date(timeFuture);
				// Remove timeFuture and dateFuture...
				if (t.isPresent()) {
					subStartTimeNode = (LeafNode<?>) t.get();
					if (!(subStartTimeNode.getValue().equals(null))) {
						if (!subStartTimeIsBeforeSystemTime(subStartTimeNode.getValue().toString())) {
							esri.setSubscriptionStarTime(subStartTimeNode.getValue().toString());
						} else {
							esri.setSubscriptionStarTime(
									new SimpleDateFormat(YANG_DATEANDTIME_FORMAT_BLUEPRINT).format(date));
						}
					} else {
						esri.setSubscriptionStarTime(
								new SimpleDateFormat(YANG_DATEANDTIME_FORMAT_BLUEPRINT).format(date));
					}
				} else {
					esri.setSubscriptionStarTime(
							new SimpleDateFormat(YANG_DATEANDTIME_FORMAT_BLUEPRINT).format(date));
				}
				LOG.info("Parsing sub-start-time complete : " + esri.getSubscriptionStartTime());
				// IV Parse sub-stop-time
				DataContainerChild<? extends PathArgument, ?> subStopTimeNode = null;
				NodeIdentifier subStopTime = new NodeIdentifier(Y_SUB_STOP_TIME_NAME);
				t = an.getChild(subStopTime);
				if (t.isPresent()) {
					subStopTimeNode = (LeafNode<?>) t.get();
					if (!(subStopTimeNode.getValue().equals(null))) {
						esri.setSubscriptionStopTime(subStopTimeNode.getValue().toString());
					} else {
						esri.setSubscriptionStopTime(null);
					}
				} else {
					esri.setSubscriptionStopTime(null);
				}
				LOG.info("Parsing sub-stop-time complete : " + esri.getSubscriptionStopTime());
				// V Parse start-time
				DataContainerChild<? extends PathArgument, ?> startTimeNode = null;
				NodeIdentifier startTime = new NodeIdentifier(N_START_TIME_NAME);
				t = an.getChild(startTime);
				if (t.isPresent()) {
					startTimeNode = (LeafNode<?>) t.get();
					if (!(startTimeNode.getValue().equals(null))) {
						esri.setStartTime(startTimeNode.getValue().toString());
					} else {
						esri.setStartTime(null);
					}
				} else {
					esri.setStartTime(null);
				}
				LOG.info("Parsing start-time complete : " + esri.getStartTime());
				// VI Parse stop-time
				DataContainerChild<? extends PathArgument, ?> stopTimeNode = null;
				NodeIdentifier stopTime = new NodeIdentifier(N_STOP_TIME_NAME);
				t = an.getChild(stopTime);
				if (t.isPresent()) {
					stopTimeNode = (LeafNode<?>) t.get();
					if (!(stopTimeNode.getValue().equals(null))) {
						esri.setStopTime(stopTimeNode.getValue().toString());
					} else {
						esri.setStopTime(null);
					}
				} else {
					esri.setStopTime(null);
				}
				LOG.info("Parsing stop-time complete : " + esri.getStopTime());
				// VII Parsing update-trigger
				NodeIdentifier updateTrigger = new NodeIdentifier(Y_UPDATE_TRIGGER_NAME);
				NodeIdentifier period = new NodeIdentifier(Y_PERIOD_NAME);
				NodeIdentifier dampeningPeriod = new NodeIdentifier(Y_DAMPENING_PERIOD_NAME);
				ChoiceNode c1 = (ChoiceNode) an.getChild(updateTrigger).get();
				if (c1.getChild(period).isPresent()) {
					// VII a Parsing periodic
					DataContainerChild<? extends PathArgument, ?> c2 = c1.getChild(period).get();
					if (c2.getValue() != null) {
						esri.setPeriod((Long) c2.getValue());
						LOG.info("Periode auf " + esri.getPeriod() + " gesetzt");
					}
				} else if (c1.getChild(dampeningPeriod).isPresent()) {
					// VII b Parsing on-Change
					DataContainerChild<? extends PathArgument, ?> c2 = c1.getChild(dampeningPeriod).get();
					if (c2.getValue() != null) {
						esri.setDampeningPeriod((Long) c2.getValue());
						LOG.info("Dampening Periode auf " + esri.getDampeningPeriod() + " gesetzt");
					}
					NodeIdentifier noSynchOnStart = new NodeIdentifier(Y_NO_SYNCH_ON_START_NAME);
					t = c1.getChild(noSynchOnStart);
					if (t.isPresent()) {
						esri.setNoSynchOnStart(true);
					} else {
						esri.setNoSynchOnStart(false);
					}
					LOG.info("Parsing no-synch-on-start complete: " + esri.getNoSynchOnStart());
					// excluded Change is not supported yet..
					// DataContainerChild<? extends PathArgument, ?>
					// excludedChangeNode = null;
					// NodeIdentifier excludedChange = new
					// NodeIdentifier(Y_EXCLUDED_CHANGE_NAME);
					// t = c1.getChild(excludedChange);
					// if (t.isPresent()) {
					// LOG.info("t: "+t);
					// excludedChangeNode = (LeafSetNode<?>) t.get();
					// LOG.info("excludedChangeNode: "+excludedChangeNode);
					// Set<LeafNode<?>> excludedChangeNode2 = null;
					// excludedChangeNode2 = (Set<LeafNode<?>>)
					// excludedChangeNode.getValue();
					// LOG.info("excludedChangeNode2: "+excludedChangeNode);
					// if (excludedChangeNode.getValue().equals(null)) {
					// esri.setExcludedChange(excludedChangeNode.getValue().toString());
					// } else {
					// esri.setExcludedChange(null);
					// }
					// } else {
					// esri.setExcludedChange(null);
					// }
					LOG.info("Parsing excluded-change complete : " + esri.getExcludedChange());
				} else {
					error = Errors.printError(errors.input_period_error);
				}
				// Check for Errors
				if (!error.equals("")) {
					LOG.error(error);
					esri = null;
					return esri;
				}
				LOG.info("Parsing update-trigger complete " + "P: " + esri.getPeriod() + " DP: "
						+ esri.getDampeningPeriod());
				// VIII Parsing dscp
				DataContainerChild<? extends PathArgument, ?> dscpNode = null;
				NodeIdentifier dscp = new NodeIdentifier(Y_DSCP_NAME);
				t = an.getChild(dscp);
				if (t.isPresent()) {
					dscpNode = (LeafNode<?>) t.get();
					if (dscpNode.getValue().equals(null)) {
						esri.setDscp(String.valueOf(dscpNode.getValue()));
					} else {
						esri.setDscp("0");
					}
				} else {
					esri.setDscp("0");
				}
				LOG.info("Parsing dscp complete : " + esri.getDscp());
				// IX Parsing sub-priority
				DataContainerChild<? extends PathArgument, ?> subPriorityNode = null;
				NodeIdentifier subPriority = new NodeIdentifier(Y_SUB_PRIORITY_NAME);
				t = an.getChild(subPriority);
				if (t.isPresent()) {
					subPriorityNode = (LeafNode<?>) t.get();
					if (subPriorityNode.getValue().equals(null)) {
						esri.setSubscriptionPriority(String.valueOf(subPriorityNode.getValue()));
					} else {
						esri.setSubscriptionPriority("0");
					}
				} else {
					esri.setSubscriptionPriority("0");
				}
				LOG.info("Parsing sub-priority complete : " + esri.getSubscriptionPriority());
				// IX Parsing sub-dependency
				DataContainerChild<? extends PathArgument, ?> subDependencyNode = null;
				NodeIdentifier subDependency = new NodeIdentifier(Y_SUB_DEPENDENCY_NAME);
				t = an.getChild(subDependency);
				if (t.isPresent()) {
					subDependencyNode = (LeafNode<?>) t.get();
					if (subDependencyNode.getValue().equals(null)) {
						esri.setSubscriptionDependency((String) subDependencyNode.getValue());
					} else {
						esri.setSubscriptionDependency("0");
					}
				} else {
					esri.setSubscriptionDependency("0");
				}
				LOG.info("Parsing sub-dependency complete : " + esri.getSubscriptionDependency());
				// TODO Check it
				// XI Parse filter-type
				NodeIdentifier filtertype = new NodeIdentifier(N_SUBTREE_FILTER_TYPE_NAME);
				NodeIdentifier subtreeFilter = new NodeIdentifier(N_SUBTREE_FILTER_NAME);
				if (conNode.getChild(filtertype).isPresent()) {
					ChoiceNode c2 = (ChoiceNode) conNode.getChild(filtertype).get();
					DataContainerChild<? extends PathArgument, ?> t2 = c2.getChild(subtreeFilter).get();
					if (t2 != null) {
						AnyXmlNode anyXmlFilter = (AnyXmlNode) t2;
						org.w3c.dom.Node nodeFilter = anyXmlFilter.getValue().getNode();
						org.w3c.dom.Document document = nodeFilter.getOwnerDocument();
						document.renameNode(nodeFilter, NOTIFICATION_NS, "filter");
						DOMSource domSource = anyXmlFilter.getValue();
						org.w3c.dom.Node test = domSource.getNode();
						String test2 = domSource.getSystemId();
						String test3 = test.getNodeName();
						esri.setFilter(domSource);
						// XmlElement dataSrc =
						// XmlElement.fromDomElement(domSource);

						Transformer transformer = TransformerFactory.newInstance().newTransformer();
						transformer.setOutputProperty(OutputKeys.INDENT, "yes");
						transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
						// initialize StreamResult with File object to save to
						// file
						StreamResult result = new StreamResult(new StringWriter());
						DOMSource source = domSource;
						transformer.transform(source, result);
						String xmlString = result.getWriter().toString();
						LOG.info("Original xmlString: " + xmlString);
						xmlString = xmlString.split("\\<")[3];
						// LOG.info("xmlString2: "+xmlString);
						xmlString = xmlString.split("\\s+")[0];
						// LOG.info("xmlString3: "+xmlString);
						// LOG.info("Test: " + test + "Test2: " + test2 +
						// "Test3: " + test3);
					} else {
						LOG.error("Only subtree filter supported at the moment.");
					}
				}
				LOG.info("Parsing filter-type complete : " + esri.getFilter());
				// Set the SubscriptionStreamStatus to inactive, as long as no
				// Notification is sent to client
				esri.setSubscriptionStreamStatus(SubscriptionStreamStatus.inactive);
				// Check if it is a modify subscription
				if (!isEstablish) {
					// Parse subscription-id
					DataContainerChild<? extends PathArgument, ?> subIdNode = null;
					NodeIdentifier sub_id = new NodeIdentifier(N_SUB_ID_NAME);
					t = conNode.getChild(sub_id);
					if (t.isPresent()) {
						subIdNode = t.get();
						if (subIdNode.getValue() != null) {
							String subscription_id = subIdNode.getValue().toString();
							esri.setSubscription_id(subscription_id);
						} else {
							error = Errors.printError(errors.input_sub_id_error);
						}
					} else {
						error = Errors.printError(errors.input_sub_id_error);
					}
					LOG.info("Parsed subscription ID is: " + esri.getSubscriptionId());
				}
				// TODO Check if Input is corrrect:
				// if (correctEstablishOrModifyInput(esri)) {
				return esri;
				// }
			} catch (Exception e) {
				LOG.error(e.toString());
			}
		} else {
			error = Errors.printError(errors.input_not_instance_of);
			esri = null;
		}
		return esri;
	}

	private boolean subStartTimeIsBeforeSystemTime(String subStartTime) {
		Long currentTime = new Date().getTime();
		DateFormat format = new SimpleDateFormat(PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT);
		Long subscriptionStartTime;
		try {
			subscriptionStartTime = format.parse(subStartTime).getTime();
			if (subscriptionStartTime < currentTime) {
				return true;
			}
		} catch (ParseException e) {
			LOG.error("Failed to parse subscription-start-time");
			e.printStackTrace();
		}

		return false;
	}

	// TODO check the input
	private Boolean correctEstablishOrModifyInput(SubscriptionInfo esri) {
		Boolean result = false;
		if (esri.getEncoding().equals("encode-xml")) {
		} else if (esri.getEncoding().equals("encode-json")) {
		} else {
			LOG.error("Wrong encoding");
			return result;
		}
		LOG.info("Correct encoding");
		// Comparison if start-time is before system time is proved while
		// parsing
		// Compare if sub-start-time is before sub-stop-time.
		// if (!(subscriptionInfo.getStartTime()==null)) {
		// DateFormat format = new
		// SimpleDateFormat(PeriodicNotification.YANG_DATEANDTIME_FORMAT_BLUEPRINT);
		// try {
		// Long subscriptionStartTime =
		// format.parse(esri.getSubscriptionStartTime()).getTime();
		// Long subscriptionStopTime =
		// format.parse(esri.getSubscriptionStopTime()).getTime();
		//
		// if (subscriptionStartTime >= subscriptionStopTime) {
		// return result;
		// }
		// } catch (ParseException e) {
		// LOG.error("Failed to parse subscription-start-time");
		// e.printStackTrace();
		// }
		// }
		// Compare if stream is either NETCONF, OPERATIONAL or CONFIGURATION
		switch (esri.getStream()) {

		case "YANG-PUSH":
			break;
		case "OPERATIONAL":
			break;
		case "CONFIGURATION":
			break;
		default:
			return result;
		}
		// Compare if Filter is correct, atm just subtree is supported
		// if (true) {
		// if (true) {
		// if (esri.getFilter() != "subtree") {
		// System.out.println(String.valueOf(date));
		// LOG.error("Only subtree supported");
		// return result;
		// }
		// }
		// }
		// Compare qos paramters: dscp, sub-dependecy and sub-priority
		// Input should be checked now
		result = true;
		LOG.info("Correct Rpc Input");
		return result;
	}

	/***********************************
	 * Section for MODIFY-SUBSCRIPTION *
	 ***********************************/
	private CheckedFuture<DOMRpcResult, DOMRpcException> modifySubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		String sid = "";
		ContainerNode output = null;
		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateFailedCheckedFuture(createDOMRpcException("Input missing or null"));
		}
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateFailedCheckedFuture(createDOMRpcException("Input missing or null"));
		}

		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseEstablishAndModifySubExternalRpcInput(input, error, false);
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		// get subscription id from subscription engine.
		sid = this.subscriptionEngine.generateSubscriptionId();
		LOG.info(sid);
		inputData.setSubscription_id(sid);

		// Saving the Subscription Information locally & on MDSAL datastore
		this.subscriptionEngine.updateMdSal(inputData, operations.modify);

		// The novel Notifications will be registered
		if (!inputData.getDampeningPeriod().equals(null)) {
			notificationEngine.registerOnChangeNotification(inputData.getSubscriptionId());
			LOG.info("Register on-Change-Notifications");
		} else if (!inputData.getPeriod().equals(null)) {
			notificationEngine.registerPeriodicNotification(inputData.getSubscriptionId());
			LOG.info("Register periodic-Notifications");
		} else {
			LOG.error("Wrong Subscription exists, neither on-Change nor periodic Subscription");

			return Futures.immediateFailedCheckedFuture(
					createDOMRpcException("Wrong Subscription exists, neither on-Change nor periodic Subscription"));
		}
		output = createModifySubOutput(inputData.getSubscriptionId());
		// TODO Here should OAM message with 'subscription modify' be sent
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	private ContainerNode createModifySubOutput(String sid) {
		ContainerNode cn = null;
		cn = Builders.containerBuilder().withNodeIdentifier(N_ESTABLISH_SUB_OUTPUT)
				.withChild(ImmutableNodes.leafNode(N_RESULT_NAME, "ok"))
				.withChild(ImmutableNodes.leafNode(N_SUB_ID_NAME, sid)).build();
		return cn;
	}
}
