package org.nzbhydra.searching;

import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.nzbhydra.config.Category.Subtype;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.config.IndexerConfig;
import org.nzbhydra.config.SearchModuleType;
import org.nzbhydra.config.SearchSourceRestriction;
import org.nzbhydra.downloading.FileDownloadEntity;
import org.nzbhydra.downloading.FileDownloadRepository;
import org.nzbhydra.indexers.Indexer;
import org.nzbhydra.logging.LoggingMarkers;
import org.nzbhydra.mediainfo.InfoProvider;
import org.nzbhydra.searching.searchrequests.SearchRequest;
import org.nzbhydra.searching.searchrequests.SearchRequest.SearchSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequestScope
public class IndexerForSearchSelector {

    private static final Logger logger = LoggerFactory.getLogger(IndexerForSearchSelector.class);
    public static final Pattern SCHEDULER_PATTERN = Pattern.compile("(?<day1>(?:mo|tu|we|th|fr|sa|su))?\\-?(?<day2>(?:mo|tu|we|th|fr|sa|su))?(?<hour1>\\d{1,2})?\\-?(?<hour2>\\d{1,2})?", Pattern.CASE_INSENSITIVE);
    private static final Random random = new Random();

    @Autowired
    private InfoProvider infoProvider;
    @Autowired
    private SearchModuleProvider searchModuleProvider;
    @Autowired
    private FileDownloadRepository nzbDownloadRepository;
    @Autowired
    private ConfigProvider configProvider;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @PersistenceContext
    private EntityManager entityManager;

    protected Clock clock = Clock.systemDefaultZone();

    private SearchRequest searchRequest;
    protected Map<Indexer, String> notSelectedIndersWithReason = new HashMap<>();


    public IndexerForSearchSelection pickIndexers(SearchRequest searchRequest) {
        this.searchRequest = searchRequest;
        //Check any indexer that's not disabled by the user. If it's disabled by the system it will be deselected with a proper message later
        List<Indexer> eligibleIndexers = searchModuleProvider.getIndexers().stream().filter(x -> x.getConfig().getState() != IndexerConfig.State.DISABLED_USER).collect(Collectors.toList());
        if (eligibleIndexers.isEmpty()) {
            logger.warn("You don't have any enabled indexers");
            return new IndexerForSearchSelection();
        }

        List<Indexer> selectedIndexers = new ArrayList<>();
        logger.debug("Picking indexers out of " + eligibleIndexers.size());

        Stopwatch stopwatch = Stopwatch.createStarted();
        for (Indexer indexer : eligibleIndexers) {
            if (!checkInternalAndNotEvenShown(indexer)) {
                continue;
            }
            if (!checkIndexerSelectedByUser(indexer)) {
                continue;
            }
            if (!checkIndexerConfigComplete(indexer)) {
                continue;
            }
            if (!checkSearchSource(indexer)) {
                continue;
            }
            if (!checkIndexerStatus(indexer)) {
                continue;
            }
            if (!checkTorznabOnlyUsedForTorrentOrInternalSearches(indexer)) {
                continue;
            }
            if (!checkDisabledForCategory(indexer)) {
                continue;
            }
            if (!checkSchedule(indexer)) {
                continue;
            }
            if (!checkLoadLimiting(indexer)) {
                continue;
            }
            if (!checkSearchId(indexer)) {
                continue;
            }
            if (!checkIndexerHitLimit(indexer)) {
                continue;
            }

            selectedIndexers.add(indexer);
        }
        logger.debug(LoggingMarkers.PERFORMANCE, "Selection of indexers took {}ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
        if (selectedIndexers.isEmpty()) {
            logger.warn("No indexers were selected for this search. You probably don't have any indexers configured which support the provided ID type or all of your indexers which do are currently disabled. You can enable query generation to work around this.");
        } else {
            logger.info("Selected {} out of {} indexers: {}", selectedIndexers.size(), eligibleIndexers.size(), Joiner.on(", ").join(selectedIndexers.stream().map(Indexer::getName).collect(Collectors.toList())));
        }

        eventPublisher.publishEvent(new IndexerSelectionEvent(searchRequest, selectedIndexers.size()));

        return new IndexerForSearchSelection(notSelectedIndersWithReason, selectedIndexers);
    }

    protected boolean checkIndexerConfigComplete(Indexer indexer) {
        if (!indexer.getConfig().isConfigComplete()) {
            String message = "Not using " + indexer.getName() + " because configuration is not complete. Please open it in the GUI and complete the config. Call the caps check manually to make sure everything is checked.";
            return handleIndexerNotSelected(indexer, message, "Configuration incomplete");
        }
        return true;
    }

    protected boolean checkSearchId(Indexer indexer) {
        boolean needToSearchById = !searchRequest.getIdentifiers().isEmpty() && !searchRequest.getQuery().isPresent();
        if (needToSearchById) {
            boolean canUseAnyProvidedId = !Collections.disjoint(searchRequest.getIdentifiers().keySet(), indexer.getConfig().getSupportedSearchIds());
            boolean cannotSearchProvidedOrConvertableId = !canUseAnyProvidedId && !infoProvider.canConvertAny(searchRequest.getIdentifiers().keySet(), Sets.newHashSet(indexer.getConfig().getSupportedSearchIds()));
            boolean queryGenerationEnabled = configProvider.getBaseConfig().getSearching().getGenerateQueries().meets(searchRequest.getSource());
            if (cannotSearchProvidedOrConvertableId && !queryGenerationEnabled) {
                String message = String.format("Not using %s because the search did not provide any ID that the indexer can handle and query generation is disabled", indexer.getName());
                return handleIndexerNotSelected(indexer, message, "No usable ID");
            }
        }
        return true;
    }

    protected boolean checkLoadLimiting(Indexer indexer) {
        boolean preventedByLoadLimiting = indexer.getConfig().getLoadLimitOnRandom().isPresent() && random.nextInt(indexer.getConfig().getLoadLimitOnRandom().get()) + 1 != 1;
        if (preventedByLoadLimiting) {
            String message = String.format("Not using %s because load limiting prevented it. Chances of it being picked: 1/%d", indexer.getName(), indexer.getConfig().getLoadLimitOnRandom().get());
            return handleIndexerNotSelected(indexer, message, "Disabled temporarily");
        }
        return true;
    }

    protected boolean checkDisabledForCategory(Indexer indexer) {
        if (searchRequest.getCategory().getSubtype().equals(Subtype.ALL)) {
            return true;
        }
        boolean indexerDisabledForThisCategory = !indexer.getConfig().getEnabledCategories().isEmpty() && !indexer.getConfig().getEnabledCategories().contains(searchRequest.getCategory().getName());
        if (indexerDisabledForThisCategory) {
            String message = String.format("Not using %s because it's disabled for category %s", indexer.getName(), searchRequest.getCategory().getName());
            return handleIndexerNotSelected(indexer, message, "Disabled for category");
        }
        return true;
    }

    protected boolean checkIndexerStatus(Indexer indexer) {
        if (indexer.getConfig().getState() == IndexerConfig.State.DISABLED_SYSTEM_TEMPORARY) {
            if (indexer.getConfig().getDisabledUntil() == null || Instant.ofEpochMilli(indexer.getConfig().getDisabledUntil()).isBefore(clock.instant())) {
                return true;
            }
            if (configProvider.getBaseConfig().getSearching().isIgnoreTemporarilyDisabled()) {
                logger.debug("{} is marked as disabled until {} but user chose to ignore this", indexer.getName(), indexer.getConfig().getDisabledUntil());
                return true;
            }
            String message = String.format("Not using %s because it's disabled until %s due to a previous error ", indexer.getName(), Instant.ofEpochMilli(indexer.getConfig().getDisabledUntil()));
            return handleIndexerNotSelected(indexer, message, "Disabled temporarily because of previous errors");
        }
        if (indexer.getConfig().getState() == IndexerConfig.State.DISABLED_SYSTEM) {
            String message = String.format("Not using %s because it's disabled due to a previous unrecoverable error", indexer.getName());
            return handleIndexerNotSelected(indexer, message, "Disabled permanently because of previous unrecoverable error");
        }
        return true;
    }

    protected boolean checkIndexerSelectedByUser(Indexer indexer) {
        boolean indexerNotSelectedByUser =
                searchRequest.getSource() == SearchSource.INTERNAL
                        && (searchRequest.getIndexers().isPresent() && !searchRequest.getIndexers().get().isEmpty())
                        && !searchRequest.getIndexers().get().contains(indexer.getName());
        if (indexerNotSelectedByUser) {
            //Don't send a search log message for this because showing it to the leader would be useless. He knows he hasn't selected it
            logger.info(String.format("Not using %s because it was not selected by the user", indexer.getName()));
            notSelectedIndersWithReason.put(indexer, "Not selected by the user");
            return false;
        }
        return true;
    }

    /**
     * If an indexer was not shown an the search page (for any reason) it should not be used but also no message should be
     * logged or shown to the user. It doesn't make sense to log "Indexer is incomplete" to the user when he didn't have a chance to even select it
     */
    protected boolean checkInternalAndNotEvenShown(Indexer indexer) {
        if (searchRequest.getSource() == SearchSource.API) {
            return true;
        }

        return indexer.getConfig().isEligibleForInternalSearch(configProvider.getBaseConfig().getSearching().isIgnoreTemporarilyDisabled());
    }

    protected boolean checkIndexerHitLimit(Indexer indexer) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        IndexerConfig indexerConfig = indexer.getConfig();
        if (!indexerConfig.getHitLimit().isPresent() && !indexerConfig.getDownloadLimit().isPresent()) {
            return true;
        }
        LocalDateTime comparisonTime;
        LocalDateTime now = LocalDateTime.now(clock);
        if (indexerConfig.getHitLimitResetTime().isPresent()) {

            comparisonTime = now.with(ChronoField.HOUR_OF_DAY, indexerConfig.getHitLimitResetTime().get());
            if (comparisonTime.isAfter(now)) {
                comparisonTime = comparisonTime.minus(1, ChronoUnit.DAYS);
            }
        } else {
            comparisonTime = now.minus(1, ChronoUnit.DAYS);
        }
        if (indexerConfig.getHitLimit().isPresent()) {
            Query query = entityManager.createNativeQuery("SELECT x.TIME FROM INDEXERAPIACCESS_SHORT x WHERE x.INDEXER_ID = (:indexerId) ORDER BY TIME DESC LIMIT (:hitLimit)");
            query.setParameter("indexerId", indexer.getIndexerEntity().getId());
            query.setParameter("hitLimit", indexerConfig.getHitLimit().get());
            List resultList = query.getResultList();

            if (resultList.size() == indexerConfig.getHitLimit().get()) { //Found as many as we want, so now we must check if they're all in the time window
                Instant earliestAccess = ((Timestamp) Iterables.getLast(resultList)).toInstant();
                if (earliestAccess.isAfter(comparisonTime.toInstant(ZoneOffset.UTC))) {
                    LocalDateTime nextPossibleHit = calculateNextPossibleHit(indexerConfig, earliestAccess);

                    String message = String.format("Not using %s because all %d allowed API hits were already made. The next API hit should be possible at %s", indexerConfig.getName(), indexerConfig.getHitLimit().get(), nextPossibleHit);
                    logger.debug(LoggingMarkers.PERFORMANCE, "Detection of API limit reached took {}ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
                    return handleIndexerNotSelected(indexer, message, "API hit limit reached");
                }
            }
        }
        if (indexerConfig.getDownloadLimit().isPresent()) {
            Page<FileDownloadEntity> page = nzbDownloadRepository.findBySearchResultIndexerOrderByTimeDesc(indexer.getIndexerEntity(), new PageRequest(0, indexerConfig.getDownloadLimit().get()));
            if (page.getContent().size() == indexerConfig.getDownloadLimit().get() && Iterables.getLast(page.getContent()).getTime().isAfter(comparisonTime.toInstant(ZoneOffset.UTC))) {
                LocalDateTime nextPossibleHit = calculateNextPossibleHit(indexerConfig, page.getContent().get(page.getContent().size() - 1).getTime());

                String message = String.format("Not using %s because all %d allowed download were already made. The next download should be possible at %s", indexerConfig.getName(), indexerConfig.getDownloadLimit().get(), nextPossibleHit);
                logger.debug(LoggingMarkers.PERFORMANCE, "Detection of download limit reached took {}ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
                return handleIndexerNotSelected(indexer, message, "Download limit reached");
            }
        }

        logger.debug(LoggingMarkers.PERFORMANCE, "Detection if hit limits were reached for indexer {} took {}ms", indexer.getName(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
        return true;
    }

    LocalDateTime calculateNextPossibleHit(IndexerConfig indexerConfig, Instant firstInWindowAccessTime) {
        LocalDateTime nextPossibleHit;
        if (indexerConfig.getHitLimitResetTime().isPresent()) {
            //Next possible hit is at the hour of day defined by the reset time. If that is already in the past it will be the next day at that time
            LocalDateTime now = LocalDateTime.now(clock);
            nextPossibleHit = now.with(ChronoField.HOUR_OF_DAY, indexerConfig.getHitLimitResetTime().get()).with(ChronoField.MINUTE_OF_HOUR, 0);
            if (nextPossibleHit.isBefore(now)) {
                nextPossibleHit = nextPossibleHit.plus(1, ChronoUnit.DAYS);
            }
        } else {
            //Next possible hit is at the earlierst 24 hours after the first hit in the hit limit time window (5 hits are limit, the first was made now, then the next hit is available tomorrow at this time)
            nextPossibleHit = LocalDateTime.ofInstant(firstInWindowAccessTime, ZoneOffset.UTC).plus(1, ChronoUnit.DAYS);
        }
        return nextPossibleHit;
    }

    protected boolean checkSearchSource(Indexer indexer) {
        boolean wrongSearchSource = indexer.getConfig().getEnabledForSearchSource() != SearchSourceRestriction.BOTH && !searchRequest.getSource().name().equals(indexer.getConfig().getEnabledForSearchSource().name());
        if (wrongSearchSource) {
            String message = String.format("Not using %s because the search source is %s but the indexer is only enabled for %s searches", indexer.getName(), searchRequest.getSource(), indexer.getConfig().getEnabledForSearchSource());
            return handleIndexerNotSelected(indexer, message, "Not enabled for this search context");
        }
        return true;
    }

    protected boolean checkTorznabOnlyUsedForTorrentOrInternalSearches(Indexer indexer) {
        if (searchRequest.getDownloadType() == DownloadType.TORRENT && indexer.getConfig().getSearchModuleType() != SearchModuleType.TORZNAB) {
            String message = String.format("Not using %s because a torrent search is requested", indexer.getName());
            return handleIndexerNotSelected(indexer, message, "No torrent search");
        }
        if (searchRequest.getDownloadType() == DownloadType.NZB && indexer.getConfig().getSearchModuleType() == SearchModuleType.TORZNAB && searchRequest.getSource() == SearchSource.API) {
            String message = String.format("Not using %s because torznab indexers cannot be used by API NZB searches", indexer.getName());
            return handleIndexerNotSelected(indexer, message, "NZB API search");
        }
        return true;
    }

    protected boolean checkSchedule(Indexer indexer) {
        if (!indexer.getConfig().getSchedule().isEmpty() && indexer.getConfig().getSchedule().stream().noneMatch(this::isInTime)) {
            String message = String.format("Not using %s because the current time is out of its schedule", indexer.getName());
            return handleIndexerNotSelected(indexer, message, "Out of schedule");
        }
        return true;
    }

    protected boolean isInTime(String scheduleTime) {
        Map<String, Integer> days = new HashMap<>();
        days.put("mo", 1);
        days.put("tu", 2);
        days.put("we", 3);
        days.put("th", 4);
        days.put("fr", 5);
        days.put("sa", 6);
        days.put("su", 7);

        LocalDateTime now = LocalDateTime.now(clock);
        Matcher matcher = SCHEDULER_PATTERN.matcher(scheduleTime.toLowerCase());
        if (!matcher.matches()) {
            logger.error("Unable to parse schedule string {}", scheduleTime);
            return false;
        }
        if (matcher.group("day1") != null) {
            int fromDay = days.get(matcher.group("day1"));
            int toDay;
            if (matcher.group("day2") == null) {
                toDay = fromDay;
            } else {
                toDay = days.get(matcher.group("day2"));
            }

            DayOfWeek currentDay = now.getDayOfWeek();
            if (!inRange(fromDay, toDay, currentDay.getValue())) {
                logger.debug(LoggingMarkers.SCHEDULER, "Current date does not match scheduler string {}: Current day {} is not between {} and {}", scheduleTime, currentDay, DayOfWeek.of(fromDay).name(), DayOfWeek.of(toDay % 7).name());
                return false;
            }
        }

        if (matcher.group("hour1") != null) {
            int fromHour = Integer.valueOf(matcher.group("hour1"));
            int toHour;
            if (matcher.group("hour2") != null) {
                toHour = Integer.valueOf(matcher.group("hour2"));
            } else {
                toHour = fromHour;
            }
            if (fromHour > toHour) {
                if (!(now.getHour() >= fromHour || now.getHour() <= toHour)) {
                    logger.debug(LoggingMarkers.SCHEDULER, "Current date does not match scheduler string {}: Current hour {} is not between {} and {}", scheduleTime, now.getHour(), toHour, fromHour);
                    return false;
                }
            } else if (now.getHour() < fromHour || now.getHour() > toHour) {
                logger.debug(LoggingMarkers.SCHEDULER, "Current date does not match scheduler string {}: Current hour {} is not between {} and {}", scheduleTime, now.getHour(), fromHour, toHour);
                return false;
            }
        }

        return true;
    }

    private boolean inRange(int a, int b, int val) {
        return val >= Math.min(a, b) && val <= Math.max(a, b);
    }

    private boolean handleIndexerNotSelected(Indexer indexer, String message, String reason) {
        logger.info(message);
        notSelectedIndersWithReason.put(indexer, reason);
        eventPublisher.publishEvent(new SearchMessageEvent(searchRequest, message));
        return false;
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class IndexerForSearchSelection {
        private Map<Indexer, String> notPickedIndexersWithReason = new HashMap<>();
        private List<Indexer> selectedIndexers = new ArrayList<>();
    }


}
