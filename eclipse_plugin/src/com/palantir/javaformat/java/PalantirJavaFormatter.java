/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.palantir.javaformat.java;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

/** Runs the Palantir Java formatter on the given code. */
public class PalantirJavaFormatter extends CodeFormatter {

    /** The kind of snippet to format. */
    public enum SnippetKind {
        COMPILATION_UNIT,
        CLASS_BODY_DECLARATIONS,
        STATEMENTS,
        EXPRESSION
    }

    private class SnippetWrapper {
        int offset;
        final StringBuilder contents = new StringBuilder();

        public SnippetWrapper append(String str) {
            contents.append(str);
            return this;
        }

        public SnippetWrapper appendSource(String source) {
            this.offset = contents.length();
            contents.append(source);
            return this;
        }

        public void closeBraces(int initialIndent) {
            for (int i = initialIndent; --i >= 0; ) {
                contents.append("\n").append(createIndentationString(i)).append("}");
            }
        }
    }

    private static final int INDENTATION_SIZE = 4;
    private static final CharMatcher NOT_WHITESPACE = CharMatcher.whitespace().negate();

    private final FormatterService formatterService =
            ServiceLoader.load(FormatterService.class).findFirst().orElseThrow();

    @Override
    public TextEdit format(
            int kind, String source, int offset, int length, int indentationLevel, String lineSeparator) {
        IRegion[] regions = new IRegion[] {new Region(offset, length)};
        return formatInternal(kind, source, regions, indentationLevel);
    }

    @Override
    public TextEdit format(int kind, String source, IRegion[] regions, int indentationLevel, String lineSeparator) {
        return formatInternal(kind, source, regions, indentationLevel);
    }

    private static Range<Integer> offsetRange(Range<Integer> range, int offset) {
        range = range.canonical(DiscreteDomain.integers());
        return Range.closedOpen(range.lowerEndpoint() + offset, range.upperEndpoint() + offset);
    }

    private static List<Range<Integer>> offsetRanges(List<Range<Integer>> ranges, int offset) {
        List<Range<Integer>> result = new ArrayList<>();
        for (Range<Integer> range : ranges) {
            result.add(offsetRange(range, offset));
        }
        return result;
    }

    /**
     * Runs the Palantir Java formatter on the given source, with only the given
     * ranges specified.
     */
    public ImmutableList<Replacement> format(
            SnippetKind kind, String source, List<Range<Integer>> ranges, int initialIndent, boolean includeComments)
            throws FormatterException {
        RangeSet<Integer> rangeSet = TreeRangeSet.create();
        for (Range<Integer> range : ranges) {
            rangeSet.add(range);
        }
        if (includeComments) {
            if (kind != SnippetKind.COMPILATION_UNIT) {
                throw new IllegalArgumentException("comment formatting is only supported for compilation units");
            }
            return formatterService.getFormatReplacements(source, ranges);
        }
        SnippetWrapper wrapper = snippetWrapper(kind, source, initialIndent);
        ranges = offsetRanges(ranges, wrapper.offset);

        String replacement = formatterService.formatSourceReflowStringsAndFixImports(wrapper.contents.toString());
        replacement = replacement.substring(
                wrapper.offset, replacement.length() - (wrapper.contents.length() - wrapper.offset - source.length()));

        return toReplacements(source, replacement).stream()
                .filter(r -> rangeSet.encloses(r.getReplaceRange()))
                .collect(toImmutableList());
    }

    /**
     * Generates {@code Replacement}s rewriting {@code source} to
     * {@code replacement}, under the assumption that they differ in whitespace
     * alone.
     */
    private static List<Replacement> toReplacements(String source, String replacement) {
        if (!NOT_WHITESPACE.retainFrom(source).equals(NOT_WHITESPACE.retainFrom(replacement))) {
            throw new IllegalArgumentException("source = \"" + source + "\", replacement = \"" + replacement + "\"");
        }
        /*
         * In the past we seemed to have problems touching non-whitespace text in the
         * formatter, even just replacing some code with itself. Retrospective attempts
         * to reproduce this have failed, but this may be an issue for future changes.
         */
        List<Replacement> replacements = new ArrayList<>();
        int i = NOT_WHITESPACE.indexIn(source);
        int j = NOT_WHITESPACE.indexIn(replacement);
        if (i != 0 || j != 0) {
            replacements.add(Replacement.create(0, i, replacement.substring(0, j)));
        }
        while (i != -1 && j != -1) {
            int i2 = NOT_WHITESPACE.indexIn(source, i + 1);
            int j2 = NOT_WHITESPACE.indexIn(replacement, j + 1);
            if (i2 == -1 || j2 == -1) {
                break;
            }
            if ((i2 - i) != (j2 - j) || !source.substring(i + 1, i2).equals(replacement.substring(j + 1, j2))) {
                replacements.add(Replacement.create(i + 1, i2, replacement.substring(j + 1, j2)));
            }
            i = i2;
            j = j2;
        }
        return replacements;
    }

    private SnippetWrapper snippetWrapper(SnippetKind kind, String source, int initialIndent) {
        /*
         * Synthesize a dummy class around the code snippet provided by Eclipse. The
         * dummy class is correctly formatted -- the blocks use correct indentation,
         * etc.
         */
        switch (kind) {
            case COMPILATION_UNIT: {
                SnippetWrapper wrapper = new SnippetWrapper();
                for (int i = 1; i <= initialIndent; i++) {
                    wrapper.append("class Dummy {\n").append(createIndentationString(i));
                }
                wrapper.appendSource(source);
                wrapper.closeBraces(initialIndent);
                return wrapper;
            }
            case CLASS_BODY_DECLARATIONS: {
                SnippetWrapper wrapper = new SnippetWrapper();
                for (int i = 1; i <= initialIndent; i++) {
                    wrapper.append("class Dummy {\n").append(createIndentationString(i));
                }
                wrapper.appendSource(source);
                wrapper.closeBraces(initialIndent);
                return wrapper;
            }
            case STATEMENTS: {
                SnippetWrapper wrapper = new SnippetWrapper();
                wrapper.append("class Dummy {\n").append(createIndentationString(1));
                for (int i = 2; i <= initialIndent; i++) {
                    wrapper.append("{\n").append(createIndentationString(i));
                }
                wrapper.appendSource(source);
                wrapper.closeBraces(initialIndent);
                return wrapper;
            }
            case EXPRESSION: {
                SnippetWrapper wrapper = new SnippetWrapper();
                wrapper.append("class Dummy {\n").append(createIndentationString(1));
                for (int i = 2; i <= initialIndent; i++) {
                    wrapper.append("{\n").append(createIndentationString(i));
                }
                wrapper.append("Object o = ");
                wrapper.appendSource(source);
                wrapper.append(";");
                wrapper.closeBraces(initialIndent);
                return wrapper;
            }
            default:
                throw new IllegalArgumentException("Unknown snippet kind: " + kind);
        }
    }

    @Override
    public String createIndentationString(int indentationLevel) {
        Preconditions.checkArgument(
                indentationLevel >= 0, "Indentation level cannot be less than zero. Given: %s", indentationLevel);
        int spaces = indentationLevel * INDENTATION_SIZE;
        StringBuilder buf = new StringBuilder(spaces);
        for (int i = 0; i < spaces; i++) {
            buf.append(' ');
        }
        return buf.toString();
    }

    /** Runs the Palantir Java formatter on the given source, with only the given ranges specified. */
    private TextEdit formatInternal(int kind, String source, IRegion[] regions, int initialIndent) {
        try {
            boolean includeComments = (kind & CodeFormatter.F_INCLUDE_COMMENTS) == CodeFormatter.F_INCLUDE_COMMENTS;
            kind &= ~CodeFormatter.F_INCLUDE_COMMENTS;
            SnippetKind snippetKind;
            switch (kind) {
                case ASTParser.K_EXPRESSION:
                    snippetKind = SnippetKind.EXPRESSION;
                    break;
                case ASTParser.K_STATEMENTS:
                    snippetKind = SnippetKind.STATEMENTS;
                    break;
                case ASTParser.K_CLASS_BODY_DECLARATIONS:
                    snippetKind = SnippetKind.CLASS_BODY_DECLARATIONS;
                    break;
                case ASTParser.K_COMPILATION_UNIT:
                    snippetKind = SnippetKind.COMPILATION_UNIT;
                    break;
                default:
                    throw new IllegalArgumentException(String.format("Unknown snippet kind: %d", kind));
            }
            List<Replacement> replacements =
                    format(snippetKind, source, rangesFromRegions(regions), initialIndent, includeComments);
            if (idempotent(source, regions, replacements)) {
                // Do not create edits if there's no diff.
                return null;
            }
            // Convert replacements to text edits.
            return editFromReplacements(replacements);
        } catch (IllegalArgumentException | FormatterException exception) {
            // Do not format on errors.
            return null;
        }
    }

    private List<Range<Integer>> rangesFromRegions(IRegion[] regions) {
        List<Range<Integer>> ranges = new ArrayList<>();
        for (IRegion region : regions) {
            ranges.add(Range.closedOpen(region.getOffset(), region.getOffset() + region.getLength()));
        }
        return ranges;
    }

    /** @return {@code true} if input and output texts are equal, else {@code false}. */
    private boolean idempotent(String source, IRegion[] regions, List<Replacement> replacements) {
        // This implementation only checks for single replacement.
        if (replacements.size() == 1) {
            Replacement replacement = replacements.get(0);
            String output = replacement.getReplacementString();
            // Entire source case: input = output, nothing changed.
            if (output.equals(source)) {
                return true;
            }
            // Single region and single replacement case: if they are equal, nothing changed.
            if (regions.length == 1) {
                Range<Integer> range = replacement.getReplaceRange();
                String snippet = source.substring(range.lowerEndpoint(), range.upperEndpoint());
                if (output.equals(snippet)) {
                    return true;
                }
            }
        }
        return false;
    }

    private TextEdit editFromReplacements(List<Replacement> replacements) {
        // Split the replacements that cross line boundaries.
        TextEdit edit = new MultiTextEdit();
        for (Replacement replacement : replacements) {
            Range<Integer> replaceRange = replacement.getReplaceRange();
            edit.addChild(new ReplaceEdit(
                    replaceRange.lowerEndpoint(),
                    replaceRange.upperEndpoint() - replaceRange.lowerEndpoint(),
                    replacement.getReplacementString()));
        }
        return edit;
    }
}
