package dev.linzhang.qodercli;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the problems the IDE is currently showing for a file — the red/yellow squiggles produced by
 * the on-the-fly code analyzer and by inspections.
 *
 * <p>This is the one thing a terminal-only agent cannot get for free: it would have to run a
 * compiler or a linter to approximate it, and even then it would miss everything that only the
 * IDE's inspections know about. Handing the list over as part of the prompt saves the agent a
 * round of guessing.
 *
 * <p>The data is read from the document's markup model rather than from the analyzer itself:
 * {@code DaemonCodeAnalyzerImpl.getHighlights} would be the direct route, but that class belongs to
 * a module JetBrains marks as internal, which the Marketplace verifier rejects. Reading the
 * highlighters is equivalent — they are what the analyzer publishes — and stays on public API.
 */
final class IdeDiagnostics {

    /** Upper bound on how many problems we put into a prompt, so it stays readable. */
    private static final int MAX_REPORTED = 30;

    private IdeDiagnostics() {
    }

    /**
     * Errors and warnings currently highlighted in {@code editor}, formatted as
     * {@code 第 42 行 [ERROR] message} and ordered by position. When the editor has a selection only
     * the problems inside it are returned, matching the "ask about the selection" behaviour of the
     * surrounding menu.
     *
     * <p>Must be called on the EDT (it reads the editor and its markup model). Returns an empty
     * list if the file is clean or the analyzer has not produced anything yet.
     */
    static @NotNull List<String> collect(@NotNull Project project, @NotNull Editor editor) {
        return collect(project, editor, MAX_REPORTED);
    }

    /**
     * Whether the IDE reports at least one error or warning here. Used by the menu's {@code update},
     * which runs on every popup and only needs a yes/no answer, so this stops at the first hit
     * instead of collecting, sorting and formatting the whole list.
     *
     * <p>Must be called on the EDT.
     */
    static boolean hasAny(@NotNull Project project, @NotNull Editor editor) {
        return !collect(project, editor, 1).isEmpty();
    }

    private static @NotNull List<String> collect(@NotNull Project project,
                                                 @NotNull Editor editor,
                                                 int limit) {
        Document document = editor.getDocument();
        RangeHighlighter[] highlighters;
        try {
            MarkupModel markupModel = DocumentMarkupModel.forDocument(document, project, false);
            if (markupModel == null) {
                return Collections.emptyList();
            }
            highlighters = markupModel.getAllHighlighters();
        } catch (Throwable t) {
            // Never let a diagnostics hiccup break the menu.
            return Collections.emptyList();
        }
        if (highlighters.length == 0) {
            return Collections.emptyList();
        }

        SelectionModel selectionModel = editor.getSelectionModel();
        boolean hasSelection = selectionModel.hasSelection();
        int selectionStart = selectionModel.getSelectionStart();
        int selectionEnd = selectionModel.getSelectionEnd();
        int textLength = document.getTextLength();

        List<RangeHighlighter> relevant = new ArrayList<>();
        for (RangeHighlighter highlighter : highlighters) {
            if (!(highlighter.getErrorStripeTooltip() instanceof HighlightInfo)) {
                continue;
            }
            HighlightInfo info = (HighlightInfo) highlighter.getErrorStripeTooltip();
            HighlightSeverity severity = info.getSeverity();
            if (severity == null || severity.compareTo(HighlightSeverity.WARNING) < 0) {
                continue;
            }
            String description = info.getDescription();
            if (description == null || description.isBlank()) {
                continue;
            }
            int offset = highlighter.getStartOffset();
            if (offset < 0 || offset > textLength) {
                continue;
            }
            if (hasSelection && (offset < selectionStart || offset > selectionEnd)) {
                continue;
            }
            relevant.add(highlighter);
            if (limit <= 1) {
                // The caller only wants to know whether anything is reported; ordering is
                // irrelevant, so stop as soon as we have one.
                break;
            }
        }
        if (relevant.isEmpty()) {
            return Collections.emptyList();
        }
        // Highlighters come in markup-tree order; present them the way the user reads the file.
        relevant.sort(Comparator.comparingInt(RangeHighlighter::getStartOffset));

        List<String> problems = new ArrayList<>();
        for (RangeHighlighter highlighter : relevant) {
            HighlightInfo info = (HighlightInfo) highlighter.getErrorStripeTooltip();
            int line = document.getLineNumber(highlighter.getStartOffset()) + 1;
            // Flatten the message: the prompt is sent as a single line, because a newline typed into
            // an already-running qodercli session would submit it prematurely.
            String message = info.getDescription().replace('\n', ' ').replace('\r', ' ').trim();
            problems.add("\u7b2c " + line + " \u884c [" + info.getSeverity().getName() + "] " + message);
            if (problems.size() >= limit) {
                break;
            }
        }
        return problems;
    }
}
