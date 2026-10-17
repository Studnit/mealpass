package com.mealtoken.controller;

import com.mealtoken.notification.NotificationService;
import com.mealtoken.security.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import com.mealtoken.notification.VoiceService;
import com.mealtoken.repository.NotificationRepository;
import java.util.*;

@Controller
public class NotificationController {
    private final NotificationService notifications;
    private final NotificationRepository repo;
    private final VoiceService voice;

    public NotificationController(NotificationService notifications, NotificationRepository repo, VoiceService voice) {
        this.notifications = notifications; this.repo = repo; this.voice = voice;
    }

    /**
     * Polled by the owner/staff screens to read new events aloud. {@code after < 0} only returns the current newest id
     * (so old notifications are never spoken when a page opens). Only the signed-in user's own notifications are returned.
     */
    @GetMapping("/notifications/poll")
    @ResponseBody
    public Map<String, Object> poll(@RequestParam(defaultValue = "-1") long after) {
        Long uid = CurrentUser.require().getId();
        long latest = repo.findFirstByUserIdOrderByIdDesc(uid).map(n -> n.getId()).orElse(0L);
        List<Map<String, String>> items = new ArrayList<>();
        if (after >= 0) {
            for (var n : repo.findTop20ByUserIdAndIdGreaterThanOrderByIdAsc(uid, after)) {
                String[] p = n.getMessage().split("\\|", -1);
                String text = null;
                if (p[0].equals("notif.owner.meal") && p.length >= 5) text = voice.say("voice.owner.meal", p[4], p[1]);
                else if (p[0].equals("notif.req.new") && p.length >= 4) text = voice.say("voice.owner.request", p[3], p[1]);
                if (text != null) items.add(Map.of("text", text, "lang", voice.langTag()));
            }
        }
        return Map.of("latest", latest, "items", items);
    }

    @GetMapping("/notifications")
    public String list(@RequestParam(defaultValue = "0") int page, Model m) {
        Long uid = CurrentUser.require().getId();
        m.addAttribute("page", notifications.page(uid, page));
        m.addAttribute("section", "notifications");
        return "shared/notifications";
    }

    @PostMapping("/notifications/read")
    public String markRead() {
        notifications.markAllRead(CurrentUser.require().getId());
        return "redirect:/notifications";
    }
}
