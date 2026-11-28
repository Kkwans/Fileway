#!/usr/bin/env python3
import argparse
import json
import urllib.request
from pathlib import Path

DEFAULT_URL = "https://raw.githubusercontent.com/jlshix/movielens-douban-dataset/master/spider.json"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the offline Douban rating index")
    parser.add_argument("output", type=Path)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--url", default=DEFAULT_URL)
    args = parser.parse_args()

    source = args.input.open("r", encoding="utf-8") if args.input else urllib.request.urlopen(args.url, timeout=120)
    count = 0
    with source, args.output.open("w", encoding="utf-8") as target:
        for line in source:
            if not line.strip():
                continue
            row = json.loads(line)
            rating = float(row.get("rating", {}).get("average") or 0)
            if rating <= 0 or rating > 10:
                continue
            year_text = str(row.get("year") or "")
            year = int(year_text[:4]) if len(year_text) >= 4 and year_text[:4].isdigit() else 0
            record = {
                "id": str(row.get("_id", "")),
                "title": row.get("title", ""),
                "year": year,
                "imdb": row.get("imdb", ""),
                "rating": rating,
                "votes": int(row.get("rating", {}).get("rating_people") or 0),
            }
            target.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
            count += 1
    print(f"wrote {count} ratings to {args.output}")


if __name__ == "__main__":
    main()
