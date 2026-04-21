package com.grid07.social_api.Schedular;

import com.grid07.social_api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSchedular {

    private final RedisTemplate<String, String> redisTemplate;
    private final NotificationService notificationService;

    /**
     * Runs every 5 minutes.
     * Scans Redis for all users with pending notifications and flushes them.
     */
    @Scheduled(fixedRate = 300000) // 300,000 ms = 5 minutes
    public void sweepPendingNotifications() {
        log.info("CRON: Starting notification sweep...");

        // Scan for all keys matching "user:*:pending_notifs"
        Set<String> keys = redisTemplate.keys("user:*:pending_notifs");

        if (keys == null || keys.isEmpty()) {
            log.info("CRON: No pending notifications found.");
            return;
        }

        for (String key : keys) {
            // Extract userId from key format "user:{id}:pending_notifs"
            String[] parts = key.split(":");
            if (parts.length >= 2) {
                try {
                    Long userId = Long.parseLong(parts[1]);
                    notificationService.flushPendingNotifications(userId);
                } catch (NumberFormatException e) {
                    log.error("CRON: Could not parse userId from key: {}", key);
                }
            }
        }

        log.info("CRON: Notification sweep complete.");
    }
}