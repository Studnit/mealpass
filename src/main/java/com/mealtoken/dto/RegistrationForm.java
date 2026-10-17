package com.mealtoken.dto;

import jakarta.validation.constraints.*;

public class RegistrationForm {
    @NotBlank @Size(max = 150)
    private String fullName;

    @NotBlank @Email @Size(max = 190)
    private String email;

    @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "{validation.phone}")
    private String phone;

    @NotBlank @Size(min = 8, max = 72)
    private String password;

    /** CUSTOMER or OWNER only; SUPER_ADMIN/STAFF can never be self-registered. */
    @NotBlank @Pattern(regexp = "CUSTOMER|OWNER")
    private String accountType = "CUSTOMER";

    @Size(max = 150)
    private String hotelName;

    @Size(max = 80)
    private String city;

    @Size(max = 80)
    private String area;

    public String getFullName() { return fullName; }
    public void setFullName(String v) { this.fullName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String v) { this.accountType = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getArea() { return area; }
    public void setArea(String v) { this.area = v; }
    public String getHotelName() { return hotelName; }
    public void setHotelName(String v) { this.hotelName = v; }
}
