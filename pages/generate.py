"""Build the GitHub Pages download list from archived APK releases."""

import html
import json
import os
import re
import urllib.request
from pathlib import Path

repo = os.environ["GITHUB_REPOSITORY"]
token = os.environ["GH_TOKEN"]
url = f"https://api.github.com/repos/{repo}/releases?per_page=100"
entries = []

while url:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "DirekteSport-TV-pages-builder",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request) as response:
        releases = json.load(response)
        link_header = response.headers.get("Link", "")
    for release in releases:
        for asset in release["assets"]:
            name = asset["name"]
            if re.fullmatch(r"DirekteSport-TV-[0-9a-f]{12}\.apk", name):
                entries.append(
                    {
                        "name": name,
                        "url": asset["browser_download_url"],
                        "date": release["published_at"][:10],
                        "size": asset["size"],
                        "build": release["tag_name"],
                    }
                )
    match = re.search(r'<([^>]+)>; rel="next"', link_header)
    url = match.group(1) if match else None

entries.sort(key=lambda entry: int(entry["build"].removeprefix("build-")), reverse=True)
if not entries:
    raise RuntimeError("No archived APKs found")

rows = "\n".join(
    f'<li><a href="{html.escape(entry["url"], quote=True)}">'
    f'{html.escape(entry["name"])}</a> '
    f'<span>Build {html.escape(entry["build"].removeprefix("build-"))}'
    f' · {entry["date"]} · {entry["size"] / 1048576:.1f} MB</span></li>'
    for entry in entries
)
template = Path("pages/index.html").read_text(encoding="utf-8")
page = template.replace("__LATEST_APK__", html.escape(entries[0]["url"], quote=True))
page = page.replace("__APK_LIST__", rows)
Path("site-build/index.html").write_text(page, encoding="utf-8")
Path("site-build/history.json").write_text(
    json.dumps(entries, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
)
