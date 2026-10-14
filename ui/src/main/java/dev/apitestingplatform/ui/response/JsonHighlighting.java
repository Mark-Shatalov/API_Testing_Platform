package dev.apitestingplatform.ui.response;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import java.util.Collection;
import java.util.List;

/** Linear-time page highlighting; no regular expressions or recursive syntax tree. */
public final class JsonHighlighting {
    private JsonHighlighting() { }
    public static StyleSpans<Collection<String>> styles(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int position = 0;
        int tokens = 0;
        while (position < text.length()) {
            if (++tokens > 16_384) { spans.add(List.of(), text.length() - position); break; }
            int start = position;
            char character = text.charAt(position++);
            String style = "";
            if (character == '"') {
                while (position < text.length()) {
                    char next = text.charAt(position++);
                    if (next == '\\' && position < text.length()) position++;
                    else if (next == '"') break;
                }
                int next = position;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
                style = next < text.length() && text.charAt(next) == ':' ? "json-key" : "json-string";
            } else if (Character.isDigit(character) || character == '-') {
                while (position < text.length() && "0123456789.eE+-".indexOf(text.charAt(position)) >= 0) position++;
                style = "json-number";
            } else if (Character.isLetter(character)) {
                while (position < text.length() && Character.isLetter(text.charAt(position))) position++;
                style = "json-literal";
            } else if ("{}[]:,".indexOf(character) >= 0) style = "json-punctuation";
            spans.add(style.isEmpty() ? List.of() : List.of(style), position - start);
        }
        return spans.create();
    }
}
