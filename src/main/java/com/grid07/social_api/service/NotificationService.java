package com.grid07.social_api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final Duration NOTIF_COOLDOWN = Duration.ofMinutes(15);
    private static final String NOTIF_COOLDOWN_PREFIX = "notif_cooldown:user_";
    private static final String PENDING_NOTIF_PREFIX = "user:";

    /**
     * Called when a bot interacts with a user's post.
     * Either sends immediately (and sets cooldown) or queues in Redis List.
     */
    public void handleBotNotification(Long userId, String botName, Long postId) {
        String cooldownKey = NOTIF_COOLDOWN_PREFIX + userId;
        String pendingKey = PENDING_NOTIF_PREFIX + userId + ":pending_notifs";
        String message = "Bot " + botName + " replied to your post " + postId;

        Boolean cooldownActive = redisTemplate.hasKey(cooldownKey);

        if (Boolean.TRUE.equals(cooldownActive)) {
            // User was recently notified — push to pending queue
            redisTemplate.opsForList().rightPush(pendingKey, message);
            log.info("Notification queued for user {}: {}", userId, message);
        } else {
            // No recent notification — send immediately and set cooldown
            log.info("Push Notification Sent to User {}: {}", userId, message);
            redisTemplate.opsForValue().set(cooldownKey, "1", NOTIF_COOLDOWN);
        }
    }

    /**
     * Called by the CRON sweeper every 5 minutes.
     * Pops all pending notifications for a user, summarizes, and clears the list.
     */
    public void flushPendingNotifications(Long userId) {
        String pendingKey = PENDING_NOTIF_PREFIX + userId + ":pending_notifs";

        // Get all messages from the Redis List
        List<String> pending = redisTemplate.opsForList().range(pendingKey, 0, -1);

        if (pending == null || pending.isEmpty()) return;

        // Delete the list from Redis
        redisTemplate.delete(pendingKey);

        // Log the summary
        String firstMessage = pending.get(0);
        int othersCount = pending.size() - 1;
        if (othersCount > 0) {
            log.info("Summarized Push Notification for User {}: {} and {} others interacted with your posts.",
                    userId, extractBotName(firstMessage), othersCount);
        } else {
            log.info("Summarized Push Notification for User {}: {}", userId, firstMessage);
        }
    }

    private String extractBotName(String message) {
        // message format: "Bot X replied to your post Y"
        // Extract the bot name between "Bot " and " replied"
        try {
            return message.split(" replied")[0]; // "Bot X"
        } catch (Exception e) {
            return message;
        }
    }
}