from __future__ import annotations

import hashlib
import logging
import pickle
import time
from difflib import get_close_matches
from pathlib import Path
from typing import Any

from flask import Flask, jsonify, request
from flask_cors import CORS
from sklearn.metrics.pairwise import cosine_similarity

from train_model import (
    DATA_DIR,
    FEATURE_MATRIX_ARTIFACT,
    MOVIES_ARTIFACT,
    SIMILARITY_ARTIFACT,
    VECTORIZER_ARTIFACT,
    normalize_text,
    train_model,
)


logging.basicConfig(level=logging.INFO)
log = logging.getLogger("movie-recommendation-api")

GENRE_ALIASES = {
    "science fiction": {"Sci-Fi"},
    "scifi": {"Sci-Fi"},
    "sci fi": {"Sci-Fi"},
    "romcom": {"Romance", "Comedy"},
    "rom com": {"Romance", "Comedy"},
    "kids": {"Children"},
    "superhero": {"Action", "Adventure", "Fantasy", "Sci-Fi"},
    "superheroes": {"Action", "Adventure", "Fantasy", "Sci-Fi"},
}

TITLE_ARTICLES = {"a", "an", "the"}


def search_title_key(value: str | None) -> str:
    raw_title = (value or "").strip()
    match = raw_title.rsplit(", ", 1)
    if len(match) == 2 and match[1].casefold() in TITLE_ARTICLES:
        raw_title = f"{match[1]} {match[0]}"
    return normalize_text(raw_title)


class RecommendationEngine:
    def __init__(self) -> None:
        self.movies = None
        self.similarity_index = None
        self.vectorizer = None
        self.feature_matrix = None
        self.title_to_index: dict[str, int] = {}
        self.title_norms: list[str] = []
        self.title_token_map: dict[str, list[int]] = {}
        self.genre_phrase_map: dict[str, set[str]] = {}
        self.genre_to_indexes: dict[str, list[int]] = {}
        self.top_rated_indexes: list[int] = []
        self.username_seed_pool: list[int] = []
        self.movie_ids: list[int] = []
        self.weighted_ratings: list[float] = []
        self.rating_counts: list[int] = []
        self.genre_sets: list[set[str]] = []
        self.tag_sets: list[set[str]] = []
        self.title_token_sets: list[set[str]] = []
        self.serialized_movies: list[dict[str, Any]] = []
        self.load()

    def warmup(self) -> None:
        started = time.perf_counter()
        try:
            self.recommend(limit=5)
            if self.serialized_movies:
                self.recommend(movie=self.serialized_movies[0]["title"], limit=5)
            log.info("Recommendation engine warm-up completed in %.3fs.", time.perf_counter() - started)
        except Exception:
            log.exception("Recommendation engine warm-up failed.")

    def load(self) -> None:
        required_paths = (
            MOVIES_ARTIFACT,
            SIMILARITY_ARTIFACT,
            VECTORIZER_ARTIFACT,
            FEATURE_MATRIX_ARTIFACT,
        )
        if not all(path.exists() for path in required_paths):
            log.info("Model artifacts not found. Training from CSV data.")
            train_model()

        with MOVIES_ARTIFACT.open("rb") as stream:
            self.movies = pickle.load(stream)

        with SIMILARITY_ARTIFACT.open("rb") as stream:
            self.similarity_index = pickle.load(stream)

        with VECTORIZER_ARTIFACT.open("rb") as stream:
            self.vectorizer = pickle.load(stream)

        with FEATURE_MATRIX_ARTIFACT.open("rb") as stream:
            self.feature_matrix = pickle.load(stream)

        self.movies = self.movies.reset_index(drop=True)
        self.movies["title_norm"] = self.movies["title_norm"].fillna("")
        self.movies["search_title_norm"] = self.movies["title_clean"].map(search_title_key)
        self.movies["genre_list"] = self.movies["genre_list"].apply(lambda value: value if isinstance(value, list) else [])
        self.movies["tag_list"] = self.movies["tag_list"].apply(lambda value: value if isinstance(value, list) else [])

        self.title_norms = self.movies["search_title_norm"].tolist()
        self.movie_ids = self.movies["movieId"].fillna(0).astype(int).tolist()
        self.weighted_ratings = self.movies["weighted_rating"].fillna(0.0).astype(float).tolist()
        self.rating_counts = self.movies["rating_count"].fillna(0).astype(int).tolist()
        self.title_to_index = {}
        self.title_token_map = {}
        self.genre_sets = []
        self.tag_sets = []
        self.title_token_sets = []
        self.serialized_movies = []

        for index, row in self.movies.iterrows():
            title_norm = row["title_norm"]
            search_title_norm = row["search_title_norm"]
            genre_set = set(row["genre_list"])
            tag_set = set(row["tag_list"])
            title_tokens = self._title_tokens(search_title_norm)

            if title_norm and title_norm not in self.title_to_index:
                self.title_to_index[title_norm] = int(index)
            if search_title_norm and search_title_norm not in self.title_to_index:
                self.title_to_index[search_title_norm] = int(index)
            for token in title_tokens:
                self.title_token_map.setdefault(token, []).append(int(index))
            self.genre_sets.append(genre_set)
            self.tag_sets.append(tag_set)
            self.title_token_sets.append(title_tokens)
            self.serialized_movies.append(
                {
                    "slug": str(row.get("slug", "")),
                    "title": str(row.get("title_clean", row.get("title", ""))),
                    "genre": str(row.get("genre_display", "")),
                    "rating": round(float(row.get("display_rating", 0.0)), 1),
                    "poster": str(row.get("poster", "")),
                    "year": int(row.get("year", 0) or 0),
                    "overview": str(row.get("overview", "")),
                }
            )

        self.genre_phrase_map = {}
        for genre_list in self.movies["genre_list"]:
            for genre in genre_list:
                normalized_genre = normalize_text(genre)
                if not normalized_genre:
                    continue
                self.genre_phrase_map.setdefault(normalized_genre, set()).add(genre)
                for token in normalized_genre.split():
                    if token:
                        self.genre_phrase_map.setdefault(token, set()).add(genre)

        self.top_rated_indexes = (
            self.movies.sort_values(
                ["weighted_rating", "rating_count", "avg_rating", "title_clean"],
                ascending=[False, False, False, True]
            ).index.astype(int).tolist()
        )
        self.genre_to_indexes = {}
        for index in self.top_rated_indexes:
            for genre in self.genre_sets[index]:
                self.genre_to_indexes.setdefault(genre, []).append(int(index))
        popular_seed_indexes = (
            self.movies.sort_values(
                ["rating_count", "weighted_rating", "avg_rating", "title_clean"],
                ascending=[False, False, False, True]
            ).index.astype(int).tolist()
        )
        seed_pool_size = min(1500, len(popular_seed_indexes))
        self.username_seed_pool = popular_seed_indexes[:seed_pool_size] or self.top_rated_indexes

    def recommend(
            self,
            *,
            username: str | None = None,
            movie: str | None = None,
            search: str | None = None,
            limit: int = 10
    ) -> list[dict[str, Any]]:
        query = movie or search
        safe_limit = max(1, min(int(limit), 10))

        if normalize_text(query):
            return self._recommend_for_query(query or "", safe_limit)
        if normalize_text(username):
            return self._recommend_for_username(username or "", safe_limit)
        return self._serialize_indexes(self._top_rated(safe_limit))

    def _recommend_for_query(self, query: str, limit: int) -> list[dict[str, Any]]:
        normalized_query = normalize_text(query)
        matched_genres = self._matched_genres(normalized_query)
        if matched_genres and self._query_looks_like_genre(normalized_query):
            return self._serialize_indexes(self._genre_recommendations(matched_genres, limit))

        movie_index = self._find_movie_index(normalized_query)
        if movie_index is not None:
            return self._serialize_indexes(self._recommend_from_anchor(movie_index, limit))
        if matched_genres:
            return self._serialize_indexes(self._genre_recommendations(matched_genres, limit))

        vector_match = self._best_vector_match(normalized_query)
        if vector_match is not None:
            return self._serialize_indexes(self._recommend_from_anchor(vector_match, limit))

        return self._serialize_indexes(self._top_rated(limit))

    def _recommend_for_username(self, username: str, limit: int) -> list[dict[str, Any]]:
        if not self.username_seed_pool:
            return self._serialize_indexes(self._top_rated(limit))

        digest = hashlib.sha256(normalize_text(username).encode("utf-8")).hexdigest()
        seed_position = int(digest[:8], 16) % len(self.username_seed_pool)
        anchor_index = self.username_seed_pool[seed_position]
        return self._serialize_indexes(self._recommend_from_anchor(anchor_index, limit))

    def _find_movie_index(self, normalized_query: str) -> int | None:
        if not normalized_query:
            return None

        exact_index = self.title_to_index.get(normalized_query)
        if exact_index is not None:
            return exact_index

        contains_mask = self.movies["search_title_norm"].str.contains(normalized_query, regex=False, na=False)
        contains_indexes = self.movies.index[contains_mask].astype(int).tolist()
        if contains_indexes:
            return self._best_ranked_candidate(normalized_query, contains_indexes)

        query_tokens = normalized_query.split()
        if query_tokens:
            token_mask = self.movies["search_title_norm"].map(
                lambda value: any(token in value for token in query_tokens)
            )
            token_indexes = self.movies.index[token_mask].astype(int).tolist()
            if token_indexes:
                best_token_match = self._best_ranked_candidate(normalized_query, token_indexes)
                if best_token_match is not None:
                    return best_token_match

        close_titles = get_close_matches(normalized_query, self.title_norms, n=5, cutoff=0.72)
        if close_titles:
            return self.title_to_index.get(close_titles[0])

        return None

    def _best_ranked_candidate(self, normalized_query: str, candidate_indexes: list[int]) -> int | None:
        query_tokens = set(normalized_query.split())
        best_index = None
        best_score = -1.0

        for candidate_index in candidate_indexes[:500]:
            row = self.movies.iloc[candidate_index]
            title_norm = row["search_title_norm"]
            title_tokens = set(title_norm.split())
            overlap = len(query_tokens & title_tokens) / max(len(query_tokens), 1)
            contains_bonus = 1.0 if normalized_query in title_norm else 0.0
            starts_bonus = 0.4 if title_norm.startswith(normalized_query) else 0.0
            rating_bonus = float(row["weighted_rating"]) / 5.0
            vote_bonus = min(float(row["rating_count"]) / 5000.0, 0.35)
            score = overlap + contains_bonus + starts_bonus + rating_bonus + vote_bonus

            if score > best_score:
                best_score = score
                best_index = int(candidate_index)

        return best_index if best_score >= 0.65 else None

    def _best_vector_match(self, normalized_query: str) -> int | None:
        if not normalized_query:
            return None

        query_vector = self.vectorizer.transform([normalized_query])
        scores = cosine_similarity(query_vector, self.feature_matrix).flatten()
        best_index = int(scores.argmax())
        best_score = float(scores[best_index])
        return best_index if best_score >= 0.08 else None

    def _matched_genres(self, normalized_query: str) -> set[str]:
        if not normalized_query:
            return set()

        matched = set(self.genre_phrase_map.get(normalized_query, set()))
        matched.update(GENRE_ALIASES.get(normalized_query, set()))
        for token in normalized_query.split():
            matched.update(self.genre_phrase_map.get(token, set()))
        return matched

    def _query_looks_like_genre(self, normalized_query: str) -> bool:
        if normalized_query in self.genre_phrase_map or normalized_query in GENRE_ALIASES:
            return True
        tokens = normalized_query.split()
        return bool(tokens) and len(tokens) <= 3 and all(token in self.genre_phrase_map for token in tokens)

    def _recommend_from_anchor(self, anchor_index: int, limit: int) -> list[int]:
        excluded = {self.movie_ids[anchor_index]}
        anchor_genres = self.genre_sets[anchor_index]
        anchor_tags = self.tag_sets[anchor_index]
        anchor_title_tokens = self.title_token_sets[anchor_index]
        candidate_scores: dict[int, float] = {}

        for neighbor_index, similarity_score in self._similar_indexes(anchor_index, max(limit * 12, 80)):
            candidate_scores[neighbor_index] = candidate_scores.get(neighbor_index, 0.0) + (similarity_score * 2.8)

        for franchise_index in self._franchise_indexes(anchor_index, max(limit * 8, 40)):
            candidate_scores[franchise_index] = candidate_scores.get(franchise_index, 0.0) + (
                    3.6
                    + (self.weighted_ratings[franchise_index] / 5.0)
                    + min(self.rating_counts[franchise_index] / 5000.0, 0.35)
            )

        for same_genre_index in self._same_genre_indexes(anchor_index, max(limit * 12, 80)):
            genres = self.genre_sets[same_genre_index]
            tags = self.tag_sets[same_genre_index]
            title_tokens = self.title_token_sets[same_genre_index]
            genre_overlap = len(anchor_genres & genres)
            tag_overlap = len(anchor_tags & tags)
            title_overlap = len(anchor_title_tokens & title_tokens)
            rating_bonus = self.weighted_ratings[same_genre_index] / 5.0
            vote_bonus = min(self.rating_counts[same_genre_index] / 5000.0, 0.35)

            candidate_scores[same_genre_index] = candidate_scores.get(same_genre_index, 0.0) + (
                    (genre_overlap * 1.2)
                    + (tag_overlap * 0.18)
                    + (title_overlap * 2.4)
                    + rating_bonus
                    + vote_bonus
            )

        ranked_indexes = []
        for candidate_index, _ in sorted(
                candidate_scores.items(),
                key=lambda entry: self._recommendation_sort_key(anchor_index, entry[0], entry[1]),
                reverse=True
        ):
            movie_id = self.movie_ids[candidate_index]
            if movie_id in excluded:
                continue
            ranked_indexes.append(candidate_index)
            excluded.add(movie_id)
            if len(ranked_indexes) >= limit:
                return ranked_indexes

        for top_rated_index in self._top_rated(limit * 6):
            movie_id = self.movie_ids[top_rated_index]
            if movie_id in excluded:
                continue
            ranked_indexes.append(top_rated_index)
            excluded.add(movie_id)
            if len(ranked_indexes) >= limit:
                break

        return ranked_indexes[:limit]

    def _similar_indexes(self, anchor_index: int, limit: int) -> list[tuple[int, float]]:
        neighbor_count = min(limit + 1, len(self.movies.index))
        distances, indices = self.similarity_index.kneighbors(
            self.feature_matrix[anchor_index],
            n_neighbors=neighbor_count
        )
        ranked_indexes: list[tuple[int, float]] = []
        for distance, index in zip(distances[0], indices[0]):
            candidate_index = int(index)
            if candidate_index == anchor_index:
                continue
            similarity_score = 1.0 - float(distance)
            if similarity_score < 0.05:
                continue
            ranked_indexes.append((candidate_index, similarity_score))
        return ranked_indexes

    def _franchise_indexes(self, anchor_index: int, limit: int) -> list[int]:
        anchor_title_tokens = self.title_token_sets[anchor_index]
        if not anchor_title_tokens:
            return []

        candidate_pool = {
            candidate_index
            for token in anchor_title_tokens
            for candidate_index in self.title_token_map.get(token, [])
            if candidate_index != anchor_index
        }

        candidates = []
        for candidate_index in candidate_pool:
            title_tokens = self.title_token_sets[candidate_index]
            overlap = len(anchor_title_tokens & title_tokens)
            if overlap == 0:
                continue
            candidates.append(
                (
                    overlap,
                    self.weighted_ratings[candidate_index],
                    self.rating_counts[candidate_index],
                    int(candidate_index),
                )
            )

        candidates.sort(reverse=True)
        return [candidate_index for _, _, _, candidate_index in candidates[:limit]]

    def _recommendation_sort_key(self, anchor_index: int, candidate_index: int, score: float) -> tuple[float, float, float, float]:
        anchor_genres = self.genre_sets[anchor_index]
        anchor_tags = self.tag_sets[anchor_index]
        anchor_title_tokens = self.title_token_sets[anchor_index]
        genre_overlap = len(anchor_genres & self.genre_sets[candidate_index])
        tag_overlap = len(anchor_tags & self.tag_sets[candidate_index])
        title_overlap = len(anchor_title_tokens & self.title_token_sets[candidate_index])
        return (
            score,
            float(genre_overlap),
            float(tag_overlap + title_overlap),
            self.weighted_ratings[candidate_index],
        )

    def _title_tokens(self, normalized_title: str) -> set[str]:
        return {
            token
            for token in normalized_title.split()
            if token and token not in TITLE_ARTICLES and len(token) >= 3
        }

    def _same_genre_indexes(self, anchor_index: int, limit: int) -> list[int]:
        anchor_genres = self.genre_sets[anchor_index]
        if not anchor_genres:
            return []

        per_genre_limit = max(limit * 2, 40)
        candidate_scores: dict[int, int] = {}
        for genre in anchor_genres:
            for candidate_index in self.genre_to_indexes.get(genre, [])[:per_genre_limit]:
                if candidate_index == anchor_index:
                    continue
                candidate_scores[candidate_index] = candidate_scores.get(candidate_index, 0) + 1

        ranked = sorted(
            candidate_scores.items(),
            key=lambda entry: (
                entry[1],
                self.weighted_ratings[entry[0]],
                self.rating_counts[entry[0]],
            ),
            reverse=True,
        )
        return [candidate_index for candidate_index, _ in ranked[:limit]]

    def _genre_recommendations(self, matched_genres: set[str], limit: int) -> list[int]:
        per_genre_limit = max(limit * 8, 60)
        candidate_scores: dict[int, int] = {}
        for genre in matched_genres:
            for candidate_index in self.genre_to_indexes.get(genre, [])[:per_genre_limit]:
                candidate_scores[candidate_index] = candidate_scores.get(candidate_index, 0) + 1

        ranked = sorted(
            candidate_scores.items(),
            key=lambda entry: (
                entry[1],
                self.weighted_ratings[entry[0]],
                self.rating_counts[entry[0]],
            ),
            reverse=True,
        )
        if ranked:
            return [candidate_index for candidate_index, _ in ranked[:limit]]
        return self._top_rated(limit)

    def _top_rated(self, limit: int) -> list[int]:
        return self.top_rated_indexes[:limit]

    def _serialize_indexes(self, indexes: list[int]) -> list[dict[str, Any]]:
        return [self.serialized_movies[int(index)].copy() for index in indexes]


app = Flask(__name__)
CORS(app)
engine = RecommendationEngine()
engine.warmup()


@app.get("/health")
def health() -> Any:
    return jsonify(
        {
            "status": "ok",
            "movies_loaded": len(engine.movies.index),
            "artifacts_dir": str(Path(DATA_DIR).resolve()),
        }
    )


@app.get("/recommend")
def recommend() -> Any:
    username = request.args.get("username")
    movie = request.args.get("movie")
    search = request.args.get("search")
    limit = request.args.get("limit", default=10, type=int)
    limit = max(1, min(limit, 10))
    started = time.perf_counter()

    try:
        payload = engine.recommend(username=username, movie=movie, search=search, limit=limit)
    except Exception:
        log.exception("Recommendation request failed for movie=%r search=%r username=%r", movie, search, username)
        payload = engine.recommend(limit=limit)

    duration = time.perf_counter() - started
    log.info(
        "Recommendation request completed in %.3fs for movie=%r search=%r username=%r with %s results.",
        duration,
        movie,
        search,
        username,
        len(payload),
    )
    return jsonify(payload)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
