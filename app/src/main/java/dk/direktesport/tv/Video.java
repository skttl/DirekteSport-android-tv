package dk.direktesport.tv;

import org.json.JSONObject;

final class Video {
    final String id;
    final String type;
    final String title;
    final String category;
    final String state;
    final boolean paid;
    final long startsAt;

    Video(JSONObject json) {
        id = json.optString("id");
        type = json.optString("type");
        title = json.optString("headline", "Uden titel");
        category = json.isNull("categoryName") ? "" : json.optString("categoryName", "");
        state = json.optString("state", "");
        paid = json.optBoolean("paid");
        startsAt = json.optLong("broadcastStart");
    }

    String pageUrl() {
        return "https://direktesport.dk/video/live-sport/" + type + "/" + id;
    }

    boolean isLive() {
        return "livestream".equals(type);
    }
}
