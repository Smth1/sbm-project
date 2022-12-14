package com.soa.labs.gatewayservice.repository.impl;

import com.soa.labs.gatewayservice.model.ApiLimiter;
import com.soa.labs.gatewayservice.repository.ApiLimiterCustomRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class ApiLimiterRepositoryImpl implements ApiLimiterCustomRepository {

    private static final String API_LIMITER_MATCH_REGEX_QUERY =
            "SELECT * " +
                    "FROM api_limiter apiLimiter " +
                    "WHERE apiLimiter.active = true AND :path SIMILAR TO apiLimiter.path " +
                    "AND apiLimiter.method = :method";
    private final DatabaseClient databaseClient;

    public ApiLimiterRepositoryImpl(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<ApiLimiter> findMatchesApiLimiter(String path, String method) {
        return databaseClient.sql(API_LIMITER_MATCH_REGEX_QUERY)
                .bind("path", path)
                .bind("method", method)
                .map(row -> ApiLimiter.builder()
                        .id(row.get("id", Long.class))
                        .path(row.get("path", String.class))
                        .method(row.get("method", String.class))
                        .threshold(row.get("threshold", Integer.class))
                        .ttl(row.get("ttl", Integer.class))
                        .active(row.get("active", Boolean.class))
                        .build())
                .first();
    }
}
