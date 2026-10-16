# Getting real multi-hop

There is no software switch to forbid a direct link: `NearbyTransport` connects to anything in radio range (P2P_CLUSTER, `MAX_LINKS = 4`). Relaying only happens when phones physically can't reach each other, or when a phone is out of link slots.

Ways to get it, cheapest first:

1. **Distance / walls.** Put phone A and phone C out of each other's range with B in between (different rooms, a floor apart). Expect on A: `Phát hiện nút "C" … cách 2 bước`.
2. **More than 5 phones together.** Each keeps at most 4 links, so some pairs must relay.
3. **Leave and rejoin.** Start A–B–C together, then carry C away from A while staying near B; A should log `Mất kết nối` for C's direct link and keep (or re-learn) a 2-hop route via B.

Evidence that relaying works end-to-end: a traceroute from A to C in the UI ends with `Dò đường xong: 2 bước` (or more), and a DM or board post from A shows up on C.

If every node keeps reporting `cách 1 bước`, multi-hop is unverified — say so rather than inferring it from the `FakeNetwork` tests.
