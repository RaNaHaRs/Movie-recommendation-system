package com.example.harsh.moviesearch.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.harsh.moviesearch.dto.MovieResponse;
import com.example.harsh.moviesearch.service.MovieCatalogService;
import com.example.harsh.moviesearch.service.RecommendationService;

@Controller
public class SearchController {

    private final MovieCatalogService movieCatalogService;
    private final RecommendationService recommendationService;

    public SearchController(
            MovieCatalogService movieCatalogService,
            RecommendationService recommendationService
    ) {
        this.movieCatalogService = movieCatalogService;
        this.recommendationService = recommendationService;
    }

    @GetMapping("/search")
    public String search(
            @RequestParam(name = "query", required = false) String query,
            @RequestHeader(name = "X-Requested-With", required = false) String requestedWith,
            Model model
    ) {
        String normalizedQuery = normalizeQuery(query);
        model.addAttribute("searchQuery", normalizedQuery == null ? "" : normalizedQuery);
        model.addAttribute("searchPerformed", normalizedQuery != null);

        try {
            List<MovieResponse> similarMovies = normalizedQuery == null
                    ? List.of()
                    : recommendationService.searchRecommendations(normalizedQuery);
            model.addAttribute("searchResults", movieCatalogService.getSearchResultsView(similarMovies, normalizedQuery));
        } catch (RuntimeException error) {
            List<MovieResponse> fallbackMovies = normalizedQuery == null
                    ? List.of()
                    : movieCatalogService.searchLocally(normalizedQuery, 10);
            model.addAttribute("searchResults", movieCatalogService.getSearchResultsView(fallbackMovies, normalizedQuery));
            model.addAttribute("searchError", "Search recommendations are temporarily unavailable. Showing fallback results.");
        }

        if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
            return "landing :: recommendationPanel";
        }

        return "landing";
    }

    private String normalizeQuery(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return null;
        }

        String query = rawQuery.trim();
        if (query.length() > 120) {
            return query.substring(0, 120);
        }
        return query;
    }
}
