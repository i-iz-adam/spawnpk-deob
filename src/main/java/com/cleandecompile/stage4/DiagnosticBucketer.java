package com.cleandecompile.stage4;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.*;

/**
 * Sorts javac error diagnostics into the categories the project plan calls
 * out as mechanically fixable, using javac's internal diagnostic codes
 * ({@code getCode()}). These codes are part of javac's implementation, not
 * a committed public API, so they can drift between JDK versions --
 * {@link #categorize} falls back to matching the human-readable message
 * text when the code itself doesn't match a known prefix, so a JDK bump
 * degrades gracefully into "unresolved symbol matched by message text"
 * rather than silently miscategorizing everything.
 */
public final class DiagnosticBucketer {

    public enum Category {
        UNRESOLVED_SYMBOL,      // missed rename or wrong/missing import
        DUPLICATE_METHOD,       // bridge method collision
        INCOMPATIBLE_TYPES,     // raw type from lost generics
        ILLEGAL_FORWARD_REFERENCE,
        OTHER
    }

    public record Bucketed(Category category, Diagnostic<? extends JavaFileObject> diagnostic) {}

    public List<Bucketed> categorize(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<Bucketed> out = new ArrayList<>();
        for (var d : diagnostics) {
            out.add(new Bucketed(categorizeOne(d), d));
        }
        return out;
    }

    public Map<Category, List<Bucketed>> group(List<Bucketed> bucketed) {
        Map<Category, List<Bucketed>> grouped = new EnumMap<>(Category.class);
        for (var b : bucketed) grouped.computeIfAbsent(b.category(), c -> new ArrayList<>()).add(b);
        return grouped;
    }

    private Category categorizeOne(Diagnostic<? extends JavaFileObject> d) {
        String code = String.valueOf(d.getCode());
        String message = d.getMessage(Locale.ENGLISH);

        if (code.contains("cant.resolve") || code.contains("cant.find.symbol")
                || message.contains("cannot find symbol")) {
            return Category.UNRESOLVED_SYMBOL;
        }
        if (code.contains("already.defined") || message.contains("already defined")
                || message.contains("is already defined in")) {
            return Category.DUPLICATE_METHOD;
        }
        if (code.contains("prob.found.req") || message.contains("incompatible types")
                || message.contains("unchecked") || message.contains("raw type")) {
            return Category.INCOMPATIBLE_TYPES;
        }
        if (code.contains("illegal.forward.ref") || message.contains("illegal forward reference")) {
            return Category.ILLEGAL_FORWARD_REFERENCE;
        }
        return Category.OTHER;
    }
}
