package com.handoff.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Spring JDBC repository for auth tables (organizations, users, memberships, refresh_tokens).
 */
@Repository
public class AuthRepository {

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRecord(
            UUID id,
            String email,
            String passwordHash,
            String displayName,
            Instant createdAt
    ) {}

    public record OrganizationRecord(
            UUID id,
            String name,
            Instant createdAt
    ) {}

    public record MembershipRecord(
            UUID userId,
            UUID organizationId,
            Role role,
            Instant createdAt
    ) {}

    public record RefreshTokenRecord(
            UUID id,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            boolean revoked,
            Instant createdAt
    ) {}

    public Optional<UserRecord> findUserByEmail(String email) {
        try {
            UserRecord user = jdbc.queryForObject(
                    "SELECT id, email, password_hash, display_name, created_at FROM users WHERE lower(email) = lower(?)",
                    new UserRowMapper(),
                    email
            );
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<UserRecord> findUserById(UUID id) {
        try {
            UserRecord user = jdbc.queryForObject(
                    "SELECT id, email, password_hash, display_name, created_at FROM users WHERE id = ?",
                    new UserRowMapper(),
                    id
            );
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void createUser(UUID id, String email, String passwordHash, String displayName) {
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, ?, ?)",
                id, email, passwordHash, displayName
        );
    }

    public void createOrganization(UUID id, String name) {
        jdbc.update(
                "INSERT INTO organizations (id, name) VALUES (?, ?)",
                id, name
        );
    }

    public Optional<OrganizationRecord> findOrganizationByName(String name) {
        try {
            OrganizationRecord org = jdbc.queryForObject(
                    "SELECT id, name, created_at FROM organizations WHERE name = ?",
                    (rs, rowNum) -> new OrganizationRecord(
                            (UUID) rs.getObject("id"),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    name
            );
            return Optional.ofNullable(org);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void createMembership(UUID userId, UUID organizationId, Role role) {
        jdbc.update(
                "INSERT INTO memberships (user_id, organization_id, role) VALUES (?, ?, ?)",
                userId, organizationId, role.name()
        );
    }

    public Optional<MembershipRecord> findMembershipByUserId(UUID userId) {
        try {
            MembershipRecord membership = jdbc.queryForObject(
                    "SELECT user_id, organization_id, role, created_at FROM memberships WHERE user_id = ?",
                    (rs, rowNum) -> new MembershipRecord(
                            (UUID) rs.getObject("user_id"),
                            (UUID) rs.getObject("organization_id"),
                            Role.valueOf(rs.getString("role")),
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    userId
            );
            return Optional.ofNullable(membership);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void insertRefreshToken(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                id, userId, tokenHash, Timestamp.from(expiresAt)
        );
    }

    public Optional<RefreshTokenRecord> findRefreshToken(String tokenHash) {
        try {
            RefreshTokenRecord token = jdbc.queryForObject(
                    "SELECT id, user_id, token_hash, expires_at, revoked, created_at FROM refresh_tokens WHERE token_hash = ?",
                    (rs, rowNum) -> new RefreshTokenRecord(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("user_id"),
                            rs.getString("token_hash"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getBoolean("revoked"),
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    tokenHash
            );
            return Optional.ofNullable(token);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Atomically rotates a refresh token using a conditional update:
     * WHERE token_hash = ? AND revoked = false AND expires_at > now()
     *
     * Returns 1 if rotated, 0 if already revoked, expired, or non-existent.
     */
    public int rotateRefreshToken(String tokenHash) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked = true WHERE token_hash = ? AND revoked = false AND expires_at > now()",
                tokenHash
        );
    }

    public void revokeRefreshToken(String tokenHash) {
        jdbc.update("UPDATE refresh_tokens SET revoked = true WHERE token_hash = ?", tokenHash);
    }

    public void revokeAllUserRefreshTokens(UUID userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked = true WHERE user_id = ?", userId);
    }

    private static class UserRowMapper implements RowMapper<UserRecord> {
        @Override
        public UserRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UserRecord(
                    (UUID) rs.getObject("id"),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getString("display_name"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }
    }
}
