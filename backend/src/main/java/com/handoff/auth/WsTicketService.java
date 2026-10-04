package com.handoff.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.handoff.common.ApiException;
import com.handoff.common.UnauthenticatedException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Service managing single-use WebSocket tickets stored in Redis.
 * Key: hg:ticket:{ticket}
 * Consumed atomically via GETDEL.
 */
@Service
public class WsTicketService {

    private static final String TICKET_KEY_PREFIX = "hg:ticket:";

    private final StringRedisTemplate redis;
    private final JwtService jwtService;
    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public record WsTicketPayload(
            UUID userId,
            UUID organizationId,
            Role role,
            String name
    ) {}

    public WsTicketService(
            StringRedisTemplate redis,
            JwtService jwtService,
            RateLimiterService rateLimiterService,
            ObjectMapper objectMapper
    ) {
        this.redis = redis;
        this.jwtService = jwtService;
        this.rateLimiterService = rateLimiterService;
        this.objectMapper = objectMapper;
    }

    public WsTicketResponse createTicket(UserPrincipal principal) {
        rateLimiterService.checkWsTicketRateLimit(principal.userId());

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        WsTicketPayload payload = new WsTicketPayload(
                principal.userId(),
                principal.organizationId(),
                principal.role(),
                principal.displayName()
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ApiException("INTERNAL", "Failed to serialize ticket payload", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        long ttlSeconds = jwtService.getWsTicketExpirationSeconds();
        String redisKey = TICKET_KEY_PREFIX + ticket;
        redis.opsForValue().set(redisKey, json, Duration.ofSeconds(ttlSeconds));

        return new WsTicketResponse(ticket, ttlSeconds);
    }

    /**
     * Atomically consumes a ticket via Redis GETDEL.
     * If ticket was already consumed, expired, or non-existent, throws UnauthenticatedException with code TICKET_INVALID.
     */
    public WsTicketPayload consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new UnauthenticatedException("TICKET_INVALID", "WebSocket ticket is missing or empty");
        }

        String redisKey = TICKET_KEY_PREFIX + ticket;
        String json = redis.opsForValue().getAndDelete(redisKey);

        if (json == null || json.isBlank()) {
            throw new UnauthenticatedException("TICKET_INVALID", "WebSocket ticket is invalid, expired, or already used");
        }

        try {
            return objectMapper.readValue(json, WsTicketPayload.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("INTERNAL", "Failed to deserialize ticket payload", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
