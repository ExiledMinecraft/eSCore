# Хуки и Java API eSCore

## Получение API

### Через `ServicesManager`

```java
RegisteredServiceProvider<eScoreApi> provider =
        Bukkit.getServicesManager().getRegistration(eScoreApi.class);

if (provider != null) {
    eScoreApi api = provider.getProvider();
}
```

### Через статический провайдер

```java
eScoreApi api = eScoreProvider.get();
```

## Что умеет API

Через `eScoreApi` можно:

- читать значения игрока;
- читать значения игрока с учётом области `все серверы / текущий сервер`;
- записывать значения игрока;
- увеличивать числовые значения;
- резолвить placeholder `eSCore`;
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
Object getPlayerValue(UUID playerId, String key, Object defaultValue, eScoreScope scope);
void setPlayerValue(UUID playerId, String key, Object value);
double addToPlayerNumber(UUID playerId, String key, double delta);
Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId);
Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId, eScoreScope scope);
Optional<Object> resolveValueHook(OfflinePlayer player, String providerId, String key);
Optional<Object> getScriptContext(OfflinePlayer player, String placeholderId);
Map<String, String> getIntegrationStates();
eScoreRegistration registerPlaceholderHook(eScorePlaceholderHook hook);
eScoreRegistration registerValueProvider(eScoreValueProvider provider);
eScoreRegistration registerEventBridge(eScoreEventBridge<?> bridge);
void fireChannelEvent(String channel, UUID playerId, Map<String, Object> payload);
```

## Области значений

Для чтения значений и placeholder'ов используется enum `eScoreScope`:

- `eScoreScope.ALL_SERVERS`
- `eScoreScope.CURRENT_SERVER`

Это нужно для multi-server сценария, когда один и тот же placeholder должен уметь отдавать:

- суммарное значение по всем серверам;
- только локальное значение текущего сервера.

Пример:

```java
Object totalPlaytime = api.getPlayerValue(playerId, "playtime", 0D, eScoreScope.ALL_SERVERS);
Object localPlaytime = api.getPlayerValue(playerId, "playtime", 0D, eScoreScope.CURRENT_SERVER);

String total = api.resolvePlaceholder(player, "playtime").orElse("0");
String local = api.resolvePlaceholder(player, "playtime__server").orElse("0");
```

По умолчанию `eSCore` читает значение со всех серверов. Режим `CURRENT_SERVER` особенно полезен для скриптов с `serverScoped: true`.

## Регистрация собственного placeholder-hook'а

```java
api.registerPlaceholderHook(new eScorePlaceholderHook() {
    @Override
    public String getId() {
        return "example_current_mode";
    }

    @Override
    public String resolve(OfflinePlayer player, eScoreApi api) {
        return "lobby";
    }
});
```

После этого placeholder будет доступен через `eSCore`.

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
  update(event, current, ctx) {
    return ctx.number(current, 0) + ctx.number(event.payload().amount, 0);
  }
}]
```

## Auto event bridge

Если нужно быстро связать Bukkit/Paper-событие с каналом `eSCore`, используйте `registerEventBridge(...)`.

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

## Value provider для `hook:`-источников

Если плагин умеет отдавать собственное значение напрямую, его лучше регистрировать как `eScoreValueProvider`.

```java
api.registerValueProvider(new eScoreValueProvider() {
    @Override
    public String getId() {
        return "worldguard";
    }

    @Override
    public Object resolve(OfflinePlayer player, String key, eScoreApi api) {
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

`eSCore` вызывает Bukkit event при изменении значения игрока.

```java
@EventHandler
public void onValueChange(eScoreValueChangeEvent event) {
    if (event.getKey().equals("dragon_kills")) {
        System.out.println(event.getNewValue());
    }
}
```

Импорт:

```java
import ru.exiledproject.developments.api.event.eScoreValueChangeEvent;
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

Это часть встроенной интеграции `eSCore`, а не внешний bridge.
