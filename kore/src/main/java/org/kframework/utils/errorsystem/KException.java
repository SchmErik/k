// Copyright (c) 2012-2019 K Team. All Rights Reserved.
package org.kframework.utils.errorsystem;

import org.apache.commons.lang3.StringUtils;
import org.kframework.attributes.HasLocation;
import org.kframework.attributes.Location;
import org.kframework.attributes.Source;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class KException implements Serializable, HasLocation {
    protected final ExceptionType type;
    final KExceptionGroup exceptionGroup;
    private final Source source;
    private final Location location;
    private final String message;
    private final Throwable exception;
    private final String sourceText;
    private StringBuilder trace = new StringBuilder();

    private static final Map<KExceptionGroup, String> labels;
    static {
        labels = new HashMap<KException.KExceptionGroup, String>();
        labels.put(KExceptionGroup.COMPILER, "Compiler");
        labels.put(KExceptionGroup.OUTER_PARSER, "Outer Parser");
        labels.put(KExceptionGroup.INNER_PARSER, "Inner Parser");
        labels.put(KExceptionGroup.LISTS, "Lists");
        labels.put(KExceptionGroup.INTERNAL, "Internal");
        labels.put(KExceptionGroup.CRITICAL, "Critical");
        labels.put(KExceptionGroup.DEBUGGER, "Debugger");
    }

    public static KException criticalError(String message) {
        return new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, message);
    }

    public KException(ExceptionType type, KExceptionGroup label, String message) {
        this(type, label, message, null, (Location) null, null);
    }

    public KException(ExceptionType type, KExceptionGroup label, String message, Throwable e) {
        this(type, label, message, null, (Location) null, e);
    }

    public KException(ExceptionType type, KExceptionGroup label, String message, Source source, Location location) {
        this(type, label, message, source, location, null);
    }

    public KException(
            ExceptionType type,
            KExceptionGroup label,
            String message,
            Source source,
            Location location,
            Throwable exception) {
        super();
        this.type = type;
        this.exceptionGroup = label;
        this.message = message;
        this.source = source;
        this.location = location;
        this.exception = exception;
        this.sourceText = getSourceLineText();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KException that = (KException) o;
        return type == that.type &&
                exceptionGroup == that.exceptionGroup &&
                Objects.equals(source, that.source) &&
                Objects.equals(location, that.location) &&
                Objects.equals(message, that.message) &&
                Objects.equals(exception, that.exception) &&
                Objects.equals(trace.toString(), that.trace.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, exceptionGroup, source, location, message, exception, trace.toString());
    }

    @Override
    public Optional<Location> location() {
        return Optional.ofNullable(location);
    }

    @Override
    public Optional<Source> source() {
        return Optional.ofNullable(source);
    }

    public enum KExceptionGroup {
        OUTER_PARSER, INNER_PARSER, COMPILER, LISTS, INTERNAL, CRITICAL, DEBUGGER
    }

    public enum ExceptionType {
        ERROR,
        NON_EXHAUSTIVE_MATCH,
        UNDELETED_TEMP_DIR,
        MISSING_HOOK_OCAML,
        MISSING_SYNTAX_MODULE,
        INVALID_EXIT_CODE,
        INVALID_CONFIG_VAR,
        FUTURE_ERROR,
        UNUSED_VAR,
        PROOF_LINT,
        NON_LR_GRAMMAR,
        FIRST_HIDDEN, // warnings below here are hidden by default
        MISSING_HOOK_JAVA,
        USELESS_RULE,
        UNRESOLVED_FUNCTION_SYMBOL,
        MALFORMED_MARKDOWN,
        INVALIDATED_CACHE,
        UNUSED_SYMBOL
    }

    @Override
    public String toString() {
        return toString(false);
    }

    private enum IndicatorLinePosition {
        above,below,
    }

    private StringBuilder getIndicatorLine(int lineNumberPadding) {
        StringBuilder sourceLineText = new StringBuilder();
        sourceLineText.append("\n\t" + StringUtils.repeat(' ', lineNumberPadding) + " .\t");
        sourceLineText.append(StringUtils.repeat(' ', location.startColumn() - 1));
        sourceLineText.append(StringUtils.repeat('~', location.endColumn() - location.startColumn()));

        return sourceLineText;
    }

    private String getTopIndicatorLine(int lineNumberPadding, IndicatorLinePosition position) {
        StringBuilder indicatorLineText = getIndicatorLine(lineNumberPadding);
        char arrow = '^';

        if (position.equals(IndicatorLinePosition.above)) {
            arrow = 'v';
        }
        indicatorLineText.setCharAt(indicatorLineText.indexOf("~"), arrow);
        return indicatorLineText.toString();
    }

    private String getBottomIndicatorLine(int lineNumberPadding) {
        StringBuilder indicatorLineText = getIndicatorLine(lineNumberPadding);

        indicatorLineText.setCharAt(indicatorLineText.lastIndexOf("~"), '^');
        return indicatorLineText.toString();
    }


    private String getSourceText (int lineNumber, int lineNumberPadding) throws java.io.IOException {
        StringBuilder sourceLineText = new StringBuilder();
        sourceLineText.append("\n\t");
        sourceLineText.append(String.format("%"+lineNumberPadding+"s", String.valueOf(lineNumber)) + " |\t");
        Stream lines = Files.lines(Paths.get(getSource().source()));
        sourceLineText.append((String) lines.skip(lineNumber - 1).findFirst().get());
        return sourceLineText.toString();
    }

    private String getSourceLineText() {
        StringBuilder sourceLineText = new StringBuilder();

        try {
            int lineNumberCharacterLength = String.valueOf(location.endLine()).length();
            int errorLineSpan = location.endLine() - location.startLine() + 1;

            if (errorLineSpan == 1) { // the error spans only one line of code
                sourceLineText.append(getSourceText(location.startLine(), lineNumberCharacterLength));
                sourceLineText.append(getTopIndicatorLine(lineNumberCharacterLength, IndicatorLinePosition.below));
            } else if (errorLineSpan > 1) { // the error spans multiple lines of code

                /* All errors should display a maximum of 3 lines from source to avoid overcrowding the terminal */

                sourceLineText.append(getTopIndicatorLine(lineNumberCharacterLength, IndicatorLinePosition.above));
                sourceLineText.append(getSourceText(location.startLine(), lineNumberCharacterLength));

                if (errorLineSpan == 3) {
                    sourceLineText.append(getSourceText(location.startLine() + 1, lineNumberCharacterLength));
                } else if (errorLineSpan > 3) {
                    // Represent the middle line of errors that span longer than three lines with a single line
                    // containing ellipses (...). This avoid crowding the console with lines of source code.
                    sourceLineText.append("\n\t");
                    sourceLineText.append(String.format("%"+lineNumberCharacterLength+"s", ".") + "..");
                }

                sourceLineText.append(getSourceText(location.endLine(), lineNumberCharacterLength));
                sourceLineText.append(getBottomIndicatorLine(lineNumberCharacterLength));

            }

            // Add a new line to break up each message. This looks nice when errors are printed on console.
            sourceLineText.append("\n");
        }
        catch (Exception e) {
            return null;
        }
        return sourceLineText.toString();
    }

    public String toString(boolean verbose) {
        return "[" + (type == ExceptionType.ERROR ? "Error" : "Warning") + "] " + labels.get(exceptionGroup) + ": " + message
                + (exception == null ? "" : " (" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")")
                + trace.toString() + traceTail()
                + (source == null ? "" : "\n\t" + source)
                + (location == null ? "" : "\n\t" + location)
                + (sourceText == null ? "" : sourceText);
    }

    public String getMessage() {
        return message;
    }

    public Throwable getException() {
        return exception;
    }

    public ExceptionType getType() {
        return type;
    }

    private String traceTail() {
        if (identicalFrames > 1) {
            return " * " + identicalFrames;
        }
        return "";
    }

    private int frames = 0;
    private int identicalFrames = 1;
    private CharSequence lastFrame;
    public void addTraceFrame(CharSequence frame) {
        if (frames < 1024) {
            if (frame.equals(lastFrame)) {
                identicalFrames++;
            } else {
                if (identicalFrames > 1) {
                    trace.append(" * ").append(identicalFrames);
                    identicalFrames = 1;
                }
                trace.append("\n  ").append(frame);
                lastFrame = frame;
                frames++;
            }
        }
    }

    public void formatTraceFrame(String format, Object... args) {
        StringBuilder sb = new StringBuilder();
        new Formatter(sb).format(format, args);
        addTraceFrame(sb);
    }

    public Source getSource() {
        return source;
    }

    public Location getLocation() {
        return location;
    }

    public KExceptionGroup getExceptionGroup() {
        return exceptionGroup;
    }
}
