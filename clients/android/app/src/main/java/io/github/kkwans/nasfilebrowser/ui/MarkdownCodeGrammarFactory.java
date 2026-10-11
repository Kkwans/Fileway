package io.github.kkwans.nasfilebrowser.ui;

import java.util.Set;

import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;

/** Java owns the annotation-processor output; Kotlin only sees this existing factory API. */
@PrismBundle(includeAll = true, grammarLocatorClassName = ".MarkdownPrismGrammarLocator")
public final class MarkdownCodeGrammarFactory {
    private MarkdownCodeGrammarFactory() {}

    public static Prism4j create() {
        return new Prism4j(new MarkdownPrismGrammarLocator());
    }

    public static Set<String> languages() {
        return new MarkdownPrismGrammarLocator().languages();
    }
}
