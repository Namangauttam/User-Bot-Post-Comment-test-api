package com.social.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String PENDING_NOTIFS_KEY  = "user:%d:pending_notifs";
    private static final String NOTIF_COOLDOWN_KEY  = "user:%d:notif_cooldown";


    public void handleBotInteraction(Long userId, String botName, String postInfo) {
        String cooldownKey = String.format(NOTIF_COOLDOWN_KEY, userId);
        String notifsKey   = String.format(PENDING_NOTIFS_KEY, userId);
        String message     = String.format("%s replied to your %s", botName, postInfo);


        Boolean onCooldown = redisTemplate.hasKey(cooldownKey);

        if (Boolean.TRUE.equals(onCooldown)) {
            redisTemplate.opsForList().rightPush(notifsKey, message);
            log.info("Notification queued for user {}: {}", userId, message);
        } else {
            log.info("Push Notification Sent to User {}: {}", userId, message);
            redisTemplate.opsForValue()
                    .set(cooldownKey, "1", Duration.ofMinutes(15));
        }
    }


    public List<String> drainPendingNotifications(Long userId) {
        String key = String.format(PENDING_NOTIFS_KEY, userId);


        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);
        redisTemplate.delete(key);
        return messages == null ? List.of() : messages;
    }


    public java.util.Set<String> getUsersWithPendingNotifs() {

        return redisTemplate.keys("user:*:pending_notifs");
    }
}