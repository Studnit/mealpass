package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "application_settings") @Getter @Setter
public class ApplicationSetting {
    @Id @Column(name = "setting_key", length = 80) private String key;
    @Column(name = "setting_value", nullable = false, length = 500) private String value;
}
