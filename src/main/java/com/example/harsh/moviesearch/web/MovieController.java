package com.example.harsh.moviesearch.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.harsh.moviesearch.service.MovieCatalogService;
import com.example.harsh.moviesearch.service.RecommendationService;

@Controller
public class MovieController {

    private final MovieCatalogService movieCatalogService;
    private final RecommendationService recommendationService;

    public MovieController(MovieCatalogService movieCatalogService, RecommendationService recommendationService) {
        this.movieCatalogService = movieCatalogService;
        this.recommendationService = recommendationService;
    }

    @GetMapping({"/movie/{identifier}", "/movies/{identifier}"})
    public String movieDetails(@PathVariable String identifier, Model model, RedirectAttributes redirectAttributes) {
        String slug = movieCatalogService.resolveMovieSlugStrict(identifier).orElse(null);
        if (slug == null) {
            redirectAttributes.addAttribute("notice", "Movie not found.");
            return "redirect:/";
        }

        String movieTitle = movieCatalogService.getTitleBySlug(slug).orElse(slug);
        var movieDetails = movieCatalogService.getMovieDetailsByIdentifier(
                slug,
                recommendationService.relatedRecommendations(movieTitle)
        ).orElse(null);
        if (movieDetails == null) {
            redirectAttributes.addAttribute("notice", "Movie details are unavailable right now.");
            return "redirect:/";
        }
        model.addAttribute("movie", movieDetails);
        model.addAttribute("searchQuery", "");
        return "movie-detail";
    }
}
