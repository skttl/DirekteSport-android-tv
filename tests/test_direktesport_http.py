"""Offline checks for the HTTP smoke test, without contacting DirekteSport."""

import json
import unittest
from unittest.mock import call, patch
from urllib.error import HTTPError

import direktesport_http as smoke


class SmokeTestLogicTest(unittest.TestCase):
    @patch.object(smoke, "get")
    def test_master_playlist_resolves_redirect_and_relative_segment(self, get):
        get.side_effect = [
            (json.dumps({"src": ["//cdn.example/master.m3u8"]}).encode(), "unused"),
            (b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow/index.m3u8\n",
             "https://cdn.example/redirect/master.m3u8"),
            (b"#EXTM3U\n#EXTINF:6,\nsegment.ts\n",
             "https://cdn.example/redirect/low/index.m3u8"),
            (b"video bytes", "unused"),
        ]
        smoke.check_stream("example-id")
        self.assertEqual("https://cdn.example/master.m3u8", get.call_args_list[1].args[0])
        self.assertEqual("https://cdn.example/redirect/low/index.m3u8",
                         get.call_args_list[2].args[0])
        get.assert_called_with("https://cdn.example/redirect/low/segment.ts",
                               "HLS media segment", sample=True)

    @patch.object(smoke, "get")
    def test_html_response_is_not_accepted_as_playlist(self, get):
        get.side_effect = [
            (b'{"src":["https://cdn.example/video.m3u8"]}', "unused"),
            (b"<html>Service unavailable</html>", "unused"),
        ]
        with self.assertRaisesRegex(AssertionError, "missing EXTM3U"):
            smoke.check_stream("example-id")

    @patch.object(smoke, "urlopen")
    def test_http_failure_does_not_expose_signed_url(self, urlopen):
        url = "https://cdn.example/video.m3u8?signature=private-test-value"
        urlopen.side_effect = HTTPError(url, 403, "Forbidden", {}, None)
        with self.assertRaises(AssertionError) as result:
            smoke.get(url, "HLS playlist")
        self.assertEqual("HLS playlist: HTTP 403", str(result.exception))

    @patch.object(smoke, "urlopen")
    def test_empty_media_response_fails(self, urlopen):
        urlopen.return_value.__enter__.return_value.read.return_value = b""
        with self.assertRaisesRegex(AssertionError, "empty or oversized"):
            smoke.get("https://cdn.example/segment.ts", "HLS media segment", sample=True)

    @patch.object(smoke, "feed")
    @patch.object(smoke, "check_stream")
    def test_no_active_live_stream_is_skipped(self, check_stream, feed):
        feed.return_value = {"totalPages": 1, "videos": [{"id": "future", "state": "upcoming"}]}
        test = smoke.DirekteSportHttpTest("test_live_stream")
        with self.assertRaises(unittest.SkipTest):
            test.test_live_stream()
        check_stream.assert_not_called()

    @patch.object(smoke, "feed")
    @patch.object(smoke, "check_stream")
    def test_active_paid_live_stream_on_next_page_is_checked(self, check_stream, feed):
        feed.side_effect = [
            {"totalPages": 2, "videos": [{"id": "future", "state": "upcoming"}]},
            {"totalPages": 2, "videos": [{"id": "active", "state": "live", "paid": True}]},
        ]
        smoke.DirekteSportHttpTest("test_live_stream").test_live_stream()
        check_stream.assert_called_once_with("active")
        self.assertEqual([call("livestreams", 0), call("livestreams", 1)], feed.call_args_list)

    @patch.object(smoke, "feed")
    @patch.object(smoke, "check_stream")
    def test_latest_paid_vod_is_checked_without_login(self, check_stream, feed):
        feed.return_value = {"videos": [{"id": "latest", "paid": True}]}
        smoke.DirekteSportHttpTest("test_video_on_demand").test_video_on_demand()
        check_stream.assert_called_once_with("latest")


if __name__ == "__main__":
    unittest.main(verbosity=2)
