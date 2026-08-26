package io.labdeck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class LabDeckApplicationTests {

    @Test
    void applicationStarts(ApplicationContext context) {
        assertDoesNotThrow(() -> context.getBean(LabDeckApplication.class));
    }
}
