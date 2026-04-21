package com.grid07.social_api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class GuardrailService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final int HORIZONTAL_CAP = 100;   // Max bot replies per post
    private static final int VERTICAL_CAP = 20;       // Max comment depth
    private static final Duration COOLDOWN_TTL = Duration.ofMinutes(10); // Bot-human cooldown

    /**
     * HORIZONTAL CAP
     * Atomically increments bot reply counter and checks if it exceeds 100.
     * Uses Redis INCR — thread-safe by design.
     */
    public boolean tryIncrementBotCount(Long postId) {
        String key = "post:" + postId + ":bot_count";
        Long count = redisTemplate.opsForValue().increment(key);
        if (count > HORIZONTAL_CAP) {
            // We went over — decrement back and reject
            redisTemplate.opsForValue().decrement(key);
            log.warn("HORIZONTAL CAP hit for post {}. Bot reply rejected.", postId);
            return false;
        }
        return true;
    }

    /**
     * VERTICAL CAP
     * Simple check — no Redis needed, depth is passed in the request.
     */
    public boolean checkVerticalCap(int depthLevel) {
        if (depthLevel > VERTICAL_CAP) {
            log.warn("VERTICAL CAP hit. Depth {} exceeds max {}.", depthLevel, VERTICAL_CAP);
            return false;
        }
        return true;
    }

    /**
     * COOLDOWN CAP
     * Checks if bot already interacted with this human in the last 10 minutes.
     * Uses Redis SET NX (Set if Not eXists) — atomic operation.
     */
    public boolean checkAndSetCooldown(Long botId, Long humanId) {
        String key = "cooldown:bot_" + botId + ":human_" + humanId;
        // setIfAbsent = Redis SET NX EX — sets key only if it doesn't exist, with TTL
        Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(key, "1", COOLDOWN_TTL);
        if (Boolean.FALSE.equals(wasSet)) {
            log.warn("COOLDOWN CAP: Bot {} already interacted with Human {} recently.", botId, humanId);
            return false; // Key already existed — cooldown active
        }
        return true; // Key was newly set — interaction allowed
    }

    public Long getBotCount(Long postId) {
        String key = "post:" + postId + ":bot_count";
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0L;
    }
}