package com.example.harsh.moviesearch.dto;

import java.util.Locale;

public record MovieResponse(
        String slug,
        String title,
        String genre,
        double rating,
        String poster,
        int year,
        String overview
) {

    public String posterImage() {
        return poster;
    }

    public String ratingDisplay() {
        return String.format(Locale.US, "%.1f", rating);
    }
}
