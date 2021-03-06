package org.nzbhydra.searching.searchrequests;

import org.nzbhydra.config.Category;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.mediainfo.InfoProvider;
import org.nzbhydra.mediainfo.InfoProvider.IdType;
import org.nzbhydra.mediainfo.MovieInfo;
import org.nzbhydra.mediainfo.TvInfo;
import org.nzbhydra.searching.SearchType;
import org.nzbhydra.searching.searchrequests.SearchRequest.SearchSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SearchRequestFactory {

    @Autowired
    private ConfigProvider configProvider;
    @Autowired
    private InfoProvider infoProvider;


    public SearchRequest getSearchRequest(SearchType searchType, SearchSource source, Category category, long searchRequestId, Integer offset, Integer limit) {
        SearchRequest searchRequest = new SearchRequest(source, searchType, offset, limit);
        searchRequest.setSource(source);
        searchRequest.setCategory(category);
        searchRequest.setSearchRequestId(searchRequestId);
        MDC.put("SEARCH", String.valueOf(searchRequestId));
        if (!searchRequest.getMaxage().isPresent() && configProvider.getBaseConfig().getSearching().getMaxAge().isPresent()) {
            searchRequest.setMaxage(configProvider.getBaseConfig().getSearching().getMaxAge().get());
        }

        return searchRequest;
    }

    public SearchRequest extendWithSavedIdentifiers(SearchRequest request) {
        if (!request.getIdentifiers().isEmpty()) {
            if (request.getIdentifiers().keySet().stream().anyMatch(x -> InfoProvider.TV_ID_TYPES.contains(x))) {
                TvInfo tvInfo = infoProvider.findTvInfoInDatabase(request.getIdentifiers());
                if (tvInfo != null) {
                    request.getIdentifiers().put(IdType.TVDB, tvInfo.getTvdbId());
                    request.getIdentifiers().put(IdType.TVMAZE, tvInfo.getTvmazeId());
                    request.getIdentifiers().put(IdType.TVRAGE, tvInfo.getTvrageId());
                }
            }
            if (request.getIdentifiers().keySet().stream().anyMatch(x -> InfoProvider.MOVIE_ID_TYPES.contains(x))) {
                MovieInfo movieInfo = infoProvider.findMovieInfoInDatabase(request.getIdentifiers());
                if (movieInfo != null) {
                    request.getIdentifiers().put(IdType.TMDB, movieInfo.getTmdbId());
                    request.getIdentifiers().put(IdType.IMDB, movieInfo.getImdbId());
                }
            }
        }
        return request;
    }

}
