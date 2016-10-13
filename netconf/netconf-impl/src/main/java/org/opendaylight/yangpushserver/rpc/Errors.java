/*
 * Copyright © 2016 Cisco Systems Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangpushserver.rpc;

import org.opendaylight.yangpushserver.subscription.SubscriptionEngine;

/**
 * 
 * Possible error strings that may be used in {@link RpcImpl} and/or 
 * {@link SubscriptionEngine}.
 * 
 * @author Philipp Konegen
 *
 */
public class Errors {
    private static final String errorString[] ={
            "Input missing or null",
            "Period is missing in Input or value is absent",
            "Stream is missing in Input or value is absent",
            "Invalid filter in Input",
            "Node_Name absent in Input or value is absent",
            "Input is not a instance of Contaier Node",
            "Start-time is missing in Input or value is absent",
            "Stop-time is missing in Input or value is absent",
            "Node not found in ODL topology",
            "Node has no capability to support datastore push create-subscription",
            "YANG-PUSH capability version missmatch",
            "Invalid Period for subscription",
            "Error in subscription filter",
            "Subscription not possible, due to resource unavailability",
            "Subscription id error in RPC input",
            "YANG model is missing",
            "Encoding is missing in Input or value is absent",
            "Update-trigger is missing in Input or value is absent",
            "Subscription-Id is missing in Input or value is absent",
    };

    public static enum errors{
        input_error,
        input_period_error,
        input_stream_error,
        input_filter_error,
        input_node_error,
        input_not_instance_of,
        input_start_time_error,
        input_stop_time_error,
        node_not_found_error,
        node_capability_error,
        node_capability_version_error,
        period_error,
        filter_error,
        subscription_creation_error,
        input_sub_id_error,
        y_model_missing, 
        input_encoding_error, 
        input_update_trigger_error, 
        input_subscription_id_error,
    };

    /**
     * 
     * @param id
     * @return
     */
    public static String printError(errors id) {
        return errorString[id.ordinal()];
    }
}