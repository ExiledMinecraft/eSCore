// CoinsEngine balance leaderboard for WScore.
// Main placeholder: %wscore_ce_balance%
// Leaderboard placeholders:
// %wscore_ce_balance_1_alltime_name%
// %wscore_ce_balance_1_alltime_value%
//
// Currency id in this preset: money
// Replace `money` below if your CoinsEngine currency uses another id.

wscore.register(wscore.createLeaderboardCounter({
  id: "ce_balance",
  name: "CoinsEngine Balance",
  description: "CoinsEngine balance mirrored into WScore for all-time leaderboards",

  storageKey: "ce_balance",
  source: "%coinsengine_balance_raw_money%",

  defaultValue: 0,
  zeroDataPlaceholder: "0",

  cacheTtlMillis: 250,
  sourceRefreshTtlMillis: 1000,
  flushIntervalMillis: 1500,

  persistence: { mode: "backend" },
  sync: { mode: "auto" },

  leaderboards: {
    zeroDataPlaceholder: "---",
    hasData: function (entry) {
      return entry.hasEntry() && entry.value() > 0;
    },
    periods: {
      alltime: { mode: "alltime" }
    }
  },

  noDataWhen: function (value, ctx) {
    return ctx.isBlank(value);
  },

  // Mirror the latest external balance into WScore storage.
  // This keeps the leaderboard up to date whenever the player's own placeholder is queried.
  transform: function (value, ctx) {
    const balance = Math.max(0, ctx.number(value, 0));
    const stored = ctx.number(ctx.data("ce_balance", 0), 0);

    if (stored !== balance) {
      ctx.setData("ce_balance", balance);
    }

    return balance;
  },

  // Initial sync on join so online players appear in the leaderboard even before their
  // personal placeholder is rendered somewhere else.
  watch: [{
    event: "paper.player_join",
    player: function (event) {
      return event.getPlayer();
    },
    update: function (event, current, ctx) {
      return Math.max(0, ctx.number(ctx.placeholder("%coinsengine_balance_raw_money%"), 0));
    }
  }],

  render: function (value, ctx) {
    return wscore.convert.compactNumber(ctx.number(value, 0), 1);
  }
}));
