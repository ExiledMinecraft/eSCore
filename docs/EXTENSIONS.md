# Расширения eSCore

## Что такое расширение

Расширение `eSCore` — это отдельный jar-файл в каталоге:

- `plugins/eSCore/extensions`

Оно нужно, когда вы хотите:

- интегрировать сторонний плагин с `eSCore`;
- слушать его события;
- отправлять их в JS-каналы;
- регистрировать Java placeholder-hook'и;
- регистрировать поставщиков значений;
- строить мост между Bukkit API и скриптами.

Это не expansion PlaceholderAPI.

Expansion'ы PlaceholderAPI лежат отдельно:

- `plugins/PlaceholderAPI/expansions`

## Когда внутреннего расширения недостаточно

Если интеграции нужен собственный `onLoad()` Bukkit-плагина, её лучше делать отдельным плагином-спутником.

Типичный пример:

- `WorldGuard`, потому что кастомный флаг нужно регистрировать до `WorldGuard onEnable()`.

Именно поэтому интеграция `eSCore-WorldGuard` сделана отдельным Bukkit-плагином, а не внутренним расширением `eSCore`.

## Интерфейс

Нужно реализовать `eScoreExtension`.

```java
public final class ExampleExtension implements eScoreExtension {
    @Override
    public String getId() {
        return "example-extension";
    }

    @Override
    public String getAuthor() {
        return "WillowStudio";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void enable(eScoreApi api, JavaPlugin plugin) {
    }

    @Override
    public void disable() {
    }
}
```

## Типовые сценарии

### Java-плагин ловит событие и отправляет его в канал

Это основной сценарий, когда администратор сервера должен менять бизнес-логику без пересборки jar-файла.

Поток работы:

1. Java-расширение ловит событие стороннего плагина.
2. Определяет игрока и полезную нагрузку.
3. Отправляет channel event в `eSCore`.
4. JS-скрипт решает, как считать и что сохранять.

### Java-плагин отдаёт значение напрямую

Это нужно, когда скрипту удобнее читать исходное значение, а не строку placeholder'а.

Тогда расширение регистрирует `eScoreValueProvider`, после чего скрипт может читать значение так:

```js
source: "hook:example:current_mode"
```

или так:

```js
ctx.hook("example", "current_mode", "default")
```

### Java-плагин регистрирует собственный placeholder-hook

Это полезно, когда часть логики должна остаться на стороне Java и не должна идти через JS.

## Пример: расширение отправляет событие в канал

### Java

```java
api.fireChannelEvent(
        "example:coins_reward",
        player.getUniqueId(),
        Map.of("amount", 50)
);
```

### JS

```js
escore.register(escore.createCounter({
  id: "reward_coins",
  watch: [{
    channel: "example:coins_reward",
    update(event, current, ctx) {
      return ctx.number(current, 0) + ctx.number(event.payload().amount, 0);
    }
  }]
}));
```

## Пример: автоматический мост событий

Если расширение работает с обычным Bukkit/Paper-событием, можно не писать ручной listener и использовать `registerEventBridge(...)`.

```java
api.registerEventBridge(new eScoreEventBridge<PlayerJoinEvent>() {
    @Override
    public String getChannel() {
        return "example:player_join";
    }

    @Override
    public Class<PlayerJoinEvent> getEventClass() {
        return PlayerJoinEvent.class;
    }

    @Override
    public UUID extractPlayerId(PlayerJoinEvent event, eScoreApi api) {
        return event.getPlayer().getUniqueId();
    }

    @Override
    public Map<String, Object> payload(PlayerJoinEvent event, eScoreApi api) {
        return Map.of("name", event.getPlayer().getName());
    }
});
```

Тогда JS-скрипт сможет слушать канал:

```js
watch: [{
  channel: "example:player_join",
  update(event, current, ctx) {
    return ctx.number(current, 0) + 1;
  }
}]
```

## Когда логику лучше держать в Java

Логику лучше держать на стороне Java, если:

- она сложная и тесно завязана на API другого плагина;
- нужно много синхронных вызовов к чужому API;
- нужна строгая типизация и собственные сервисы;
- скрипту достаточно получить уже готовое значение.

В таком случае можно:

- сразу писать значения через `api.setPlayerValue(...)`;
- регистрировать placeholder-hook;
- регистрировать поставщика значений.

## Когда логику лучше выносить в JS

Логику лучше выносить в JS, если:

- администратор сервера должен менять правила без пересборки jar-файла;
- условия подсчёта часто меняются;
- нужен быстрый эксперимент с placeholder'ами;
- один и тот же поток событий должен использоваться в нескольких скриптах.

## Области значений по серверам

Если расширение читает или показывает данные игрока, можно использовать scope-aware API:

```java
Object total = api.getPlayerValue(playerId, "playtime", 0D, eScoreScope.ALL_SERVERS);
Object local = api.getPlayerValue(playerId, "playtime", 0D, eScoreScope.CURRENT_SERVER);
```

Это особенно важно для счётчиков со `serverScoped: true`, где локальные значения хранятся отдельно по `server-id`, а обычный placeholder по умолчанию суммирует все сервера.

## Структура проекта

Исходники внутреннего расширения можно хранить как отдельный модуль проекта, но итоговый jar должен попадать в:

- `plugins/eSCore/extensions`

Плагины-спутники, которым нужен полноценный Bukkit lifecycle, кладутся как обычные плагины в:

- `plugins/`

Пример такого модуля в проекте:

- `extensions/wscore-worldguard`
