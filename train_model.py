from __future__ import annotations

import json
import pickle
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.neighbors import NearestNeighbors


BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
MOVIES_CSV_PATH = DATA_DIR / "movies.csv"
RATINGS_CSV_PATH = DATA_DIR / "ratings.csv"
TAGS_CSV_PATH = DATA_DIR / "tags.csv"
LINKS_CSV_PATH = DATA_DIR / "links.csv"
INTEGRATION_DATASET_PATH = DATA_DIR / "movies.json"

MOVIES_ARTIFACT = DATA_DIR / "movies.pkl"
SIMILARITY_ARTIFACT = DATA_DIR / "similarity.pkl"
VECTORIZER_ARTIFACT = DATA_DIR / "vectorizer.pkl"
FEATURE_MATRIX_ARTIFACT = DATA_DIR / "feature_matrix.pkl"

RATINGS_CHUNK_SIZE = 1_000_000
TAGS_CHUNK_SIZE = 250_000
MAX_TAGS_PER_MOVIE = 8


def normalize_text(value: str | None) -> str:
    text = unicodedata.normalize("NFKD", value or "").encode("ascii", "ignore").decode("ascii")
    lowered = text.strip().casefold()
    return re.sub(r"[^a-z0-9]+", " ", lowered).strip()


def slugify(value: str) -> str:
    slug = normalize_text(value).replace(" ", "-")
    return slug or "movie"


def split_title_and_year(raw_title: str) -> tuple[str, int]:
    match = re.search(r"\((\d{4})\)\s*$", raw_title or "")
    year = int(match.group(1)) if match else 0
    clean_title = re.sub(r"\s*\(\d{4}\)\s*$", "", raw_title or "").strip()
    return clean_title or (raw_title or "").strip(), year


def load_ratings_summary() -> pd.DataFrame:
    rating_sum: dict[int, float] = defaultdict(float)
    rating_count: dict[int, int] = defaultdict(int)

    for chunk in pd.read_csv(
            RATINGS_CSV_PATH,
            usecols=["movieId", "rating"],
            dtype={"movieId": "int32", "rating": "float32"},
            chunksize=RATINGS_CHUNK_SIZE
    ):
        grouped = chunk.groupby("movieId")["rating"].agg(["sum", "count"])
        for movie_id, total in grouped["sum"].items():
            rating_sum[int(movie_id)] += float(total)
        for movie_id, count in grouped["count"].items():
            rating_count[int(movie_id)] += int(count)

    summary = pd.DataFrame(
        {
            "movieId": list(rating_count.keys()),
            "rating_sum": [rating_sum[movie_id] for movie_id in rating_count],
            "rating_count": [rating_count[movie_id] for movie_id in rating_count],
        }
    )
    if summary.empty:
        return pd.DataFrame(columns=["movieId", "rating_sum", "rating_count", "avg_rating"])
    summary["avg_rating"] = summary["rating_sum"] / summary["rating_count"]
    return summary


def load_tags_summary() -> pd.DataFrame:
    tag_counters: dict[int, Counter[str]] = defaultdict(Counter)

    for chunk in pd.read_csv(
            TAGS_CSV_PATH,
            usecols=["movieId", "tag"],
            dtype={"movieId": "int32", "tag": "string"},
            chunksize=TAGS_CHUNK_SIZE
    ):
        chunk["tag_norm"] = chunk["tag"].fillna("").map(normalize_text)
        chunk = chunk.loc[chunk["tag_norm"] != "", ["movieId", "tag_norm"]]
        grouped = chunk.groupby(["movieId", "tag_norm"]).size()
        for (movie_id, tag), count in grouped.items():
            tag_counters[int(movie_id)][tag] += int(count)

    rows = []
    for movie_id, counter in tag_counters.items():
        top_tags = [tag for tag, _ in counter.most_common(MAX_TAGS_PER_MOVIE)]
        rows.append(
            {
                "movieId": movie_id,
                "tag_list": top_tags,
                "tag_text": " ".join(top_tags),
            }
        )

    if not rows:
        return pd.DataFrame(columns=["movieId", "tag_list", "tag_text"])
    return pd.DataFrame(rows)


def build_slug_series(frame: pd.DataFrame) -> pd.Series:
    usage: dict[str, int] = defaultdict(int)
    slugs: list[str] = []

    for movie_id, title, year in frame[["movieId", "title_clean", "year"]].itertuples(index=False):
        base_slug = slugify(title)
        usage[base_slug] += 1
        if usage[base_slug] == 1:
            slugs.append(base_slug)
            continue

        if year > 0:
            dated_slug = f"{base_slug}-{year}"
            if dated_slug not in usage:
                usage[dated_slug] = 1
                slugs.append(dated_slug)
                continue

        unique_slug = f"{base_slug}-{movie_id}"
        usage[unique_slug] += 1
        slugs.append(unique_slug)

    return pd.Series(slugs, index=frame.index, dtype="string")


def build_overview(genre_display: str, tag_list: list[str], avg_rating: float, rating_count: int) -> str:
    parts = []
    if genre_display:
        parts.append(f"Genres: {genre_display}.")
    if tag_list:
        parts.append("Themes: " + ", ".join(tag_list[:4]) + ".")
    if rating_count > 0:
        parts.append(f"Community rating {avg_rating * 2:.1f}/10 from {rating_count:,} ratings.")
    return " ".join(parts).strip() or "Movie recommendation generated from the dataset."


def build_tagline(title: str, genre_display: str, tag_list: list[str]) -> str:
    if tag_list:
        return f"{title} blends {genre_display.lower()} with {', '.join(tag_list[:2])}."
    if genre_display:
        return f"{title} is a {genre_display.lower()} recommendation from the MovieLens catalog."
    return f"{title} is a data-backed recommendation from the MovieLens catalog."


def export_integration_catalog(movies: pd.DataFrame) -> None:
    records = []
    for row in movies.itertuples(index=False):
        genre_display = str(row.genre_display or "")
        tag_list = list(row.tag_list or [])
        records.append(
            {
                "slug": str(row.slug),
                "title": str(row.title_clean),
                "year": int(row.year),
                "genre": genre_display,
                "rating": float(row.display_rating),
                "popularity": int(row.rating_count),
                "poster": "",
                "tagline": build_tagline(str(row.title_clean), genre_display, tag_list),
                "overview": str(row.overview),
                "tags": tag_list,
                "director": "Unknown",
                "duration": "N/A",
                "releaseDate": str(row.year) if int(row.year) > 0 else "Unknown",
                "studio": "MovieLens Dataset",
                "cast": [],
                "gallery": [],
            }
        )

    with INTEGRATION_DATASET_PATH.open("w", encoding="utf-8") as stream:
        json.dump(records, stream, ensure_ascii=True)


def prepare_movies_frame() -> pd.DataFrame:
    movies = pd.read_csv(
        MOVIES_CSV_PATH,
        usecols=["movieId", "title", "genres"],
        dtype={"movieId": "int32", "title": "string", "genres": "string"}
    )
    movies = movies.drop_duplicates(subset=["movieId"]).copy()
    title_parts = movies["title"].fillna("").map(split_title_and_year)
    movies["title_clean"] = title_parts.map(lambda value: value[0])
    movies["year"] = title_parts.map(lambda value: value[1]).astype("int32")
    movies["title_norm"] = movies["title_clean"].map(normalize_text)
    movies["genre_display"] = movies["genres"].fillna("(no genres listed)").str.replace("|", " | ", regex=False)
    movies["genres"] = movies["genres"].fillna("(no genres listed)")
    movies["genre_list"] = movies["genres"].map(
        lambda value: [part for part in value.split("|") if part and part != "(no genres listed)"]
    )
    movies["genre_text"] = movies["genre_list"].map(lambda values: " ".join(normalize_text(value) for value in values))

    ratings_summary = load_ratings_summary()
    tags_summary = load_tags_summary()

    movies = movies.merge(ratings_summary[["movieId", "avg_rating", "rating_count"]], on="movieId", how="left")
    movies = movies.merge(tags_summary, on="movieId", how="left")

    movies["avg_rating"] = movies["avg_rating"].fillna(0.0).astype("float32")
    movies["rating_count"] = movies["rating_count"].fillna(0).astype("int32")
    movies["tag_list"] = movies["tag_list"].apply(lambda value: value if isinstance(value, list) else [])
    movies["tag_text"] = movies["tag_text"].fillna("")

    rated_movies = movies.loc[movies["rating_count"] > 0]
    global_average = float(rated_movies["avg_rating"].mean()) if not rated_movies.empty else 3.0
    minimum_votes = float(rated_movies["rating_count"].quantile(0.60)) if not rated_movies.empty else 25.0
    minimum_votes = max(minimum_votes, 25.0)

    vote_count = movies["rating_count"].astype("float32")
    average_rating = movies["avg_rating"].astype("float32")
    movies["weighted_rating"] = ((vote_count / (vote_count + minimum_votes)) * average_rating) + (
            (minimum_votes / (vote_count + minimum_votes)) * global_average
    )
    movies.loc[movies["rating_count"] == 0, "weighted_rating"] = global_average * 0.92

    movies["content"] = (
            movies["title_norm"] + " " +
            movies["title_norm"] + " " +
            movies["genre_text"] + " " +
            movies["genre_text"] + " " +
            movies["tag_text"]
    ).str.strip()
    movies.loc[movies["content"] == "", "content"] = movies["title_norm"]
    movies["slug"] = build_slug_series(movies)
    movies["poster"] = ""
    movies["overview"] = [
        build_overview(genre_display, tag_list, float(avg_rating), int(rating_count))
        for genre_display, tag_list, avg_rating, rating_count in movies[
            ["genre_display", "tag_list", "avg_rating", "rating_count"]
        ].itertuples(index=False)
    ]
    movies["display_rating"] = (movies["weighted_rating"].clip(lower=0.0, upper=5.0) * 2.0).round(1)

    if LINKS_CSV_PATH.exists():
        links = pd.read_csv(
            LINKS_CSV_PATH,
            usecols=["movieId", "imdbId", "tmdbId"],
            dtype={"movieId": "int32", "imdbId": "string", "tmdbId": "string"}
        )
        movies = movies.merge(links, on="movieId", how="left")

    movies = movies.sort_values(["weighted_rating", "rating_count", "title_clean"], ascending=[False, False, True])
    return movies.reset_index(drop=True)


def train_model() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    movies = prepare_movies_frame()

    vectorizer = TfidfVectorizer(
        stop_words="english",
        ngram_range=(1, 2),
        min_df=2,
        max_df=0.85,
        sublinear_tf=True
    )
    feature_matrix = vectorizer.fit_transform(movies["content"])

    # Store a cosine-neighbor index instead of an 87k x 87k dense matrix.
    similarity_index = NearestNeighbors(metric="cosine", algorithm="brute")
    similarity_index.fit(feature_matrix)

    with MOVIES_ARTIFACT.open("wb") as stream:
        pickle.dump(movies, stream, protocol=pickle.HIGHEST_PROTOCOL)

    with SIMILARITY_ARTIFACT.open("wb") as stream:
        pickle.dump(similarity_index, stream, protocol=pickle.HIGHEST_PROTOCOL)

    with VECTORIZER_ARTIFACT.open("wb") as stream:
        pickle.dump(vectorizer, stream, protocol=pickle.HIGHEST_PROTOCOL)

    with FEATURE_MATRIX_ARTIFACT.open("wb") as stream:
        pickle.dump(feature_matrix, stream, protocol=pickle.HIGHEST_PROTOCOL)

    export_integration_catalog(movies)

    print(f"Saved {len(movies):,} movies to {MOVIES_ARTIFACT}")
    print(f"Saved cosine neighbor index to {SIMILARITY_ARTIFACT}")
    print(f"Saved TF-IDF vectorizer to {VECTORIZER_ARTIFACT}")
    print(f"Saved sparse feature matrix to {FEATURE_MATRIX_ARTIFACT}")
    print(f"Saved Spring integration catalog to {INTEGRATION_DATASET_PATH}")


if __name__ == "__main__":
    train_model()
