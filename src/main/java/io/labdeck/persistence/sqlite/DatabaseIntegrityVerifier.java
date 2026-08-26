package io.labdeck.persistence.sqlite;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
final class DatabaseIntegrityVerifier implements InitializingBean {

    private final JdbcTemplate jdbc;

    DatabaseIntegrityVerifier(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        List<String> integrityResults = jdbc.queryForList("PRAGMA quick_check", String.class);
        if (!integrityResults.equals(List.of("ok"))) {
            throw new IllegalStateException("The LabDeck database integrity check failed.");
        }
        if (!jdbc.queryForList("PRAGMA foreign_key_check").isEmpty()) {
            throw new IllegalStateException("The LabDeck database contains invalid references.");
        }
    }
}
