package ru.exiledproject.developments.script;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScriptToolkit {
    private static final Pattern LEGACY_SIMPLE_CODES = Pattern.compile("(?i)(?:&|§)[0-9A-FK-OR]");
    private static final Pattern LEGACY_HEX_CODES = Pattern.compile("(?i)(?:&|§)#[0-9A-F]{6}");
    private static final Pattern LEGACY_X_HEX_CODES = Pattern.compile("(?i)(?:&|§)x(?:(?:&|§)[0-9A-F]){6}");
    private static final Pattern MINIMESSAGE_TAGS = Pattern.compile("(?s)</?[!#a-z0-9_:-]+(?:\s*:[^<>]*)?>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern LIST_PREFIX = Pattern.compile("^\\s*(?:[-*•▪●‣]+|\\d+[.)-]?|[A-Za-zА-Яа-яЁё][.)]|(?i:[ivxlcdm]+)[.)])\\s*");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\p{ASCII}]");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D+");
    private static final Pattern NON_LETTERS_DIGITS = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}]+");
    private static final Pattern SYMBOLS = Pattern.compile("[^\\p{L}\\p{Nd}\\s]+");
    private static final Pattern COMPACT_NUMBER = Pattern.compile("^([+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+))\\s*([\\p{L}.]+)?$");
    private static final Pattern TIME_SEGMENT = Pattern.compile("(\\d+)\\s*([dhmsдчмс])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern URL = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private ScriptToolkit() {
    }

    public static final class Normalize {
        public String lower(Object input) {
            return toText(input).toLowerCase(Locale.ROOT);
        }

        public String upper(Object input) {
            return toText(input).toUpperCase(Locale.ROOT);
        }

        public String trim(Object input) {
            return toText(input).trim();
        }

        public String collapseWhitespace(Object input) {
            return WHITESPACE.matcher(trim(input)).replaceAll(" ");
        }

        public String normalizeNewlines(Object input) {
            return toText(input).replace("\r\n", "\n").replace('\r', '\n');
        }

        public String stripColors(Object input) {
            return stripLegacyFormatting(toText(input));
        }

        public String stripMiniMessage(Object input) {
            return stripMiniMessageFormatting(toText(input));
        }

        public String stripFormatting(Object input) {
            return stripMiniMessage(stripColors(input));
        }

        public String stripPunctuation(Object input) {
            return PUNCTUATION.matcher(toText(input)).replaceAll("").trim();
        }

        public String stripSymbols(Object input) {
            return SYMBOLS.matcher(toText(input)).replaceAll("").trim();
        }

        public String lettersDigits(Object input) {
            return NON_LETTERS_DIGITS.matcher(toText(input)).replaceAll("");
        }

        public String digits(Object input) {
            return NON_DIGITS.matcher(toText(input)).replaceAll("");
        }

        public String ascii(Object input) {
            String normalized = Normalizer.normalize(toText(input), Normalizer.Form.NFD);
            normalized = COMBINING_MARKS.matcher(normalized).replaceAll("");
            return NON_ASCII.matcher(normalized).replaceAll("");
        }

        public String title(Object input) {
            List<String> words = splitWords(collapseWhitespace(input).toLowerCase(Locale.ROOT));
            List<String> output = new ArrayList<>(words.size());
            for (String word : words) {
                output.add(capitalize(word));
            }
            return String.join(" ", output);
        }

        public String sentence(Object input) {
            String collapsed = collapseWhitespace(input).toLowerCase(Locale.ROOT);
            if (collapsed.isEmpty()) {
                return collapsed;
            }
            return Character.toUpperCase(collapsed.charAt(0)) + collapsed.substring(1);
        }

        public String slug(Object input) {
            String ascii = ascii(input).toLowerCase(Locale.ROOT);
            String slug = NON_ALNUM.matcher(ascii).replaceAll("-");
            return trimDelimiter(slug, '-');
        }

        public String snake(Object input) {
            return slug(input).replace('-', '_');
        }

        public String kebab(Object input) {
            return slug(input);
        }

        public String camel(Object input) {
            List<String> words = splitWords(slug(input).replace('-', ' '));
            if (words.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder(words.get(0));
            for (int index = 1; index < words.size(); index++) {
                builder.append(capitalize(words.get(index)));
            }
            return builder.toString();
        }

        public String compactLines(Object input) {
            List<String> output = new ArrayList<>();
            for (String line : lines(input)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    output.add(trimmed);
                }
            }
            return String.join("\n", output);
        }

        public String uniqueLines(Object input) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String line : lines(input)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    seen.add(trimmed);
                }
            }
            return String.join("\n", seen);
        }

        public String sortedLines(Object input) {
            List<String> output = new ArrayList<>();
            for (String line : lines(input)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    output.add(trimmed);
                }
            }
            output.sort(String.CASE_INSENSITIVE_ORDER);
            return String.join("\n", output);
        }

        public String reverseLines(Object input) {
            List<String> output = new ArrayList<>(lines(input));
            Collections.reverse(output);
            return String.join("\n", output);
        }

        public String listPlain(Object input) {
            List<String> output = new ArrayList<>();
            for (String line : lines(input)) {
                String cleaned = LIST_PREFIX.matcher(line).replaceFirst("").trim();
                if (!cleaned.isEmpty()) {
                    output.add(cleaned);
                }
            }
            return String.join("\n", output);
        }

        public String csvToLines(Object input) {
            String raw = toText(input).replace(';', ',');
            List<String> output = new ArrayList<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    output.add(trimmed);
                }
            }
            return String.join("\n", output);
        }

        public String linesToCsv(Object input) {
            return String.join(", ", compactLines(input).lines().toList());
        }

        public String inlineList(Object input, Object delimiter) {
            String joiner = toText(delimiter);
            if (joiner.isEmpty()) {
                joiner = ", ";
            }
            return String.join(joiner, compactLines(input).lines().toList());
        }

        public String firstLine(Object input) {
            List<String> lines = lines(input);
            return lines.isEmpty() ? "" : lines.get(0).trim();
        }

        public String lastLine(Object input) {
            List<String> lines = lines(input);
            return lines.isEmpty() ? "" : lines.get(lines.size() - 1).trim();
        }

        /** Первые N слов строки. */
        public String words(Object input, int count) {
            if (count <= 0) {
                return "";
            }
            String text = collapseWhitespace(input).trim();
            if (text.isEmpty()) {
                return "";
            }
            String[] parts = WHITESPACE.split(text, count + 1);
            int take = Math.min(count, parts.length);
            StringBuilder sb = new StringBuilder();
            for (int index = 0; index < take; index++) {
                if (index > 0) sb.append(' ');
                sb.append(parts[index]);
            }
            return sb.toString();
        }

        /** Удаляет URL (http:// и https://) из текста. */
        public String stripUrls(Object input) {
            return URL.matcher(toText(input)).replaceAll("").trim();
        }
    }

    public static final class Decorate {
        private final Normalize normalize = new Normalize();

        public String prefix(Object input, Object prefix) {
            return toText(prefix) + toText(input);
        }

        public String suffix(Object input, Object suffix) {
            return toText(input) + toText(suffix);
        }

        public String wrap(Object input, Object left, Object right) {
            return toText(left) + toText(input) + toText(right);
        }

        public String quote(Object input) {
            return wrap(input, '"', '"');
        }

        public String brackets(Object input) {
            return wrap(input, "[", "]");
        }

        public String parentheses(Object input) {
            return wrap(input, "(", ")");
        }

        public String bulletList(Object input) {
            return bulletList(input, "- ");
        }

        public String bulletList(Object input, Object bulletPrefix) {
            String bullet = toText(bulletPrefix);
            if (bullet.isEmpty()) {
                bullet = "- ";
            }
            List<String> output = new ArrayList<>();
            for (String line : normalize.listPlain(input).lines().toList()) {
                if (!line.isBlank()) {
                    output.add(bullet + line);
                }
            }
            return String.join("\n", output);
        }

        public String numberedList(Object input) {
            return numberedList(input, 1);
        }

        public String numberedList(Object input, int start) {
            List<String> output = new ArrayList<>();
            int index = start;
            for (String line : normalize.listPlain(input).lines().toList()) {
                if (!line.isBlank()) {
                    output.add(index++ + ". " + line);
                }
            }
            return String.join("\n", output);
        }

        public String joinLines(Object input, Object delimiter) {
            String joiner = toText(delimiter);
            return String.join(joiner, normalize.compactLines(input).lines().toList());
        }

        public String indent(Object input, int spaces) {
            String indentation = " ".repeat(Math.max(0, spaces));
            List<String> output = new ArrayList<>();
            for (String line : lines(input)) {
                output.add(indentation + line);
            }
            return String.join("\n", output);
        }

        public String repeat(Object input, int count, Object delimiter) {
            String text = toText(input);
            String joiner = toText(delimiter);
            List<String> output = new ArrayList<>();
            for (int index = 0; index < Math.max(0, count); index++) {
                output.add(text);
            }
            return String.join(joiner, output);
        }

        public String truncate(Object input, int maxLength) {
            String text = toText(input);
            return maxLength < 0 || text.length() <= maxLength ? text : text.substring(0, maxLength);
        }

        public String ellipsis(Object input, int maxLength) {
            String text = toText(input);
            if (maxLength < 0 || text.length() <= maxLength) {
                return text;
            }
            if (maxLength <= 3) {
                return ".".repeat(Math.max(0, maxLength));
            }
            return text.substring(0, maxLength - 3) + "...";
        }

        public String padLeft(Object input, int length, Object padding) {
            return pad(toText(input), length, toText(padding), true);
        }

        public String padRight(Object input, int length, Object padding) {
            return pad(toText(input), length, toText(padding), false);
        }

        public String center(Object input, int length, Object padding) {
            String text = toText(input);
            String fill = toText(padding);
            if (fill.isEmpty()) {
                fill = " ";
            }
            while (text.length() < length) {
                text = fill.charAt(0) + text + fill.charAt(0);
            }
            return text.length() > length ? text.substring(0, length) : text;
        }

        public String tag(Object label, Object value) {
            return '[' + toText(label) + "] " + toText(value);
        }

        public String frame(Object input, Object border) {
            String line = toText(border);
            if (line.isBlank()) {
                line = "==========";
            }
            return line + "\n" + toText(input) + "\n" + line;
        }

        private String pad(String text, int length, String fill, boolean left) {
            if (fill.isEmpty()) {
                fill = " ";
            }
            char character = fill.charAt(0);
            StringBuilder builder = new StringBuilder(text);
            while (builder.length() < length) {
                if (left) {
                    builder.insert(0, character);
                } else {
                    builder.append(character);
                }
            }
            return builder.toString();
        }
    }

    public static final class Convert {
        public double expandCompactNumber(Object input) {
            return parseCompactNumber(toText(input)).doubleValue();
        }

        public String expandCompactNumberText(Object input) {
            return toPlainString(parseCompactNumber(toText(input)));
        }

        public String compactNumber(double value) {
            return compactNumber(value, 2);
        }

        public String compactNumber(double value, int maxDecimals) {
            BigDecimal absolute = BigDecimal.valueOf(Math.abs(value));
            BigDecimal divisor = BigDecimal.ONE;
            String suffix = "";
            if (absolute.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) >= 0) {
                divisor = BigDecimal.valueOf(1_000_000_000_000L);
                suffix = "T";
            } else if (absolute.compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
                divisor = BigDecimal.valueOf(1_000_000_000L);
                suffix = "B";
            } else if (absolute.compareTo(BigDecimal.valueOf(1_000_000L)) >= 0) {
                divisor = BigDecimal.valueOf(1_000_000L);
                suffix = "M";
            } else if (absolute.compareTo(BigDecimal.valueOf(1_000L)) >= 0) {
                divisor = BigDecimal.valueOf(1_000L);
                suffix = "K";
            }
            if (suffix.isEmpty()) {
                return toPlainString(BigDecimal.valueOf(value));
            }
            BigDecimal scaled = BigDecimal.valueOf(value).divide(divisor, Math.max(0, maxDecimals), RoundingMode.HALF_UP).stripTrailingZeros();
            return toPlainString(scaled) + suffix;
        }

        public String legacyToMiniMessage(Object input) {
            String text = toText(input);
            if (text.isEmpty()) {
                return text;
            }
            return MINI_MESSAGE.serialize(deserializeLegacy(text));
        }

        public String miniMessageToLegacy(Object input) {
            return miniMessageToLegacy(input, '&');
        }

        public String miniMessageToLegacy(Object input, char legacyChar) {
            String text = toText(input);
            if (text.isEmpty()) {
                return text;
            }
            try {
                Component component = MINI_MESSAGE.deserialize(text);
                return legacySerializer(legacyChar).serialize(component);
            } catch (Exception ignored) {
                return text;
            }
        }

        public String ampersandToSection(Object input) {
            return toText(input).replace('&', '§');
        }

        public String sectionToAmpersand(Object input) {
            return toText(input).replace('§', '&');
        }

        public String stripLegacyFormatting(Object input) {
            return stripLegacyFormatting(toText(input));
        }

        /**
         * Округлить число до N знаков после запятой.
         * decimals=0 вернёт целое число без дробной части.
         */
        public String formatNumber(double value, int decimals) {
            if (decimals <= 0) {
                return String.valueOf(Math.round(value));
            }
            return toPlainString(BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros());
        }

        /**
         * Форматировать число по шаблону DecimalFormat, например {@code "#,##0.00"}.
         */
        public String formatNumber(double value, Object pattern) {
            try {
                DecimalFormat fmt = new DecimalFormat(toText(pattern));
                fmt.setRoundingMode(RoundingMode.HALF_UP);
                return fmt.format(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }

        /**
         * Парсит строку длительности в секунды.
         * Поддерживает: {@code 1d 2h 30m 45s} и {@code 1д 2ч 30м 45с}.
         */
        public long parseTime(Object input) {
            String text = toText(input).trim();
            if (text.isEmpty()) {
                return 0L;
            }
            Matcher matcher = TIME_SEGMENT.matcher(text);
            long total = 0L;
            while (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                String unit = matcher.group(2).toLowerCase(Locale.ROOT);
                total += switch (unit) {
                    case "d", "д" -> amount * 86400L;
                    case "h", "ч" -> amount * 3600L;
                    case "m", "м" -> amount * 60L;
                    case "s", "с" -> amount;
                    default -> 0L;
                };
            }
            return total;
        }

        /**
         * Форматирует секунды в читаемую строку: {@code "1d 2h 30m 45s"}.
         * Нули пропускаются, кроме случая когда всё равно 0 — тогда {@code "0s"}.
         */
        public String formatTime(long totalSeconds) {
            if (totalSeconds < 0) {
                totalSeconds = 0;
            }
            long d = totalSeconds / 86400L;
            long h = (totalSeconds % 86400L) / 3600L;
            long m = (totalSeconds % 3600L) / 60L;
            long s = totalSeconds % 60L;
            StringBuilder sb = new StringBuilder();
            if (d > 0) sb.append(d).append('d');
            if (h > 0) { if (!sb.isEmpty()) sb.append(' '); sb.append(h).append('h'); }
            if (m > 0) { if (!sb.isEmpty()) sb.append(' '); sb.append(m).append('m'); }
            if (s > 0 || sb.isEmpty()) { if (!sb.isEmpty()) sb.append(' '); sb.append(s).append('s'); }
            return sb.toString();
        }
    }

    public static final class Builders {
        public TextPipelineBuilder pipeline() {
            return new TextPipelineBuilder();
        }

        public Template template(Object pattern) {
            return new Template(toText(pattern));
        }

        public RegexReplacer regexReplacer(Object pattern, Object replacement) {
            return new RegexReplacer(toText(pattern), toText(replacement));
        }

        public TextListBuilder list() {
            return new TextListBuilder();
        }
    }

    public static final class TextPipelineBuilder {
        private final Normalize normalize = new Normalize();
        private final Decorate decorate = new Decorate();
        private final Convert convert = new Convert();
        private final List<UnaryOperator<String>> steps = new ArrayList<>();

        public TextPipelineBuilder lower() {
            steps.add(normalize::lower);
            return this;
        }

        public TextPipelineBuilder upper() {
            steps.add(normalize::upper);
            return this;
        }

        public TextPipelineBuilder trim() {
            steps.add(normalize::trim);
            return this;
        }

        public TextPipelineBuilder collapseWhitespace() {
            steps.add(normalize::collapseWhitespace);
            return this;
        }

        public TextPipelineBuilder stripColors() {
            steps.add(normalize::stripColors);
            return this;
        }

        public TextPipelineBuilder stripMiniMessage() {
            steps.add(normalize::stripMiniMessage);
            return this;
        }

        public TextPipelineBuilder stripFormatting() {
            steps.add(normalize::stripFormatting);
            return this;
        }

        public TextPipelineBuilder stripPunctuation() {
            steps.add(normalize::stripPunctuation);
            return this;
        }

        public TextPipelineBuilder stripSymbols() {
            steps.add(normalize::stripSymbols);
            return this;
        }

        public TextPipelineBuilder ascii() {
            steps.add(normalize::ascii);
            return this;
        }

        public TextPipelineBuilder title() {
            steps.add(normalize::title);
            return this;
        }

        public TextPipelineBuilder sentence() {
            steps.add(normalize::sentence);
            return this;
        }

        public TextPipelineBuilder slug() {
            steps.add(normalize::slug);
            return this;
        }

        public TextPipelineBuilder snake() {
            steps.add(normalize::snake);
            return this;
        }

        public TextPipelineBuilder kebab() {
            steps.add(normalize::kebab);
            return this;
        }

        public TextPipelineBuilder camel() {
            steps.add(normalize::camel);
            return this;
        }

        public TextPipelineBuilder plainList() {
            steps.add(normalize::listPlain);
            return this;
        }

        public TextPipelineBuilder uniqueLines() {
            steps.add(normalize::uniqueLines);
            return this;
        }

        public TextPipelineBuilder sortLines() {
            steps.add(normalize::sortedLines);
            return this;
        }

        public TextPipelineBuilder prefix(Object prefix) {
            steps.add(value -> decorate.prefix(value, prefix));
            return this;
        }

        public TextPipelineBuilder suffix(Object suffix) {
            steps.add(value -> decorate.suffix(value, suffix));
            return this;
        }

        public TextPipelineBuilder wrap(Object left, Object right) {
            steps.add(value -> decorate.wrap(value, left, right));
            return this;
        }

        public TextPipelineBuilder bulletList() {
            steps.add(decorate::bulletList);
            return this;
        }

        public TextPipelineBuilder numberedList() {
            steps.add(decorate::numberedList);
            return this;
        }

        public TextPipelineBuilder ellipsis(int maxLength) {
            steps.add(value -> decorate.ellipsis(value, maxLength));
            return this;
        }

        public TextPipelineBuilder legacyToMiniMessage() {
            steps.add(convert::legacyToMiniMessage);
            return this;
        }

        public TextPipelineBuilder miniMessageToLegacy() {
            steps.add(convert::miniMessageToLegacy);
            return this;
        }

        public TextPipelineBuilder words(int count) {
            steps.add(value -> normalize.words(value, count));
            return this;
        }

        public TextPipelineBuilder stripUrls() {
            steps.add(normalize::stripUrls);
            return this;
        }

        public TextPipelineBuilder formatTime() {
            steps.add(value -> {
                try {
                    return convert.formatTime((long) Double.parseDouble(value.trim()));
                } catch (NumberFormatException ignored) {
                    return value;
                }
            });
            return this;
        }

        public TextPipelineBuilder formatNumber(int decimals) {
            steps.add(value -> {
                try {
                    return convert.formatNumber(Double.parseDouble(value.trim()), decimals);
                } catch (NumberFormatException ignored) {
                    return value;
                }
            });
            return this;
        }

        public TextPipelineBuilder regexReplace(Object pattern, Object replacement) {
            RegexReplacer replacer = new RegexReplacer(toText(pattern), toText(replacement));
            steps.add(replacer::apply);
            return this;
        }

        public TextPipeline build() {
            return new TextPipeline(List.copyOf(steps));
        }

        public String apply(Object input) {
            return build().apply(input);
        }
    }

    public static final class TextPipeline {
        private final List<UnaryOperator<String>> steps;

        public TextPipeline(List<UnaryOperator<String>> steps) {
            this.steps = steps;
        }

        public String apply(Object input) {
            String value = toText(input);
            for (UnaryOperator<String> step : steps) {
                value = step.apply(value);
            }
            return value;
        }
    }

    public static final class RegexReplacer {
        private final Pattern pattern;
        private final String replacement;

        public RegexReplacer(String pattern, String replacement) {
            this.pattern = Pattern.compile(pattern);
            this.replacement = replacement;
        }

        public String apply(Object input) {
            return pattern.matcher(toText(input)).replaceAll(replacement);
        }
    }

    public static final class Template {
        private final String pattern;
        private final Map<String, String> bindings = new LinkedHashMap<>();

        public Template(String pattern) {
            this.pattern = pattern;
        }

        public Template bind(String key, Object value) {
            bindings.put(key, toText(value));
            return this;
        }

        public Template clear() {
            bindings.clear();
            return this;
        }

        public String render() {
            String output = pattern;
            for (Map.Entry<String, String> entry : bindings.entrySet()) {
                output = output.replace('{' + entry.getKey() + '}', entry.getValue());
            }
            return output;
        }
    }

    public static final class TextListBuilder {
        private final Normalize normalize = new Normalize();
        private final List<String> lines = new ArrayList<>();
        private String prefix = "";
        private String suffix = "";
        private String separator = "\n";
        private Mode mode = Mode.PLAIN;
        private int start = 1;

        public TextListBuilder add(Object value) {
            String text = toText(value).trim();
            if (!text.isEmpty()) {
                lines.add(text);
            }
            return this;
        }

        public TextListBuilder addLines(Object value) {
            for (String line : normalize.listPlain(value).lines().toList()) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return this;
        }

        public TextListBuilder clear() {
            lines.clear();
            return this;
        }

        public TextListBuilder prefix(Object value) {
            prefix = toText(value);
            return this;
        }

        public TextListBuilder suffix(Object value) {
            suffix = toText(value);
            return this;
        }

        public TextListBuilder separator(Object value) {
            separator = toText(value);
            return this;
        }

        public TextListBuilder plain() {
            mode = Mode.PLAIN;
            return this;
        }

        public TextListBuilder bulleted() {
            mode = Mode.BULLET;
            return this;
        }

        public TextListBuilder numbered() {
            return numbered(1);
        }

        public TextListBuilder numbered(int start) {
            mode = Mode.NUMBERED;
            this.start = start;
            return this;
        }

        public String build() {
            List<String> output = new ArrayList<>();
            int index = start;
            for (String line : lines) {
                String rendered = switch (mode) {
                    case PLAIN -> line;
                    case BULLET -> "- " + line;
                    case NUMBERED -> index++ + ". " + line;
                };
                output.add(prefix + rendered + suffix);
            }
            return String.join(separator, output);
        }

        private enum Mode {
            PLAIN,
            BULLET,
            NUMBERED
        }
    }

    private static List<String> lines(Object input) {
        String normalized = toText(input).replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        return normalized.lines().toList();
    }

    private static List<String> splitWords(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        return List.of(input.split("\\s+"));
    }

    private static String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }

    private static String trimDelimiter(String input, char delimiter) {
        int start = 0;
        int end = input.length();
        while (start < end && input.charAt(start) == delimiter) {
            start++;
        }
        while (end > start && input.charAt(end - 1) == delimiter) {
            end--;
        }
        return input.substring(start, end);
    }

    private static BigDecimal parseCompactNumber(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Matcher matcher = COMPACT_NUMBER.matcher(input.replace(" ", ""));
        if (!matcher.matches()) {
            return parsePlainNumber(input);
        }

        BigDecimal base = parsePlainNumber(matcher.group(1));
        BigDecimal multiplier = compactMultiplier(matcher.group(2));
        return base.multiply(multiplier).stripTrailingZeros();
    }

    private static BigDecimal parsePlainNumber(String input) {
        try {
            return new BigDecimal(input.replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal compactMultiplier(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return BigDecimal.ONE;
        }
        String normalized = suffix.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "k", "к", "тыс", "тыс." -> BigDecimal.valueOf(1_000L);
            case "m", "м", "млн", "млн." -> BigDecimal.valueOf(1_000_000L);
            case "b", "б", "млрд", "млрд." -> BigDecimal.valueOf(1_000_000_000L);
            case "t", "т", "трлн", "трлн." -> BigDecimal.valueOf(1_000_000_000_000L);
            default -> BigDecimal.ONE;
        };
    }

    private static String stripLegacyFormatting(String input) {
        String stripped = LEGACY_X_HEX_CODES.matcher(input).replaceAll("");
        stripped = LEGACY_HEX_CODES.matcher(stripped).replaceAll("");
        return LEGACY_SIMPLE_CODES.matcher(stripped).replaceAll("");
    }

    private static String stripMiniMessageFormatting(String input) {
        return MINIMESSAGE_TAGS.matcher(input).replaceAll("");
    }

    private static String toPlainString(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Component deserializeLegacy(String input) {
        if (input.indexOf('§') >= 0) {
            return LEGACY_SECTION.deserialize(input);
        }
        return LEGACY_AMP.deserialize(input);
    }

    private static LegacyComponentSerializer legacySerializer(char legacyChar) {
        return legacyChar == '§' ? LEGACY_SECTION : LEGACY_AMP;
    }

    private static String toText(Object input) {
        if (input == null) {
            return "";
        }
        if (input instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object value : iterable) {
                values.add(toText(value));
            }
            return String.join("\n", values);
        }
        if (input.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(input);
            List<String> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(toText(java.lang.reflect.Array.get(input, index)));
            }
            return String.join("\n", values);
        }
        return String.valueOf(input);
    }
}
