package com.mealtoken.controller;

import com.mealtoken.service.QrService;
import com.mealtoken.util.PublicUrl;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;

/**
 * One generic "get the app" QR for every hotel. It opens /app (install + sign up + find a hotel). The optional city and
 * area only pre-select the hotel finder; the QR holds no personal data.
 */
@Controller
public class AppController {
    public static final String SESSION_CITY = "finder.city";
    public static final String SESSION_AREA = "finder.area";

    private final QrService qr;
    private final PublicUrl publicUrl;

    public AppController(QrService qr, PublicUrl publicUrl) { this.qr = qr; this.publicUrl = publicUrl; }

    @GetMapping("/app")
    public String landing(@RequestParam(required = false) String city, @RequestParam(required = false) String area,
                          HttpSession session, Model m) {
        city = clean(city); area = clean(area);
        if (city != null) { session.setAttribute(SESSION_CITY, city); session.setAttribute(SESSION_AREA, area == null ? "" : area); }
        m.addAttribute("city", city == null ? "" : city);
        m.addAttribute("area", area == null ? "" : area);
        m.addAttribute("appUrl", target(city, area));
        return "app";
    }

    @GetMapping(value = "/app/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@RequestParam(required = false) String city, @RequestParam(required = false) String area,
                                        @RequestParam(defaultValue = "false") boolean download) {
        var b = ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic());
        if (download) b.header("Content-Disposition", "attachment; filename=\"meal-app-qr.png\"");
        return b.body(qr.png(target(clean(city), clean(area)), 640));
    }

    @GetMapping("/app/poster")
    public String poster(@RequestParam(required = false) String city, @RequestParam(required = false) String area, Model m) {
        m.addAttribute("city", clean(city) == null ? "" : clean(city));
        m.addAttribute("area", clean(area) == null ? "" : clean(area));
        m.addAttribute("appUrl", target(clean(city), clean(area)));
        return "app-poster";
    }

    private String target(String city, String area) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(publicUrl.url("/app"));
        if (city != null) b.queryParam("city", city);
        if (city != null && area != null) b.queryParam("area", area);
        return b.encode().build().toUriString();
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\p{Cntrl}", "");
        return t.isEmpty() ? null : (t.length() > 80 ? t.substring(0, 80) : t);
    }
}
