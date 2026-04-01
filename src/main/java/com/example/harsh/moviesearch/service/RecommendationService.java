package com.example.harsh.moviesearch.service;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.harsh.moviesearch.dto.MovieResponse;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final RestTemplate restTemplate;
    private final MovieCatalogService movieCatalogService;
    private final String recommendationApiBaseUrl;

    public RecommendationService(
            RestTemplate restTemplate,
            MovieCatalogService movieCatalogService,
            @Value("${recommendation.api.base-url:http://localhost:5000}") String recommendationApiBaseUrl
    ) {
        this.restTemplate = restTemplate;
        this.movieCatalogService = movieCatalogService;
        this.recommendationApiBaseUrl = recommendationApiBaseUrl;
    }

    public List<MovieResponse> defaultRecommendations() {
        URI uri = UriComponentsBuilder.fromUriString(recommendationApiBaseUrl)
                .path("/recommend")
                .queryParam("limit", 10)
                .build(true)
                .toUri();

        return fetchRecommendations(uri, () -> movieCatalogService.defaultRecommendations(10));
    }

    public List<MovieResponse> searchRecommendations(String query) {
        if (!StringUtils.hasText(query)) {
            return defaultRecommendations();
        }

        URI uri = UriComponentsBuilder.fromUriString(recommendationApiBaseUrl)
                .path("/recommend")
                .queryParam("movie", query)
                .queryParam("limit", 10)
                .build(true)
                .toUri();

        return fetchRecommendations(uri, () -> movieCatalogService.searchLocally(query, 10));
    }

    public List<MovieResponse> relatedRecommendations(String movieTitle) {
        if (!StringUtils.hasText(movieTitle)) {
            return List.of();
        }

        URI uri = UriComponentsBuilder.fromUriString(recommendationApiBaseUrl)
                .path("/recommend")
                .queryParam("movie", movieTitle)
                .queryParam("limit", 5)
                .build(true)
                .toUri();

        return fetchRecommendations(uri, () -> movieCatalogService.recommendLocallyByMovie(movieTitle, 5));
    }

    private List<MovieResponse> fetchRecommendations(URI uri, Supplier<List<MovieResponse>> fallback) {
        long started = System.nanoTime();
        try {
            MovieResponse[] response = restTemplate.getForObject(uri, MovieResponse[].class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (response == null || response.length == 0) {
                log.warn("Recommendation API returned an empty response in {} ms for {}. Falling back to local catalog.", elapsedMs, uri);
                return fallback.get();
            }
            List<MovieResponse> movies = Arrays.stream(response)
                    .filter(Objects::nonNull)
                    .toList();
            log.info("Recommendation API returned {} movies in {} ms for {}", movies.size(), elapsedMs, uri);
            return movies;
        } catch (RestClientException error) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            log.warn("Recommendation API call failed after {} ms for {}. Falling back to local catalog.", elapsedMs, uri, error);
            return fallback.get();
        }
    }
}
