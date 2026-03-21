# Douyin POI Structure Notes (2026-03-21)

Source artifacts are under `artifacts/poi-structure-20260321/`.

## Capture Coverage

- `page00-home`: merchant header and first visible group-buy cards
- `page03`: `展开全部` becomes visible; `热门服务` starts below the group-buy list
- `page04-after-expand-all`: `展开全部` expands more group-buy cards into the current list
- `page05-resumed` to `page11`: continued group-buy card stream after expansion
- `page12`: visual boundary reached; top tab indicator moved to `推荐`

## Stable Structure

- The main page is `com.bytedance.locallife.page.poi.LifePoiActivity`.
- The primary scroll container in valid dumps is a `RecyclerView` under resource id `com.ss.android.ugc.aweme:id/gp3`.
- Merchant header content is present in `content-desc`, not just `text`.
- Verified header fields from `page00-home.xml`:
  - `郑州美莱医疗美容医院`
  - `关注`
  - `回头客1千+`
  - `无隐形消费`
- Group-buy cards are mostly `android.view.ViewGroup` nodes with merged `content-desc` payloads that include title, availability, original price, current price, sold count, and CTA text such as `领券抢购`.

## Important Behavior

- `展开全部` was visible in `page03.png`, but not reliably recoverable from XML string matching.
- After tapping `展开全部`, more group-buy cards appeared before the feed transitioned further down.
- By `page12.png`, the page had crossed into the `推荐` section even though repeated `uiautomator dump` calls failed with `ERROR: could not get idle state.`

## Implementation Implications

- Do not rely only on `text=` matches; use `content-desc`, bounds, and screenshot evidence together.
- Treat `page12.png` as the current visual boundary artifact for the end of group-buy extraction.
- Coupon popups and other overlays can invalidate XML capture and should be cleared before structure sampling.
