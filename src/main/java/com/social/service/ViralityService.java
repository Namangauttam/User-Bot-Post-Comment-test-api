package com.social.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;


@Slf4j
@Service
@RequiredArgsConstructor
public class ViralityService {


    private final RedisTemplate<String, String> redisTemplate;


    private static final String VIRALITY_KEY   = "post:%d:virality_score";
    private static final String BOT_COUNT_KEY  = "post:%d:bot_count";
    private static final String COOLDOWN_KEY   = "cooldown:bot_%d:human_%d";

    private static final int MAX_BOT_REPLIES   = 100;
    private static final int MAX_DEPTH         = 20;


    public void addBotReply(Long postId) {
        incrementVirality(postId, 1);
    }

    public void addHumanLike(Long postId) {
        incrementVirality(postId, 20);
    }

    public void addHumanComment(Long postId) {
        incrementVirality(postId, 50);
    }

    private void incrementVirality(Long postId, long points) {
        String key = String.format(VIRALITY_KEY, postId);

        redisTemplate.opsForValue().increment(key, points);
        log.info("Virality for post {} is now: {}", postId, getViralityScore(postId));
    }

    public Long getViralityScore(Long postId) {
        String val = redisTemplate.opsForValue().get(String.format(VIRALITY_KEY, postId));
        return val == null ? 0L : Long.parseLong(val);
    }



    public boolean tryIncrementBotCount(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);


        Long newCount = redisTemplate.opsForValue().increment(key);

        if (newCount > MAX_BOT_REPLIES) {

            redisTemplate.opsForValue().decrement(key);
            log.warn("Horizontal cap hit for post {}. Bot reply rejected.", postId);
            return false;
        }

        return true;
    }



    public boolean isDepthAllowed(int depthLevel) {
        if (depthLevel > MAX_DEPTH) {
            log.warn("Vertical cap hit. Depth {} exceeds max {}.", depthLevel, MAX_DEPTH);
            return false;
        }
        return true;
    }


    public void decrementBotCount(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        redisTemplate.opsForValue().decrement(key);
    }
    public boolean tryAcquireCooldown(Long botId, Long humanId) {
        String key = String.format(COOLDOWN_KEY, botId, humanId);


        Boolean wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofMinutes(10));

        if (Boolean.FALSE.equals(wasSet)) {
            log.warn("Cooldown active: bot {} cannot interact with human {} yet.", botId, humanId);
            return false;
        }

        return true;
    }
}