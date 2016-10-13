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
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.event.notifications.rev160615.SubscriptionResponse;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.establish.subscription.input.filter.type.UpdateFilter;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.push.rev160615.update.filter.update.filter.Subtree;
import org.opendaylight.yangpushserver.impl.YangpushProvider;
import org.opendaylight.yangpushserver.notification.NotificationEngine;
import org.opendaylight.yangpushserver.notification.OAMNotification.OAMStatus;
import org.opendaylight.yangpushserver.notification.PeriodicNotification;
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
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.ImmutableNodes;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;

/**
 * This singleton class will handle and process the incoming different supported
 * RPC's - establish, delete and modify a subscription.
 * 
 * @author Philipp Konegen
 *
 */
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
	// ietf-event-notifications

	public static final NodeIdentifier N_SUBSCRIPTION_RESPONSE = NodeIdentifier.create(SubscriptionResponse.QNAME);
	public static final NodeIdentifier N_ESTABLISH_SUB_OUTPUT = NodeIdentifier
			.create(EstablishSubscriptionOutput.QNAME);
	public static final NodeIdentifier N_ESTABLISH_SUB_INPUT = NodeIdentifier.create(EstablishSubscriptionInput.QNAME);
	public static final NodeIdentifier N_DELETE_SUB_OUTPUT = NodeIdentifier.create(DeleteSubscriptionOutput.QNAME);
	public static final NodeIdentifier N_DELETE_SUB_INPUT = NodeIdentifier.create(DeleteSubscriptionInput.QNAME);
	public static final NodeIdentifier N_MODIFY_SUB_OUTPUT = NodeIdentifier.create(ModifySubscriptionOutput.QNAME);
	public static final NodeIdentifier N_MODIFY_SUB_INPUT = NodeIdentifier.create(ModifySubscriptionInput.QNAME);

	// QNames used to construct establish filter present in
	// ietf-event-notifications
	public static final NodeIdentifier N_UPDATE_FILTER = NodeIdentifier.create(UpdateFilter.QNAME);
	public static final NodeIdentifier N_SUBTREE = NodeIdentifier.create(Subtree.QNAME);
	// public static final QName N_FILTER_TYPE_NAME = QName.create(NOTIF_BIS,
	// NOTIF_BIS_DATE, "filter-type");

	// QNames used to construct the RPC's
	public static final DOMRpcIdentifier ESTABLISH_SUBSCRIPTION_RPC = DOMRpcIdentifier
			.create(SchemaPath.create(true, QName.create(EstablishSubscriptionInput.QNAME, "establish-subscription")));
	public static final DOMRpcIdentifier MODIFY_SUBSCRIPTION_RPC = DOMRpcIdentifier
			.create(SchemaPath.create(true, QName.create(ModifySubscriptionInput.QNAME, "modify-subscription")));
	public static final DOMRpcIdentifier DELETE_SUBSCRIPTION_RPC = DOMRpcIdentifier
			.create(SchemaPath.create(true, QName.create(DeleteSubscriptionInput.QNAME, "delete-subscription")));

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private DOMRpcProviderService service;
	private DOMDataBroker globalDomDataBroker;
	private YangpushProvider provider;
	private SubscriptionEngine subscriptionEngine = null;
	private NotificationEngine notificationEngine = null;
	private SubscriptionInfo subscriptionInfo = new SubscriptionInfo();
	String date = String.valueOf(new Date().getTime());

	public RpcImpl(DOMRpcProviderService service, DOMDataBroker globalDomDataBroker, YangpushProvider provider) {
		super();
		this.service = service;
		this.globalDomDataBroker = globalDomDataBroker;
		this.provider = provider;
		this.subscriptionEngine = SubscriptionEngine.getInstance();
		this.notificationEngine = NotificationEngine.getInstance();
		registerRPCs();
	}

	/**
	 * Registers RPC present in ietf-yang-push module to DOMRpcProviderService.
	 */
	private void registerRPCs() {
		service.registerRpcImplementation(this, ESTABLISH_SUBSCRIPTION_RPC, MODIFY_SUBSCRIPTION_RPC,
				DELETE_SUBSCRIPTION_RPC);
	}

	/**
	 * This method is invoked on RPC invocation of the registered method. Here
	 * will be distinguished between establish, modify and delete subscription.
	 * 
	 * @param rpc
	 *            This is used to invoke the correct requested method, for the
	 *            defined localname.
	 * @param input
	 *            The whole input -represented in nodes- that is sent beneath
	 *            the rpc
	 * @return Either a {@link CheckedFuture} in case of succes, or a
	 *         FailedCheckedFuture which will result in a RPC-Error.
	 */
	@Override
	public CheckedFuture<DOMRpcResult, DOMRpcException> invokeRpc(DOMRpcIdentifier rpc, NormalizedNode<?, ?> input) {

		LOG.info("sub-RPC invoked");
		if (rpc.equals(ESTABLISH_SUBSCRIPTION_RPC)) {
			LOG.info("This is a establish subscription RPC");
			return establishSubscriptionRpcHandler(input);
		} else if (rpc.equals(MODIFY_SUBSCRIPTION_RPC)) {
			LOG.info("This is a modify subscrition RPC.");
			return modifySubscriptionRpcHandler(input);
		} else if (rpc.equals(DELETE_SUBSCRIPTION_RPC)) {
			LOG.info("This is a delete subscrition RPC");
			return deleteSubscriptionRpcHandler(input);
		}
		LOG.info("Unknown RPC...");
		return Futures.immediateFailedCheckedFuture(createDOMRpcException("RPC invocation not supported!"));
	}

	/**
	 * Creating a {@link DOMRpcException} with the given string. It will end up
	 * in an RpcError with the string as exception message.
	 * 
	 * @param string
	 * @return {@link DOMRpcException}
	 */
	private DOMRpcException createDOMRpcException(String string) {
		// RpcError.ErrorType test = new RpcErrors();
		// RpcError output =
		// RpcResultBuilder.newError(RpcError.ErrorType.RPC, null, "test");
		// LOG.info(output.toString());
		return new DOMRpcException(string) {
		};
	}

	/**
	 * This method is invoked either if something during the processing of a RPC
	 * would cause an error or if the input is missing/null.
	 * 
	 * @param error
	 *            The error that would cause the conflict.
	 */
	private ContainerNode createSubResponse(String error) {
		final ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(N_SUBSCRIPTION_RESPONSE)
				.withChild(ImmutableNodes.leafNode(N_SUB_RESULT_NAME, error)).build();
		LOG.info("subResponse node: " + cn);
		return cn;
	}

	/***********************************
	 * Section for DELETE-SUBSCRIPTION *
	 ***********************************/
	/**
	 * This method will handle the incomming delete subscription RPC. After
	 * checking for null, the input nodes are going to be parsed. If the
	 * subscription exists the following order will be executed:
	 * <p>
	 * 1. Unregistering the notifications, so that no more notifications will be
	 * sent.
	 * <p>
	 * 2. The subscription itself will be deleted from the local map and inside
	 * MD-SAL {@link SubscriptionEngine}
	 * <p>
	 * 3. Creating the delete subscription output.
	 * <p>
	 * 4. The {@link YangpushProvider} will be notified about the deleted
	 * subscription.
	 * <p>
	 * 5. The created output will be sent.
	 * 
	 * @param input
	 *            The input presented as NormalizedNode.
	 * @return CheckedFuture either with the correct output in case of success
	 *         or with an error-rpc.
	 */
	private CheckedFuture<DOMRpcResult, DOMRpcException> deleteSubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String error = "";
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateCheckedFuture(
					(DOMRpcResult) new DefaultDOMRpcResult(createSubResponse("error - input missing or null")));
		}
		LOG.info("Going to parse RPC input");
		SubscriptionInfo inputData = parseDeleteSubExternalRpcInput(input);
		LOG.info("Parsing complete");
		LOG.info("Delete Subscription parsed Input: " + inputData.toString());
		if (subscriptionEngine.checkIfSubscriptionExists(inputData.getSubscriptionId())) {
			// TODO The client authorization should be checked here.
			// Unregistering the notifications
			notificationEngine.unregisterNotification(inputData.getSubscriptionId());
			// Deleting the subscription from data store.
			subscriptionEngine.updateMdSal(inputData, operations.delete);
			ContainerNode output = createDeleteSubOutput(inputData);
			provider.onDeletedSubscription(inputData.getSubscriptionId());
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
		} else {
			LOG.error("No such subscription with ID:" + inputData.getSubscriptionId());
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(
					createSubResponse("error no such subscription with ID:" + inputData.getSubscriptionId())));
		}
	}

	/**
	 * This method will create the delete subscription output. At the moment
	 * only the case for success is supported.
	 * 
	 * @param subscriptionInfo
	 *            All of the parsed information about a subscription.
	 * @return A ContainerNode which represents the output and can be delivered
	 *         via a new {@link DefaultDOMRpcResult}
	 */
	private ContainerNode createDeleteSubOutput(SubscriptionInfo subscriptionInfo) {
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
	 *            The input presented as NormalizedNode.
	 * @param error
	 * @return SubscriptionInfo
	 */
	private SubscriptionInfo parseDeleteSubExternalRpcInput(NormalizedNode<?, ?> input) {
		SubscriptionInfo dsri = new SubscriptionInfo();
		ContainerNode conNode = null;
		String error = "";
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
	/**
	 * This method will handle the incomming establish subscription RPC. After
	 * checking for null, the input nodes are going to be parsed. Then the
	 * following order will be executed:
	 * <p>
	 * <p>
	 * 1. Generating the unique subscription id inside
	 * {@link SubscriptionEngine} sent.
	 * <p>
	 * 2. The subscription itself will be stored to the local map and MD-SAL
	 * inside {@link SubscriptionEngine}
	 * <p>
	 * 3. Registering either periodic or on-change notifications
	 * <p>
	 * 4. Creating the establish subscription output.
	 * <p>
	 * 4. The {@link YangpushProvider} will be notified about the established
	 * subscription.
	 * <p>
	 * 5. The created output will be sent.
	 * <p>
	 * 
	 * @param input
	 *            The input presented as NormalizedNode.
	 * @return
	 */
	private CheckedFuture<DOMRpcResult, DOMRpcException> establishSubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		String sid = "";

		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateCheckedFuture(
					(DOMRpcResult) new DefaultDOMRpcResult(createSubResponse("error - input missing or null")));
		}
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateCheckedFuture(
					(DOMRpcResult) new DefaultDOMRpcResult(createSubResponse("error - input missing or null")));
		}
		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseEstablishSubExternalRpcInput(input);
		if (inputData == null) {
			LOG.error("Parsing failed");
			return Futures.immediateCheckedFuture(
					(DOMRpcResult) new DefaultDOMRpcResult(createSubResponse("error - parsing failed")));
		}
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		// get subscription id from subscription engine.
		sid = this.subscriptionEngine.generateSubscriptionId();
		inputData.setSubscription_id(sid);
		// TODO The client authorization should be checked here.
		// Saving the Subscription Information locally & on MDSAL datastore
		this.subscriptionEngine.updateMdSal(inputData, operations.establish);
		// Check if on-Change-Notifications or periodic-Notifications
		if (inputData.getDampeningPeriod() != null) {
			LOG.info("Register on-Change-Notifications");
			notificationEngine.registerOnChangeNotification(inputData.getSubscriptionId());
		} else if (inputData.getPeriod() != null) {
			LOG.info("Register periodic-Notifications");
			notificationEngine.registerPeriodicNotification(inputData.getSubscriptionId());
		} else {
			LOG.error("Wrong Subscription exists, neither on-Change nor periodic Subscription");
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(
					createSubResponse("error - wrong subscription, neither on-Change nor periodic subscription")));
		}
		ContainerNode output = createEstablishSubOutput(inputData.getSubscriptionId());
		provider.onEstablishedSubscription(inputData.getSubscriptionId());
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
		// Not yet supported
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

	/**
	 * Parsing the whole establish subscription RPC, part by part, and stores it
	 * in a {@link SubscriptionInfo}. Also the default values for each parameter
	 * will be set, if not present in input.
	 * 
	 * @param input
	 *            The input presented as NormalizedNode.
	 * @return SubscriptionInfo
	 */
	private SubscriptionInfo parseEstablishSubExternalRpcInput(NormalizedNode<?, ?> input) {
		SubscriptionInfo esri = new SubscriptionInfo();
		ContainerNode conNode = null;
		String error = "";
		// Checks if input is missing/null
		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			return null;
		}
		if (input instanceof ContainerNode) {
			try {
				// General variables before separate parsing starts..
				conNode = (ContainerNode) input;
				Set<QName> childNames = new HashSet<>();
				AugmentationNode an = getAugmentationNodeFromInput(input);
				LOG.info("AugmentationNode: " + an);
				LOG.info("Whole node: " + conNode);
				/**
				 * Whole example node
				 * 
				 * ImmutableContainerNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)input,
				 * value=[ImmutableAugmentationNode{nodeIdentifier=AugmentationIdentifier{childNames=[(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-dependency,
				 * (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-priority,
				 * (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)update-trigger,
				 * (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)dscp,
				 * (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-start-time,
				 * (urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)subscription-stop-time]},
				 * value=[ImmutableChoiceNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)update-trigger,
				 * value=[ImmutableLeafNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-yang-push?revision=2016-06-15)period,
				 * value=500, attributes={}}]}]},
				 * ImmutableLeafNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)encoding,
				 * value=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)encode-xml,
				 * attributes={}},
				 * ImmutableLeafNode{nodeIdentifier=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)stream,
				 * value=(urn:ietf:params:xml:ns:yang:ietf-event-notifications?revision=2016-06-15)NETCONF,
				 * attributes={}}], attributes={}}
				 */
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
				// DateFuture was needed for a special version of ncclient.
				Date date = new Date();
				// Long timeFuture = date.getTime() + 5000;
				// Date dateFuture = new Date(timeFuture);
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
					esri.setSubscriptionStarTime(new SimpleDateFormat(YANG_DATEANDTIME_FORMAT_BLUEPRINT).format(date));
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
				// XI Parse filter-type (only subtree filter is supported)
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
					} else {
						LOG.error("Only subtree filter supported at the moment.");
					}
				}
				LOG.info("Parsing filter-type complete : " + esri.getFilter());
				// Set the SubscriptionStreamStatus to inactive, as long as no
				// notifications will be sent to client
				esri.setSubscriptionStreamStatus(SubscriptionStreamStatus.inactive);
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

	/**
	 * Looks for the AugmentationNode inside the input.
	 * 
	 * @param input
	 *            The input presented as NormalizedNode
	 * @return AugmentationNode from the input
	 */
	private AugmentationNode getAugmentationNodeFromInput(NormalizedNode<?, ?> input) {
		LOG.info("Looking for AugmentationNode");
		ContainerNode conNode = (ContainerNode) input;
		Iterator<DataContainerChild<? extends PathArgument, ?>> itr = conNode.getValue().iterator();
		while (itr.hasNext()) {
			Object next = itr.next();
			if (next instanceof AugmentationNode) {
				AugmentationNode result = (AugmentationNode) next;
				return result;
			}
		}
		return null;
	}

	/**
	 * Checks if the subscription-start-time is before the actual system time.
	 * 
	 * @param subStartTime
	 *            Represented in String format.
	 * @return Boolean
	 */
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
	/**
	 * This method checks the parsed input for syntax and semantic issues.
	 * 
	 * @param subscriptionInfo
	 * @return true, if the input is correct and matches the server capabilites
	 */
	private Boolean correctEstablishOrModifyInput(SubscriptionInfo subscriptionInfo) {
		Boolean result = false;
		if (subscriptionInfo.getEncoding().equals("encode-xml")) {
		} else if (subscriptionInfo.getEncoding().equals("encode-json")) {
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
		switch (subscriptionInfo.getStream()) {

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
		if (Integer.valueOf(subscriptionInfo.getSubscriptionId()) <= Integer.valueOf("0")) {
			return result;
		}
		// Input should be checked now
		result = true;
		LOG.info("Correct Rpc Input");
		return result;
	}

	/***********************************
	 * Section for MODIFY-SUBSCRIPTION *
	 ***********************************/
	/**
	 * This method will handle the incoming modify subscription RPC. After
	 * checking for null, the input nodes are going to be parsed. Then the
	 * following order will be executed:
	 * <p>
	 * <p>
	 * 1. Check in {@link SubscriptionEngine} if the subscription exists, if not
	 * sent error-rpc.
	 * <p>
	 * 2. Unregister the notifications in {@link NotificationEngine}.
	 * <p>
	 * 3. The modified subscription will be stored to the local map and MD-SAL
	 * inside {@link SubscriptionEngine}
	 * <p>
	 * 4. If period and damepening period and period are null, an error-rpc will
	 * be sent.
	 * <p>
	 * 5. Scheduling the OAM notifications and yang-push notifications, to
	 * ensure the rpc-reply reaches the client before these notifications.
	 * <p>
	 * 6. Creating the modify subscription output.
	 * <p>
	 * 7. The created output will be sent.
	 * <p>
	 * 
	 * @param input
	 *            The input presented as NormalizedNode.
	 * @return
	 */
	private CheckedFuture<DOMRpcResult, DOMRpcException> modifySubscriptionRpcHandler(NormalizedNode<?, ?> input) {
		ContainerNode output = null;
		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateCheckedFuture(
					(DOMRpcResult) new DefaultDOMRpcResult(createSubResponse("error - input missing or null")));
		}
		if (input.equals(null)) {
			LOG.error(Errors.printError(errors.input_error));
			return Futures.immediateCheckedFuture(
					(DOMRpcResult) new DefaultDOMRpcResult(createSubResponse("error - input missing or null")));
		}
		LOG.info("Going to parse RPC input");
		// Parse input arg
		SubscriptionInfo inputData = parseModifySubExternalRpcInput(input);
		// parsing should have been 'ok'
		LOG.info("Parsing complete");
		if (!subscriptionEngine.checkIfSubscriptionExists(inputData.getSubscriptionId())) {
			LOG.error("No such subscription with ID:" + inputData.getSubscriptionId());
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(
					createSubResponse("error no such subscription with ID:" + inputData.getSubscriptionId())));
		}
		// TODO The client authorization should be checked here.
		// Unregistering the notifications
		notificationEngine.unregisterNotification(inputData.getSubscriptionId());
		// Saving the Subscription Information locally & on MDSAL datastore
		this.subscriptionEngine.updateMdSal(inputData, operations.modify);

		if (inputData.getPeriod() == null && inputData.getDampeningPeriod() == null) {
			LOG.error("Wrong Subscription exists, neither on-Change nor periodic Subscription");
			return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(
					createSubResponse("error - wrong subscription, neither on-Change nor periodic subscription")));
		}
		// Workaround to ensure that the rpc-reply is send before OAM
		// notifications or yang-push notifications
		scheduler.schedule(() -> {
				// The OAM message with 'subscription modify' will be sent
				notificationEngine.oamNotification(inputData.getSubscriptionId(), OAMStatus.subscription_modified,
						null);
				// The novel notifications will be registered
				if (inputData.getDampeningPeriod() != null) {
					notificationEngine.registerOnChangeNotification(inputData.getSubscriptionId());
					LOG.info("Register on-Change-Notifications");
				} else if (inputData.getPeriod() != null) {
					notificationEngine.registerPeriodicNotification(inputData.getSubscriptionId());
					LOG.info("Register periodic-Notifications");
				} 	
		}, YangpushProvider.DELAY_TO_ENSURE_RPC_REPLY, TimeUnit.MILLISECONDS);
		output = createModifySubOutput(inputData.getSubscriptionId());
		return Futures.immediateCheckedFuture((DOMRpcResult) new DefaultDOMRpcResult(output));
	}

	/**
	 * Creates a container node for modify subscription RPC output.
	 *
	 * @param sub_id
	 *            individual Id of a subscription
	 * @return containerNode for modify subscription output
	 */
	private ContainerNode createModifySubOutput(String sub_id) {
		SubscriptionInfo subscriptionInfo = subscriptionEngine.getSubscription(sub_id);

		NodeIdentifier result = new NodeIdentifier(N_RESULT_NAME);
		NodeIdentifier subid = new NodeIdentifier(N_SUB_ID_NAME);
		NodeIdentifier updateTrigger = new NodeIdentifier(Y_UPDATE_TRIGGER_NAME);
		NodeIdentifier period = new NodeIdentifier(Y_PERIOD_NAME);
		NodeIdentifier dampeningPeriod = new NodeIdentifier(Y_DAMPENING_PERIOD_NAME);
		NodeIdentifier noSynchOnStart = new NodeIdentifier(Y_NO_SYNCH_ON_START_NAME);
		// Not yet supported
		NodeIdentifier exlcludedChange = new NodeIdentifier(Y_EXCLUDED_CHANGE_NAME);

		Long sidValue = Long.valueOf(sub_id);
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
		if (!sub_id.equals("-1")) {
			final ContainerNode cn = Builders.containerBuilder().withNodeIdentifier(N_MODIFY_SUB_OUTPUT)
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

	/**
	 * Parsing the whole modify subscription RPC, part by part, and stores it in
	 * a {@link SubscriptionInfo}. If values not present in input, they will be
	 * set to the values of the subscription before the modification.
	 * 
	 * @param input
	 *            The input presented as NormalizedNode.
	 * @return SubscriptionInfo
	 */
	private SubscriptionInfo parseModifySubExternalRpcInput(NormalizedNode<?, ?> input) {
		SubscriptionInfo msri = new SubscriptionInfo();
		ContainerNode conNode = null;
		String error = "";
		// Checks if input is missing/null
		if (input == null) {
			LOG.error(Errors.printError(errors.input_error));
			msri = null;
			return msri;
		}
		if (input instanceof ContainerNode) {
			try {
				// General variables before separate parsing starts..
				conNode = (ContainerNode) input;
				Set<QName> childNames = new HashSet<>();
				AugmentationNode an = getAugmentationNodeFromInput(input);
				LOG.info("AugmentationNode: " + an);
				LOG.info("Whole node: " + conNode);
				// Parse subscription-id
				DataContainerChild<? extends PathArgument, ?> subIdNode = null;
				NodeIdentifier sub_id = new NodeIdentifier(N_SUB_ID_NAME);
				Optional<DataContainerChild<? extends PathArgument, ?>> t = conNode.getChild(sub_id);
				if (t.isPresent()) {
					subIdNode = t.get();
					if (subIdNode.getValue() != null) {
						String subscription_id = subIdNode.getValue().toString();
						msri.setSubscription_id(subscription_id);
					} else {
						error = Errors.printError(errors.input_sub_id_error);
					}
				} else {
					error = Errors.printError(errors.input_sub_id_error);
				}
				LOG.info("Parsed subscription ID is: " + msri.getSubscriptionId());
				SubscriptionInfo oldSubscriptionInfo = subscriptionEngine.getSubscription(msri.getSubscriptionId());
				LOG.info("Old subscription is: " + oldSubscriptionInfo);
				// I Parse encoding - NO AUGMENTATION
				DataContainerChild<? extends PathArgument, ?> encodingNode = null;
				childNames.add(N_ENCODING_NAME);
				NodeIdentifier ni = new NodeIdentifier(N_ENCODING_NAME);
				t = conNode.getChild(ni);
				if (t.isPresent()) {
					encodingNode = (LeafNode<?>) t.get();
					if (encodingNode.getValue() != null) {
						msri.setEncoding(encodingNode.getValue().toString().split("\\)")[1]);
					} else {
						msri.setEncoding(oldSubscriptionInfo.getEncoding());
					}
				} else {
					msri.setEncoding(oldSubscriptionInfo.getEncoding());
				}
				LOG.info("Parsing encode complete : " + msri.getEncoding());
				// II Parse stream - NO AUGMENTATION
				DataContainerChild<? extends PathArgument, ?> streamNode = null;
				NodeIdentifier stream = new NodeIdentifier(N_STREAM_NAME);
				t = conNode.getChild(stream);
				if (t.isPresent()) {
					streamNode = (LeafNode<?>) t.get();
					if (streamNode.getValue() != null) {
						msri.setStream(streamNode.getValue().toString().split("\\)")[1]);
					} else {
						msri.setStream(oldSubscriptionInfo.getStream());
					}
				} else {
					msri.setStream(oldSubscriptionInfo.getStream());
				}
				LOG.info("Parsing stream complete : " + msri.getStream());
				// III Parse sub-start-time
				DataContainerChild<? extends PathArgument, ?> subStartTimeNode = null;
				NodeIdentifier subStartTime = new NodeIdentifier(Y_SUB_START_TIME_NAME);
				t = an.getChild(subStartTime);
				if (t.isPresent()) {
					subStartTimeNode = (LeafNode<?>) t.get();
					if (!(subStartTimeNode.getValue().equals(null))) {
						if (!subStartTimeIsBeforeSystemTime(subStartTimeNode.getValue().toString())) {
							msri.setSubscriptionStarTime(subStartTimeNode.getValue().toString());
						} else {
							msri.setSubscriptionStarTime(oldSubscriptionInfo.getSubscriptionStartTime());
						}
					} else {
						msri.setSubscriptionStarTime(oldSubscriptionInfo.getSubscriptionStartTime());
					}
				} else {
					msri.setSubscriptionStarTime(oldSubscriptionInfo.getSubscriptionStartTime());
				}
				LOG.info("Parsing sub-start-time complete : " + msri.getSubscriptionStartTime());
				// IV Parse sub-stop-time
				DataContainerChild<? extends PathArgument, ?> subStopTimeNode = null;
				NodeIdentifier subStopTime = new NodeIdentifier(Y_SUB_STOP_TIME_NAME);
				t = an.getChild(subStopTime);
				if (t.isPresent()) {
					subStopTimeNode = (LeafNode<?>) t.get();
					if (!(subStopTimeNode.getValue().equals(null))) {
						msri.setSubscriptionStopTime(subStopTimeNode.getValue().toString());
					} else {
						msri.setSubscriptionStopTime(oldSubscriptionInfo.getSubscriptionStopTime());
					}
				} else {
					msri.setSubscriptionStopTime(oldSubscriptionInfo.getSubscriptionStopTime());
				}
				LOG.info("Parsing sub-stop-time complete : " + msri.getSubscriptionStopTime());
				// V Parse start-time
				DataContainerChild<? extends PathArgument, ?> startTimeNode = null;
				NodeIdentifier startTime = new NodeIdentifier(N_START_TIME_NAME);
				t = an.getChild(startTime);
				if (t.isPresent()) {
					startTimeNode = (LeafNode<?>) t.get();
					if (!(startTimeNode.getValue().equals(null))) {
						msri.setStartTime(startTimeNode.getValue().toString());
					} else {
						msri.setStartTime(oldSubscriptionInfo.getStartTime());
					}
				} else {
					msri.setStartTime(oldSubscriptionInfo.getStartTime());
				}
				LOG.info("Parsing start-time complete : " + msri.getStartTime());
				// VI Parse stop-time
				DataContainerChild<? extends PathArgument, ?> stopTimeNode = null;
				NodeIdentifier stopTime = new NodeIdentifier(N_STOP_TIME_NAME);
				t = an.getChild(stopTime);
				if (t.isPresent()) {
					stopTimeNode = (LeafNode<?>) t.get();
					if (!(stopTimeNode.getValue().equals(null))) {
						msri.setStopTime(stopTimeNode.getValue().toString());
					} else {
						msri.setStopTime(oldSubscriptionInfo.getStopTime());
					}
				} else {
					msri.setStopTime(oldSubscriptionInfo.getStopTime());
				}
				LOG.info("Parsing stop-time complete : " + msri.getStopTime());
				// VII Parsing update-trigger
				NodeIdentifier updateTrigger = new NodeIdentifier(Y_UPDATE_TRIGGER_NAME);
				NodeIdentifier period = new NodeIdentifier(Y_PERIOD_NAME);
				NodeIdentifier dampeningPeriod = new NodeIdentifier(Y_DAMPENING_PERIOD_NAME);
				ChoiceNode c1 = (ChoiceNode) an.getChild(updateTrigger).get();
				if (c1.getChild(period).isPresent()) {
					// VII a Parsing periodic
					DataContainerChild<? extends PathArgument, ?> c2 = c1.getChild(period).get();
					if (c2.getValue() != null) {
						msri.setPeriod((Long) c2.getValue());
						LOG.info("Periode auf " + msri.getPeriod() + " gesetzt");
					}
				} else if (c1.getChild(dampeningPeriod).isPresent()) {
					// VII b Parsing on-Change
					DataContainerChild<? extends PathArgument, ?> c2 = c1.getChild(dampeningPeriod).get();
					if (c2.getValue() != null) {
						msri.setDampeningPeriod((Long) c2.getValue());
						LOG.info("Dampening Periode auf " + msri.getDampeningPeriod() + " gesetzt");
					}
					NodeIdentifier noSynchOnStart = new NodeIdentifier(Y_NO_SYNCH_ON_START_NAME);
					t = c1.getChild(noSynchOnStart);
					if (t.isPresent()) {
						msri.setNoSynchOnStart(true);
					} else {
						msri.setNoSynchOnStart(false);
					}
					LOG.info("Parsing no-synch-on-start complete: " + msri.getNoSynchOnStart());
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
					LOG.info("Parsing excluded-change complete : " + msri.getExcludedChange());
				} else {
					if (oldSubscriptionInfo.getPeriod() != null) {
						msri.setPeriod(oldSubscriptionInfo.getPeriod());
					} else {
						msri.setDampeningPeriod(oldSubscriptionInfo.getDampeningPeriod());
					}
				}
				LOG.info("Parsing update-trigger complete " + "P: " + msri.getPeriod() + " DP: "
						+ msri.getDampeningPeriod());
				// VIII Parsing dscp
				DataContainerChild<? extends PathArgument, ?> dscpNode = null;
				NodeIdentifier dscp = new NodeIdentifier(Y_DSCP_NAME);
				t = an.getChild(dscp);
				if (t.isPresent()) {
					dscpNode = (LeafNode<?>) t.get();
					if (dscpNode.getValue().equals(null)) {
						msri.setDscp(String.valueOf(dscpNode.getValue()));
					} else {
						msri.setDscp(oldSubscriptionInfo.getDscp());
					}
				} else {
					msri.setDscp(oldSubscriptionInfo.getDscp());
				}
				LOG.info("Parsing dscp complete : " + msri.getDscp());
				// IX Parsing sub-priority
				DataContainerChild<? extends PathArgument, ?> subPriorityNode = null;
				NodeIdentifier subPriority = new NodeIdentifier(Y_SUB_PRIORITY_NAME);
				t = an.getChild(subPriority);
				if (t.isPresent()) {
					subPriorityNode = (LeafNode<?>) t.get();
					if (subPriorityNode.getValue().equals(null)) {
						msri.setSubscriptionPriority(String.valueOf(subPriorityNode.getValue()));
					} else {
						msri.setSubscriptionPriority(oldSubscriptionInfo.getSubscriptionPriority());
					}
				} else {
					msri.setSubscriptionPriority(oldSubscriptionInfo.getSubscriptionPriority());
				}
				LOG.info("Parsing sub-priority complete : " + msri.getSubscriptionPriority());
				// IX Parsing sub-dependency
				DataContainerChild<? extends PathArgument, ?> subDependencyNode = null;
				NodeIdentifier subDependency = new NodeIdentifier(Y_SUB_DEPENDENCY_NAME);
				t = an.getChild(subDependency);
				if (t.isPresent()) {
					subDependencyNode = (LeafNode<?>) t.get();
					if (subDependencyNode.getValue().equals(null)) {
						msri.setSubscriptionDependency((String) subDependencyNode.getValue());
					} else {
						msri.setSubscriptionDependency(oldSubscriptionInfo.getSubscriptionDependency());
					}
				} else {
					msri.setSubscriptionDependency(oldSubscriptionInfo.getSubscriptionDependency());
				}
				LOG.info("Parsing sub-dependency complete : " + msri.getSubscriptionDependency());
				// XI Parse filter-type (only subtree filter is supported)
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
						msri.setFilter(domSource);
					} else {
						msri.setFilter(oldSubscriptionInfo.getFilter());
					}
				}
				LOG.info("Parsing filter-type complete : " + msri.getFilter());
				// Set the SubscriptionStreamStatus to inactive, as long as no
				// Notification is sent to client
				msri.setSubscriptionStreamStatus(SubscriptionStreamStatus.inactive);
				// Check if it is a modify subscription
				// TODO Check if Input is corrrect:
				// if (correctEstablishOrModifyInput(esri)) {
				return msri;
				// }
			} catch (Exception e) {
				LOG.error(e.toString());
			}
		} else {
			error = Errors.printError(errors.input_not_instance_of);
			msri = null;
		}
		return msri;
	}
}