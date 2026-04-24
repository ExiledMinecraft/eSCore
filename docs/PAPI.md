# PlaceholderAPI-совместимый слой eSCore

`eSCore` содержит встроенный PlaceholderAPI-совместимый слой.

Это означает, что основной плагин сам:

- регистрирует собственные expansion'ы;
- умеет загружать внешние PlaceholderAPI expansion'ы;
- предоставляет `/papi`-совместимую команду;
- поддерживает eCloud;
- умеет парсить `%placeholder%` внутри своего runtime.

Для базовой работы `eSCore` отдельный PlaceholderAPI не нужен.

## Что именно встроено

Внутри проекта есть совместимый слой, повторяющий основные сценарии PlaceholderAPI:

- реестр expansion'ов;
- загрузка expansion'ов из jar;
- list/info/register/unregister;
- parse/parserel;
- eCloud refresh/download/update;
- интеграция с внутренним runtime `eSCore`.

При этом `eSCore` остаётся одним плагином, а не «плагином плюс второй встроенный плагин рядом».

## Основные namespace

Собственные placeholder'ы `eSCore` доступны через:

- `%escore_<placeholder>%`

Старый namespace сохраняется как legacy-совместимость:

- `%wscore_<placeholder>%`

Оба namespace указывают на одну и ту же внутреннюю логику.

Для новой документации, scoreboard-конфигов и примеров используйте `%escore_*%`.

## Каталоги

`eSCore` использует два разных каталога:

- `plugins/eSCore/`
- `plugins/PlaceholderAPI/expansions/`

Скрипты, внутренние расширения и runtime-файлы находятся внутри `plugins/eSCore/`.

Внешние PlaceholderAPI expansion'ы находятся в стандартном каталоге PlaceholderAPI:

- `plugins/PlaceholderAPI/expansions/`

Если expansion скачан через `/papi ecloud download` или зарегистрирован через `/papi register`, он попадает именно туда.

Каталог `plugins/eSCore/extensions/` для внешних PlaceholderAPI expansion'ов не используется.

## Поведение `/papi reload`

Команда `/papi reload` в `eSCore` относится только к PlaceholderAPI-совместимому слою.

Она:

- обновляет cloud-каталог expansion'ов;
- повторно сканирует внешние expansion jar;
- заново строит состояние встроенного PlaceholderAPI bridge;
- пересчитывает статус зависимых интеграций.

Она не предназначена для полной перезагрузки runtime плагина.

Для полной перезагрузки `eSCore` используйте:

- `/escore reload`

Скрипты `eSCore` не должны перезагружаться через `/papi reload`.

## Команды

Поддерживаются команды:

- `/papi help`
- `/papi reload`
- `/papi list`
- `/papi info <name>`
- `/papi parse <me|--null|player> <text>`
- `/papi bcparse <me|--null|player> <text>`
- `/papi cmdparse <me|--null|player> <command>`
- `/papi parserel <player1> <player2> <text>`
- `/papi register <file>`
- `/papi unregister <name>`
- `/papi version`
- `/papi dump`
- `/papi ecloud ...`

## Что делает `/papi list`

`/papi list` показывает активные expansion'ы, зарегистрированные во внутреннем менеджере PlaceholderAPI-совместимого слоя.

В этот список попадают:

- собственные expansion'ы `eSCore`;
- внешние expansion'ы, найденные в `plugins/PlaceholderAPI/expansions/`.

## Что делает `/papi info`

Команда `/papi info` сначала проверяет локально зарегистрированные expansion'ы.

Если локальный expansion не найден, `eSCore` обращается к eCloud-кэшу и пытается показать информацию о remote expansion'е.

Это нужно для сценария, когда администратор хочет узнать:

- установлен ли expansion;
- какая у него версия;
- есть ли обновление;
- какие placeholder'ы он публикует.

## Встроенный eCloud

`eSCore` старается вести себя максимально близко к PlaceholderAPI, но при этом аккуратно относится к таймаутам и фоновым операциям.

Поддерживаются:

- фоновый preload cloud-каталога;
- обновление кэша по запросу;
- список expansion'ов;
- поиск expansion'ов;
- просмотр версий;
- скачивание jar;
- update уже установленных expansion'ов.

## Зеркала eCloud

По умолчанию в конфиге можно указать несколько mirror URL.

`eSCore` использует их как официальный набор источников и кэширует полученные данные в своём cloud-manager.

Если какой-то источник временно недоступен:

- runtime не должен валить весь плагин;
- ошибки выводятся как диагностические;
- следующая команда может инициировать повторный прогрев кэша.

## User-Agent и сетевое поведение

По умолчанию сетевые запросы маркируются user-agent'ом:

- `eSCore/1.0`

Это нужно для нормальной идентификации запросов к eCloud/mirror endpoint'ам.

## Внешний PlaceholderAPI как отдельный плагин

Если на сервере отдельно стоит плагин `PlaceholderAPI`, `eSCore` предупреждает об этом.

Причина простая:

- у `eSCore` уже есть собственный PlaceholderAPI-совместимый runtime;
- второй такой слой рядом может привести к конфликтам ожиданий, каталогов или состояния expansion'ов.

Практический вывод:

- либо используйте встроенный слой `eSCore`;
- либо очень осознанно держите внешний PlaceholderAPI только если понимаете пересечения.

## Как `eSCore` использует PlaceholderAPI внутри себя

Скрипты `eSCore` могут обращаться:

- к внутренним placeholder'ам `eSCore`;
- к внешним PlaceholderAPI expansion'ам;
- к Java hooks/value providers.

То есть один скрипт может:

- брать `%player_name%` из внешнего expansion'а;
- брать `escore:dragon_kills` из собственного runtime;
- брать `hook:luckperms:prefix` из Java integration provider.

## Совместимость со старыми pack'ами

Если у вас уже есть старые scoreboard или script-pack, которые используют:

- `%wscore_*%`
- `/wscore reload`

их не нужно массово ломать немедленно.

Совместимость оставлена специально для миграции.

Но для новых паков и документации правильный вариант такой:

- `%escore_*%`
- `/escore ...`

## Что считать expansion'ом, а что расширением eSCore

Важно различать:

- PlaceholderAPI expansion
- внутреннее Java-расширение `eSCore`

Expansion:

- живёт в каталоге `plugins/PlaceholderAPI/expansions/`;
- регистрируется через PlaceholderAPI-совместимый слой;
- публикует `%placeholder%`.

Внутреннее расширение `eSCore`:

- живёт в `plugins/eSCore/extensions/`;
- реализует `eScoreExtension`;
- может регистрировать hooks/providers/bridge'и;
- не обязано быть PlaceholderAPI expansion'ом.

## Диагностика

Если PlaceholderAPI-совместимый слой ведёт себя неожиданно, полезно проверить:

- есть ли отдельный плагин `PlaceholderAPI` рядом;
- доступен ли каталог `plugins/PlaceholderAPI/expansions/`;
- проходит ли `/papi reload` без ошибок;
- что показывает `/papi list`;
- что показывает `/papi ecloud status`;
- какой namespace используется в placeholder'ах: `%escore_*%` или legacy `%wscore_*%`.

## Рекомендация

Для новой настройки сервера:

- используйте `%escore_*%`;
- используйте `/escore` для управления основным runtime;
- используйте `/papi` только для PlaceholderAPI-совместимого слоя;
- оставляйте `%wscore_*%` только там, где уже есть существующий продакшен-конфиг.
