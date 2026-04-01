package com.example.harsh.moviesearch.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.harsh.moviesearch.service.MovieCatalogService;

@Controller
public class LandingController {

    private final MovieCatalogService movieCatalogService;

    public LandingController(MovieCatalogService movieCatalogService) {
        this.movieCatalogService = movieCatalogService;
    }

    @GetMapping({"/", "/home"})
    public String landing(
            @RequestParam(name = "notice", required = false) String notice,
            Model model
    ) {
        populateLanding(model, notice);
        return "landing";
    }

    @GetMapping({"/dashboard", "/movies/dashboard", "/favorites", "/profile", "/login", "/signup", "/logout"})
    public String legacyRouteRedirect() {
        return "redirect:/";
    }

    private void populateLanding(Model model, String notice) {
        model.addAttribute("searchResults", movieCatalogService.getSearchResultsView(List.of(), null));
        model.addAttribute("searchPerformed", false);
        if (StringUtils.hasText(notice)) {
            model.addAttribute("landingNotice", notice);
        }
    }
}
