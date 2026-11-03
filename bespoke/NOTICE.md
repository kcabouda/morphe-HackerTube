# Bespoke feed data

`catalog.json` is derived from the **HackerTube data export** dated 2026-10-31
(<https://hackertube.org/data>), made available under the
**Open Data Commons Attribution License (ODC-By) v1.0**:
<https://opendatacommons.org/licenses/by/1-0/>.

What was changed: kept only videos with at least one Hacker News recommender and
dropped `shelf_life == "news"` videos; kept a subset of fields (id, title, channel,
topics, score, recommender and strong-recommendation counts, first year, content type, level, "why HN" summary). See
`build_catalog.py`.

Attribution:

- HackerTube (<https://hackertube.org>, Andrej Karpathy): video selection, comment analysis.
- open-index/hacker-news (<https://huggingface.co/datasets/open-index/hacker-news>).
- Hacker News data is provided by Y Combinator. Comments belong to their authors;
  video titles and channel names belong to their creators.

The upstream export's own documentation is kept verbatim in `UPSTREAM_DATA.md`.
The export was obtained from the copy bundled in github.com/cq0206/HackerTube
(`extension/data/videos.jsonl`), because hackertube.org's download link was not
working on 2026-11-02.
