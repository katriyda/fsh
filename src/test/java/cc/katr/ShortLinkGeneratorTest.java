package cc.katr;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortLinkGeneratorTest {

    @Test
    void testConcurrentCollision() throws InterruptedException {
        int threadCount = 10000;
        Set<String> generatedLinks = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 使用 JDK 21+ 虚拟线程池测试高并发下的性能与唯一性
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        String link = ShortLinkGenerator.generate();
                        generatedLinks.add(link);
                        // 测试合法性 Base62 (a-z, A-Z, 0-9)
                        assertTrue(link.matches("^[a-zA-Z0-9]{8}$"));
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await();

        // 当集合大小等于线程生成总任务数，说明 100% 毫无碰撞
        assertEquals(threadCount, generatedLinks.size(), "Short links should have 0 collision in 10,000 parallel requests");
    }
}
