package com.handoff.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Pings PostgreSQL and Redis, returns a map like:
     * { "status": "UP", "database": "UP", "redis": "UP" }
     * If any component is down, "status" becomes "DOWN".
     */
    public Map<String, String> check() {
        Map<String, String> health = new LinkedHashMap<>();
        health.put("status", "UP");

        // Check PostgreSQL
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            health.put("database", "UP");
        } catch (DataAccessException e) {
            health.put("database", "DOWN");
            health.put("status", "DOWN");
        }

        // Check Redis
        try {
            // RedisCallback gives us a raw RedisConnection.
            // serverCommands().ping() sends the PING command and expects "PONG".
            redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            health.put("redis", "UP");
        } catch (Exception e) {
            health.put("redis", "DOWN");
            health.put("status", "DOWN");
        }

        return health;
    }
}
