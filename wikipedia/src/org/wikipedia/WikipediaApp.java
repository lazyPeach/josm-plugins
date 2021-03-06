// License: GPL. For details, see LICENSE file.
package org.wikipedia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.datatransfer.ClipboardUtils;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Utils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.wikipedia.data.WikidataEntry;
import org.wikipedia.data.WikipediaEntry;
import org.wikipedia.tools.XPath;

public final class WikipediaApp {

    public static final Pattern WIKIDATA_PATTERN = Pattern.compile("Q\\d+");
    private static final XPath X_PATH = XPath.getInstance();
    private final String wikipediaLang;
    private final String siteId;

    private WikipediaApp(final String wikipediaLang) {

        // FIXME: the proper way to get any wiki's site id is through an API call:
        // https://zh-yue.wikipedia.org/w/api.php?action=query&meta=siteinfo&siprop=general
        // use "wikiid" value. The value may be cached as it will never change
        String siteId = wikipediaLang.replace('-', '_');
        switch (siteId) {
            case "be_tarask":
                siteId = "be_x_old";
                break;
        }

        this.wikipediaLang = wikipediaLang;
        this.siteId = siteId + "wiki";
    }

    public static WikipediaApp forLanguage(final String wikipediaLang) {
        return new WikipediaApp(wikipediaLang);
    }

    static String getMediawikiLocale(Locale locale) {
        if (!locale.getCountry().isEmpty()) {
            return locale.getLanguage() + "-" + locale.getCountry().toLowerCase();
        } else {
            return locale.getLanguage();
        }
    }

    public String getSiteUrl() {
        if ("wikidata".equals(wikipediaLang)) {
            return "https://www.wikidata.org";
        } else {
            return "https://" + wikipediaLang + ".wikipedia.org";
        }
    }

    private static HttpClient.Response connect(String url) throws IOException {
        final HttpClient.Response response = HttpClient.create(new URL(url)).setReasonForRequest("Wikipedia").connect();
        if (response.getResponseCode() != 200) {
            throw new IOException("Server responded with HTTP " + response.getResponseCode());
        }
        return response;
    }

    public List<WikipediaEntry> getEntriesFromCoordinates(LatLon min, LatLon max) {
        try {
            // construct url
            final String url = getSiteUrl() + "/w/api.php"
                    + "?action=query"
                    + "&list=geosearch"
                    + "&format=xml"
                    + "&gslimit=500"
                    + "&gsbbox=" + max.lat() + "|" + min.lon() + "|" + min.lat() + "|" + max.lon();
            // parse XML document
            try (final InputStream in = connect(url).getContent()) {
                final Document doc = newDocumentBuilder().parse(in);
                final List<WikipediaEntry> entries = X_PATH.evaluateNodes("//gs", doc).stream()
                        .map(node -> {
                            final String name = X_PATH.evaluateString("@title", node);
                            final LatLon latLon = new LatLon(
                                    X_PATH.evaluateDouble("@lat", node),
                                    X_PATH.evaluateDouble("@lon", node));
                            if ("wikidata".equals(wikipediaLang)) {
                                return new WikidataEntry(name, null, latLon, null);
                            } else {
                                return new WikipediaEntry(wikipediaLang, name, latLon);
                            }
                        }).collect(Collectors.toList());
                if ("wikidata".equals(wikipediaLang)) {
                    return getLabelForWikidata(entries, Locale.getDefault()).stream().collect(Collectors.toList());
                } else {
                    return entries;
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static List<WikidataEntry> getWikidataEntriesForQuery(final String languageForQuery, final String query, final Locale localeForLabels) {
        try {
            final String url = "https://www.wikidata.org/w/api.php" +
                    "?action=wbsearchentities" +
                    "&language=" + languageForQuery +
                    "&strictlanguage=false" +
                    "&search=" + Utils.encodeUrl(query) +
                    "&limit=50" +
                    "&format=xml";
            try (final InputStream in = connect(url).getContent()) {
                final Document xml = newDocumentBuilder().parse(in);
                final List<WikidataEntry> r = X_PATH.evaluateNodes("//entity", xml).stream()
                        .map(node -> new WikidataEntry(X_PATH.evaluateString("@id", node), null, null, null))
                        .collect(Collectors.toList());
                return getLabelForWikidata(r, localeForLabels);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<WikipediaEntry> getEntriesFromCategory(String category, int depth) {
        try {
            final String url = "https://tools.wmflabs.org/cats-php/"
                    + "?lang=" + wikipediaLang
                    + "&depth=" + depth
                    + "&cat=" + Utils.encodeUrl(category);

            try (final BufferedReader reader = connect(url).getContentReader()) {
                return reader.lines()
                        .map(line -> new WikipediaEntry(wikipediaLang, line.trim().replace("_", " ")))
                        .collect(Collectors.toList());
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static List<WikipediaEntry> getEntriesFromClipboard(final String wikipediaLang) {
        return Pattern.compile("[\\n\\r]+")
                .splitAsStream(ClipboardUtils.getClipboardStringContent())
                .map(x -> new WikipediaEntry(wikipediaLang, x))
                .collect(Collectors.toList());
    }

    public void updateWIWOSMStatus(List<WikipediaEntry> entries) {
        if (entries.size() > 20) {
            partitionList(entries, 20).forEach(chunk -> updateWIWOSMStatus(chunk));
            return;
        }
        Map<String, Boolean> status = new HashMap<>();
        if (!entries.isEmpty()) {
            final String url = "https://tools.wmflabs.org/wiwosm/osmjson/getGeoJSON.php?action=check&lang=" + wikipediaLang;
            try {
                final String articles = entries.stream().map(i -> i.article).collect(Collectors.joining(","));
                final String requestBody = "articles=" + Utils.encodeUrl(articles);
                try (final BufferedReader reader = HttpClient.create(new URL(url), "POST").setReasonForRequest("Wikipedia")
                                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                                .setRequestBody(requestBody.getBytes(StandardCharsets.UTF_8))
                                .connect().getContentReader()) {
                    reader.lines().forEach(line -> {
                        //[article]\t[0|1]
                        final String[] x = line.split("\t");
                        if (x.length == 2) {
                            status.put(x[0], "1".equals(x[1]));
                        } else {
                            Main.error("Unknown element " + line);
                        }
                    });
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        for (WikipediaEntry i : entries) {
            i.setWiwosmStatus(status.get(i.article));
        }
    }

    public Stream<String> getWikipediaArticles(final OsmPrimitive p) {
        if ("wikidata".equals(wikipediaLang)) {
            return Stream.of(p.get("wikidata")).filter(Objects::nonNull);
        }
        return Stream
                .of("wikipedia", "wikipedia:" + wikipediaLang)
                .map(key -> WikipediaEntry.parseTag(key, p.get(key)))
                .filter(Objects::nonNull)
                .filter(wp -> wikipediaLang.equals(wp.lang))
                .map(wp -> wp.article);
    }

    /**
     * Returns a map mapping wikipedia articles to wikidata ids.
     */
    public Map<String, String> getWikidataForArticles(Collection<String> articles) {
        return articles.stream()
                .distinct()
                .collect(Collectors.groupingBy(new Function<String, Integer>() {
                    final AtomicInteger group = new AtomicInteger();
                    final AtomicInteger count = new AtomicInteger();
                    final AtomicInteger length = new AtomicInteger();

                    @Override
                    public Integer apply(String o) {
                        // max. 50 titles, max. 2048 of URL encoded title chars (to avoid HTTP 414)
                        if (count.incrementAndGet() > 50 || length.addAndGet(Utils.encodeUrl(o).length()) > 2048) {
                            count.set(0);
                            length.set(0);
                            return group.incrementAndGet();
                        } else {
                            return group.get();
                        }
                    }
                }))
                .values()
                .stream()
                .flatMap(chunk -> resolveWikidataItems(chunk).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get Wikidata IDs. For any unknown IDs, resolve them (normalize and get redirects),
     * and try getting Wikidata IDs again
     */
    private Map<String, String> resolveWikidataItems(Collection<String> articles) {
        final Map<String, String> result = getWikidataForArticles0(articles);
        final List<String> unresolved = articles.stream()
                .filter(title -> !result.containsKey(title))
                .collect(Collectors.toList());
        if (!unresolved.isEmpty()) {
            final Map<String, String> redirects = resolveRedirectsForArticles(unresolved);
            final Map<String, String> result2 = getWikidataForArticles0(redirects.values());
            redirects.forEach((original, resolved) -> {
                if (result2.containsKey(resolved)) {
                    result.put(original, result2.get(resolved));
                }
            });
        }
        return result;
    }

    private Map<String, String> getWikidataForArticles0(Collection<String> articles) {
        if (articles.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            final String url = "https://www.wikidata.org/w/api.php" +
                    "?action=wbgetentities" +
                    "&props=sitelinks" +
                    "&sites=" + siteId +
                    "&sitefilter=" + siteId +
                    "&format=xml" +
                    "&titles=" + articles.stream().map(Utils::encodeUrl).collect(Collectors.joining("|"));
            final Map<String, String> r = new TreeMap<>();
            try (final InputStream in = connect(url).getContent()) {
                final Document xml = newDocumentBuilder().parse(in);
                X_PATH.evaluateNodes("//entity", xml).forEach(node -> {
                    final String wikidata = X_PATH.evaluateString("./@id", node);
                    final String wikipedia = X_PATH.evaluateString("./sitelinks/sitelink/@title", node);
                    if (WIKIDATA_PATTERN.matcher(wikidata).matches()) { // non existing entries result in negative integers
                        r.put(wikipedia, wikidata);
                    }
                });
            }
            return r;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Given a list of wikipedia titles, returns a map of corresponding normalized title names,
     * or if the title is a redirect page, the result is the redirect target.
     */
    private Map<String, String> resolveRedirectsForArticles(Collection<String> articles) {
        try {
            final String url = getSiteUrl() + "/w/api.php" +
                    "?action=query" +
                    "&redirects" +
                    "&format=xml" +
                    "&titles=" + articles.stream().map(Utils::encodeUrl).collect(Collectors.joining("|"));
            try (final InputStream in = connect(url).getContent()) {
                final Document xml = newDocumentBuilder().parse(in);

                // Add both redirects and normalization results to the same map
                final Collector<Node, ?, Map<String, String>> fromToCollector = Collectors.toMap(
                        node -> X_PATH.evaluateString("./@from", node),
                        node -> X_PATH.evaluateString("./@to", node)
                );
                final Map<String, String> normalized = X_PATH.evaluateNodes("//normalized/n", xml)
                        .stream()
                        .collect(fromToCollector);
                final Map<String, String> redirects = X_PATH.evaluateNodes("//redirects/r", xml)
                        .stream()
                        .collect(fromToCollector);
                // We should only return those keys that were originally requested, excluding titles that are both normalized and redirected
                return articles.stream()
                        .collect(Collectors.toMap(Function.identity(), title -> {
                                    final String normalizedTitle = normalized.getOrDefault(title, title);
                                    return redirects.getOrDefault(normalizedTitle, normalizedTitle);
                                }
                        ));
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<String> getCategoriesForPrefix(final String prefix) {
        try {
            final String url = getSiteUrl() + "/w/api.php"
                    + "?action=query"
                    + "&list=prefixsearch"
                    + "&format=xml"
                    + "&psnamespace=14"
                    + "&pslimit=50"
                    + "&pssearch=" + Utils.encodeUrl(prefix);
            // parse XML document
            try (final InputStream in = connect(url).getContent()) {
                final Document doc = newDocumentBuilder().parse(in);
                return X_PATH.evaluateNodes("//ps/@title", doc).stream()
                        .map(Node::getNodeValue)
                        .map(value -> value.contains(":") ? value.split(":", 2)[1] : value)
                        .collect(Collectors.toList());
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String getLabelForWikidata(String wikidataId, Locale locale, String... preferredLanguage) {
        try {
            final List<WikidataEntry> entry = Collections.singletonList(new WikidataEntry(wikidataId, null, null, null));
            return getLabelForWikidata(entry, locale, preferredLanguage).get(0).label;
        } catch (IndexOutOfBoundsException ignore) {
            return null;
        }
    }

    static List<WikidataEntry> getLabelForWikidata(List<? extends WikipediaEntry> entries, Locale locale, String ... preferredLanguage) {
        if (entries.size() > 50) {
            return partitionList(entries, 50).stream()
                    .flatMap(chunk -> getLabelForWikidata(chunk, locale, preferredLanguage).stream())
                    .collect(Collectors.toList());
        } else if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            final String url = "https://www.wikidata.org/w/api.php" +
                    "?action=wbgetentities" +
                    "&props=labels|descriptions" +
                    "&ids=" + entries.stream().map(x -> x.article).collect(Collectors.joining("|")) +
                    "&format=xml";
            final Collection<String> languages = new ArrayList<>();
            if (locale != null) {
                languages.add(getMediawikiLocale(locale));
                languages.add(getMediawikiLocale(new Locale(locale.getLanguage())));
            }
            languages.addAll(Arrays.asList(preferredLanguage));
            languages.add("en");
            languages.add(null);
            final List<WikidataEntry> r = new ArrayList<>(entries.size());
            try (final InputStream in = connect(url).getContent()) {
                final Document xml = newDocumentBuilder().parse(in);
                for (final WikipediaEntry entry : entries) {
                    final Node entity = X_PATH.evaluateNode("//entity[@id='" + entry.article + "']", xml);
                    if (entity == null) {
                        continue;
                    }
                    r.add(new WikidataEntry(
                            entry.article,
                            getFirstField(languages, "label", entity),
                            entry.coordinate,
                            getFirstField(languages, "description", entity)
                    ));
                }
            }
            return r;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String getFirstField(Collection<String> languages, String field, Node entity) {
        return languages.stream()
                .map(language -> X_PATH.evaluateString(language != null
                        ? ".//" + field + "[@language='" + language + "']/@value"
                        : ".//" + field + "/@value", entity))
                .filter(label -> label != null && !label.isEmpty())
                .findFirst()
                .orElse(null);
    }

    public Collection<WikipediaEntry> getInterwikiArticles(String article) {
        try {
            final String url = getSiteUrl() + "/w/api.php" +
                    "?action=query" +
                    "&prop=langlinks" +
                    "&titles=" + Utils.encodeUrl(article) +
                    "&lllimit=500" +
                    "&format=xml";
            try (final InputStream in = connect(url).getContent()) {
                final Document xml = newDocumentBuilder().parse(in);
                return X_PATH.evaluateNodes("//ll", xml).stream()
                        .map(node -> {
                            final String lang = X_PATH.evaluateString("@lang", node);
                            final String name = node.getTextContent();
                            return new WikipediaEntry(lang, name);
                        }).collect(Collectors.toList());
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public LatLon getCoordinateForArticle(String article) {
        try {
            final String url = getSiteUrl() + "/w/api.php" +
                    "?action=query" +
                    "&prop=coordinates" +
                    "&titles=" + Utils.encodeUrl(article) +
                    "&format=xml";
            try (final InputStream in = connect(url).getContent()) {
                final Document xml = newDocumentBuilder().parse(in);
                final Node node = X_PATH.evaluateNode("//coordinates/co", xml);
                if (node == null) {
                    return null;
                } else {
                    return new LatLon(X_PATH.evaluateDouble("@lat", node), X_PATH.evaluateDouble("@lon", node));
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T> List<List<T>> partitionList(final List<T> list, final int size) {
        return new AbstractList<List<T>>() {
            @Override
            public List<T> get(int index) {
                final int fromIndex = index * size;
                final int toIndex = Math.min(fromIndex + size, list.size());
                return list.subList(fromIndex, toIndex);
            }

            @Override
            public int size() {
                return (int) Math.ceil(((float) list.size()) / size);
            }
        };
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            Main.warn("Cannot create DocumentBuilder");
            Main.warn(e);
            throw new RuntimeException(e);
        }
    }
}
