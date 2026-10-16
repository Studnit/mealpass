package com.mealtoken.controller;

import com.mealtoken.exception.BusinessException;
import com.mealtoken.service.PasswordResetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PasswordController {
    private final PasswordResetService reset;

    public PasswordController(PasswordResetService reset) { this.reset = reset; }

    @GetMapping("/forgot")
    public String forgot() { return "forgot"; }

    @PostMapping("/forgot")
    public String forgotSubmit(@RequestParam String email, RedirectAttributes ra) {
        reset.request(email);
        ra.addFlashAttribute("resetSent", true); // identical response whether or not the account exists
        return "redirect:/login";
    }

    @GetMapping("/reset/{token}")
    public String resetForm(@PathVariable String token, Model m) {
        if (!reset.isValid(token)) { m.addAttribute("err", "error.reset.invalid"); }
        m.addAttribute("token", token);
        return "reset";
    }

    @PostMapping("/reset/{token}")
    public String resetSubmit(@PathVariable String token, @RequestParam String password, RedirectAttributes ra) {
        try {
            reset.reset(token, password);
        } catch (BusinessException e) {
            ra.addFlashAttribute("err", e.getMessage());
            return "redirect:/reset/" + token;
        }
        ra.addFlashAttribute("resetDone", true);
        return "redirect:/login";
    }
}
