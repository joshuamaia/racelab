package dev.racelab.infrastructure.remote;

import dev.racelab.application.port.RemoteWorkerGateway;
import dev.racelab.domain.model.TransactionOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Calls the peer backend instance (another JVM) to execute workers there. */
@Component
public class HttpRemoteWorkerGateway implements RemoteWorkerGateway {

    private final String peerUrl;
    private final String peerInstanceId;
    private final RestClient longCalls;
    private final RestClient healthCalls;
    private volatile long checkedAt;
    private volatile boolean available;

    public HttpRemoteWorkerGateway(@Value("${racelab.peer.url:}") String peerUrl,
                                   @Value("${racelab.peer.instance-id:secondary}") String peerInstanceId,
                                   @Value("${racelab.internal-token}") String token) {
        this.peerUrl = peerUrl;
        this.peerInstanceId = peerInstanceId;
        SimpleClientHttpRequestFactory longFactory = new SimpleClientHttpRequestFactory();
        longFactory.setConnectTimeout(2_000);
        longFactory.setReadTimeout(15 * 60_000);
        SimpleClientHttpRequestFactory shortFactory = new SimpleClientHttpRequestFactory();
        shortFactory.setConnectTimeout(800);
        shortFactory.setReadTimeout(1_500);
        this.longCalls = RestClient.builder().requestFactory(longFactory).defaultHeader(InternalApi.TOKEN_HEADER, token).build();
        this.healthCalls = RestClient.builder().requestFactory(shortFactory).build();
    }

    @Override
    public boolean isPeerAvailable() {
        if (peerUrl == null || peerUrl.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt > 2_000) {
            try {
                healthCalls.get().uri(peerUrl + "/actuator/health/readiness").retrieve().toBodilessEntity();
                available = true;
            } catch (RuntimeException e) {
                available = false;
            }
            checkedAt = now;
        }
        return available;
    }

    @Override
    public String peerInstanceId() {
        return peerInstanceId;
    }

    public String peerUrl() {
        return peerUrl;
    }

    @Override
    public List<TransactionOutcome> execute(RemoteWorkerRequest request) {
        return longCalls.post().uri(peerUrl + "/internal/workers/execute").body(request).retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }
}
