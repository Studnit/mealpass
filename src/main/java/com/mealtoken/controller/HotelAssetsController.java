package com.mealtoken.controller;

import com.mealtoken.service.HotelService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;

@Controller
public class HotelAssetsController {
    private final HotelService hotels;

    public HotelAssetsController(HotelService hotels) { this.hotels = hotels; }

    /** Public: hotel logos are shown on the discovery pages. Only validated PNG/JPEG content is ever stored. */
    @GetMapping("/hotel-logo/{id}")
    @ResponseBody
    public ResponseEntity<byte[]> logo(@PathVariable Long id) {
        HotelService.LogoFile f = hotels.logo(id);
        if (f == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(f.bytes());
    }
}
