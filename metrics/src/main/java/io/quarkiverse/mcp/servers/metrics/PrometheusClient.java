package io.quarkiverse.mcp.servers.metrics;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST Client for the Prometheus HTTP API.
 */
@Path("/api/v1")
@RegisterRestClient(configKey = "prometheus")
public interface PrometheusClient {

    @GET
    @Path("/query")
    PrometheusResponse query(@QueryParam("query") String query, @QueryParam("time") String time);

    @GET
    @Path("/query_range")
    PrometheusResponse queryRange(@QueryParam("query") String query,
            @QueryParam("start") String start,
            @QueryParam("end") String end,
            @QueryParam("step") String step);
}
