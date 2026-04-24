# Хуки и Java API WScore

## Получение API

### Через `ServicesManager`

```java
RegisteredServiceProvider<WScoreApi> provider =
        Bukkit.getServicesManager().getRegistration(WScoreApi.class);

if (provider != null) {
    WScoreApi api = provider.getProvider();
}
```

### Через статический провайдер

```java
WScoreApi api = WScoreProvider.get();
```

## Что умеет API

Через `WScoreApi` можно:
- читать значения игрока;
- записывать значения игрока;
- увеличивать числовые значения;
- резолвить placeholder WScore;
- читать контекст, который экспортирует JS-скрипт;
- регистрировать value provider'ы для `hook:`-источников;
- регистрировать свои placeholder-hook'и;
- отправлять события в JS через каналы;
- регистрировать Bukkit/Paper event bridge без ручного listener-кода;
- читать состояние интеграций.

## Основные методы

```java
Optional<Object> getPlayerValue(UUID playerId, String key);
Object getPlayerValue(UUID playerId, String key, Object defaultValue);
void setPlayerValue(UUID playerId, String key, Object value);
double addToPlayerNumber(UUID playerId, String key, double delta);
Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId);
Optional<Object> resolveValueHook(OfflinePlayer player, String providerId, String key);
Optional<Object> getScriptContext(OfflinePlayer player, String placeholderId);
Map<String, String> getIntegrationStates();
WScoreRegistration registerPlaceholderHook(WScorePlaceholderHook hook);
WScoreRegistration registerValueProvider(WScoreValueProvider provider);
WScoreRegistration registerEventBridge(WScoreEventBridge<?> bridge);
void fireChannelEvent(String channel, UUID playerId, Map<String, Object> payload);
```

## Регистрация собственного placeholder-hook'а

```java
api.registerPlaceholderHook(new WScorePlaceholderHook() {
    @Override
    public String getId() {
        return "example_current_mode";
    }

    @Override
    public String onRequest(OfflinePlayer player) {
        return "lobby";
    }
});
```

После этого placeholder будет доступен через WScore.

## Ручная отправка события в канал

```java
api.fireChannelEvent(
        "example:coins_reward",
        player.getUniqueId(),
        Map.of("amount", 50)
);
```

JS-скрипт может слушать этот канал так:

```js
watch: [{
  channel: "example:coins_reward",
  update: function (event, current, ctx) {
    return ctx.number(current, 0) + ctx.number(event.payload().amount, 0);
  }
}]
```

## Auto event bridge

Если нужно быстро связать Bukkit/Paper-событие с каналом WScore, используйте `registerEventBridge(...)`.

```java
api.registerEventBridge(new WScoreEventBridge<PlayerJoinEvent>() {
    @Override
    public String channel() {
        return "example:player_join";
    }

    @Override
    public Class<PlayerJoinEvent> eventType() {
        return PlayerJoinEvent.class;
    }

    @Override
    public UUID extractPlayerId(PlayerJoinEvent event, WScoreApi api) {
        return event.getPlayer().getUniqueId();
    }

    @Override
    public Map<String, Object> payload(PlayerJoinEvent event, WScoreApi api) {
        return Map.of("name", event.getPlayer().getName());
    }
});
```

## Value provider для `hook:`-источников

Если плагин умеет отдавать собственное значение напрямую, его лучше регистрировать как `WScoreValueProvider`.

```java
api.registerValueProvider(new WScoreValueProvider() {
    @Override
    public String getId() {
        return "worldguard";
    }

    @Override
    public Object getValue(OfflinePlayer player, String key) {
        if ("current_region".equalsIgnoreCase(key)) {
            return "spawn";
        }
        return null;
    }
});
```

После этого значение доступно в JS так:

```js
source: "hook:worldguard:current_region"
```

или так:

```js
ctx.hook("worldguard", "current_region", "")
```

## Чтение контекста скрипта из Java

Если JS-скрипт экспортирует `context`, Java может его прочитать:

```java
api.getScriptContext(player, "dragon_kills").ifPresent(context -> {
    System.out.println(context);
});
```

## Событие изменения значения

WScore вызывает Bukkit event при изменении значения игрока.

```java
@EventHandler
public void onValueChange(WScoreValueChangeEvent event) {
    if (event.getKey().equals("dragon_kills")) {
        System.out.println(event.getNewValue());
    }
}
```

Это удобно для:
- реакции на изменение счётчиков;
- отладки;
- синхронизации с другим плагином;
- логирования.

## Когда использовать что

### Placeholder hook

Используйте, когда значение удобно отдавать сразу строкой из Java.

### Value provider

Используйте, когда Java должна отдавать исходное значение, а JS потом сам решит, как его считать и форматировать.

### Channel event

Используйте, когда Java только поставляет факт события, а вся логика подсчёта должна жить в JS.

### Event bridge

Используйте, когда нужно быстро связать Bukkit/Paper event с JS-каналом без ручного listener-кода.

## Интеграция с LuckPerms из JS

Отдельный Java-хук для этого не нужен.

Если встроенная интеграция LuckPerms включена, скрипт может напрямую:
- читать группу, префикс и суффикс;
- проверять группы и права;
- задавать runtime-контексты через `ctx.setLuckPermsContext(...)`;
- читать их через `ctx.luckPermsContext(...)`.

Это часть встроенной интеграции WScore, а не внешний bridge.
