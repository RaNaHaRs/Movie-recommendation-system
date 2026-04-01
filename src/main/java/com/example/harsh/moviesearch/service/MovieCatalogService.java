package com.example.harsh.moviesearch.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import com.example.harsh.moviesearch.dto.MovieResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class MovieCatalogService {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private final Path datasetPath;
    private final List<MovieRecord> catalog;
    private final Map<String, MovieRecord> moviesBySlug;
    private final Map<String, MovieRecord> moviesByTitle;

    public MovieCatalogService(
            ObjectMapper objectMapper,
            @Value("${catalog.dataset.path:data/movies.json}") String datasetPath
    ) {
        this.datasetPath = Path.of(datasetPath);
        this.catalog = List.copyOf(loadCatalog(objectMapper));
        this.moviesBySlug = new LinkedHashMap<>();
        this.moviesByTitle = new LinkedHashMap<>();

        for (MovieRecord movie : catalog) {
            moviesBySlug.put(movie.slug(), movie);
            moviesByTitle.put(normalize(movie.title()), movie);
        }
    }

    public LandingView getLandingView() {
        List<MovieRecord> topRated = topRatedRecords(4);
        List<MovieRecord> trending = trendingRecords(4);
        MovieRecord hero = !topRated.isEmpty() ? topRated.get(0) : defaultMovie();

        return new LandingView(
                toFeaturedMovie(hero),
                toMovieCards(topRated),
                toMovieCards(trending)
        );
    }

    public LandingPageView getLandingPageView(List<MovieResponse> recommendedMovies) {
        List<MovieCard> recommendationCards = toMovieCardsFromResponses(recommendedMovies);
        MovieRecord heroMovie = resolveHeroMovie(recommendedMovies);

        return new LandingPageView(
                toFeaturedMovie(heroMovie),
                recommendationCards,
                toMovieCards(trendingRecords(10)),
                toMovieCards(topRatedRecords(10))
        );
    }

    public SearchResultsView getSearchResultsView(List<MovieResponse> similarMovies, String searchQuery) {
        boolean searchMode = StringUtils.hasText(searchQuery);
        String trimmedQuery = searchMode ? searchQuery.trim() : "";

        return new SearchResultsView(
                toMovieCardsFromResponses(similarMovies),
                searchMode ? "Search Results" : "",
                searchMode
                        ? "Recommendations returned by the Python recommendation model for \"" + trimmedQuery + "\"."
                        : "Search for a movie to get recommendations.",
                searchMode
                        ? "No movies found."
                        : "Search for a movie to get recommendations."
        );
    }

    public DashboardView getDashboardView(String username, List<MovieResponse> recommendedMovies, String searchQuery) {
        List<MovieCard> recommendationCards = toMovieCardsFromResponses(recommendedMovies);
        MovieRecord heroMovie = resolveHeroMovie(username, recommendedMovies);
        boolean searchMode = StringUtils.hasText(searchQuery);

        return new DashboardView(
                toFeaturedMovie(heroMovie),
                recommendationCards,
                toMovieCards(trendingRecords(10)),
                toMovieCards(topRatedRecords(10)),
                toMovieCards(favoriteRecords(username, 6)),
                searchMode ? "Search Results" : "Recommended For You",
                searchMode
                        ? "Similar titles from the Python model for \"" + searchQuery.trim() + "\"."
                        : "Live recommendations loaded from the Python model for @" + username + ".",
                searchMode
                        ? "No close matches were found for \"" + searchQuery.trim() + "\"."
                        : "Recommendations are not available yet."
        );
    }

    public FavoritesView getFavoritesView(String username) {
        return new FavoritesView(toMovieCards(favoriteRecords(username, 12)));
    }

    public ProfileView getProfileView(String username) {
        List<MovieRecord> favorites = favoriteRecords(username, 6);
        List<MovieRecord> recent = similarRecords(seedMovie(username), 3);
        List<String> tasteSignals = topTasteSignals(favorites);

        return new ProfileView(
                List.of(
                        new ProfileStat("Saved", Integer.toString(favorites.size())),
                        new ProfileStat("Recommended", Integer.toString(similarRecords(seedMovie(username), 6).size())),
                        new ProfileStat("Top Rated", Integer.toString(topRatedRecords(6).size()))
                ),
                tasteSignals,
                toMovieCards(recent)
        );
    }

    public Optional<MovieDetailsView> getMovieDetails(String slug, List<MovieResponse> relatedMovies) {
        MovieRecord movie = moviesBySlug.get(slug);
        if (movie == null) {
            return Optional.empty();
        }

        List<MovieCard> relatedCards = toMovieCardsFromResponses(relatedMovies);
        if (relatedCards.isEmpty()) {
            relatedCards = toMovieCards(similarRecords(movie, 5));
        }

        return Optional.of(new MovieDetailsView(
                movie.slug(),
                movie.title(),
                movie.year(),
                movie.genre(),
                movie.ratingDisplay(),
                movie.duration(),
                movie.overview(),
                movie.tagline(),
                movie.releaseDate(),
                movie.director(),
                movie.studio(),
                movie.poster(),
                movie.gallery(),
                movie.cast(),
                movie.tags(),
                relatedCards
        ));
    }

    public Optional<MovieDetailsView> getMovieDetailsByIdentifier(String identifier, List<MovieResponse> relatedMovies) {
        return resolveMovieFromQuery(identifier)
                .flatMap(movie -> getMovieDetails(movie.slug(), relatedMovies));
    }

    public Optional<String> getTitleBySlug(String slug) {
        return Optional.ofNullable(moviesBySlug.get(slug)).map(MovieRecord::title);
    }

    public Optional<String> resolveMovieSlug(String identifier) {
        return resolveMovieFromQuery(identifier).map(MovieRecord::slug);
    }

    public Optional<String> resolveMovieSlugStrict(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Optional.empty();
        }

        String rawIdentifier = identifier.trim().toLowerCase(Locale.ROOT);
        MovieRecord exactBySlug = moviesBySlug.get(rawIdentifier);
        if (exactBySlug != null) {
            return Optional.of(exactBySlug.slug());
        }

        String normalizedIdentifier = normalize(identifier);
        MovieRecord exactByTitle = moviesByTitle.get(normalizedIdentifier);
        if (exactByTitle != null) {
            return Optional.of(exactByTitle.slug());
        }

        return Optional.empty();
    }

    public List<MovieResponse> defaultRecommendations(int limit) {
        return toMovieResponses(topRatedRecords(limit));
    }

    public List<MovieResponse> recommendLocallyForUser(String username, int limit) {
        return toMovieResponses(similarRecords(seedMovie(username), limit));
    }

    public List<MovieResponse> searchLocally(String query, int limit) {
        return resolveMovieFromQuery(query)
                .map(movie -> toMovieResponses(similarRecords(movie, limit)))
                .orElseGet(List::of);
    }

    public List<MovieResponse> recommendLocallyByMovie(String movieTitle, int limit) {
        return resolveMovieFromQuery(movieTitle)
                .map(movie -> toMovieResponses(similarRecords(movie, limit)))
                .orElseGet(() -> defaultRecommendations(limit));
    }

    private List<MovieRecord> loadCatalog(ObjectMapper objectMapper) {
        try (var reader = Files.newBufferedReader(datasetPath)) {
            return objectMapper.readValue(reader, new TypeReference<List<MovieRecord>>() {
            });
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load movie dataset from " + datasetPath.toAbsolutePath(), error);
        }
    }

    private MovieRecord resolveHeroMovie(String username, List<MovieResponse> recommendedMovies) {
        if (!recommendedMovies.isEmpty()) {
            Optional<MovieRecord> recommendedHero = resolveMovie(recommendedMovies.get(0));
            if (recommendedHero.isPresent()) {
                return recommendedHero.get();
            }
        }
        return seedMovie(username);
    }

    private MovieRecord resolveHeroMovie(List<MovieResponse> recommendedMovies) {
        if (!recommendedMovies.isEmpty()) {
            Optional<MovieRecord> recommendedHero = resolveMovie(recommendedMovies.get(0));
            if (recommendedHero.isPresent()) {
                return recommendedHero.get();
            }
        }
        return defaultMovie();
    }

    private List<MovieRecord> trendingRecords(int limit) {
        return catalog.stream()
                .sorted(Comparator.comparingInt(MovieRecord::popularity).reversed()
                        .thenComparing(Comparator.comparingDouble(MovieRecord::rating).reversed()))
                .limit(limit)
                .toList();
    }

    private List<MovieRecord> topRatedRecords(int limit) {
        return catalog.stream()
                .sorted(Comparator.comparingDouble(MovieRecord::rating).reversed()
                        .thenComparing(Comparator.comparingInt(MovieRecord::popularity).reversed()))
                .limit(limit)
                .toList();
    }

    private List<MovieRecord> favoriteRecords(String username, int limit) {
        List<MovieRecord> similar = similarRecords(seedMovie(username), Math.max(limit * 2, limit));
        List<MovieRecord> favorites = new ArrayList<>();
        for (int index = 0; index < similar.size() && favorites.size() < limit; index += 2) {
            favorites.add(similar.get(index));
        }
        return favorites;
    }

    private MovieRecord seedMovie(String username) {
        if (catalog.isEmpty()) {
            throw new IllegalStateException("Movie dataset is empty.");
        }
        int index = Math.floorMod(normalize(username).hashCode(), catalog.size());
        return catalog.get(index);
    }

    private MovieRecord defaultMovie() {
        if (catalog.isEmpty()) {
            throw new IllegalStateException("Movie dataset is empty.");
        }
        return catalog.get(0);
    }

    private List<MovieRecord> similarRecords(MovieRecord anchor, int limit) {
        return catalog.stream()
                .filter(candidate -> !candidate.slug().equals(anchor.slug()))
                .sorted(Comparator.comparingDouble((MovieRecord candidate) -> similarityScore(anchor, candidate)).reversed())
                .limit(limit)
                .toList();
    }

    private double similarityScore(MovieRecord anchor, MovieRecord candidate) {
        Set<String> anchorTokens = tokens(anchor.title(), anchor.genre(), anchor.overview(), String.join(" ", anchor.tags()));
        Set<String> candidateTokens = tokens(candidate.title(), candidate.genre(), candidate.overview(), String.join(" ", candidate.tags()));

        long sharedTokens = anchorTokens.stream().filter(candidateTokens::contains).count();
        double union = Math.max(1, anchorTokens.size() + candidateTokens.size() - sharedTokens);
        double jaccard = sharedTokens / union;
        double sameGenreBonus = normalize(anchor.genre()).equals(normalize(candidate.genre())) ? 0.35 : 0.0;
        double sharedTagBonus = sharedTerms(anchor.tags(), candidate.tags()) * 0.05;
        double ratingBonus = candidate.rating() / 100.0;
        double popularityBonus = candidate.popularity() / 1000.0;
        return jaccard + sameGenreBonus + sharedTagBonus + ratingBonus + popularityBonus;
    }

    private int sharedTerms(Collection<String> left, Collection<String> right) {
        Set<String> normalizedRight = new LinkedHashSet<>();
        for (String value : right) {
            normalizedRight.add(normalize(value));
        }

        int matches = 0;
        for (String value : left) {
            if (normalizedRight.contains(normalize(value))) {
                matches++;
            }
        }
        return matches;
    }

    private Optional<MovieRecord> resolveMovieFromQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }

        String rawQuery = query.trim().toLowerCase(Locale.ROOT);
        MovieRecord exactBySlug = moviesBySlug.get(rawQuery);
        if (exactBySlug != null) {
            return Optional.of(exactBySlug);
        }

        String normalizedQuery = normalize(query);
        MovieRecord exactByTitle = moviesByTitle.get(normalizedQuery);
        if (exactByTitle != null) {
            return Optional.of(exactByTitle);
        }

        return catalog.stream()
                .map(movie -> Map.entry(movie, queryScore(normalizedQuery, movie)))
                .filter(entry -> entry.getValue() >= 0.12)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private double queryScore(String normalizedQuery, MovieRecord movie) {
        String title = normalize(movie.title());
        if (title.equals(normalizedQuery) || movie.slug().equals(normalizedQuery)) {
            return 10.0;
        }

        double containsScore = title.contains(normalizedQuery) || normalizedQuery.contains(title) ? 4.0 : 0.0;
        Set<String> queryTokens = tokens(normalizedQuery);
        Set<String> movieTokens = tokens(movie.title(), movie.genre(), movie.overview(), String.join(" ", movie.tags()));
        long shared = queryTokens.stream().filter(movieTokens::contains).count();
        if (shared == 0 && containsScore == 0.0) {
            return 0.0;
        }
        double overlap = queryTokens.isEmpty() ? 0.0 : shared / (double) queryTokens.size();
        return containsScore + overlap + (movie.rating() / 100.0);
    }

    private List<String> topTasteSignals(List<MovieRecord> favorites) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MovieRecord movie : favorites) {
            for (String tag : movie.tags()) {
                counts.merge(tag, 1, Integer::sum);
            }
        }

        List<String> signals = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(4)
                .map(Map.Entry::getKey)
                .toList();

        return signals.isEmpty()
                ? List.of("Sci-fi", "Thrillers", "Character drama", "Stylized visuals")
                : signals;
    }

    private List<MovieResponse> toMovieResponses(List<MovieRecord> movies) {
        return movies.stream()
                .map(movie -> new MovieResponse(
                        movie.slug(),
                        movie.title(),
                        movie.genre(),
                        movie.rating(),
                        movie.poster(),
                        movie.year(),
                        movie.overview()
                ))
                .toList();
    }

    private FeaturedMovie toFeaturedMovie(MovieRecord movie) {
        return new FeaturedMovie(
                movie.slug(),
                movie.title(),
                movie.tagline(),
                movie.genre(),
                movie.ratingDisplay(),
                movie.duration(),
                movie.poster()
        );
    }

    private List<MovieCard> toMovieCards(List<MovieRecord> movies) {
        return movies.stream().map(this::toMovieCard).toList();
    }

    private List<MovieCard> toMovieCardsFromResponses(List<MovieResponse> responses) {
        Map<String, MovieCard> cardsBySlug = new LinkedHashMap<>();
        for (MovieResponse response : responses) {
            MovieCard card = toMovieCard(response);
            cardsBySlug.putIfAbsent(card.slug(), card);
        }
        return List.copyOf(cardsBySlug.values());
    }

    private MovieCard toMovieCard(MovieRecord movie) {
        return new MovieCard(
                movie.slug(),
                movie.title(),
                movie.genre(),
                movie.ratingDisplay(),
                movie.poster(),
                Integer.toString(movie.year()),
                movie.overview()
        );
    }

    private MovieCard toMovieCard(MovieResponse response) {
        Optional<MovieRecord> resolvedMovie = resolveMovie(response);
        if (resolvedMovie.isPresent()) {
            return toMovieCard(resolvedMovie.get());
        }

        return new MovieCard(
                response.slug(),
                response.title(),
                response.genre(),
                response.ratingDisplay(),
                response.poster(),
                response.year() > 0 ? Integer.toString(response.year()) : "",
                response.overview()
        );
    }

    private Optional<MovieRecord> resolveMovie(MovieResponse response) {
        if (response == null) {
            return Optional.empty();
        }

        if (StringUtils.hasText(response.slug())) {
            MovieRecord bySlug = moviesBySlug.get(response.slug());
            if (bySlug != null) {
                return Optional.of(bySlug);
            }
        }

        if (StringUtils.hasText(response.title())) {
            return Optional.ofNullable(moviesByTitle.get(normalize(response.title())));
        }

        return Optional.empty();
    }

    private Set<String> tokens(String... values) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.isBlank()) {
                continue;
            }
            for (String token : normalized.split(" ")) {
                if (token.length() >= 2) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private String normalize(String value) {
        String lowered = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return NON_ALPHANUMERIC.matcher(lowered).replaceAll(" ").trim();
    }

    public record LandingView(
            FeaturedMovie heroMovie,
            List<MovieCard> recommendedPreview,
            List<MovieCard> trendingPreview
    ) {
    }

    public record LandingPageView(
            FeaturedMovie heroMovie,
            List<MovieCard> recommendedMovies,
            List<MovieCard> trendingMovies,
            List<MovieCard> topRatedMovies
    ) {
    }

    public record SearchResultsView(
            List<MovieCard> similarMovies,
            String heading,
            String message,
            String emptyStateMessage
    ) {
    }

    public record DashboardView(
            FeaturedMovie heroMovie,
            List<MovieCard> recommendedMovies,
            List<MovieCard> trendingMovies,
            List<MovieCard> topRatedMovies,
            List<MovieCard> favoriteMovies,
            String recommendationHeading,
            String recommendationMessage,
            String emptyStateMessage
    ) {
    }

    public record FavoritesView(List<MovieCard> favorites) {
    }

    public record ProfileView(
            List<ProfileStat> stats,
            List<String> tasteSignals,
            List<MovieCard> recentMovies
    ) {
    }

    public record FeaturedMovie(
            String slug,
            String title,
            String tagline,
            String genre,
            String rating,
            String duration,
            String posterImage
    ) {
    }

    public record MovieCard(
            String slug,
            String title,
            String genre,
            String rating,
            String posterImage,
            String year,
            String blurb
    ) {
    }

    public record ProfileStat(String label, String value) {
    }

    public record MovieDetailsView(
            String slug,
            String title,
            int year,
            String genre,
            String rating,
            String duration,
            String synopsis,
            String description,
            String releaseDate,
            String director,
            String studio,
            String posterImage,
            List<String> gallery,
            List<CastMember> cast,
            List<String> tags,
            List<MovieCard> relatedMovies
    ) {
    }

    public record CastMember(String actorName, String characterName, String avatar) {
    }

    private record MovieRecord(
            String slug,
            String title,
            int year,
            String genre,
            double rating,
            int popularity,
            String poster,
            String tagline,
            String overview,
            List<String> tags,
            String director,
            String duration,
            String releaseDate,
            String studio,
            List<CastMember> cast,
            List<String> gallery
    ) {
        String ratingDisplay() {
            return String.format(Locale.US, "%.1f", rating);
        }
    }
}
