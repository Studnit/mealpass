package com.mealtoken.web;

import com.mealtoken.entity.Subscription;
import com.mealtoken.repository.UserRepository;
import com.mealtoken.service.RedeemCommand;
import com.mealtoken.service.RedemptionResult;
import com.mealtoken.service.RedemptionService;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class WebSecurityIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired UserRepository users;
    @Autowired RedemptionService redemption;

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", password))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).doesNotContain("error");
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String email(Long userId) { return users.findById(userId).orElseThrow().getEmail(); }

    @Test
    void anonymousUsersAreSentToLogin_andPublicPagesStayOpen() throws Exception {
        for (String p : new String[]{"/owner/dashboard", "/staff/dashboard", "/customer/dashboard", "/admin/dashboard", "/customer/today"})
            mvc.perform(get(p)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/register")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void pwaFilesArePublic() throws Exception {
        mvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk());
        mvc.perform(get("/sw.js")).andExpect(status().isOk());
        mvc.perform(get("/offline.html")).andExpect(status().isOk());
        mvc.perform(get("/icons/icon-192.png")).andExpect(status().isOk());
        mvc.perform(get("/login")).andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("camera=(self)")));
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/login").param("username", "a@b.c").param("password", "x")).andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        World w = fx.newWorld();
        mvc.perform(post("/login").with(csrf()).param("username", email(w.owner().userId())).param("password", "nope"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void rolesCannotCrossIntoEachOthersAreas() throws Exception {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        MockHttpSession owner = login(email(w.owner().userId()), Fixtures.PASSWORD);
        MockHttpSession cust = login(email(c), Fixtures.PASSWORD);

        mvc.perform(get("/owner/dashboard").session(owner)).andExpect(status().isOk());
        mvc.perform(get("/admin/dashboard").session(owner)).andExpect(status().isForbidden());
        mvc.perform(get("/customer/today").session(owner)).andExpect(status().isForbidden());

        mvc.perform(get("/customer/today").session(cust)).andExpect(status().isOk());
        mvc.perform(get("/owner/dashboard").session(cust)).andExpect(status().isForbidden());
        mvc.perform(get("/staff/dashboard").session(cust)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").session(cust)).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotReadAnotherCustomersReceipt() throws Exception {
        World w = fx.newWorld();
        Long alice = fx.newCustomer(), bob = fx.newCustomer();
        fx.activeSubscription(w, alice, w.planA());
        RedemptionResult r = redemption.redeem(new RedeemCommand(alice, w.hotel().getId(), w.lunch().getId(), alice, "rc", "TEST"));

        MockHttpSession a = login(email(alice), Fixtures.PASSWORD);
        MockHttpSession b = login(email(bob), Fixtures.PASSWORD);
        mvc.perform(get("/customer/receipt/" + r.referenceNumber()).session(a)).andExpect(status().isOk());
        MvcResult denied = mvc.perform(get("/customer/receipt/" + r.referenceNumber()).session(b)).andReturn();
        assertThat(denied.getResponse().getStatus()).isIn(404, 302, 303);
        assertThat(denied.getResponse().getContentAsString()).doesNotContain(r.referenceNumber());
    }

    @Test
    void ownerOfAnotherHotelCannotSeeOrReverseThisHotelsAttendance() throws Exception {
        World w = fx.newWorld();
        World other = fx.newWorld();
        Long c = fx.newCustomer();
        fx.activeSubscription(w, c, w.planA());
        RedemptionResult r = redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "xo", "TEST"));

        MockHttpSession intruder = login(email(other.owner().userId()), Fixtures.PASSWORD);
        MvcResult page = mvc.perform(get("/owner/table/attendance").session(intruder)).andExpect(status().isOk()).andReturn();
        assertThat(page.getResponse().getContentAsString()).doesNotContain(r.referenceNumber());
    }

    @Test
    void invalidQrIdentifierShowsAnErrorAndNeverLeaksHotels() throws Exception {
        Long c = fx.newCustomer();
        MockHttpSession s = login(email(c), Fixtures.PASSWORD);
        MvcResult res = mvc.perform(get("/customer/scan/not-a-real-code").session(s)).andReturn();
        assertThat(res.getResponse().getStatus()).isNotEqualTo(500);
    }

    @Test
    void redeemPostRequiresCsrf() throws Exception {
        Long c = fx.newCustomer();
        MockHttpSession s = login(email(c), Fixtures.PASSWORD);
        mvc.perform(post("/customer/redeem").session(s).param("hotelId", "1").param("slotId", "1")).andExpect(status().isForbidden());
    }
}
