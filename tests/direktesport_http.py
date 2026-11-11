"""Read-only smoke tests against the HTTP endpoints used by DS Play."""

import json
import unittest
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import Request, urlopen


BASE = "https://direktesport.dk"
FOLDER = "4bf2555a-ac21-49e7-90c4-b277f9a0271b"
CONFIG = "https://ljsp.lwcdn.com/web/public/native/config/7e165983-ccb1-453f-bc68-0d8ee7199e66/"


def get(url, label, *, sample=False):
    headers = {"Referer": BASE + "/", "User-Agent": "DS-Play-HTTP-integration-test"}
    if sample:
        headers["Range"] = "bytes=0-1023"
    try:
        with urlopen(Request(url, headers=headers), timeout=20) as response:
            body = response.read(1024 if sample else 2_000_001)
            final_url = response.url
    except HTTPError as error:
        error.close()
        raise AssertionError(f"{label}: HTTP {error.code}") from None
    except (URLError, TimeoutError, OSError):
        # Signed stream URLs must not appear in CI output.
        raise AssertionError(f"{label}: HTTP connection failed") from None
    if not body or (not sample and len(body) > 2_000_000):
        raise AssertionError(f"{label}: empty or oversized response")
    return body, final_url


def get_json(url, label):
    body, _ = get(url, label)
    try:
        return json.loads(body)
    except (ValueError, UnicodeError):
        raise AssertionError(f"{label}: invalid JSON") from None


def feed(kind, page=0):
    params = {"folderId": FOLDER, "page": page, "pageSize": 20}
    if kind == "livestreams":
        params.update(showLive=1, daysForward=0)
    data = get_json(f"{BASE}/flowplayer/api/{kind}?{urlencode(params)}", kind)
    if not isinstance(data, dict) or not isinstance(data.get("videos"), list):
        raise AssertionError(f"{kind}: missing videos array")
    for video in data["videos"]:
        if not isinstance(video, dict) or not video.get("id"):
            raise AssertionError(f"{kind}: video without an id")
    return data


def check_stream(video_id):
    config = get_json(CONFIG + video_id, "video configuration")
    sources = config.get("src", [])
    source = next((urljoin("https:", value) for value in sources
                   if isinstance(value, str)
                   and urlsplit(urljoin("https:", value)).scheme == "https"
                   and urlsplit(value).path.endswith(".m3u8")), None)
    if source is None:
        raise AssertionError("video configuration: no HTTPS HLS source")
    for _ in range(5):
        body, source = get(source, "HLS playlist")
        try:
            lines = body.decode("utf-8-sig").strip().splitlines()
        except UnicodeError:
            raise AssertionError("HLS playlist: invalid text") from None
        if not lines or lines[0] != "#EXTM3U":
            raise AssertionError("HLS playlist: missing EXTM3U header")
        entry = next((line.strip() for line in lines
                      if line.strip() and not line.lstrip().startswith("#")), None)
        if entry is None:
            raise AssertionError("HLS playlist: no variant or media segment")
        target = urljoin(source, entry)
        if any(line.startswith("#EXT-X-STREAM-INF:") for line in lines):
            source = target
            continue
        if not any(line.startswith("#EXTINF:") for line in lines):
            raise AssertionError("HLS playlist: no media durations")
        get(target, "HLS media segment", sample=True)
        return
    raise AssertionError("HLS playlist: too many nested variant playlists")


class DirekteSportHttpTest(unittest.TestCase):
    def test_feeds(self):
        for kind, expected_type in (("livestreams", "livestream"),
                                    ("videos-on-demand", "video-on-demand")):
            with self.subTest(feed=kind):
                data = feed(kind)
                self.assertIsInstance(data.get("totalPages"), int)
                if kind == "videos-on-demand":
                    self.assertTrue(data["videos"], "VOD feed is empty")
                for video in data["videos"]:
                    self.assertEqual(expected_type, video.get("type"))

    def test_video_on_demand(self):
        videos = feed("videos-on-demand")["videos"]
        self.assertTrue(videos, "VOD feed is empty")
        check_stream(videos[0]["id"])

    def test_live_stream(self):
        page = 0
        while True:
            data = feed("livestreams", page)
            live = next((video for video in data["videos"]
                         if video.get("state") == "live"), None)
            if live is not None:
                check_stream(live["id"])
                return
            page += 1
            if page >= data.get("totalPages", 0):
                self.skipTest("No current livestream; live feed was checked")


if __name__ == "__main__":
    unittest.main(verbosity=2)
