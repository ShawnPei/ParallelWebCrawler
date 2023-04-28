package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final int maxDepth;
  private final List<Pattern> ignoredUrls;
  private final PageParserFactory parserFactory;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @TargetParallelism int threadCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls,
          PageParserFactory parserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.maxDepth = maxDepth;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.ignoredUrls = ignoredUrls;
    this.parserFactory = parserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
    for (String url : startingUrls) {
      pool.invoke(new CrawlInternal(url, deadline, maxDepth, counts, visitedUrls));
    }
    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }

    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();

  }
  public class CrawlInternal extends RecursiveAction {
    private final String url;
    private final Instant ddl;
    private final int maxDepth;
    private final ConcurrentMap<String, Integer> counts;
    private final ConcurrentSkipListSet<String> visitedUrls;

    public CrawlInternal(String url, Instant ddl, int maxDepth, ConcurrentMap<String, Integer> counts, ConcurrentSkipListSet<String> visitedUrls) {
      this.url = url;
      this.ddl = ddl;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrls = visitedUrls;
    }

    @Override
    protected void compute() {
      if (maxDepth == 0 || clock.instant().isAfter(ddl)) {
        return;
      }

      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return;
        }
      }
      if (!visitedUrls.add(url)) {
        return ;
      }
      visitedUrls.add(url);
      PageParser.Result result = parserFactory.get(url).parse();
      for (Map.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
        if (counts.containsKey(e.getKey())) {
          counts.put(e.getKey(), e.getValue() + counts.get(e.getKey()));
        } else {
          counts.put(e.getKey(), e.getValue());
        }
      }
      List<CrawlInternal> subclass = new ArrayList<>();
      for (String link : result.getLinks()) {
        subclass.add(new CrawlInternal(link, ddl, maxDepth - 1, counts, visitedUrls));
      }
      invokeAll(subclass);
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
