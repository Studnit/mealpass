package com.mealtoken.web;

import com.mealtoken.repository.UserRepository;
import com.mealtoken.service.RedeemCommand;
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
class VoiceIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired UserRepository users;
    @Autowired RedemptionService redemption;

    private MockHttpSession login(Long userId) throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("username", users.findById(userId).orElseThrow().getEmail())
                .param("password", Fixtures.PASSWORD)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void ownerHearsHotelAndCustomerNameOnlyForNewEvents() throws Exception {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        fx.activeSubscription(w, c, w.planA());
        MockHttpSession owner = login(w.owner().userId());

        // opening the page must not replay history: after=-1 just reports the newest id
        String first = mvc.perform(get("/notifications/poll?after=-1").session(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(first).contains("\"items\":[]");
        long latest = Long.parseLong(first.replaceAll(".*\"latest\":(\\d+).*", "$1"));

        redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "v1", "TEST"));

        String second = mvc.perform(get("/notifications/poll?after=" + latest).session(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String customerName = users.findById(c).orElseThrow().getFullName();
        assertThat(second).contains(w.hotel().getName()).contains(customerName).contains("received the meal request");
    }

    @Test
    void customerPollNeverReturnsOwnerAnnouncements() throws Exception {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        fx.activeSubscription(w, c, w.planA());
        redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "v2", "TEST"));
        String body = mvc.perform(get("/notifications/poll?after=0").session(login(c))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"items\":[]");
    }

    @Test
    void pollRequiresLogin() throws Exception {
        mvc.perform(get("/notifications/poll?after=0")).andExpect(status().is3xxRedirection());
    }
}
