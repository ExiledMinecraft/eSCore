wscore.register(wscore.createLeaderboardCounter({
  id: "player_kills",
  name: "Player Kills",
  description: "Counts PvP kills",
  storageKey: "player_kills",
  bypass: false,
  zeroDataPlaceholder: "0",
  cacheTtlMillis: 250,
  flushIntervalMillis: 1500,
  leaderboards: {
    zeroDataPlaceholder: "---",
    hasData: function (entry) {
      return entry.hasEntry() && entry.value() > 0;
    },
    periods: {
      alltime: { mode: "alltime" },
      day: { mode: "day" },
      week: { mode: "week" },
      month: { mode: "month" },
      year: { mode: "year" }
    }
  },
  watch: [{
    event: "paper.entity_death",
    filter: function (event, current, ctx) {
      if (event.getEntity().getType().name() !== "PLAYER") {
        return false;
      }
      var killerId = ctx.causingPlayerId(event);
      if (killerId == null) {
        return false;
      }
      return killerId !== event.getEntity().getUniqueId().toString();
    },
    player: function (event, ctx) {
      return ctx.causingPlayerId(event);
    },
    update: function (event, current, ctx) {
      return ctx.number(current, 0) + 1;
    }
  }],
  render: function (value, ctx) {
    return String(ctx.number(value, 0));
  }
}));
