package io.labdeck.lab;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository {

    void append(TestRunRecord testRun);

    Optional<TestRunRecord> findById(String id);

    List<TestRunRecord> findRecentByLab(String labId, int limit);
}
