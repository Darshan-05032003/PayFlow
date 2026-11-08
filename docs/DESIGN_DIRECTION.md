# PayFlow design directions

Brief: premium, calm, powerful money. Deep graphite with layered depth, one signature accent, subtle metallic sheen and grain, big confident numerals, tactile press physics. Light theme also first-class. Must not look like NeoPOP or like SplitSmart.

Open [`design-preview.html`](design-preview.html) in a browser to see both directions, press the buttons and roll the amount. It works offline.

**The ₹ glyph decided the fonts.** I rendered ₹ in 31 OFL fonts. Instrument Serif, DM Serif Display, Bodoni Moda, Young Serif, Sora, Hanken Grotesk, Instrument Sans, Outfit, Urbanist, Onest, JetBrains Mono and Fredoka all lack it, so Android would silently substitute a system ₹ next to the custom digits. Every font proposed below has it.

**Honest hero number.** A UPI app can't read a bank balance, so PayFlow has no real "balance" to display. The hero odometer shows **Sent this month**, which is real data from Room; sandbox mode can also show a clearly labelled demo balance. Tap-to-hide works on whichever is shown.

## A. Aurum Noir ⭐ recommended
**Mood:** A private bank after hours. Layered graphite, one champagne accent that catches light like brushed metal, fine film grain, and large serif numerals that make every rupee feel considered.

**Why I recommend it:** it matches the brief almost word for word, and serif numerals set it apart from nearly every Indian fintech app. The risk with gold is that it turns gaudy, so it's reserved for one accent and the sheen.

**Palette**
| Token | Light | Dark |
|---|---|---|
| bg | `#F6F3EC` | `#0B0C0F` |
| surface | `#FFFFFF` | `#15171B` |
| raised | `#EEEAE0` | `#1F2228` |
| text | `#16171B` | `#F3F0E9` |
| textSecondary | `#5A574F` | `#AAA69C` |
| primary / onPrimary | `#17181C` / `#EFD9AE` | `#E2C48D` / `#1A1408` |
| accent | `#7A581C` | `#E2C48D` |
| success | `#1B7A4C` | `#6ED3A1` |
| pending | `#855B00` | `#E9B95F` |
| failed | `#B3362C` | `#F2948A` |
| refund (info) | `#2F5FA8` | `#93B9F2` |

**Type:** Newsreader (display and numerals; serif, tabular digits, optical sizes, has ₹) + Geist (all UI text). The minus sign always comes from Geist, because Newsreader's sits too low and reads like an underscore.

**Scale:** hero 44/48, display 30/36, title 20/26, body 15/22, label 12/16.

**Shape and texture:** radii 12 / 20 / 28. Depth from layered tones and a 1dp inner hairline, not drop shadows. Metallic sheen on the hero card and primary button; 3% film grain on dark surfaces.

**Motion:** calm and weighted. Springs (stiffness/damping): Snappy 1200/1.0, Bouncy 420/0.72, Gentle 160/0.95. Press 90ms, small 240ms, screen 420ms, stagger 40ms.

**5 signature moments**
1. Sheen sweep across the hero amount when it updates.
2. Swipe-to-pay with haptic ticks along a brushed-metal track.
3. Checkmark drawn in champagne, then a receipt prints with a perforated edge.
4. Scan & Pay ring breathes, then container-transforms into the viewfinder.
5. Biometric success opens the app with a circular reveal.

### Hero wireframes
```
HOME                        AMOUNT + PAYEE              SUCCESS + RECEIPT
+------------------------+  +------------------------+  +------------------------+
| PayFlow        (o) [=] |  | <  Pay Asha            |  |                        |
|                        |  |   (A) asha@okicici  v  |  |        ( check )       |
| Sent this month    eye |  |                        |  |     drawn, champagne   |
| ₹1,23,456              |  |      ₹ 1,250           |  |        ₹1,250          |
| 3 paid · 1 pending     |  |    (auto-shrink)       |  |     Paid to Asha       |
| ~~~~~~ sheen ~~~~~~~~  |  | [₹100][₹500][₹1000]    |  | ~ ~ ~ ~ ~ ~ ~ ~ ~ ~ ~  |
|                        |  |  Add a note...         |  | | Reference PF0MV..  | |
|   (( SCAN & PAY ))     |  |  1    2    3           |  | | Handled by GPay    | |
|     breathing ring     |  |  4    5    6           |  | | 30 Oct, 10:38 PM   | |
|                        |  |  7    8    9           |  | | Reported by GPay.  | |
| Pay  Receive  Req  Auto|  |  .    0    <-          |  | | Not verified.      | |
|                        |  |                        |  | ~ perforated edge ~ ~  |
| (T) (R) (A) (C) (P) -> |  | [==> Swipe to pay    ] |  |                        |
| Today                  |  |  ticks · snap-back     |  | [Share]  [Pay again]   |
| (R) Rahul    - ₹300    |  |                        |  |        [ Done ]        |
| [Home][Scan][Hist][Me] |  +------------------------+  +------------------------+
+------------------------+
```

### Contrast (WCAG AA, 4.5:1)
| Mode | Pair | Ratio | AA |
|---|---|---:|---|
| light | `text` on `bg` | 16.16:1 | ✓ |
| light | `text` on `surface` | 17.91:1 | ✓ |
| light | `text` on `raised` | 14.91:1 | ✓ |
| light | `textSecondary` on `bg` | 6.51:1 | ✓ |
| light | `textSecondary` on `surface` | 7.21:1 | ✓ |
| light | `textSecondary` on `raised` | 6.01:1 | ✓ |
| light | `onPrimary` on `primary` | 12.84:1 | ✓ |
| light | `accent` on `bg` | 5.85:1 | ✓ |
| light | `accent` on `surface` | 6.48:1 | ✓ |
| light | `success` on `surface` | 5.34:1 | ✓ |
| light | `pending` on `surface` | 6.01:1 | ✓ |
| light | `failed` on `surface` | 6.03:1 | ✓ |
| light | `info` on `surface` | 6.32:1 | ✓ |
| light | `success` on `bg` | 4.82:1 | ✓ |
| light | `pending` on `bg` | 5.42:1 | ✓ |
| light | `failed` on `bg` | 5.44:1 | ✓ |
| light | `info` on `bg` | 5.70:1 | ✓ |
| dark | `text` on `bg` | 17.18:1 | ✓ |
| dark | `text` on `surface` | 15.77:1 | ✓ |
| dark | `text` on `raised` | 14.00:1 | ✓ |
| dark | `textSecondary` on `bg` | 8.05:1 | ✓ |
| dark | `textSecondary` on `surface` | 7.39:1 | ✓ |
| dark | `textSecondary` on `raised` | 6.56:1 | ✓ |
| dark | `onPrimary` on `primary` | 10.91:1 | ✓ |
| dark | `accent` on `bg` | 11.66:1 | ✓ |
| dark | `accent` on `surface` | 10.69:1 | ✓ |
| dark | `success` on `surface` | 9.82:1 | ✓ |
| dark | `pending` on `surface` | 9.89:1 | ✓ |
| dark | `failed` on `surface` | 8.01:1 | ✓ |
| dark | `info` on `surface` | 8.94:1 | ✓ |
| dark | `success` on `bg` | 10.71:1 | ✓ |
| dark | `pending` on `bg` | 10.78:1 | ✓ |
| dark | `failed` on `bg` | 8.73:1 | ✓ |
| dark | `info` on `bg` | 9.74:1 | ✓ |

## B. Ion Glass
**Mood:** A precise instrument. Cool blue-black, frosted glass panels floating over a slow aurora, and one electric ion-lime accent. Sharper and faster than A, more tech than private bank.

**Palette**
| Token | Light | Dark |
|---|---|---|
| bg | `#F2F5F8` | `#080B12` |
| surface | `#FFFFFF` | `#111623` |
| raised | `#E6EBF1` | `#1B2233` |
| text | `#0E1420` | `#EEF2F8` |
| textSecondary | `#505E77` | `#9AA6BA` |
| primary / onPrimary | `#0E1420` / `#C9F24B` | `#C9F24B` / `#10160A` |
| accent | `#4A6300` | `#C9F24B` |
| success | `#12784F` | `#5FDCA6` |
| pending | `#855B00` | `#EDBE66` |
| failed | `#B3362C` | `#F4938A` |
| refund (info) | `#2A5CB0` | `#8FB8F7` |

**Type:** Space Grotesk (display and numerals, has ₹) + DM Sans (UI, has ₹). Scale as in A, hero 40/44.

**Shape and texture:** radii 10 / 16 / 24. Depth from glass blur (Haze, API 31+, dim scrim below) and 1dp light borders. Animated gradient noise behind the hero card; no grain.

**Motion:** crisp and quick. Snappy 1500/1.0, Bouncy 520/0.62, Gentle 220/0.9. Press 90ms, small 200ms, screen 360ms, stagger 30ms.

**5 signature moments**
1. Aurora drifts behind the hero card, responding to scroll.
2. Swipe-to-pay trail glows ion-lime and snaps with a spring.
3. Scan beam shader with brackets that lock onto the QR.
4. Success ring pulses outward through the glass layers.
5. Glass bottom bar with a sliding pill and morphing icons.

### Hero wireframes
```
HOME                        AMOUNT + PAYEE              SUCCESS + RECEIPT
+------------------------+  +------------------------+  +------------------------+
| ░░ aurora ░░░░░░░░░░░░ |  | <  Pay Asha            |  | ░░ aurora ░░░░░░░░░░░░ |
| ░ Sent this month  eye |  | ┌ glass ─────────────┐ |  |     ))) (check) (((    |
| ░ ₹1,23,456            |  | │ (A) asha@okicici v │ |  |     pulse through      |
| ░░░░░░░░░░░░░░░░░░░░░░ |  | └────────────────────┘ |  |     the glass layers   |
| ┌ glass ─────────────┐ |  |      ₹ 1,250           |  |        ₹1,250          |
| │ [ SCAN & PAY ]  >> │ |  | [₹100][₹500][₹1000]    |  | ┌ glass receipt ─────┐ |
| └────────────────────┘ |  |  Add a note...         |  | │ Reference PF0MV..  │ |
| Pay  Receive  Req  Auto|  |  1    2    3           |  | │ Handled by GPay    │ |
| (T) (R) (A) (C) (P) -> |  |  4    5    6           |  | │ Reported by GPay.  │ |
| Today                  |  |  7    8    9           |  | │ Not verified.      │ |
| (R) Rahul    - ₹300    |  |  .    0    <-          |  | └────────────────────┘ |
| ┌ glass bottom bar ──┐ |  | [==> Swipe to pay    ] |  | [Share]  [Pay again]   |
| │ (pill)Home Scan Me │ |  |   glowing trail        |  |        [ Done ]        |
| └────────────────────┘ |  +------------------------+  +------------------------+
+------------------------+
```

### Contrast (WCAG AA, 4.5:1)
| Mode | Pair | Ratio | AA |
|---|---|---:|---|
| light | `text` on `bg` | 16.84:1 | ✓ |
| light | `text` on `surface` | 18.43:1 | ✓ |
| light | `text` on `raised` | 15.37:1 | ✓ |
| light | `textSecondary` on `bg` | 5.98:1 | ✓ |
| light | `textSecondary` on `surface` | 6.54:1 | ✓ |
| light | `textSecondary` on `raised` | 5.46:1 | ✓ |
| light | `onPrimary` on `primary` | 14.29:1 | ✓ |
| light | `accent` on `bg` | 6.24:1 | ✓ |
| light | `accent` on `surface` | 6.83:1 | ✓ |
| light | `success` on `surface` | 5.49:1 | ✓ |
| light | `pending` on `surface` | 6.01:1 | ✓ |
| light | `failed` on `surface` | 6.03:1 | ✓ |
| light | `info` on `surface` | 6.45:1 | ✓ |
| light | `success` on `bg` | 5.02:1 | ✓ |
| light | `pending` on `bg` | 5.49:1 | ✓ |
| light | `failed` on `bg` | 5.51:1 | ✓ |
| light | `info` on `bg` | 5.89:1 | ✓ |
| dark | `text` on `bg` | 17.52:1 | ✓ |
| dark | `text` on `surface` | 16.07:1 | ✓ |
| dark | `text` on `raised` | 14.13:1 | ✓ |
| dark | `textSecondary` on `bg` | 8.00:1 | ✓ |
| dark | `textSecondary` on `surface` | 7.34:1 | ✓ |
| dark | `textSecondary` on `raised` | 6.45:1 | ✓ |
| dark | `onPrimary` on `primary` | 14.27:1 | ✓ |
| dark | `accent` on `bg` | 15.26:1 | ✓ |
| dark | `accent` on `surface` | 14.00:1 | ✓ |
| dark | `success` on `surface` | 10.56:1 | ✓ |
| dark | `pending` on `surface` | 10.46:1 | ✓ |
| dark | `failed` on `surface` | 8.07:1 | ✓ |
| dark | `info` on `surface` | 8.92:1 | ✓ |
| dark | `success` on `bg` | 11.50:1 | ✓ |
| dark | `pending` on `bg` | 11.40:1 | ✓ |
| dark | `failed` on `bg` | 8.80:1 | ✓ |
| dark | `info` on `bg` | 9.72:1 | ✓ |
