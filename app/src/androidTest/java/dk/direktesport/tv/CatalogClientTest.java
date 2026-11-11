package dk.direktesport.tv;

import junit.framework.TestCase;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Integration checks against DirekteSport's public catalog, run on Android. */
public final class CatalogClientTest extends TestCase {
    public void testCategoriesMatchEveryFavoriteSport() throws Exception {
        List<CatalogClient.Category> categories = CatalogClient.categories();
        assertEquals("", categories.get(0).id);
        Set<String> ids = new HashSet<>();
        for (CatalogClient.Category sport : CatalogClient.siteSports()) {
            CatalogClient.Category match = null;
            for (CatalogClient.Category category : categories) {
                if (sport.name.equalsIgnoreCase(category.name)) match = category;
            }
            assertNotNull(sport.name, match);
            assertTrue(match.id.matches("[0-9a-f-]{36}"));
            assertTrue("Duplicate category for " + sport.name, ids.add(match.id));
            for (Video video : CatalogClient.liveToday(match.id, 0).videos) {
                assertEquals(sport.name, video.category);
                assertTrue(video.isLive());
            }
        }
    }

    public void testLiveAndArchivePagination() throws Exception {
        for (String type : new String[]{"livestream", "video-on-demand"}) {
            CatalogClient.Page first = CatalogClient.videos(type, "", "", 0);
            assertFalse(type, first.videos.isEmpty());
            assertTrue(type, first.hasMore);
            CatalogClient.Page second = CatalogClient.videos(type, "", "", 1);
            assertFalse(type, second.videos.isEmpty());
            Set<String> ids = new HashSet<>();
            for (Video video : first.videos) {
                assertEquals(type, video.type);
                ids.add(video.id);
            }
            for (Video video : second.videos) assertFalse(type, ids.contains(video.id));
        }
    }

    public void testSearchBothFeeds() throws Exception {
        for (String type : new String[]{"livestream", "video-on-demand"}) {
            Video known = CatalogClient.videos(type, "", "", 0).videos.get(0);
            CatalogClient.Page page = CatalogClient.videos(type, "", known.title, 0);
            assertFalse(type, page.videos.isEmpty());
            boolean found = false;
            for (Video video : page.videos) if (known.id.equals(video.id)) found = true;
            assertTrue("Search must find " + known.title, found);
            CatalogClient.Page missing = CatalogClient.videos(type, "", "dsplay-no-such-video-739182", 0);
            assertTrue(type, missing.videos.isEmpty());
            assertFalse(type, missing.hasMore);
        }
    }
}
