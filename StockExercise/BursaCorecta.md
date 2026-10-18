# BursaCorecta: per-offer locking

This document explains how the correct stock exchange simulation ([BursaCorecta.java](BursaCorecta.java)) is synchronized. It covers the data structures, the locks, the lock order that rules out deadlock, and a step-by-step walk through placing an order and matching it with another.

---

## 1. The problem with the previous version

Before, every `Stock` had **one** `ReentrantLock`. `place`, `modify`, `cancel`, `bestBidAsk` and the whole matching loop, including moving money and shares, ran under it. While one trade on `ACME` was being settled, **no other offer on `ACME` could be touched**, even offers that had nothing to do with that trade.

The spec says:

> Requests and offers can be modified at any time if there is no ongoing transaction for the relevant shares.

The new version reads "relevant" as **the offers taking part in the transaction**. A trade locks only:

- the **two offers** being matched (one buy, one sell), and
- the **two traders' wallets** (buyer and seller).

All other offers on the same stock can be placed, modified, cancelled and matched by other threads at the same time.

---

## 2. Building blocks

### 2.1 `Order` (an offer or request)

| Field | Type | Protected by | Notes |
|---|---|---|---|
| `seq` | `final long` | — (immutable) | Unique, increasing id from `ORDER_SEQ`. Used for time priority **and** for lock ordering. |
| `stock`, `trader`, `side` | `final` | — | |
| `price` | `final long` | — (immutable) | It is the sort key in the order book, so it **must never change** while the order is in the book. |
| `qty` | `int` | `order.lock` | Remaining quantity. |
| `active` | `volatile boolean` | written only under `order.lock` | `false` = fully executed, cancelled or replaced. Read without a lock as a quick hint. |
| `lock` | `final ReentrantLock` | — | **The per-offer lock.** |

### 2.2 `Stock` (one listed company)

| Field | Type | Purpose |
|---|---|---|
| `buys` | `ConcurrentSkipListSet<Order>` sorted by `BUY_FIRST` | Buy requests. Highest price first, ties broken by older `seq`. |
| `sells` | `ConcurrentSkipListSet<Order>` sorted by `SELL_FIRST` | Sell offers. Lowest price first, ties broken by older `seq`. |
| `history` | `ConcurrentLinkedQueue<Transaction>` | Public list of completed trades. |
| `lastTx` | `AtomicReference<Transaction>` | The newest transaction. `lastPrice()` reads its price. |

**There is no `Stock` lock anymore.** The two skip-list sets are thread-safe on their own: insert, remove and iteration are lock-free, and their iterators are *weakly consistent*. An iterator never throws `ConcurrentModificationException`; it sees the set as it is while it walks, including changes made by other threads.

### 2.3 `Trader` (wallet)

Unchanged. Each trader has one `ReentrantLock` that protects `cash` and `shares[]`. `settle()` locks the buyer and the seller in ascending `id` order, re-checks that the buyer can pay and the seller can deliver, then moves money and shares.

---

## 3. The global lock order (why there is no deadlock)

Every thread that takes more than one lock takes them in this fixed order:

```
1. Order lock with the smaller seq
2. Order lock with the larger seq
3. Trader lock with the smaller id      (inside settle)
4. Trader lock with the larger id       (inside settle)
```

- A thread **never holds more than two order locks** at once (`lockPair`).
- Order locks are always taken **before** trader locks, never the other way round.
- Within each level, locks are taken in ascending `seq` / `id` order.

A deadlock needs a cycle: thread A holds X and waits for Y, while thread B holds Y and waits for X. Here every thread climbs the same total order, so one of them would have to take a "smaller" lock while holding a "larger" one. That never happens, so no cycle can form.

`cancel` and `modify` take only **one** order lock, so they can't take part in a cycle either.

---

## 4. Step by step: placing an order

Entry point: `Stock.place(trader, side, price, qty)`.

```
place(t, side, price, qty):
    o = new Order(...)            // gets a new unique seq
    book(side).add(o)             // STEP A: insert into the book first
    match(o)                      // STEP B: then look for counterparties
    return o
```

**Step A: insert first.** The new order goes into `buys` or `sells` **before** it looks for a match. From this moment other threads can see it and trade against it. No lock is needed: the object is fully built, `seq` and `price` are final, and inserting into the skip list publishes it safely to other threads (happens-before).

**Step B: match.** The thread that placed the order now acts as the *aggressor*. It walks the opposite side of the book, best price first (section 5).

Why the order is "insert, then match" is explained in section 7.

---

## 5. Step by step: matching (`match(Order o)`)

```
for c in opposite side of the book (best price first):
 (1)  if !o.active                -> stop        // o was filled / cancelled
 (2)  if !crosses(o, c)           -> stop        // book is sorted: nothing further can match
 (3)  if c.trader == o.trader     -> skip c      // no self-trade
 (4)  lockPair(o, c)                             // lock both offers, smaller seq first
      try:
 (5)    if !o.active || !c.active -> skip c      // re-check now that we own the locks
 (6)    b = the buy of (o, c);  s = the sell of (o, c)
        n  = min(b.qty, s.qty)
        px = price of the OLDER order (smaller seq)
 (7)    switch settle(b.trader, s.trader, stock, n, px):   // locks the 2 traders (by id)
          OK:
 (8)        b.qty -= n;  s.qty -= n
            tx = new Transaction(...)
            history.add(tx)
            lastTx = whichever of (lastTx, tx) has the larger id
            if b.qty == 0 -> deactivate(b)
            if s.qty == 0 -> deactivate(s)
          BUYER_CANNOT_PAY:      deactivate(b)
          SELLER_CANNOT_DELIVER: deactivate(s)
      finally:
 (9)    unlockPair(o, c)
```

`crosses(a, b)` is true when `sell.price <= buy.price`. `deactivate(o)` sets `o.active = false` and removes `o` from its set; it is always called with `o.lock` held.

### What each step guarantees

1. **Cheap early exit.** `active` is volatile, so reading it without a lock is safe. It is only a hint; step 5 is the real check.
2. **Stop as soon as prices stop crossing.** Prices are final and the set is sorted, so if `c` doesn't cross, no order after `c` will. We never scan the whole book.
3. **Self-trade prevention.** A trader can't buy from themselves. We skip to the next candidate.
4. **Lock only what the trade touches.** Only `o` and `c` are locked. Other offers in the same book stay free.
5. **Double-check under the lock.** Between seeing `c` in the iterator and owning its lock, another thread may have filled or cancelled `c`, or filled `o`. Re-checking `active` under both locks makes the trade decision safe. `price` is final, so it doesn't need a re-check.
6. **Trade size and price.** `n = min(offer, request)`, as the spec requires. The price is the one from the order that was in the book first.
7. **Settlement.** `settle` takes the two trader locks (by id), re-checks funds and shares, and moves cash and shares atomically.
8. **Book-keeping under the order locks.** `qty` is only changed while both order locks are held. A fully filled order is deactivated and removed from the book.
9. **Release.** Both order locks are released in `finally`, so a failure can never leave a lock held.

The loop then goes on to the next candidate until `o` is filled, prices stop crossing, or the book side ends. If `o` is only partly filled, it simply **stays in the book** as a resting order, and a later aggressor will find it.

---

## 6. Worked example

Book for `ACME` before the new order. Trader ids are in brackets.

```
sells (lowest first)            buys (highest first)
  S1  seq=10  [T3]  100 x 5       B1  seq=11  [T4]  98 x 10
  S2  seq=12  [T7]  101 x 8
  S3  seq=14  [T2]  105 x 3
```

Trader **T7** places **BUY 102 x 10** → order `N`, `seq=20`.

| # | What happens | Locks held |
|---|---|---|
| A | `N` is added to `buys`. The book now has `N(102)` above `B1(98)`. | none |
| B1 | Candidate `S1` (100). Crosses (100 ≤ 102). Different trader. | — |
| B2 | `lockPair(N, S1)` → lock `S1` (seq 10) first, then `N` (seq 20). | S1, N |
| B3 | Both active. `n = min(10, 5) = 5`. Older order is `S1` → `px = 100`. | S1, N |
| B4 | `settle(T7, T3, ACME, 5, 100)` → locks T3 then T7 (by id), moves 500 cash and 5 shares. | S1, N, T3, T7 |
| B5 | `N.qty = 5`, `S1.qty = 0` → `deactivate(S1)`. Transaction recorded. | S1, N |
| B6 | Unlock both. | none |
| B7 | Candidate `S2` (101). Crosses, but `S2.trader == T7` is the same trader → **skip**. | — |
| B8 | Candidate `S3` (105). 105 > 102 → **does not cross → stop**. | — |

Result: `N` stays in the book as **BUY 102 x 5**. It is still above `S2(101)`, but that is T7's own offer, so it is correctly left alone.

**While this was happening**, another thread could have been cancelling `B1`, modifying `S3`, or trading `S2` against some other trader's buy. None of those operations needed `N`'s or `S1`'s lock, so none of them waited for this trade.

---

## 7. Why no matching pair is ever missed

Claim: when no thread is in the middle of an operation, there is no buy and sell from **different traders** whose prices cross.

Take two crossing orders `X` and `Y` placed at about the same time by different traders. Each one is **inserted first, then scans**. The skip list is linearizable, so one of the two insertions happens before the other, say `X` before `Y`. When `Y` scans the opposite side, `X` is already there, so `Y` finds it. Either `Y` trades with it, or one of them is already filled or deactivated.

What if `Y` finds `X` but has to wait for `X`'s lock, because some third order `Z` is trading with `X`? `lockPair` *blocks* (it doesn't give up), so `Y` waits, then re-checks `X.active` and either trades or moves on. Nothing is skipped silently.

What if the aggressor's scan ends while the aggressor is only partly filled? It stays in the book. Any crossing order that arrives later is inserted, scans, and finds it.

If instead we **matched first and inserted afterwards**, both `X` and `Y` could scan an empty opposite side and then both be inserted, leaving a crossing pair that nobody ever executes.

`main` checks this at the end of each run with `Stock.hasCrossingOrders()`.

---

## 8. Modify and cancel

### Cancel: `cancel(o)`

```
lock o
  if !o.active -> return false       // already filled or cancelled
  deactivate(o)                       // active = false, remove from book
unlock o
```

Only `o` is locked. If a trade on `o` is running, it holds `o.lock`, so the cancel **waits for that one transaction** and then sees the up-to-date `qty`/`active`. That is exactly "modifiable if there is no ongoing transaction for the relevant shares", applied to a single offer.

### Modify: `modify(o, newPrice, newQty)`

```
lock o
  if !o.active -> return null        // nothing left to modify
  deactivate(o)                       // take the old offer out of the book
unlock o
return place(o.trader, o.side, newPrice, newQty)   // insert a replacement, then match it
```

Modify is **cancel + place a replacement**. It returns the new `Order`, and `TraderTask.modifyRandom` swaps it into the trader's `open` list (or removes the entry if `null` came back).

Why not change `price` in place? `price` is the skip list's sort key. If it changed while the order is in the set, or while another thread's iterator is standing on that node, the book could become mis-sorted, and step (2) of matching ("stop when prices stop crossing") would no longer be valid. With immutable prices the sort order can never be broken. The replacement gets a new `seq`, so it loses its place in the time queue, which is also what real exchanges do when you change a price.

---

## 9. Public market information (no locks)

- **`bestBidAsk()`** reads `buys.first()` and `sells.first()`. It is O(1) and lock-free. The result is a snapshot, so it may be out of date a moment later. That is fine: traders use it only to decide what to offer, and every real check is made again under locks.
- **`lastPrice()`** reads `lastTx.get().price()`. Trades on the same stock can now finish in any order, so a plain `lastPrice = px` could let an *older* trade overwrite a *newer* one. `lastTx.accumulateAndGet(tx, keep the larger id)` always keeps the newest transaction.
- **`history`** is a `ConcurrentLinkedQueue`. Anyone can read it at any time.

---

## 10. What was traded away

**Strict global price-time priority.** With one stock lock, the matcher always picked *the* best pair in the whole book. Now each aggressor walks the book on its own. If two aggressors run at the same time, each one gets the best counterparty *it could see and lock*, which may not be exactly the one a single-threaded matcher would have picked. Correctness is not affected: money and shares are conserved, nothing goes negative, and no crossing pair is left. This is how real concurrent matching engines behave too.

---

## 11. Invariants checked at the end of `main`

1. No trader has negative cash or shares.
2. Total cash equals `TRADERS * INITIAL_CASH` (money is neither created nor lost).
3. For every stock, total shares equals `TRADERS * INITIAL_SHARES`.
4. No stock has a buy and a sell from different traders whose prices cross (`hasCrossingOrders`).

---

## 12. Summary of the flow

```
Trader thread
   │
   ├─ think (no locks)
   ├─ read bestBidAsk / lastPrice (lock-free)
   │
   ├─ PLACE ─► insert into skip list ─► match(o)
   │                                     │
   │            for each crossing candidate c (best first):
   │                lock o & c (by seq) ─► re-check active
   │                   ─► settle: lock buyer & seller (by id), move cash/shares
   │                   ─► update qty, record tx, deactivate filled orders
   │                unlock o & c
   │
   ├─ MODIFY ─► lock o ─► deactivate ─► unlock ─► PLACE replacement
   │
   └─ CANCEL ─► lock o ─► deactivate ─► unlock
```
