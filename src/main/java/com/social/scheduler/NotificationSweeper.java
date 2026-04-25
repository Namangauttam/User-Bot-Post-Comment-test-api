package com.social.scheduler;

import com.social.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class NotificationSweeper {

    private final NotificationService notificationService;


    @Scheduled(fixedRate = 300_000)
    public void sweepPendingNotifications() {
        log.info("=== Notification Sweeper running ===");


        Set<String> keys = notificationService.getUsersWithPendingNotifs();

        if (keys == null || keys.isEmpty()) {
            log.info("No pending notifications found.");
            return;
        }


        Pattern pattern = Pattern.compile("user:(\\d+):pending_notifs");

        for (String key : keys) {
            Matcher matcher = pattern.matcher(key);
            if (!matcher.find()) continue;

            Long userId = Long.parseLong(matcher.group(1));
            List<String> messages = notificationService.drainPendingNotifications(userId);

            if (messages.isEmpty()) continue;

            String firstSender = messages.get(0);
            int othersCount    = messages.size() - 1;

            if (othersCount == 0) {
                log.info("Summarized Push Notification for user {}: {}", userId, firstSender);
            } else {
                log.info("Summarized Push Notification for user {}: {} and {} others interacted with your posts.",
                        userId, firstSender, othersCount);
            }
        }
    }
}