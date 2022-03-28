package dev.hilla.push;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import dev.hilla.ConditionalOnFeatureFlag;
import dev.hilla.EndpointInvocationException.EndpointAccessDeniedException;
import dev.hilla.EndpointInvocationException.EndpointBadRequestException;
import dev.hilla.EndpointInvocationException.EndpointInternalException;
import dev.hilla.EndpointInvocationException.EndpointNotFoundException;
import dev.hilla.EndpointInvoker;
import dev.hilla.push.messages.fromclient.AbstractServerMessage;
import dev.hilla.push.messages.fromclient.SubscribeMessage;
import dev.hilla.push.messages.fromclient.UnsubscribeMessage;
import dev.hilla.push.messages.toclient.AbstractClientMessage;
import dev.hilla.push.messages.toclient.ClientMessageComplete;
import dev.hilla.push.messages.toclient.ClientMessageError;
import dev.hilla.push.messages.toclient.ClientMessageUpdate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Handles incoming requests from the client and connects them to fluxes
 * returned from endpoints.
 */
@Service
@ConditionalOnFeatureFlag(PushMessageHandler.PUSH_FEATURE_FLAG)
public class PushMessageHandler {

    static final String PUSH_FEATURE_FLAG = "hillaPush";
    private final EndpointInvoker endpointInvoker;
    private Map<String, Disposable> closeHandlers = new ConcurrentHashMap<>();

    /**
     * Creates the instance.
     *
     * @param endpointInvoker
     *            the endpoint invoker
     */
    public PushMessageHandler(EndpointInvoker endpointInvoker) {
        this.endpointInvoker = endpointInvoker;
    }

    /**
     * Handles the message.
     *
     * @param message
     *            the message from the client
     * @param sender
     *            a method that sends a message back to the client
     */
    public void handleMessage(AbstractServerMessage message,
            Consumer<AbstractClientMessage> sender) {
        if (message instanceof SubscribeMessage) {
            handleSubscribe((SubscribeMessage) message, sender);
        } else if (message instanceof UnsubscribeMessage) {
            handleClose((UnsubscribeMessage) message);
        } else {
            throw new IllegalArgumentException(
                    "Unknown message type: " + message.getClass().getName());
        }
    }

    private void handleSubscribe(SubscribeMessage message,
            Consumer<AbstractClientMessage> sender) {
        if (endpointInvoker.getReturnType(message.getEndpointName(),
                message.getMethodName()) != Flux.class) {
            sender.accept(new ClientMessageError(message.getId(),
                    "Method " + message.getEndpointName() + "/"
                            + message.getMethodName()
                            + " is not a Flux method"));
            return;
        }

        ArrayNode paramsArray = message.getParams();
        ObjectNode paramsObject = paramsArray.objectNode();
        for (int i = 0; i < paramsArray.size(); i++) {
            paramsObject.set(i + "", paramsArray.get(i));
        }

        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        Principal principal = authentication;
        Function<String, Boolean> isInRole = role -> {
            return authentication.getAuthorities().stream()
                    .anyMatch(grantedAuthority -> grantedAuthority
                            .getAuthority().equals("ROLE_" + role));
        };
        try {
            Flux<?> result = (Flux<?>) endpointInvoker.invoke(
                    message.getEndpointName(), message.getMethodName(),
                    paramsObject, principal, isInRole);
            Disposable closeHandler = result.subscribe(item -> {
                send(sender, new ClientMessageUpdate(message.getId(), item));
            }, error -> {
                // An exception was thrown from the Flux
                closeHandlers.remove(message.getId());
                send(sender, new ClientMessageError(message.getId(),
                        "Exception in Flux"));
                getLogger().error("Exception in Flux", error);
            }, () -> {
                // Flux completed
                closeHandlers.remove(message.getId());
                send(sender, new ClientMessageComplete(message.getId()));
            });
            closeHandlers.put(message.getId(), closeHandler);
        } catch (EndpointNotFoundException e) {
            sender.accept(new ClientMessageError(message.getId(),
                    "No such endpoint"));
            return;
        } catch (EndpointAccessDeniedException | EndpointBadRequestException
                | EndpointInternalException e) {
            sender.accept(
                    new ClientMessageError(message.getId(), e.getMessage()));
            return;
        }

    }

    private void send(Consumer<AbstractClientMessage> sender,
            AbstractClientMessage message) {
        sender.accept(message);

    }

    private void handleClose(UnsubscribeMessage message) {
        Disposable closeHandler = closeHandlers.remove(message.getId());
        if (closeHandler == null) {
            getLogger().warn("Trying to close an unknown flux with id "
                    + message.getId());
            return;
        }
        closeHandler.dispose();
    }

    private Logger getLogger() {
        return LoggerFactory.getLogger(getClass());
    }

}