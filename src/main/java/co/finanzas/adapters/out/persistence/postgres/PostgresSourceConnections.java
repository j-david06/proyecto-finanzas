package co.finanzas.adapters.out.persistence.postgres;

import co.finanzas.application.ingestion.model.SourceConnection;
import co.finanzas.application.ingestion.port.out.SourceConnectionRepository;
import co.finanzas.domain.shared.OwnerId;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PostgresSourceConnections implements SourceConnectionRepository {
    private final JdbcTemplate jdbc;
    public PostgresSourceConnections(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<SourceConnection> find(UUID id) {
        return jdbc.query("SELECT * FROM source_connections WHERE id = ?", (rs, row) -> map(rs), id)
                .stream().findFirst();
    }

    static SourceConnection map(ResultSet rs) throws SQLException {
        var array = rs.getArray("allowed_account_ids");
        Set<UUID> allowed;
        try {
            allowed = Arrays.stream((Object[]) array.getArray())
                    .map(value -> UUID.fromString(value.toString())).collect(Collectors.toUnmodifiableSet());
        } finally { array.free(); }
        var expiration = rs.getTimestamp("expires_at");
        return new SourceConnection(rs.getObject("id", UUID.class),
                new OwnerId(rs.getObject("owner_id", UUID.class)), rs.getString("source"),
                rs.getString("authentication_profile"), rs.getBytes("api_key_sha256"), allowed,
                rs.getBoolean("active"), expiration == null ? null : expiration.toInstant());
    }
}
