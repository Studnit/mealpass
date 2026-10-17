package com.mealtoken.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "hotels")
public class Hotel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 160)
    private String slug;

    private String address;

    @Column(length = 20)
    private String phone;

    @Column(length = 80)
    private String city;

    @Column(length = 80)
    private String area;

    @Column(name = "logo_path")
    private String logoPath;

    @Column(nullable = false, length = 50)
    private String timezone = "Asia/Kolkata";

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "operating_hours", length = 100)
    private String operatingHours;

    @Column(name = "enforce_meal_window", nullable = false)
    private boolean enforceMealWindow = false;

    @Column(name = "low_token_threshold", nullable = false)
    private int lowTokenThreshold = 5;

    @Column(name = "token_hold_count", nullable = false)
    private int tokenHoldCount = 5;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getArea() { return area; }
    public void setArea(String v) { this.area = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String v) { this.logoPath = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String v) { this.timezone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getOperatingHours() { return operatingHours; }
    public void setOperatingHours(String v) { this.operatingHours = v; }
    public boolean isEnforceMealWindow() { return enforceMealWindow; }
    public void setEnforceMealWindow(boolean v) { this.enforceMealWindow = v; }
    public int getLowTokenThreshold() { return lowTokenThreshold; }
    public void setLowTokenThreshold(int v) { this.lowTokenThreshold = v; }
    public int getTokenHoldCount() { return tokenHoldCount; }
    public void setTokenHoldCount(int v) { this.tokenHoldCount = v; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long v) { this.ownerId = v; }
    public Instant getCreatedAt() { return createdAt; }
}
