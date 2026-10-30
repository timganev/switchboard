package io.github.timganev.switchboard.guardian;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "switchboard.guardian.secret=a-test-secret-that-is-long-enough!")
class GuardianApplicationTest {

    @Test
    void shouldStartApplicationContext() {
    }
}
