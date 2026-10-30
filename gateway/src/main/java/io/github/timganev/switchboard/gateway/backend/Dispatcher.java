package io.github.timganev.switchboard.gateway.backend;

import io.github.timganev.switchboard.commons.Locality;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.gateway.board.InflightBoard;
import io.github.timganev.switchboard.gateway.board.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Sends a request to the least busy allowed instance and tries the next one on 429, 5xx or a connection failure.
 * It never leaves the allowed set, so a request meant for local models fails rather than reaching the cloud.
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final List<Backend> backends;
    private final ModelClient modelClient;
    private final InflightBoard board;

    public Dispatcher(List<Backend> backends, ModelClient modelClient, InflightBoard board) {
        this.backends = backends;
        this.modelClient = modelClient;
        this.board = board;
    }

    public Dispatched<ChatResponse> send(Set<Locality> allowed, ChatRequest request) {
        return dispatch(allowed, (backend, lease) -> {
            try (lease) {
                return modelClient.send(backend, request);
            }
        });
    }

    public Dispatched<ModelStream> openStream(Set<Locality> allowed, ChatRequest request) {
        return dispatch(allowed, (backend, lease) -> {
            try {
                return new LeasedModelStream(modelClient.openStream(backend, request), lease);
            } catch (RuntimeException exception) {
                lease.close();
                throw exception;
            }
        });
    }

    /**
     * Tells whether any configured instance is in the allowed set.
     */
    public boolean serves(Set<Locality> allowed) {
        return backends.stream().anyMatch(backend -> allowed.contains(backend.locality()));
    }

    private <T> Dispatched<T> dispatch(Set<Locality> allowed, BiFunction<Backend, Lease, T> call) {
        Map<String, Backend> candidates = new LinkedHashMap<>();
        backends.stream().filter(backend -> allowed.contains(backend.locality()))
                .forEach(backend -> candidates.put(backend.name(), backend));
        if (candidates.isEmpty()) {
            throw new NoBackendAvailableException(allowed);
        }
        RestClientException lastFailure = null;
        while (!candidates.isEmpty()) {
            Lease lease = board.acquire(List.copyOf(candidates.keySet()));
            Backend backend = candidates.remove(lease.instance());
            try {
                return new Dispatched<>(backend, call.apply(backend, lease));
            } catch (RestClientException exception) {
                if (!isRetryable(exception)) {
                    throw exception;
                }
                log.warn("Instance {} failed, trying the next one: {}", backend.name(), exception.getMostSpecificCause().toString());
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private static boolean isRetryable(RestClientException exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        return exception instanceof RestClientResponseException response
                && (response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError());
    }

    private record LeasedModelStream(ModelStream stream, Lease lease) implements ModelStream {

        @Override
        public void forEach(EventConsumer consumer) throws IOException {
            stream.forEach(consumer);
        }

        @Override
        public void close() {
            try {
                stream.close();
            } finally {
                lease.close();
            }
        }
    }
}
