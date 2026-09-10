package me.clip.placeholderapi.expansion.cloud;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialEcloudCatalogParserTest {
    @Test
    void parsesThePublicV3CatalogShapeAndAllVersions() {
        String json = """
                {
                  "Math": {
                    "author": "Andre601",
                    "description": "Math placeholders",
                    "latest_version": "2.0.2",
                    "source_url": "https://example.test/source",
                    "dependency_url": "",
                    "placeholders": ["%math_<expression>%"],
                    "versions": [
                      {"url":"https://dl.example.test/math-2.0.2.jar","version":"2.0.2","verified":true,"created_at":1768202783431},
                      {"url":"https://dl.example.test/math-2.0.1.jar","version":"2.0.1","verified":true}
                    ]
                  }
                }
                """;

        List<OfficialEcloudCatalogParser.Entry> entries = new OfficialEcloudCatalogParser(new Gson())
                .parse(json, "https://ecloud.placeholderapi.com");

        assertEquals(1, entries.size());
        OfficialEcloudCatalogParser.Entry entry = entries.getFirst();
        assertEquals("Math", entry.name());
        assertEquals("2.0.2", entry.latestVersion());
        assertEquals("https://dl.example.test/math-2.0.2.jar", entry.downloadUrl());
        assertEquals(List.of("%math_<expression>%"), entry.placeholders());
        assertEquals(2, entry.versions().size());
        assertTrue(entry.verified());
    }

    @Test
    void ignoresEntriesWithoutDownloadableVersions() {
        String json = "{\"Broken\":{\"latest_version\":\"1.0\",\"versions\":[]}}";
        assertTrue(new OfficialEcloudCatalogParser(new Gson()).parse(json, "https://example.test").isEmpty());
    }
}
