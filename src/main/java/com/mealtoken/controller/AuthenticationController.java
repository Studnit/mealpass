package com.mealtoken.controller;

import com.mealtoken.dto.RegistrationForm;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.context.i18n.LocaleContextHolder;

@Controller
public class AuthenticationController {
    private final RegistrationService registration;

    public AuthenticationController(RegistrationService registration) { this.registration = registration; }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("form", new RegistrationForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid RegistrationForm form, BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) return "register";
        try {
            String lang = LocaleContextHolder.getLocale().getLanguage();
            if (!lang.equals("mr") && !lang.equals("hi")) lang = "en";
            registration.register(form, lang);
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getMessage());
            return "register";
        }
        ra.addFlashAttribute("registered", true);
        return "redirect:/login"; // Post/Redirect/Get
    }

    /** Routes each role to its own dashboard after login. */
    @GetMapping("/home")
    public String home(Authentication auth) {
        var roles = auth.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        if (roles.contains("ROLE_SUPER_ADMIN")) return "redirect:/admin/dashboard";
        if (roles.contains("ROLE_OWNER"))       return "redirect:/owner/dashboard";
        if (roles.contains("ROLE_STAFF"))       return "redirect:/staff/dashboard";
        return "redirect:/customer/dashboard";
    }

    @GetMapping("/")
    public String root() { return "redirect:/home"; }
}
