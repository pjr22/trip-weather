package com.pjr22.tripweather.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        return "index.html";
    }

    /**
     * Email-verification links land here. Spring serves the SPA, which
     * inspects {@code window.location} on load and POSTs the token to
     * {@code /api/auth/verify}.
     */
    @GetMapping("/verify")
    public String verify() {
        return "index.html";
    }

    /**
     * Password-reset links land here. Same pattern as {@code /verify}: the
     * SPA reads the token from the query string and opens the reset modal.
     */
    @GetMapping("/reset-password")
    public String resetPassword() {
        return "index.html";
    }
}
