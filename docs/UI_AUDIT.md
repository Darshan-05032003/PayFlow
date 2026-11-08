# PayFlow UI audit

Audited 2026-11-09 from the code at `a1d2301`. "Before" screenshots are in [`screenshots/before/`](screenshots/before/), taken on the Pixel 7 emulator.

## Whole-app findings
| Area | Finding | Evidence |
|---|---|---|
| Motion | Effectively none. The whole UI has 2 `tween()` calls and no springs, `AnimatedContent`, `updateTransition` or shared elements. State changes snap. | Code scan of `ui/` |
| Transitions | Screens use Navigation's default crossfade; no transition is specified. | 0 `enterTransition`/`exitTransition` |
| Touch feedback | Default ripple only, and no haptics anywhere, including on a payment. | 0 haptic call sites |
| Type | System Roboto everywhere. The "branded" theme is colour only. | No `res/font` |
| Spacing | 279 literal `.dp` values in screens rather than tokens. | Code scan |
| Default components | `CircularProgressIndicator` spinners in 3 files; stock `OutlinedTextField`, `FilterChip` and `Card` make it read as Material template. | Code scan, screenshots |
| Launch | No Splash Screen API hand-off. | Not in `build.gradle.kts` |
| Testing | No screenshot tests, so visual regressions aren't caught. | Not in `build.gradle.kts` |
| Accessibility | Content descriptions are good. Missing: reduce-motion handling, verified 1.3× font scale. | Code, tests |
| Navigation | No bottom navigation; everything hangs off Home's tile grid. | `PayFlowNavHost` |

## Screen by screen
| Screen | Generic or amateur | Missing | Priority |
|---|---|---|---|
| **Home** | Eight identical tiles with no hierarchy. Two "Pay" actions (tile and FAB). Teal gradient card is the stock fintech look. FAB covers "View all". | Tap-to-hide amount, dock-on-scroll, Scan & Pay as hero, staggered feed, designed pull-to-refresh, skeleton loading | High |
| **Pay / amount** | A form. The amount appears twice (large display and a text field), and it's typed with the system keyboard. The disclaimer card takes a quarter of the screen. | Keypad, quick amounts, animated verify ✓, swipe-to-pay (a plain button sends the payment) | High |
| **UPI app sheet** | Works, but a plain list. | Spring entry, tactile rows | Medium |
| **Scan** | Static brackets; hint pill and gallery button float without structure. | Breathing brackets, scan beam, lock-on to QR bounds, payee morph, designed denied state | High |
| **Result / receipt** | The most important moment is a static text card. | Drawn checkmark, per-outcome animation, printing receipt, long-poll copy evolution, confirm/reject haptics | High |
| **History** | Stock search field and chips; "Refunded" chip clipped at the edge; flat rows with no date grouping. | Sticky date headers, search morph, swipe actions, shared-element detail | High |
| **Payment detail** | Plain stacked cards. | Shared-element entry from the row | Medium |
| **Requests** | Static cards; expiry is only a line of small text. Approve and Decline look almost equally weighted. | Countdown ring, heads-up entry, swipe to approve | Medium |
| **AutoPay** | Decent empty state, plain list and detail. | Card stack, pause/resume morph, debit timeline | Medium |
| **Security** | Long form. A big red "0 of 4" card makes it alarming rather than guiding; controls compete for attention. | Sequential animated checks, designed tamper warning, haptics/sound/motion/theme settings | Medium |
| **Lock / PIN** | Clean but default circles; dots don't animate. | Spring-fill dots, wrong-PIN shake and pulse, circular reveal on unlock | High |
| **Receive** | Good structure; plain. | Reveal for the QR, tactile buttons | Low |
| **Checkout + CheckoutKit sheet** | Store card repeats the Home gradient. The sheet is CheckoutKit's own styling and can't be themed from here without API changes. | Entry/exit motion from PayFlow's DS | Medium |
| **Dark theme** | Teal on near-black with grey surfaces; not designed as its own experience. | True layered dark | High |

## Not built yet, required by the brief
- Scratch-card demo reward (labelled "demo").
- Bottom navigation.
- In-app settings for haptics, sound, reduce motion and theme.
- Debug-only design catalog.
