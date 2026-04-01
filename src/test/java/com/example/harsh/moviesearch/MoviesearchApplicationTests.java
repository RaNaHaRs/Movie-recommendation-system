package com.example.harsh.moviesearch;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class MoviesearchApplicationTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void landingPageRendersMlRecommendationsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(content().string(containsString("Movie Discovery, Powered By ML")))
                .andExpect(content().string(containsString("Search for a movie to get recommendations.")))
                .andExpect(content().string(not(containsString("Recommended Movies"))))
                .andExpect(content().string(not(containsString("Trending Movies"))))
                .andExpect(content().string(not(containsString("Top Rated Movies"))))
                .andExpect(content().string(containsString("data-search-form")));
    }

    @Test
    void searchPageRendersLandingWithResults() throws Exception {
        mockMvc.perform(get("/search").param("query", "matrix"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(content().string(containsString("Search Results")))
                .andExpect(content().string(containsString("data-search-results-region")))
                .andExpect(content().string(containsString("value=\"matrix\"")));
    }

    @Test
    void ajaxSearchReturnsLandingRecommendationFragment() throws Exception {
        mockMvc.perform(get("/search")
                        .param("query", "matrix")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing :: recommendationPanel"))
                .andExpect(content().string(containsString("data-search-results-region")))
                .andExpect(content().string(containsString("Search Results")));
    }

    @Test
    void emptySearchShowsPromptInsteadOfError() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(content().string(containsString("Search for a movie to get recommendations.")));
    }

    @Test
    void movieDetailsArePubliclyAccessible() throws Exception {
        mockMvc.perform(get("/movie/inception"))
                .andExpect(status().isOk())
                .andExpect(view().name("movie-detail"))
                .andExpect(content().string(containsString("Inception")))
                .andExpect(content().string(containsString("Story Signals")))
                .andExpect(content().string(containsString("More Like This")));
    }

    @Test
    void missingMovieRedirectsBackToLandingNotice() throws Exception {
        mockMvc.perform(get("/movie/not-a-real-title"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/?notice=*"));
    }

    @Test
    void removedAuthAndDashboardRoutesRedirectToLanding() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/signup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/favorites"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
