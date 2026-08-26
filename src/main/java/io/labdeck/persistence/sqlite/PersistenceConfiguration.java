package io.labdeck.persistence.sqlite;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    LockedSQLiteDataSource labDeckDataSource(@Value("${labdeck.data-directory}") String dataDirectory) {
        return new SQLiteDataSourceFactory().create(Path.of(dataDirectory));
    }
}
