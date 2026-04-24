wscore.register(wscore.createLeaderboardCounter({
  id: "dragon_kills",
  name: "Dragon Kills",
  description: "Counts Ender Dragon kills",
  storageKey: "dragon_kills",
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
      return event.getEntity().getType().name() === "ENDER_DRAGON" && ctx.causingPlayerId(event) != null;
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
