# Скрипты eSCore

`eSCore` использует JS-скрипты как основной способ описания плейсхолдеров, счётчиков, форматтеров, лидербордов и логики обработки событий.

Скрипт в `eSCore` может:

- читать значения из собственного хранилища;
- читать другой placeholder `eSCore`;
- использовать данные внешних PlaceholderAPI expansion'ов;
- использовать Java value provider'ы через `hook:`;
- слушать Bukkit/Paper события и script-channel'ы;
- изменять значения игроков;
- строить собственные лидерборды;
- передавать контекст в LuckPerms integration.

## Где лежат скрипты

Рабочий каталог скриптов:

- `plugins/eSCore/scripts`

`eSCore` загружает `.js` рекурсивно из всех подкаталогов внутри `plugins/eSCore/scripts`.

Особое правило:

- каталог `lib` не загружается как набор placeholder script'ов и предназначен для модулей, которые подключаются через `escore.require(...)`.

## Как перезагружать скрипты

Для работы со скриптами используйте:

```text
/escore reload
/escore check
/escore check profile
```

`/escore reload` полностью пересобирает script runtime.

`/escore check` валидирует скрипты и показывает ошибки/предупреждения без смены runtime, если это поддерживает текущий путь проверки.

`/papi reload` для скриптов `eSCore` использовать не нужно.

## Основной JS namespace

Основной namespace:

- `escore`

Legacy alias:

- `wscore`

Оба имени указывают на один и тот же bootstrap API, но в новых скриптах и примерах следует использовать `escore`.

## Базовый пример

```js
escore.register({
  id: "player_name_upper",
  source: "%player_name%",
  render(value) {
    return escore.normalize.upper(value);
  }
});
```

Основной placeholder:

```text
%escore_player_name_upper%
```

Legacy alias:

```text
%wscore_player_name_upper%
```

## `escore.register(definition)`

Регистрирует один script definition.

```js
escore.register({
  id: "coins_short",
  storageKey: "coins",
  numeric: true,
  defaultValue: 0,
  render(value, ctx) {
    return escore.format.shortNumber(ctx.number(value, 0));
  }
});
```

## `escore.registerAll(definitions)`

Удобен, когда в одном файле сразу несколько placeholder'ов.

```js
escore.registerAll([
  {
    id: "coins",
    storageKey: "coins",
    numeric: true,
    defaultValue: 0
  },
  {
    id: "coins_short",
    source: "escore:coins",
    render(value, ctx) {
      return escore.convert.compactNumber(ctx.number(value, 0));
    }
  }
]);
```

## `escore.require(path)`

Подгружает JS-модуль из каталога `plugins/eSCore/scripts` и кэширует его в рамках текущей загрузки.

```js
const helpers = escore.require("lib/helpers.js");

escore.register({
  id: "coins_label",
  render() {
    return helpers.label("Coins");
  }
});
```

## `escore.createCounter(definition)`

Шорткат для числового счётчика с дефолтными backend/sync-настройками.

```js
escore.register(escore.createCounter({
  id: "dragon_kills",
  storageKey: "dragon_kills",
  defaultValue: 0
}));
```

По умолчанию helper добавляет:

- `numeric: true`
- `defaultValue: 0`
- `persistence.mode: "backend"`
- `sync.mode: "auto"`

## `escore.createFormatter(definition)`

Шорткат для formatter-style placeholder'ов.

```js
escore.register(escore.createFormatter({
  id: "dragon_kills_label",
  source: "escore:dragon_kills",
  render(value, ctx) {
    return escore.normalize.upper(String(value));
  }
}));
```

По умолчанию helper добавляет:

- `persistence.mode: "memory"`
- `sync.mode: "none"`

## `escore.createLeaderboardCounter(definition)`

Шорткат для счётчика с auto-generated leaderboard placeholder'ами.

```js
escore.register(escore.createLeaderboardCounter({
  id: "dragon_kills",
  storageKey: "dragon_kills",
  defaultValue: 0,
  leaderboards: {
    periods: {
      alltime: { mode: "alltime" },
      day: { mode: "day" },
      week: { mode: "week" }
    }
  }
}));
```

## Поля script definition

Часто используемые поля:

- `id`
- `aliases`
- `name`
- `description`
- `author`
- `version`
- `storageKey`
- `defaultValue`
- `numeric`
- `source`
- `transform`
- `render`
- `context`
- `dependsOn`
- `persistence`
- `sync`
- `leaderboards`
- `watch`
- `bypass`
- `bypassPermission`

## `id`

Идентификатор placeholder'а.

Допустимый формат:

- `[a-z0-9_]+`

Пример:

```js
id: "dragon_kills"
```

## `aliases`

Дополнительные id для того же placeholder'а.

```js
aliases: ["dk", "dragonkill_count"]
```

Все alias'ы нормализуются в lowercase.

## `storageKey`

Ключ в хранилище `eSCore`.

Если не задан, используется `id`.

```js
storageKey: "dragon_kills"
```

## `serverScoped`

Флаг для multi-server сценария, в котором одно и то же значение должно храниться отдельно по каждому серверу, но по умолчанию читаться как сумма со всех серверов.

```js
serverScoped: true
```

Когда `serverScoped: true`:

- запись идёт в локальный ключ текущего `server-id`;
- обычный placeholder читает сумму со всех серверов;
- placeholder с суффиксом `__server` читает только локальное значение текущего сервера.

Это особенно полезно для счётчиков вроде времени в игре, убийств, добытых ресурсов и другой статистики, которая накапливается на нескольких серверах в общей MySQL-базе.

## `numeric`

Флаг, что placeholder логически является числовым.

Это важно для:

- счётчиков;
- format helper'ов;
- leaderboard logic;
- некоторых внутренних оптимизаций.

## `defaultValue`

Значение по умолчанию, если ничего ещё нет в backend/runtime.

```js
defaultValue: 0
```

или:

```js
defaultValue: "none"
```

## Источники данных: `source`

Если `source` не задан, `eSCore` читает данные из собственного хранилища по `storageKey`.

Поддерживаются разные виды источников.

### `escore:<id>`

Чтение другого placeholder'а `eSCore`.

```js
source: "escore:dragon_kills"
```

### `wscore:<id>`

Legacy alias для старых pack'ов.

```js
source: "wscore:dragon_kills"
```

Для новых скриптов используйте `escore:`.

### `data:<key>`

Чтение raw value из player storage.

```js
source: "data:dragon_kills"
```

### `escore-server:<id>` и `wscore-server:<id>`

Чтение placeholder'а только для текущего сервера.

```js
source: "escore-server:playtime"
```

### `escore-all:<id>` и `wscore-all:<id>`

Явное чтение placeholder'а в режиме "все серверы".

```js
source: "escore-all:playtime"
```

### `data-server:<key>`

Чтение raw value только для текущего сервера.

```js
source: "data-server:playtime"
```

### `data-all:<key>`

Явное чтение raw value со всех серверов.

```js
source: "data-all:playtime"
```

### `%placeholder%`

Чтение из внешнего PlaceholderAPI expansion'а.

```js
source: "%player_name%"
```

### `hook:<provider>:<key>`

Чтение из Java value provider'а.

```js
source: "hook:luckperms:prefix"
```

### Object source

Можно использовать object-форму:

```js
source: {
  type: "hook",
  provider: "luckperms",
  key: "group"
}
```

Для object-формы `source` можно дополнительно указать `scope`:

```js
source: {
  type: "data",
  key: "playtime",
  scope: "server"
}
```

Поддерживаются типы:

- `player-data`
- `data`
- `placeholderapi`
- `papi`
- `escore`
- `wscore`
- `internal`
- `hook`
- `provider`
- `integration-hook`
- `constant`

Поддерживаются scope-значения:

- `all`
- `all_servers`
- `global`
- `server`
- `current_server`
- `local`

## Суффиксы placeholder'ов по серверу

У любого placeholder'а `eSCore` можно указать область чтения прямо в id.

По умолчанию используется режим "все серверы":

```text
%escore_playtime%
```

Для текущего сервера:

```text
%escore_playtime__server%
```

Явный режим "все серверы":

```text
%escore_playtime__all%
```

Также поддерживаются расширенные суффиксы:

- `__current_server`
- `__current`
- `__local`
- `__all_servers`
- `__global`

## `transform(value, ctx)`

Функция, которая преобразует source value перед финальным render.

```js
transform(value, ctx) {
  return ctx.number(value, 0) + 1;
}
```

## `render(value, ctx)`

Финальный renderer placeholder'а.

```js
render(value, ctx) {
  return escore.format.shortNumber(ctx.number(value, 0));
}
```

Если `render` не задан, runtime попытается вернуть значение как строку.

## `context`

Можно задать либо статическое context value, либо функцию `context(value, ctx)`.

Этот context потом может использоваться:

- в Java API;
- для интеграций;
- для внешней логики вокруг placeholder'а.

```js
context(value, ctx) {
  return {
    raw: ctx.number(value, 0),
    pretty: escore.format.shortNumber(ctx.number(value, 0))
  };
}
```

## `dependsOn`

Позволяет явно описать зависимости от других placeholder'ов.

```js
dependsOn: ["coins", "rank_points"]
```

Это полезно для invalidation и предсказуемого кэширования.

## `persistence`

Определяет, нужно ли хранить значение в backend.

Поддерживаемые режимы:

- `memory`
- `backend`

Пример:

```js
persistence: {
  mode: "backend"
}
```

## `sync`

Определяет, как скрипт должен синхронизироваться между серверами.

Поддерживаются:

- `none`
- `redis`
- `database`
- `auto`

Пример:

```js
sync: {
  mode: "auto",
  remoteTtlMillis: escore.time.minutes(30)
}
```

## `cacheTtlMillis`

Определяет, как долго можно отдавать уже готовый `%escore_<id>%` без повторного исполнения render.

```js
cacheTtlMillis: 500
```

## `sourceRefreshTtlMillis`

Определяет, как часто runtime должен заново обновлять исходное source value.

Это полезно, если:

- source дорогой;
- render дешёвый;
- нужно разделить кэш source и кэш финального placeholder'а.

## Watches

`watch` позволяет обновлять данные по событию или по каналу.

Поддерживаются два источника watch:

- Bukkit/Paper event
- script channel

### Watch по каналу

```js
watch: [
  {
    channel: "worldguard:entity_death",
    update(event, current, ctx) {
      return ctx.number(current, 0) + 1;
    }
  }
]
```

### Watch по событию

```js
watch: [
  {
    event: "paper.player_join",
    update(event, current, ctx) {
      return ctx.number(current, 0) + 1;
    }
  }
]
```

### Поля watch

- `event`
- `channel`
- `priority`
- `ignoreCancelled`
- `player`
- `filter`
- `update`

### `player(event, current, ctx)`

Позволяет явно указать, как получить игрока.

Если `player(...)` не задан, `eSCore` пытается определить игрока автоматически:

- из `PlayerEvent`;
- из `EntityEvent`, если entity — игрок;
- из projectile shooter;
- из channel payload, если playerId уже известен.

### `filter(event, current, ctx)`

Если возвращает `false`, watch-update не выполняется.

```js
filter(event, current, ctx) {
  return event.getPlayer().hasPermission("escore.track");
}
```

### `update(event, current, ctx)`

Главная функция watch.

Она возвращает новое значение.

```js
update(event, current, ctx) {
  return ctx.number(current, 0) + 1;
}
```

## Приоритеты событий

Поддерживаются стандартные Bukkit приоритеты:

- `LOWEST`
- `LOW`
- `NORMAL`
- `HIGH`
- `HIGHEST`
- `MONITOR`

## Алиасы событий

Через `escore.events` доступны алиасы:

- `paper.entity_death`
- `paper.player_join`
- `paper.player_quit`
- `paper.block_break`
- `paper.block_place`
- `paper.player_command`
- `paper.inventory_click`

Например:

```js
watch: [
  {
    event: escore.events["paper.entity_death"] || "paper.entity_death",
    update(event, current, ctx) {
      return ctx.number(current, 0) + 1;
    }
  }
]
```

## Leaderboards

Если включён блок `leaderboards`, `eSCore` автоматически создаёт placeholder'ы вида:

- `%escore_dragon_kills_1_alltime_name%`
- `%escore_dragon_kills_1_alltime_value%`
- `%escore_dragon_kills_1_day_name%`
- `%escore_dragon_kills_10_week_value%`

Legacy `%wscore_*%` для этих placeholder'ов тоже остаётся доступным.

### Периоды

Типичные периоды:

- `alltime`
- `day`
- `week`

Можно расширять конфигурацию periods в definition.

## `noDataPolicy`

Определяет, как вести себя, если исходные данные отсутствуют.

Полезно для:

- formatter'ов;
- leaderboard output;
- значений, где `null` и `0` — это разные состояния.

## Bypass permissions

Если у script watch включён bypass, `eSCore` проверяет специальные права.

Основные права:

- `escore.bypass`
- `escore.bypass.<id>`

Legacy alias:

- `wsc.bypass`
- `wsc.bypass.<id>`

Логика такая:

- глобальное bypass-право отключает watch для игрока;
- script-specific bypass отключает только один script id;
- кастомное `bypassPermission` перекрывает дефолтное имя.

Пример:

```js
bypass: true
```

или:

```js
bypassPermission: "myplugin.bypass.dragon_kills"
```

## `ctx` helper'ы

В render/transform/watch runtime передаёт context object.

Часто полезные методы:

- `ctx.number(value, fallback)`
- `ctx.string(value, fallback)`
- `ctx.bool(value, fallback)`
- `ctx.player()`
- `ctx.playerId()`
- `ctx.data(key, fallback)`
- `ctx.dataServer(key, fallback)`
- `ctx.dataAll(key, fallback)`
- `ctx.placeholder("%player_name%")`
- `ctx.escore("dragon_kills")`
- `ctx.escoreServer("dragon_kills")`
- `ctx.escoreAll("dragon_kills")`
- `ctx.wscore("dragon_kills")`
- `ctx.wscoreServer("dragon_kills")`
- `ctx.wscoreAll("dragon_kills")`
- `ctx.integration("luckperms")`
- `ctx.hook("luckperms", "prefix", "")`
- `ctx.scriptContext("dragon_kills")`
- `ctx.setData(key, value)`
- `ctx.add(key, delta)`
- `ctx.top(key, position, type)`
- `ctx.topServer(key, position, type)`
- `ctx.topAll(key, position, type)`
- `ctx.leaderboard(placeholderId, position, periodId, field)`
- `ctx.leaderboardServer(placeholderId, position, periodId, field)`
- `ctx.leaderboardAll(placeholderId, position, periodId, field)`
- `ctx.setLuckPermsContext(key, value)`
- `ctx.luckPermsContext(key, fallback)`
- `ctx.removeLuckPermsContext(key)`

Для счётчиков со `serverScoped: true` методы `ctx.setData(...)` и `ctx.add(...)` автоматически пишут данные в локальное хранилище текущего сервера, а чтение через `ctx.data(...)` и `ctx.escore(...)` по умолчанию берёт агрегированное значение со всех серверов.

## Пример formatter'а поверх собственного счётчика

```js
escore.register(escore.createCounter({
  id: "dragon_kills",
  storageKey: "dragon_kills",
  defaultValue: 0
}));

escore.register(escore.createFormatter({
  id: "dragon_kills_pretty",
  source: "escore:dragon_kills",
  render(value, ctx) {
    return escore.convert.compactNumber(ctx.number(value, 0));
  }
}));
```

## Пример event-driven счётчика

```js
escore.register(escore.createCounter({
  id: "joins",
  storageKey: "joins",
  defaultValue: 0,
  watch: [
    {
      event: "paper.player_join",
      update(event, current, ctx) {
        return ctx.number(current, 0) + 1;
      }
    }
  ]
}));
```

## Пример работы с PlaceholderAPI

```js
escore.register(escore.createFormatter({
  id: "prefix_name",
  render(value, ctx) {
    const prefix = ctx.placeholder("%luckperms_prefix%");
    const name = ctx.placeholder("%player_name%");
    return `${prefix}${name}`;
  }
}));
```

## Пример работы с LuckPerms context

```js
escore.register(escore.createCounter({
  id: "arena_kills",
  storageKey: "arena_kills",
  defaultValue: 0,
  watch: [
    {
      channel: "arena:kill",
      update(event, current, ctx) {
        ctx.setLuckPermsContext("escore:mode", "arena");
        return ctx.number(current, 0) + 1;
      }
    }
  ]
}));
```

## `escore.math`

Утилиты для чисел.

Пример:

```js
escore.math.percent(15, 60, 1)
```

## `escore.text`

Утилиты для работы с текстом.

Подходит для:

- безопасных преобразований строк;
- сборки списков;
- служебных formatter helper'ов.

## `escore.normalize`

Нормализация текста и значений.

Примеры:

```js
escore.normalize.lower("ПрИвЕт")
escore.normalize.upper("ПрИвЕт")
escore.normalize.stripFormatting("&2Text")
```

## `escore.decorate`

Декоративные helper'ы.

Примеры:

```js
escore.decorate.numberedList("А\nБ\nВ")
escore.decorate.bulletList("А\nБ\nВ")
```

## `escore.convert`

Конвертеры чисел, текста и времени.

Примеры:

```js
escore.convert.compactNumber(1250000)
escore.convert.expandCompactNumber("1k")
escore.convert.parseTime("1d 2h 30m")
```

## `escore.builders`

Factory/helper namespace для пайплайнов и шаблонов.

Используется там, где один render хочется собрать из нескольких маленьких преобразований.

## `escore.format`

Форматирование чисел и строк для итогового placeholder output.

Типичный сценарий:

- short number;
- fixed decimals;
- аккуратный вывод leaderboard values.

## `escore.time`

Утилиты времени:

```js
escore.time.seconds(5)
escore.time.minutes(5)
escore.time.hours(2)
```

Их удобно использовать в:

- TTL;
- cache settings;
- sync settings;
- runtime expiration logic.

## Практические рекомендации

- используйте `escore`, а не `wscore` в новых скриптах;
- используйте `%escore_*%` в новых scoreboard и menu config'ах;
- разделяйте счётчики и formatter'ы;
- backend persistence включайте только там, где данные реально должны переживать рестарт;
- в multi-server setup используйте `sync.mode: "auto"` или `redis`;
- оставляйте legacy `wscore` только для миграции старых script-pack'ов.
