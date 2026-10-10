#!/usr/bin/env python3
"""Pull DiPlay reports from the Supabase logs bucket.

The key stays in local.properties and is never printed.

  scripts/read-supabase-logs.py              newest report, printed
  scripts/read-supabase-logs.py --list       names and sizes only
  scripts/read-supabase-logs.py --all        download every report, print the newest
  scripts/read-supabase-logs.py FILENAME     one object

Copies land in dist/supabase-logs/, which is gitignored with the rest of dist/.
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROPS = ROOT / "local.properties"
OUT = ROOT / "dist" / "supabase-logs"
BUCKET = "logs"
PAGE = 100

SETUP = """
Supabase 里还没有名为 logs 的存储位置，或者这把钥匙还不能读它。
在 SQL Editor 里执行（某一句提示策略已存在就跳过那一句）：

insert into storage.buckets (id, name, public, file_size_limit)
values ('logs', 'logs', false, 1048576)
on conflict (id) do update set public = false, file_size_limit = 1048576;

create policy "diplay test insert"
on storage.objects for insert to anon
with check (bucket_id = 'logs');

create policy "diplay test read"
on storage.objects for select to anon
using (bucket_id = 'logs');
""".strip()


def load_props():
    if not PROPS.is_file():
        sys.exit(f"找不到 {PROPS}")
    values = {}
    for line in PROPS.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    url = values.get("diplay.supabase.url", "").rstrip("/")
    key = values.get("diplay.supabase.key", "")
    if not url.startswith("https://") or "supabase.co" not in url or not key:
        sys.exit("local.properties 里没有 diplay.supabase.url / diplay.supabase.key")
    return url, key


def request(url, key, method, path, body=None, content_type=None):
    data = None
    headers = {"apikey": key}
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        headers["Content-Type"] = content_type or "application/json"
    req = urllib.request.Request(url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def fail(status, payload):
    text = payload.decode("utf-8", "replace").strip()
    if "NoSuchBucket" in text or "Bucket not found" in text:
        sys.exit(SETUP)
    sys.exit(f"HTTP {status}\n{text[:400]}")


def bucket_ready(url, key):
    status, payload = request(url, key, "GET", f"/storage/v1/bucket/{BUCKET}")
    if status == 200:
        return
    text = payload.decode("utf-8", "replace")
    # This key may upload and read objects while bucket metadata stays hidden.
    # A successful list means the bucket is there. Only a missing bucket is setup.
    if status == 400 and ("NoSuchBucket" in text or "Bucket not found" in text):
        list_status, list_payload = request(
            url,
            key,
            "POST",
            f"/storage/v1/object/list/{BUCKET}",
            {"prefix": "", "limit": 1, "offset": 0},
        )
        if list_status == 200:
            return
        list_text = list_payload.decode("utf-8", "replace")
        if "NoSuchBucket" in list_text or "Bucket not found" in list_text:
            sys.exit(SETUP)
        fail(list_status, list_payload)
    fail(status, payload)


def list_objects(url, key):
    found = []
    offset = 0
    while True:
        status, payload = request(
            url,
            key,
            "POST",
            f"/storage/v1/object/list/{BUCKET}",
            {
                "prefix": "",
                "limit": PAGE,
                "offset": offset,
                "sortBy": {"column": "created_at", "order": "desc"},
            },
        )
        if status != 200:
            fail(status, payload)
        page = json.loads(payload.decode())
        if not isinstance(page, list):
            sys.exit(f"列表返回的不是文件清单：{payload[:200]!r}")
        found.extend(item for item in page if item.get("name") and item.get("id"))
        if len(page) < PAGE:
            return found
        offset += PAGE


def download(url, key, name):
    if "/" in name or name in (".", "..") or not name:
        sys.exit(f"拒绝这个文件名：{name}")
    quoted = urllib.parse.quote(name)
    status, payload = request(url, key, "GET", f"/storage/v1/object/{BUCKET}/{quoted}")
    if status != 200:
        fail(status, payload)
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    path.write_bytes(payload)
    return path


def print_report(path):
    text = path.read_text("utf-8", "replace")
    print(f"===== {path.name}  {path.stat().st_size} 字节  {path} =====")
    print(text, end="" if text.endswith("\n") else "\n")


def main(argv):
    url, key = load_props()
    bucket_ready(url, key)
    mode = argv[1] if len(argv) > 1 else "--latest"
    if mode in ("-h", "--help"):
        print(__doc__.strip())
        return
    objects = list_objects(url, key)
    if mode == "--list":
        if not objects:
            print("logs 里还没有报告。在车机上点「生成并上传」之后再读。")
            return
        for item in objects:
            size = (item.get("metadata") or {}).get("size", "?")
            print(f"{item['name']}\t{size}\t{item.get('created_at', '')}")
        return
    if not objects and mode in ("--latest", "--all"):
        print("logs 里还没有报告。在车机上点「生成并上传」之后再读。")
        return
    if mode == "--all":
        paths = [download(url, key, item["name"]) for item in reversed(objects)]
        print_report(paths[-1])
        return
    name = objects[0]["name"] if mode == "--latest" else mode
    print_report(download(url, key, name))


if __name__ == "__main__":
    main(sys.argv)
