package com.grid07.social_api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ViralityService {

    private final RedisTemplate<String, String> redisTemplate;

    // Points assigned per interaction type
    private static final int BOT_REPLY_POINTS = 1;
    private static final int HUMAN_LIKE_POINTS = 20;
    private static final int HUMAN_COMMENT_POINTS = 50;

    public void onBotReply(Long postId) {
        String key = "post:" + postId + ":virality_score";
        Long newScore = redisTemplate.opsForValue().increment(key, BOT_REPLY_POINTS);
        log.info("Bot replied on post {}. Virality score now: {}", postId, newScore);
    }

    public void onHumanLike(Long postId) {
        String key = "post:" + postId + ":virality_score";
        Long newScore = redisTemplate.opsForValue().increment(key, HUMAN_LIKE_POINTS);
        log.info("Human liked post {}. Virality score now: {}", postId, newScore);
    }

    public void onHumanComment(Long postId) {
        String key = "post:" + postId + ":virality_score";
        Long newScore = redisTemplate.opsForValue().increment(key, HUMAN_COMMENT_POINTS);
        log.info("Human commented on post {}. Virality score now: {}", postId, newScore);
    }

    public Long getViralityScore(Long postId) {
        String key = "post:" + postId + ":virality_score";
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : 0L;
    }
}