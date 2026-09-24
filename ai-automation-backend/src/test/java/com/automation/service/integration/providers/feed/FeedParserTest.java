package com.automation.service.integration.providers.feed;

import com.automation.service.integration.providers.feed.FeedParser.FeedEntry;
import com.automation.service.integration.providers.feed.FeedParser.ParsedFeed;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedParserTest {

    static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Beispiel-Blog</title>
                <link>https://blog.example.org/</link>
                <item>
                  <title>Erster &amp; alter Beitrag</title>
                  <link>https://blog.example.org/1</link>
                  <pubDate>Mon, 01 Sep 2026 08:00:00 +0000</pubDate>
                  <description><![CDATA[<p>Hallo <b>Welt</b> &amp; mehr</p>]]></description>
                  <author>anna@example.org (Anna)</author>
                  <guid isPermaLink="false">post-1</guid>
                </item>
                <item>
                  <title>Neuer Beitrag</title>
                  <link>https://blog.example.org/2</link>
                  <dc:date>2026-09-20T12:00:00+02:00</dc:date>
                  <content:encoded><![CDATA[<div>Nur <i>Inhalt</i></div>]]></content:encoded>
                  <dc:creator>Ben</dc:creator>
                </item>
                <item>
                  <title>Ohne Datum</title>
                  <description>&lt;p&gt;escaped &lt;em&gt;html&lt;/em&gt;&lt;/p&gt;</description>
                </item>
              </channel>
            </rss>
            """;

    static final String ATOM = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title type="text">Releases</title>
              <link rel="self" href="https://example.org/releases.atom"/>
              <link rel="alternate" href="https://example.org/releases"/>
              <entry>
                <id>tag:example.org,2026:1</id>
                <title>v1.0</title>
                <link rel="alternate" type="text/html" href="https://example.org/releases/v1.0"/>
                <updated>2026-09-10T10:00:00Z</updated>
                <content type="html">&lt;h2&gt;Neu&lt;/h2&gt;&lt;ul&gt;&lt;li&gt;Feature&lt;/li&gt;&lt;/ul&gt;</content>
                <author><name>octo</name></author>
              </entry>
              <entry>
                <id>tag:example.org,2026:2</id>
                <title type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">v1.1 <b>beta</b></div></title>
                <link href="https://example.org/releases/v1.1"/>
                <published>2026-09-15T09:30:00+01:00</published>
                <updated>2026-09-16T09:30:00Z</updated>
                <summary>Kurzfassung</summary>
              </entry>
            </feed>
            """;

    static final String RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://rdf.example.org/">
                <title>RDF-Feed</title>
                <link>https://rdf.example.org/</link>
              </channel>
              <item rdf:about="https://rdf.example.org/a">
                <title>Artikel A</title>
                <link>https://rdf.example.org/a</link>
                <dc:date>2026-09-01T10:00:00Z</dc:date>
                <dc:creator>Carla</dc:creator>
                <description>Text A</description>
              </item>
            </rdf:RDF>
            """;

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rss2_titlesLinksDatesCdataAndAuthors() {
        ParsedFeed feed = FeedParser.parse(bytes(RSS));

        assertThat(feed.format()).isEqualTo("rss");
        assertThat(feed.title()).isEqualTo("Beispiel-Blog");
        assertThat(feed.link()).isEqualTo("https://blog.example.org/");
        assertThat(feed.entries()).hasSize(3);

        FeedEntry first = feed.entries().get(0);
        assertThat(first.title()).isEqualTo("Erster & alter Beitrag");
        assertThat(first.published()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
        assertThat(first.summary()).isEqualTo("Hallo Welt & mehr");
        assertThat(first.author()).isEqualTo("anna@example.org (Anna)");
        assertThat(first.id()).isEqualTo("post-1");

        FeedEntry second = feed.entries().get(1);
        assertThat(second.published()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
        assertThat(second.summary()).isEqualTo("Nur Inhalt");
        assertThat(second.author()).isEqualTo("Ben");
        assertThat(second.id()).as("guid falls back to link").isEqualTo("https://blog.example.org/2");

        FeedEntry third = feed.entries().get(2);
        assertThat(third.published()).isNull();
        assertThat(third.summary()).isEqualTo("escaped html");
    }

    @Test
    void atom_prefersAlternateLinkAndPublishedDate() {
        ParsedFeed feed = FeedParser.parse(bytes(ATOM));

        assertThat(feed.format()).isEqualTo("atom");
        assertThat(feed.title()).isEqualTo("Releases");
        assertThat(feed.link()).isEqualTo("https://example.org/releases");

        FeedEntry v10 = feed.entries().get(0);
        assertThat(v10.link()).isEqualTo("https://example.org/releases/v1.0");
        assertThat(v10.published()).as("updated when no published").isEqualTo(Instant.parse("2026-09-10T10:00:00Z"));
        assertThat(v10.summary()).isEqualTo("Neu Feature");
        assertThat(v10.author()).isEqualTo("octo");
        assertThat(v10.id()).isEqualTo("tag:example.org,2026:1");

        FeedEntry v11 = feed.entries().get(1);
        assertThat(v11.title()).isEqualTo("v1.1 beta");
        assertThat(v11.link()).isEqualTo("https://example.org/releases/v1.1");
        assertThat(v11.published()).isEqualTo(Instant.parse("2026-09-15T08:30:00Z"));
        assertThat(v11.summary()).isEqualTo("Kurzfassung");
    }

    @Test
    void rss1Rdf() {
        ParsedFeed feed = FeedParser.parse(bytes(RDF));

        assertThat(feed.format()).isEqualTo("rdf");
        assertThat(feed.title()).isEqualTo("RDF-Feed");
        assertThat(feed.entries()).singleElement().satisfies(e -> {
            assertThat(e.title()).isEqualTo("Artikel A");
            assertThat(e.published()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
            assertThat(e.author()).isEqualTo("Carla");
            assertThat(e.id()).isEqualTo("https://rdf.example.org/a");
        });
    }

    @Test
    void summaryIsCut() {
        String rss = RSS.replace("Hallo <b>Welt</b> &amp; mehr", "wort ".repeat(300));
        FeedEntry first = FeedParser.parse(bytes(rss)).entries().get(0);
        assertThat(first.summary()).hasSizeLessThanOrEqualTo(FeedParser.SUMMARY_LENGTH + 1).endsWith("…");
    }

    @Test
    void brokenXmlIsRejected() {
        assertThatThrownBy(() -> FeedParser.parse(bytes("<rss><channel><item></rss>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid XML");
    }

    @Test
    void unknownRootIsRejected() {
        assertThatThrownBy(() -> FeedParser.parse(bytes("<html><body/></html>")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported feed format");
        assertThatThrownBy(() -> FeedParser.parse(bytes("<feed><entry/></feed>")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalEntityIsNeverResolved() throws Exception {
        Path secret = Files.createTempFile("feed-xxe", ".txt");
        Files.writeString(secret, "GEHEIM-XXE");
        try {
            String xxe = "<?xml version=\"1.0\"?>\n<!DOCTYPE rss [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>\n"
                    + "<rss version=\"2.0\"><channel><title>&x;</title></channel></rss>";
            assertThatThrownBy(() -> FeedParser.parse(bytes(xxe)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCTYPE")
                    .hasMessageNotContaining("GEHEIM-XXE");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void billionLaughsIsRejected() {
        String bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">"
                + "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">]>"
                + "<rss><channel><title>&lol2;</title></channel></rss>";
        assertThatThrownBy(() -> FeedParser.parse(bytes(bomb))).isInstanceOf(IllegalArgumentException.class);
    }
}
