package com.devicehive.websockets.handlers;

/*
 * #%L
 * DeviceHive Frontend Logic
 * %%
 * Copyright (C) 2016 - 2017 DataArt
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.devicehive.auth.HiveAuthentication;
import com.devicehive.auth.HivePrincipal;
import com.devicehive.configuration.Messages;
import com.devicehive.exceptions.HiveException;
import com.devicehive.json.strategies.JsonPolicyDef;
import com.devicehive.messages.handler.WebSocketClientHandler;
import com.devicehive.model.ErrorResponse;
import com.devicehive.model.enums.SortOrder;
import com.devicehive.model.rpc.ListNetworkRequest;
import com.devicehive.model.updates.NetworkUpdate;
import com.devicehive.resource.util.ResponseFactory;
import com.devicehive.service.NetworkService;
import com.devicehive.vo.NetworkVO;
import com.devicehive.vo.NetworkWithUsersAndDevicesVO;
import com.devicehive.websockets.converters.WebSocketResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import javax.ws.rs.core.Response;
import java.util.Collections;
import java.util.Optional;

import static com.devicehive.configuration.Constants.*;
import static com.devicehive.json.strategies.JsonPolicyDef.Policy.*;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;

@Component
public class NetworkHandlers {
    private static final Logger logger = LoggerFactory.getLogger(DeviceHandlers.class);

    @Autowired
    private NetworkService networkService;

    @Autowired
    private WebSocketClientHandler webSocketClientHandler;

    @Autowired
    private Gson gson;

    @PreAuthorize("isAuthenticated() and hasPermission(null, 'GET_NETWORK')")
    public void processNetworkList(JsonObject request, WebSocketSession session) {
        HivePrincipal principal = (HivePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        ListNetworkRequest listNetworkRequest = ListNetworkRequest.createListNetworkRequest(request);
        listNetworkRequest.setPrincipal(Optional.ofNullable(principal));

        String sortField = Optional.ofNullable(listNetworkRequest.getSortField()).map(String::toLowerCase).orElse(null);
        if (sortField != null && !ID.equalsIgnoreCase(sortField) && !NAME.equalsIgnoreCase(sortField)) {
            logger.error("Unable to proceed network list request. Invalid sortField");
            throw new HiveException(Messages.INVALID_REQUEST_PARAMETERS, BAD_REQUEST.getStatusCode());
        }

        WebSocketResponse response = new WebSocketResponse();
        if (!principal.areAllNetworksAvailable() && (principal.getNetworkIds() == null || principal.getNetworkIds().isEmpty())) {
            logger.warn("Unable to get list for empty networks");
            response.addValue(NETWORKS, Collections.<NetworkVO>emptyList(), NETWORKS_LISTED);
            webSocketClientHandler.sendMessage(request, response, session);
        } else {
            networkService.list(listNetworkRequest)
                    .thenAccept(networks -> {
                        logger.debug("Network list request proceed successfully.");
                        response.addValue(NETWORKS, networks, NETWORKS_LISTED);
                        webSocketClientHandler.sendMessage(request, response, session);
                    });
        }
    }

    @PreAuthorize("isAuthenticated() and hasPermission(#id, 'GET_NETWORK')")
    public void processNetworkGet(JsonObject request, WebSocketSession session) {
        logger.debug("Network get requested.");
        Long id = gson.fromJson(request.get(ID), Long.class);
        if (id == null) {
            logger.error(Messages.NETWORK_ID_REQUIRED);
            throw new HiveException(Messages.NETWORK_ID_REQUIRED, BAD_REQUEST.getStatusCode());
        }

        NetworkWithUsersAndDevicesVO existing = networkService.getWithDevices(id, (HiveAuthentication) SecurityContextHolder.getContext().getAuthentication());
        if (existing == null) {
            logger.error(String.format(Messages.NETWORK_NOT_FOUND, id));
            throw new HiveException(String.format(Messages.NETWORK_NOT_FOUND, id), NOT_FOUND.getStatusCode());
        }

        WebSocketResponse response = new WebSocketResponse();
        response.addValue(NETWORK, existing, NETWORK_PUBLISHED);
        webSocketClientHandler.sendMessage(request, response, session);
    }


    @PreAuthorize("isAuthenticated() and hasPermission(null, 'MANAGE_NETWORK')")
    public void processNetworkInsert(JsonObject request, WebSocketSession session) {
        logger.debug("Network insert requested");
        NetworkVO network = gson.fromJson(request.get(NETWORK), NetworkVO.class);
        if (network == null) {
            logger.error(Messages.NETWORK_REQUIRED);
            throw new HiveException(Messages.NETWORK_REQUIRED, BAD_REQUEST.getStatusCode());
        }
        NetworkVO result = networkService.create(network);
        logger.debug("New network has been created");

        WebSocketResponse response = new WebSocketResponse();
        response.addValue(NETWORK, result, NETWORK_SUBMITTED);
        webSocketClientHandler.sendMessage(request, response, session);
    }

    @PreAuthorize("isAuthenticated() and hasPermission(#id, 'MANAGE_NETWORK')")
    public void processNetworkUpdate(JsonObject request, WebSocketSession session) {
        NetworkUpdate networkToUpdate = gson.fromJson(request.get(NETWORK), NetworkUpdate.class);
        Long id = gson.fromJson(request.get(ID), Long.class);
        logger.debug("Network update requested. Id : {}", id);
        networkService.update(id, networkToUpdate);
        logger.debug("Network has been updated successfully. Id : {}", id);
        webSocketClientHandler.sendMessage(request, new WebSocketResponse(), session);
    }

    @PreAuthorize("isAuthenticated() and hasPermission(#id, 'MANAGE_NETWORK')")
    public void processNetworkDelete(JsonObject request, WebSocketSession session) {
        logger.debug("Network delete requested");
        Long id = gson.fromJson(request.get(ID), Long.class);
        boolean isDeleted = networkService.delete(id);
        if (!isDeleted) {
            logger.error(String.format(Messages.NETWORK_NOT_FOUND, id));
            throw new HiveException(String.format(Messages.NETWORK_NOT_FOUND, id), NOT_FOUND.getStatusCode());
        }
        logger.debug("Network with id = {} does not exists any more.", id);
        webSocketClientHandler.sendMessage(request, new WebSocketResponse(), session);
    }

}
