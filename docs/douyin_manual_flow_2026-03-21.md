# Douyin Manual Flow Notes (2026-03-21)

Artifacts are under `artifacts/manual-flow-20260321/`.
Second successful replay with region-oriented taps is under `artifacts/manual-flow-20260321-v3/`.

## Manual Flow Replay

1. Cold launch Douyin from a clean state.
2. Home feed screenshot: `step00-home-feed.png`
   - This is the normal video feed, not the life-service group-buy page.
   - Top channel bar contains `经验 / 关注 / 郑州 / 直播 / 商城 / 团购 / 推荐`.
3. Tap preset group-buy coordinate `1016,216`.
4. Group-buy home screenshot: `step01-after-preset-groupbuy-tap.png`
   - Tap succeeded.
   - This proves the current preset tap is valid.
   - Previous failures were caused by false “already selected” detection before the tap.
5. Tap search-entry coordinate `383,373`.
6. Dedicated search page screenshot: `step02-search-entry-opened.png`
   - Foreground activity became `SearchResultWithAssignUiModePoiLifeActivity`.
   - This is the correct life-service search page, not the generic top-right feed search.
7. Submit the search with keyboard enter.
8. Search results screenshot: `step03-search-result.png`
   - Target merchant is in the first result slot.
   - The correct entry target is the upper half of the merchant card, not the product carousel below it.
9. Tap merchant upper-half coordinate `700,500`.
10. Merchant landing screenshot: `step04-after-merchant-tap.png`
    - Foreground activity became `LifePoiActivity`.
    - Landing page is the merchant homepage with `团购` selected.

## Merchant Page Findings

- `step05-merchant-scroll-1.png`: normal group-buy list.
- `step06-merchant-scroll.png`: end of current visible group-buy list shows `展开更多`; `热门服务` is already visible below.
- `step07-merchant-scroll.png`: after continuing to scroll without expanding, the sticky tab shifts into `服务`.
- `step08-merchant-scroll.png`: continuing further shifts into `评价`, then `发现同城变美好店` and recommendation content appear.

## Conclusions For Code

- Do not skip the group-buy tap unless group-buy is explicitly confirmed as selected.
- Current preset group-buy tap and search-entry tap are both usable.
- Merchant result opening by tapping the card upper half is valid and lands in `LifePoiActivity`.
- Once `展开更多` or `展开全部` appears, expansion must happen before the next downward scroll.
- If expansion is skipped, the page naturally drifts from `团购` into `服务` -> `评价` -> recommendation/discovery sections, which ends collection.

## Successful Replay V3

Artifacts: `artifacts/manual-flow-20260321-v3/`

1. Cold-launch Douyin and wait on the normal home feed.
2. Tap the home `团购` region instead of treating one pixel as the rule.
   - Verified usable region: `x=900..1200, y=150..300`
   - Successful tap sample: `1050,220`
   - Evidence: `step01-groupbuy-home.png`
3. Tap the dedicated group-buy search-entry region, not the generic feed search.
   - Verified usable region: `x=80..500, y=320..420`
   - Successful tap sample: `290,370`
   - Evidence: `step02-search-page.png`, `step03-search-result.png`
4. On the search-result page, open the merchant by tapping the upper half of the first merchant card.
   - Verified upper-half region from XML: `x=263..1384, y=487..708`
   - Successful tap sample: `700,500`
   - Foreground activity becomes `LifePoiActivity`
   - Evidence: `step04-merchant-home.png`
5. On the merchant page, keep `团购` selected and expand before every further downward scroll.
6. `展开更多` succeeds when the page is slightly settled upward and the tap lands just above the visible text.
   - Successful tap sample in this replay: `720,1360`
   - Evidence: `step07-user-adjusted-before-expand.png`, `step08-after-user-guided-expand.png`
7. Continue scrolling inside the expanded group-buy list until `展开全部` appears.
8. `展开全部` also succeeds by tapping near the top of the visible text row, not by aiming at the service card below.
   - Successful tap sample in this replay: `720,1090`
   - Evidence: `step10-after-scroll-post-expand-more.png`, `step11b-after-expand-all-tap.png`
9. After `展开全部`, repeated downward scrolling stays in the full group-buy list.
10. The tail signal is `收起`; this replay reached it without drifting into recommendation.
   - Evidence: `step15-after-scroll-post-expand-all.png`

## Updated Rules

- Prefer bounds or region-based taps over hard-coded single points.
- For search results, only the merchant-card upper half is a valid open-store target.
- For merchant pages, `展开更多` and `展开全部` must be checked before the next scroll every time.
- `收起` is the reliable group-buy tail marker after a full expansion pass.
- For any new list page or any new stop boundary, first do a real-device manual-assisted replay, then derive clickable regions and boundary rules from screenshots plus XML before changing code.
