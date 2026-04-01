package com.example.harsh.moviesearch.config;

import java.time.Year;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class ViewModelAttributesAdvice {

    @ModelAttribute("appName")
    String appName() {
        return "MovieSearch";
    }

    @ModelAttribute("currentYear")
    int currentYear() {
        return Year.now().getValue();
    }

    @ModelAttribute("searchQuery")
    String searchQuery() {
        return "";
    }
}
