package com.mealtoken.controller;

import com.mealtoken.notification.NotificationService;
import com.mealtoken.security.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

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
