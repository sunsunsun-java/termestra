package dev.termestra.platform.cli.team;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class TeamRuntimeClient {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** Requests only wait for durable SQLite acceptance; PTY delivery runs in the background. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final HttpClient http; private final ObjectMapper json; private final TeamEnvironment env;
    private final Duration requestTimeout;
    TeamRuntimeClient(HttpClient http,ObjectMapper json,TeamEnvironment env){this(http,json,env,REQUEST_TIMEOUT);}
    TeamRuntimeClient(HttpClient http,ObjectMapper json,TeamEnvironment env,Duration requestTimeout){
        this.http=Objects.requireNonNull(http);this.json=Objects.requireNonNull(json);this.env=Objects.requireNonNull(env);
        this.requestTimeout=Objects.requireNonNull(requestTimeout);
        if(requestTimeout.isZero()||requestTimeout.isNegative())throw new IllegalArgumentException("requestTimeout must be positive");
    }

    static HttpClient defaultHttpClient(){return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();}

    String list() { return send(request(URI.create(env.baseUrl()+"/api/workspaces/"+env.workspaceId()+"/team"))
            .header("x-termestra-agent-id",env.agentId()).header("x-termestra-agent-token",env.token()).GET().build()); }
    String post(String path,ObjectNode body){return send(request(URI.create(env.baseUrl()+path)).header("content-type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());}
    ObjectNode body(){ObjectNode body=json.createObjectNode();body.put("project_id",env.workspaceId());body.put("from_agent_id",env.agentId());body.put("token",env.token());return body;}
    ArrayNode artifacts(java.util.List<String> values){ArrayNode array=json.createArrayNode();values.forEach(array::add);return array;}

    private HttpRequest.Builder request(URI uri){return HttpRequest.newBuilder(uri).timeout(requestTimeout);}

    private String send(HttpRequest request){
        CompletableFuture<HttpResponse<String>> pending=http.sendAsync(
                request,new BoundedUtf8BodyHandler(MAX_RESPONSE_BYTES));
        try{
            HttpResponse<String> response=pending.get(requestTimeout.toNanos(),TimeUnit.NANOSECONDS);
            String body=response.body();
            if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException(error(response.statusCode(),body));
            return body;
        }catch(TimeoutException error){pending.cancel(true);throw timeout(error);}
        catch(ExecutionException error){Throwable cause=error.getCause();if(causedBy(cause,HttpTimeoutException.class))throw timeout(cause);if(BoundedUtf8BodyHandler.limitExceeded(cause))throw new IllegalStateException("Termestra runtime response exceeds "+MAX_RESPONSE_BYTES+" bytes",cause);if(causedBy(cause,IOException.class))throw new IllegalStateException("Failed to reach Termestra runtime at "+env.baseUrl()+": "+cause.getMessage()+". Check TERMESTRA_PORT and make sure the Termestra runtime is still running.",cause);throw new IllegalStateException("Termestra runtime request failed",cause);}
        catch(InterruptedException error){pending.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while calling Termestra runtime",error);}
    }
    private IllegalStateException timeout(Throwable error){return new IllegalStateException("Timed out waiting for Termestra runtime after "+requestTimeout.toMillis()+" ms at "+env.baseUrl(),error);}
    private static boolean causedBy(Throwable failure,Class<? extends Throwable> type){Throwable current=failure;while(current!=null){if(type.isInstance(current))return true;current=current.getCause();}return false;}
    private String error(int status,String body){String detail=body.trim();try{JsonNode node=json.readTree(detail);if(node.path("error").isTextual())detail=node.path("error").asText();}catch(JsonProcessingException ignored){/* Preserve useful non-JSON response text. */}return detail.isBlank()?"Request failed with status "+status:"Request failed with status "+status+": "+detail;}
}
